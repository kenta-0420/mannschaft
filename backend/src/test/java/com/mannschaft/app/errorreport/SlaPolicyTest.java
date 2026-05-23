package com.mannschaft.app.errorreport;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;

class SlaPolicyTest {

    @Test
    void CRITICAL_は1時間後のSLA期限を返す() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 1, 9, 0);
        LocalDateTime due = SlaPolicy.calcDueAt(ErrorReportSeverity.CRITICAL, base);
        assertThat(due).isEqualTo(LocalDateTime.of(2026, 5, 1, 10, 0));
    }

    @Test
    void HIGH_は24時間後のSLA期限を返す() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 1, 9, 0);
        LocalDateTime due = SlaPolicy.calcDueAt(ErrorReportSeverity.HIGH, base);
        assertThat(due).isEqualTo(LocalDateTime.of(2026, 5, 2, 9, 0));
    }

    @Test
    void MEDIUM_は7日後のSLA期限を返す() {
        LocalDateTime base = LocalDateTime.of(2026, 5, 1, 9, 0);
        LocalDateTime due = SlaPolicy.calcDueAt(ErrorReportSeverity.MEDIUM, base);
        assertThat(due).isEqualTo(LocalDateTime.of(2026, 5, 8, 9, 0));
    }

    @Test
    void LOW_はnullを返す() {
        LocalDateTime due = SlaPolicy.calcDueAt(ErrorReportSeverity.LOW, LocalDateTime.now());
        assertThat(due).isNull();
    }
}
