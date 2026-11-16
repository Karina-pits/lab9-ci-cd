package ua.course.store;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Лабораторна №8 — Regression Testing and Flaky Test Debugging (SUT B, Варіант 10).
 *
 * Групи (tags):
 *   regression — УСІ тести класу (повний regression suite; включає smoke-тести);
 *   smoke      — підмножина критичних швидких сценаріїв (createOrder, returnOrder, stockRejection).
 *
 *
 * Порядок методів навмисно ВИПАДКОВИЙ (MethodOrderer.Random): якщо тест залежить від іншого,
 * це швидко проявиться. Для відтворення конкретного порядку: -Djunit.jupiter.execution.order.random.seed=<число>.
 *
 * Виправлені проблеми (симптом -> причина -> виправлення описано в README_Lab8.md):
 *   1) order dependency      — returnOrder залежав від статичного orderId, який ставив createOrder;
 *   2) shared state / dirty  — productStock і productRate створювали товар із ОДНАКОВИМ sku;
 *   3) fixed wait / timing   — orderAfterPolicyCheck чекав Thread.sleep(50) замість очікування умови;
 *   4) неявний порядок рядків — orderSelection читав без ORDER BY і залежав від порядку вставки.
 */
@Testcontainers
@Tag("regression")
@TestMethodOrder(MethodOrderer.Random.class)
class OrderRegressionIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6-alpine");

    // Спільна лише ІНФРАСТРУКТУРА (контейнер, репозиторії, сервіс). Жодних спільних ДАНИХ між тестами немає.
    static JdbcTemplate jdbc;
    static ProductRepository product;
    static OrderRepository orders;
    static OrderService service;

    @BeforeAll
    static void prepareDatabase() {
        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").executeInTransaction(false).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        product = new ProductRepository(jdbc);
        orders = new OrderRepository(jdbc);
        service = new OrderService(product, orders, (item, quantity) -> true);
    }

    /** Кожен виклик створює НОВИЙ товар з унікальним артикулом, тому тести не конфліктують у спільній БД. */
    private Product item() throws Exception {
        return item(10000, 5);
    }

    private Product item(long unitPrice, int stock) throws Exception {
        return product.create(new Product(0, UUID.randomUUID().toString(), unitPrice, stock, false));
    }

    private OrderRequest request(Product item) {
        return new OrderRequest(UUID.randomUUID().toString(), item.id(), 1, false, false);
    }

    @Test
    @Tag("smoke")
    @DisplayName("Замовлення зберігається у БД")
    void createOrder() throws Exception {
        var saved = service.placeOrder(request(item()));
        assertEquals(OrderStatus.CONFIRMED, saved.status());
        assertEquals(saved, orders.findById(saved.id()));
    }

    @Test
    @Tag("smoke")
    @DisplayName("Повернення товару змінює статус")
    void returnOrder() throws Exception {
        // ВИПРАВЛЕНО (order dependency): тест САМ створює замовлення, яке повертає,
        // і не використовує id, залишений іншим тестом.
        var placed = service.placeOrder(request(item()));
        var returned = service.returnOrder(placed.id());
        assertEquals(OrderStatus.RETURNED, returned.status());
        assertEquals(returned, orders.findById(placed.id()));
    }

    @Test
    @DisplayName("Картка товару зберігає залишок")
    void productStock() throws Exception {
        // ВИПРАВЛЕНО (shared state): унікальний артикул замість фіксованого "REGRESSION-EQUIPMENT".
        var saved = item(10000, 5);
        assertEquals(5, product.findById(saved.id()).stock());
    }

    @Test
    @DisplayName("Картка товару зберігає ціну одиниці")
    void productRate() throws Exception {
        // ВИПРАВЛЕНО (shared state): унікальний артикул; раніше обидва тести вставляли однаковий sku -> дублікат.
        var saved = item(15000, 3);
        assertEquals(15000, product.findById(saved.id()).unitPrice());
    }

    @Test
    @DisplayName("Замовлення після зовнішнього дозволу")
    void orderAfterPolicyCheck() throws Exception {
        var item = item();
        var request = request(item);
        // ВИПРАВЛЕНО (fixed wait): замість Thread.sleep(50) тест КЕРУЄ моментом, коли зовнішня перевірка відповідає,
        // і чекає на конкретні УМОВИ (перевірка почалась; операція завершилась), а не на сплив часу.
        var policyStarted = new CountDownLatch(1);
        var releasePolicy = new CountDownLatch(1);
        var gated = new OrderService(product, orders, (value, quantity) -> {
            policyStarted.countDown();
            try {
                if (!releasePolicy.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Тест не відпустив зовнішню перевірку");
                }
                return true;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Перевірку перервано", exception);
            }
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var operation = CompletableFuture.supplyAsync(() -> {
                try { return gated.placeOrder(request); }
                catch (Exception exception) { throw new CompletionException(exception); }
            }, executor);
            try {
                // умова 1: сервіс дійшов до зовнішньої перевірки
                assertTrue(policyStarted.await(5, TimeUnit.SECONDS), "Сервіс не звернувся до зовнішньої перевірки");
                // поки перевірка не відповіла, замовлення ще НЕ існує (це гарантовано засувкою, а не таймінгом)
                assertTrue(orders.findOrders(item.id(), OrderStatus.CONFIRMED).isEmpty(),
                        "До відповіді зовнішньої перевірки замовлення не має зберігатися");
            } finally {
                releasePolicy.countDown(); // у будь-якому разі відпускаємо перевірку, щоб потік не завис
            }
            // умова 2: операція завершилась (чекаємо подію, а не фіксований час)
            operation.get(5, TimeUnit.SECONDS);
            var saved = orders.findOrders(item.id(), OrderStatus.CONFIRMED);
            assertEquals(1, saved.size());
            assertEquals(request.reference(), saved.getFirst().reference());
            assertEquals(10000, saved.getFirst().total());
        }
    }

    @Test
    @DisplayName("Вибірка містить дві замовлення товару")
    void orderSelection() throws Exception {
        var item = item();
        var prefix = UUID.randomUUID().toString().substring(0, 8);
        var references = List.of(prefix + "-A", prefix + "-B");
        var fixtureOrder = Boolean.getBoolean("regression.reverseFixture") ? references.reversed() : references;
        for (var reference : fixtureOrder) service.placeOrder(new OrderRequest(reference, item.id(), 1, false, false));
        // ВИПРАВЛЕНО (неявний порядок рядків): без ORDER BY база не гарантує порядок результату.
        // Явне сортування робить результат детермінованим незалежно від порядку вставки.
        var actual = jdbc.queryForList(
                "SELECT r.reference FROM orders r JOIN order_items i ON i.order_id=r.id WHERE i.product_id=? ORDER BY r.reference",
                String.class, item.id());
        assertEquals(references, actual);
    }

    @Test
    @DisplayName("Замовлення зберігає суму й позицію")
    void orderAmount() throws Exception {
        var item = item();
        var saved = service.placeOrder(request(item));
        var stored = orders.findById(saved.id());
        assertEquals(10000, stored.total());
        assertEquals(item.id(), stored.item().productId());
        assertEquals(1, stored.item().quantity());
        assertEquals(10000, stored.item().unitPrice());
    }

    @Test
    @Tag("smoke")
    @DisplayName("Нестача товару не створює замовлення")
    void stockRejection() throws Exception {
        var item = item();
        var request = new OrderRequest(UUID.randomUUID().toString(), item.id(), 6, false, false);
        assertThrows(OrderRejectedException.class, () -> service.placeOrder(request));
        assertTrue(orders.findOrders(item.id(), OrderStatus.CONFIRMED).isEmpty());
    }

    @Test
    @DisplayName("Зовнішня відмова не створює замовлення")
    void policyRejection() throws Exception {
        var item = item();
        var denied = new OrderService(product, orders, (value, quantity) -> false);
        assertThrows(OrderRejectedException.class, () -> denied.placeOrder(request(item)));
        assertTrue(orders.findOrders(item.id(), OrderStatus.CONFIRMED).isEmpty());
    }

    @Test
    @DisplayName("Повторне повернення не змінює замовлення")
    void repeatedReturn() throws Exception {
        var saved = service.placeOrder(request(item()));
        var returned = service.returnOrder(saved.id());
        assertThrows(OrderRejectedException.class, () -> service.returnOrder(saved.id()));
        assertEquals(returned, orders.findById(saved.id()));
    }
}
