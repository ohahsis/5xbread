package com.fivexbread.saledate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 판매일·수량 테스트 데이터. V2 시드는 내일부터 7일치라, 테스트는 FROM_DAYS(10일 뒤)부터의 날짜만 쓰고 지운다.
 * 예약은 C-04 전이라 API 가 없어 테이블에 직접 넣는다.
 */
record SaleDateTestData(JdbcTemplate jdbc, LocalDate today) {

    static final int FROM_DAYS = 10;
    static final String MEMBER_PHONE = "01077770000";

    void clean() {
        LocalDate from = today.plusDays(FROM_DAYS);
        jdbc.update("DELETE FROM reservation WHERE sale_date >= ?", from);
        jdbc.update("DELETE FROM daily_stock WHERE sale_date >= ?", from);
        jdbc.update("DELETE FROM pickup_slot WHERE sale_date >= ?", from);
        jdbc.update("DELETE FROM sale_date WHERE sale_date >= ?", from);
        jdbc.update("DELETE FROM member_agreement WHERE member_id IN (SELECT id FROM member WHERE phone = ?)",
                MEMBER_PHONE);
        jdbc.update("DELETE FROM member WHERE phone = ?", MEMBER_PHONE);
    }

    /** 오픈 = 3일 전 10:00, 마감 = 전날 20:00 (Q-01 기본값) */
    void saleDate(LocalDate date) {
        saleDate(date, date.minusDays(3).atTime(10, 0), date.minusDays(1).atTime(20, 0), false);
    }

    void saleDate(LocalDate date, LocalDateTime openAt, LocalDateTime closeAt, boolean holiday) {
        jdbc.update("INSERT INTO sale_date (sale_date, open_at, close_at, holiday) VALUES (?, ?, ?, ?)",
                date, openAt, closeAt, holiday);
    }

    long slot(LocalDate date, int hour) {
        jdbc.update("INSERT INTO pickup_slot (sale_date, pickup_at) VALUES (?, ?)", date, date.atTime(hour, 0));
        return jdbc.queryForObject("SELECT id FROM pickup_slot WHERE sale_date = ? AND pickup_at = ?", Long.class,
                date, date.atTime(hour, 0));
    }

    void stock(LocalDate date, long breadId, int produced, int reservable, int reserved, Integer limit) {
        jdbc.update("""
                INSERT INTO daily_stock (sale_date, bread_id, produced_qty, reservable_qty, reserved_qty, per_user_limit)
                VALUES (?, ?, ?, ?, ?, ?)""", date, breadId, produced, reservable, reserved, limit);
    }

    void reservation(LocalDate date, long slotId) {
        Long memberId = jdbc.query("SELECT id FROM member WHERE phone = ?", rs -> rs.next() ? rs.getLong(1) : null,
                MEMBER_PHONE);
        if (memberId == null) {
            jdbc.update("INSERT INTO member (phone, password_hash, name) VALUES (?, ?, ?)",
                    MEMBER_PHONE, "$2a$10$" + "x".repeat(53), "예약손님");
            memberId = jdbc.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, MEMBER_PHONE);
        }
        LocalDateTime pickupAt = jdbc.queryForObject("SELECT pickup_at FROM pickup_slot WHERE id = ?",
                LocalDateTime.class, slotId);
        jdbc.update("""
                INSERT INTO reservation (reservation_no, member_id, phone_last4, sale_date, pickup_slot_id, pickup_at,
                                         status, total_price, idempotency_key)
                VALUES (?, ?, '0000', ?, ?, ?, 'RESERVED', 3500, ?)""",
                "%02d%02d-%04d".formatted(date.getMonthValue(), date.getDayOfMonth(), slotId % 10000),
                memberId, date, slotId, pickupAt, UUID.randomUUID().toString());
    }

    int version(LocalDate date) {
        return jdbc.queryForObject("SELECT version FROM sale_date WHERE sale_date = ?", Integer.class, date);
    }
}
