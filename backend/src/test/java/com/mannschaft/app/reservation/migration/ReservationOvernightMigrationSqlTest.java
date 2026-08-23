package com.mannschaft.app.reservation.migration;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 既存の逆順時刻を日跨ぎ表現へ補正するmigrationのSQL契約。 */
class ReservationOvernightMigrationSqlTest {

    @Test
    void v189NormalizesAllOvernightReservationTables() throws IOException {
        String sql = read("db/migration/V189.20260824000000__normalize_overnight_reservation_ranges.sql");

        assertThat(sql).contains("UPDATE reservation_slots", "SET end_date = DATE_ADD(slot_date, INTERVAL 1 DAY)");
        assertThat(sql).contains("UPDATE reservation_business_hours", "UPDATE reservation_slot_templates");
        assertThat(sql).contains("UPDATE reservation_blocked_times", "UPDATE reservation_recurring_blocked_times");
        assertThat(sql).contains("end_time < start_time", "start_time IS NOT NULL", "end_time IS NOT NULL");
    }

    private static String read(String path) throws IOException {
        try (InputStream stream = ReservationOvernightMigrationSqlTest.class.getClassLoader()
                .getResourceAsStream(path)) {
            assertThat(stream).as("migration resource").isNotNull();
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
