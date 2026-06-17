package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

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

    @Test
    @DisplayName("preview_size=0 → 件数のみ・プレビュー LIMIT クエリは呼ばない")
    void countOnly() {
        given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.PENDING)).willReturn(5L);

        PendingAggregate result = service.pendingForTeam(TEAM_ID, 0);

        assertThat(result.pendingCount()).isEqualTo(5);
        assertThat(result.items()).isEmpty();
        verify(reservationRepository, never())
                .findByTeamIdAndStatusOrderByBookedAtDesc(any(), any(), any());
    }

    @Test
    @DisplayName("preview_size>0 → team_id+PENDING でプレビュー取得・表示名はバルク解決")
    void countAndPreview() {
        given(reservationRepository.countByTeamIdAndStatus(TEAM_ID, ReservationStatus.PENDING)).willReturn(2L);
        ReservationEntity r = ReservationEntity.builder()
                .teamId(TEAM_ID).userId(99L).status(ReservationStatus.PENDING)
                .bookedAt(java.time.LocalDateTime.now()).userNote("コートA").build();
        given(reservationRepository.findByTeamIdAndStatusOrderByBookedAtDesc(
                eq(TEAM_ID), eq(ReservationStatus.PENDING), any()))
                .willReturn(new PageImpl<>(List.of(r), PageRequest.of(0, 3), 2));
        given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(99L, "山田太郎"));

        PendingAggregate result = service.pendingForTeam(TEAM_ID, 3);

        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).requestedBy()).isEqualTo("山田太郎");
        assertThat(result.items().get(0).title()).isEqualTo("コートA");
    }
}
