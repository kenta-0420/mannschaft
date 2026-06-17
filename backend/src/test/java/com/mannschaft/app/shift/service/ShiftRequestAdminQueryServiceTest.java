package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.shift.ChangeRequestStatus;
import com.mannschaft.app.shift.SwapRequestStatus;
import com.mannschaft.app.shift.entity.ShiftChangeRequestEntity;
import com.mannschaft.app.shift.entity.ShiftSwapRequestEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftSwapRequestRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P1: {@link ShiftRequestAdminQueryService} 単体テスト。
 * 番人テスト: OPEN 変更依頼 + PENDING 交代申請を team_id 単位（schedule/slot→schedule.team_id JOIN）で
 * 集計・合算していること（テナント越境防止）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftRequestAdminQueryService 単体テスト")
class ShiftRequestAdminQueryServiceTest {

    @Mock
    private ShiftChangeRequestRepository changeRequestRepository;
    @Mock
    private ShiftSwapRequestRepository swapRequestRepository;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private ShiftRequestAdminQueryService service;

    private static final Long TEAM_ID = 10L;

    @Test
    @DisplayName("preview_size=0 → OPEN 変更依頼 + PENDING 交代申請の件数合算のみ・プレビューは呼ばない")
    void countOnlySumsBothKinds() {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN)).willReturn(2L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING)).willReturn(3L);

        PendingAggregate result = service.pendingForTeam(TEAM_ID, 0);

        assertThat(result.pendingCount()).isEqualTo(5);
        assertThat(result.items()).isEmpty();
        verify(changeRequestRepository, never()).findPendingByTeam(any(), any(), any());
        verify(swapRequestRepository, never()).findPendingByTeam(any(), any(), any());
    }

    @Test
    @DisplayName("preview_size>0 → 2 種別をマージし上位 previewSize 件・申請者名はバルク解決")
    void countAndPreviewMerged() {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN)).willReturn(1L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING)).willReturn(1L);
        ShiftChangeRequestEntity c = ShiftChangeRequestEntity.builder()
                .scheduleId(100L).requestedBy(11L).status(ChangeRequestStatus.OPEN).build();
        ShiftSwapRequestEntity s = ShiftSwapRequestEntity.builder()
                .slotId(200L).requesterId(22L).status(SwapRequestStatus.PENDING).build();
        given(changeRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(ChangeRequestStatus.OPEN), any()))
                .willReturn(List.of(c));
        given(swapRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(SwapRequestStatus.PENDING), any()))
                .willReturn(List.of(s));
        given(nameResolverService.resolveUserDisplayNames(any()))
                .willReturn(Map.of(11L, "佐藤花子", 22L, "田中一郎"));

        PendingAggregate result = service.pendingForTeam(TEAM_ID, 3);

        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(PendingAggregate.Item::requestedBy)
                .containsExactlyInAnyOrder("佐藤花子", "田中一郎");
    }
}
