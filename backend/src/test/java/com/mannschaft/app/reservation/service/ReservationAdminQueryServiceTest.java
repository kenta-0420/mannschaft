package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P1: {@link ReservationAdminQueryService} 単体テスト。
 * 番人テスト: 件数・プレビューとも WHERE に team_id を含むメソッド（PENDING ステータス指定）で
 * 取得していること（テナント越境防止）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationAdminQueryService 単体テスト")
class ReservationAdminQueryServiceTest {

    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private ReservationAdminQueryService service;

    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "dev-team";

    @Test
    @DisplayName("preview_size=0 → 件数のみ・プレビュー LIMIT クエリは呼ばない")
    void countOnly() {
        given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.PENDING)).willReturn(5L);

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 0);

        assertThat(result.pendingCount()).isEqualTo(5);
        assertThat(result.items()).isEmpty();
        verify(reservationRepository, never())
                .findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("preview_size>0 → team_id+PENDING でプレビュー取得・表示名バルク解決・detail_route は個別遷移先")
    void countAndPreview() {
        given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.PENDING)).willReturn(2L);
        ReservationEntity r = ReservationEntity.builder()
                .teamId(TEAM_ID).userId(99L).status(ReservationStatus.PENDING)
                .bookedAt(java.time.LocalDateTime.now()).userNote("コートA").build();
        ReflectionTestUtils.setField(r, "id", 33L);
        given(reservationRepository.findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
                eq(TEAM_ID), eq(ReservationStatus.PENDING), any()))
                .willReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 3), 2));
        given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(99L, "山田太郎"));

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 3);

        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.items()).hasSize(1);
        PendingAggregate.Item item = result.items().get(0);
        assertThat(item.requestedBy()).isEqualTo("山田太郎");
        assertThat(item.title()).isEqualTo("コートA");
        // id は主キーの文字列（§3.3）
        assertThat(item.id()).isEqualTo("33");
        // detail_route は id を含む個別遷移先（list_route の status 付き一覧とは別物・§3.1）
        assertThat(item.detailRoute()).isEqualTo("/teams/dev-team/admin/reservations/33");
    }

    // ── P3b Wave2: summaryForTeam（承認待ち / 本日の予約数） ────────────

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    @Test
    @DisplayName("summaryForTeam → 承認待ち(PENDING)件数と本日(JST)の CONFIRMED/PENDING 予約数を team_id 絞りで集約")
    void summaryForTeam_集約() {
        given(reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(TEAM_ID, ReservationStatus.PENDING)).willReturn(6L);
        given(reservationRepository.countByTeamIdAndStatusInAndBookedAtRange(
                eq(TEAM_ID),
                eq(List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING)),
                any(), any()))
                .willReturn(9L);

        ReservationAdminQueryService.TeamReservationSummary summary = service.summaryForTeam(TEAM_ID);

        assertThat(summary.pendingCount()).isEqualTo(6L);
        assertThat(summary.todayCount()).isEqualTo(9L);

        // 本日の予約数は本日 JST の半開区間 [本日0:00, 翌日0:00) を JST→UTC へ変換して問い合わせる。
        ArgumentCaptor<LocalDateTime> fromCap = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(reservationRepository).countByTeamIdAndStatusInAndBookedAtRange(
                eq(TEAM_ID),
                eq(List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING)),
                fromCap.capture(), toCap.capture());

        LocalDate todayJst = LocalDate.now(JST);
        LocalDateTime expectedFrom = todayJst.atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime expectedTo = todayJst.plusDays(1).atStartOfDay()
                .atZone(JST).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        assertThat(fromCap.getValue()).isEqualTo(expectedFrom);
        assertThat(toCap.getValue()).isEqualTo(expectedTo);
    }
}
