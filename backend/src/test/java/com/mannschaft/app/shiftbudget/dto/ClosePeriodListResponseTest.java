package com.mannschaft.app.shiftbudget.dto;

import com.mannschaft.app.shiftbudget.service.ShiftBudgetClosePeriodResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-2152 Codex 再検分 P2-4: 締め期間一覧の {@code ended} はサーバーの業務日で判定する（終了日当日を含む）。
 */
@DisplayName("ClosePeriodListResponse の ended は業務日で判定する")
class ClosePeriodListResponseTest {

    @Test
    @DisplayName("P2-4: 期間終了日が業務日以前なら ended=true（当日を含む）、業務日より後なら false")
    void endedは業務日で判定する() {
        LocalDate businessDate = LocalDate.of(2026, 11, 17);
        ClosePeriodListResponse res = ClosePeriodListResponse.from(List.of(
                new ShiftBudgetClosePeriodResolver.ResolvedPeriod(YearMonth.of(2026, 10),
                        LocalDate.of(2026, 9, 18), LocalDate.of(2026, 10, 17), 17, true),
                new ShiftBudgetClosePeriodResolver.ResolvedPeriod(YearMonth.of(2026, 11),
                        LocalDate.of(2026, 10, 18), LocalDate.of(2026, 11, 17), 17, false),
                new ShiftBudgetClosePeriodResolver.ResolvedPeriod(YearMonth.of(2026, 12),
                        LocalDate.of(2026, 11, 18), LocalDate.of(2026, 12, 17), 17, false)),
                businessDate);

        assertThat(res.items()).extracting(ClosePeriodListResponse.ClosePeriodItem::ended)
                .containsExactly(true, true, false);
    }
}
