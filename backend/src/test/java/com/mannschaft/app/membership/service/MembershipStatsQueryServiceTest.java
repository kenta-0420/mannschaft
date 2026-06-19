package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.repository.MembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P3b Wave2: {@link MembershipStatsQueryService} 単体テスト。
 *
 * <p>番人観点:</p>
 * <ul>
 *   <li>総数・今月新規・在籍 user_id 集合とも WHERE に scope_type/scope_id を含むメソッドで取得する（IDOR 防止）。</li>
 *   <li>「今月新規」の joined_at 範囲は当月（JST）の半開区間 [当月初日, 翌月初日) を JST→UTC へ変換した値。</li>
 *   <li>active 判定（users.status）は本サービスでは行わず user_id 集合だけを返す（ドメイン境界厳守）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipStatsQueryService 単体テスト")
class MembershipStatsQueryServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Mock
    private MembershipRepository membershipRepository;

    @InjectMocks
    private MembershipStatsQueryService service;

    private static final Long TEAM_ID = 10L;

    @Test
    @DisplayName("statsForScope → 総数・今月新規・在籍 user_id 集合を scope 絞りで集約する")
    void statsForScope_集約() {
        given(membershipRepository.countActiveDistinctUsersByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(12L);
        given(membershipRepository.countActiveDistinctUsersByScopeAndJoinedAtBetween(
                eq(ScopeType.TEAM), eq(TEAM_ID), any(), any()))
                .willReturn(3L);
        given(membershipRepository.findActiveDistinctUserIdsByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of(1L, 2L, 3L));

        MembershipStatsQueryService.MemberStats stats = service.statsForScope(ScopeType.TEAM, TEAM_ID);

        assertThat(stats.totalCount()).isEqualTo(12L);
        assertThat(stats.newThisMonthCount()).isEqualTo(3L);
        assertThat(stats.activeUserIds()).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("今月新規の joined_at 範囲は当月（JST）の半開区間 [当月初日, 翌月初日) を JST→UTC 変換した値")
    void 今月新規_JST境界をUTCへ変換() {
        given(membershipRepository.countActiveDistinctUsersByScope(ScopeType.TEAM, TEAM_ID)).willReturn(0L);
        given(membershipRepository.findActiveDistinctUserIdsByScope(ScopeType.TEAM, TEAM_ID))
                .willReturn(List.of());
        given(membershipRepository.countActiveDistinctUsersByScopeAndJoinedAtBetween(
                eq(ScopeType.TEAM), eq(TEAM_ID), any(), any()))
                .willReturn(0L);

        service.statsForScope(ScopeType.TEAM, TEAM_ID);

        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(membershipRepository).countActiveDistinctUsersByScopeAndJoinedAtBetween(
                eq(ScopeType.TEAM), eq(TEAM_ID), fromCap.capture(), toCap.capture());

        // 期待値: 当月初日 0:00 JST → UTC、翌月初日 0:00 JST → UTC
        LocalDate todayJst = LocalDate.now(JST);
        LocalDate firstOfMonth = todayJst.withDayOfMonth(1);
        LocalDateTime expectedFrom = firstOfMonth.atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime expectedTo = firstOfMonth.plusMonths(1).atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        assertThat(fromCap.getValue()).isEqualTo(expectedFrom);
        assertThat(toCap.getValue()).isEqualTo(expectedTo);
        // JST は UTC+9 のため、当月初日 0:00 JST は前日 15:00 UTC になる（境界が UTC へ正しく前倒しされる）。
        assertThat(fromCap.getValue()).isBefore(toCap.getValue());
    }
}
