package ua.course.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Лабораторна №9 — UNIT tests для CI (етап "Unit tests").

 */
class OrderServiceUnitTest {

    // Репозиторії без реальної БД (жодного підключення не відкривається)
    private final JdbcTemplate noDatabase = new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://localhost:1/unused", "u", "p"));
    private final OrderService service = new OrderService(
            new ProductRepository(noDatabase), new OrderRepository(noDatabase), (product, quantity) -> true);

    // персональне правило: знижка 20% від 250 000

    @Test
    @DisplayName("Сума нижче порогу (249 999) без знижки")
    void calculateTotal_belowThreshold_hasNoDiscount() {
        assertEquals(100, service.calculateTotal(249_999, 1, false, false, false));
    }

    @Test
    @DisplayName("Рівно на порозі (250 000) знижка 20% застосовується")
    void calculateTotal_atThreshold_appliesTwentyPercent() {
        assertEquals(200_000, service.calculateTotal(250_000, 1, false, false, false));
    }

    @Test
    @DisplayName("Поріг визначається сумою ціна × кількість: 100 000 × 3 = 300 000 -> 240 000")
    void calculateTotal_thresholdIsCheckedOnSubtotal() {
        assertEquals(240_000, service.calculateTotal(100_000, 3, false, false, false));
        assertEquals(200_000, service.calculateTotal(100_000, 2, false, false, false)); // 200 000 < 250 000, без знижки
    }

    @Test
    @DisplayName("Акційний товар знижки не отримує навіть вище порогу")
    void calculateTotal_onSaleProduct_hasNoDiscount() {
        assertEquals(300_000, service.calculateTotal(300_000, 1, false, false, true));
    }

    @Test
    @DisplayName("Сума знижки округлюється донизу: 250 001 -> 200 001")
    void calculateTotal_discountIsRoundedDown() {
        // 20% від 250 001 = 50 000,2 -> 50 000; підсумок 250 001 - 50 000 = 200 001
        assertEquals(200_001, service.calculateTotal(250_001, 1, false, false, false));
    }

    @Test
    @DisplayName("Преміальність і купон на знижку не впливають")
    void calculateTotal_premiumAndCouponDoNotChangeDiscount() {
        assertEquals(200_000, service.calculateTotal(250_000, 1, true, true, false));
        assertEquals(249_999, service.calculateTotal(249_999, 1, true, true, false));
    }

    @Test
    @DisplayName("Скорочена форма calculateTotal(ціна, кількість) використовує false для всіх ознак")
    void calculateTotal_shortForm_usesDefaultFlags() {
        assertEquals(240_000, service.calculateTotal(300_000, 1));
    }

    // валідація

    @Test
    @DisplayName("Ціна поза діапазоном 100–1 000 000 відхиляється")
    void calculateTotal_priceOutOfRange_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(99, 1));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(1_000_001, 1));
    }

    @Test
    @DisplayName("Кількість поза діапазоном 1–20 відхиляється")
    void calculateTotal_quantityOutOfRange_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10_000, 0));
        assertThrows(IllegalArgumentException.class, () -> service.calculateTotal(10_000, 21));
    }

    @Test
    @DisplayName("Кількість 1 і 20 допустима (межі включно)")
    void calculateTotal_quantityBoundaries_areAccepted() {
        assertEquals(10_000, service.calculateTotal(10_000, 1));
        assertEquals(200_000, service.calculateTotal(10_000, 20)); // 200 000 < 250 000, без знижки
    }

    @Test
    @DisplayName("Порожня заявка (null) відхиляється до звернення до БД")
    void placeOrder_nullRequest_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.placeOrder(null));
    }

    @Test
    @DisplayName("Порожній або задовгий код замовлення відхиляється")
    void placeOrder_invalidReference_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.placeOrder(new OrderRequest("  ", 1, 1, false, false)));
        assertThrows(IllegalArgumentException.class, () -> service.placeOrder(new OrderRequest("X".repeat(41), 1, 1, false, false)));
    }

    @Test
    @DisplayName("Недодатний id товару або кількість поза межами відхиляються")
    void placeOrder_invalidProductOrQuantity_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.placeOrder(new OrderRequest("A-1", 0, 1, false, false)));
        assertThrows(IllegalArgumentException.class, () -> service.placeOrder(new OrderRequest("A-1", 1, 21, false, false)));
    }

    @Test
    @DisplayName("Повернення з недодатним id відхиляється")
    void returnOrder_invalidId_isRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.returnOrder(0));
        assertThrows(IllegalArgumentException.class, () -> service.returnOrder(-5));
    }
}
