package com.mannschaft.app.team.batch;

import com.mannschaft.app.team.service.TeamOrgGroupUnassignService;
import com.mannschaft.app.team.service.TeamOrgPendingExpiryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F01.2.1 7-A — 加盟の夜間バッチの単体テスト（期限の計算と、1件の失敗で残りを止めないこと）。
 */
@DisplayName("F01.2.1 7-A 期限切れ・孤児修復バッチ（単体）")
class TeamOrgAffiliationBatchesTest {

    private static final Instant NOW = Instant.parse("2026-10-14T18:10:00.123Z");

    @Test
    @DisplayName("§6.8 期限は Clock の現在時刻（秒に丸める）から設定値の日数を引いたもので、COOLDOWN は現在時刻で判定する")
    void 期限は設定値の日数で計算する() {
        TeamOrgPendingExpiryService service = mock(TeamOrgPendingExpiryService.class);
        Instant now = Instant.parse("2026-10-14T18:10:00Z");
        Instant threshold = Instant.parse("2026-08-15T18:10:00Z");
        when(service.findExpiredPendingIds(threshold)).thenReturn(List.of());
        when(service.findExpiredCooldownIds(now)).thenReturn(List.of());

        new TeamOrgPendingExpiryBatch(service, Clock.fixed(NOW, ZoneOffset.UTC), 60).expire();

        verify(service).findExpiredPendingIds(threshold);
        verify(service).findExpiredCooldownIds(now);
    }

    @Test
    @DisplayName("§6.8 1件の期限切れ処理が失敗しても、残りの PENDING と COOLDOWN の処理を続け、例外を外へ投げない（WARN と件数で残す）")
    void 一件の失敗で残りを止めない() {
        TeamOrgPendingExpiryService service = mock(TeamOrgPendingExpiryService.class);
        UUID cooldown = UUID.randomUUID();
        when(service.findExpiredPendingIds(any())).thenReturn(List.of(1L, 2L, 3L));
        when(service.expire(eq(1L), any())).thenReturn(true);
        when(service.expire(eq(2L), any())).thenThrow(new IllegalStateException("boom"));
        when(service.expire(eq(3L), any())).thenReturn(true);
        when(service.findExpiredCooldownIds(any())).thenReturn(List.of(cooldown));
        when(service.deleteExpiredCooldown(eq(cooldown), any())).thenReturn(true);

        new TeamOrgPendingExpiryBatch(service, Clock.fixed(NOW, ZoneOffset.UTC), 60).expire();

        verify(service).expire(eq(3L), any());
        verify(service).deleteExpiredCooldown(eq(cooldown), any());
    }

    @Test
    @DisplayName("AC-F11 孤児の (組織, グループ) ごとに付け替えを呼び、1件の失敗で残りを止めない")
    void 孤児修復は一件の失敗で残りを止めない() {
        TeamOrgGroupUnassignService service = mock(TeamOrgGroupUnassignService.class);
        UUID g1 = UUID.randomUUID();
        UUID g2 = UUID.randomUUID();
        when(service.findOrphanGroupAssignments()).thenReturn(List.of(
                new TeamOrgGroupUnassignService.GroupAssignment(10L, g1),
                new TeamOrgGroupUnassignService.GroupAssignment(20L, g2)));
        when(service.unassignGroup(10L, g1)).thenThrow(new IllegalStateException("boom"));
        when(service.unassignGroup(20L, g2)).thenReturn(3);

        new TeamOrgGroupOrphanRepairBatch(service).repair();

        verify(service).unassignGroup(20L, g2);
    }
}
