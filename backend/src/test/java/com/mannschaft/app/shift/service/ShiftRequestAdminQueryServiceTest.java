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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    private static final String TEAM_SLUG = "dev-team";

    @Test
    @DisplayName("preview_size=0 → OPEN 変更依頼 + PENDING 交代申請の件数合算のみ・プレビューは呼ばない")
    void countOnlySumsBothKinds() {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN)).willReturn(2L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING)).willReturn(3L);

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 0);

        assertThat(result.pendingCount()).isEqualTo(5);
        assertThat(result.items()).isEmpty();
        verify(changeRequestRepository, never()).findPendingByTeam(any(), any(), any());
        verify(swapRequestRepository, never()).findPendingByTeam(any(), any(), any());
    }

    @Test
    @DisplayName("変更依頼と交代申請がともに0件 → 両プレビューと表示名取得を省略する")
    void 両種別ゼロ件はプレビューを取得しない() {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN)).willReturn(0L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING)).willReturn(0L);

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 3);

        assertThat(result.pendingCount()).isZero();
        assertThat(result.items()).isEmpty();
        verify(changeRequestRepository, never()).findPendingByTeam(any(), any(), any());
        verify(swapRequestRepository, never()).findPendingByTeam(any(), any(), any());
        verifyNoInteractions(nameResolverService);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @DisplayName("片方のみ承認待ちがある場合も両種別のプレビューを取得して項目を返す")
    void 片方のみ正数ならプレビューを取得する(boolean changeOnly) {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN))
                .willReturn(changeOnly ? 1L : 0L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING))
                .willReturn(changeOnly ? 0L : 1L);
        ShiftChangeRequestEntity change = ShiftChangeRequestEntity.builder()
                .id(88L).scheduleId(100L).requestedBy(11L).status(ChangeRequestStatus.OPEN).build();
        ShiftSwapRequestEntity swap = ShiftSwapRequestEntity.builder()
                .slotId(200L).requesterId(11L).status(SwapRequestStatus.PENDING).build();
        ReflectionTestUtils.setField(swap, "id", 99L);
        given(changeRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(ChangeRequestStatus.OPEN), any()))
                .willReturn(changeOnly ? List.of(change) : List.of());
        given(swapRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(SwapRequestStatus.PENDING), any()))
                .willReturn(changeOnly ? List.of() : List.of(swap));
        given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(11L, "申請者"));

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 3);

        assertThat(result.pendingCount()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).title()).isEqualTo(changeOnly ? "シフト変更依頼" : "シフト交代申請");
        verify(changeRequestRepository).findPendingByTeam(eq(TEAM_ID), eq(ChangeRequestStatus.OPEN), any());
        verify(swapRequestRepository).findPendingByTeam(eq(TEAM_ID), eq(SwapRequestStatus.PENDING), any());
    }

    @Test
    @DisplayName("preview_size>0 → 2 種別マージ・id は主キー文字列・detail_route は種別ごとの個別遷移先")
    void countAndPreviewMerged() {
        given(changeRequestRepository.countPendingByTeam(TEAM_ID, ChangeRequestStatus.OPEN)).willReturn(1L);
        given(swapRequestRepository.countPendingByTeam(TEAM_ID, SwapRequestStatus.PENDING)).willReturn(1L);
        ShiftChangeRequestEntity c = ShiftChangeRequestEntity.builder()
                .id(88L).scheduleId(100L).requestedBy(11L).status(ChangeRequestStatus.OPEN).build();
        ShiftSwapRequestEntity s = ShiftSwapRequestEntity.builder()
                .slotId(200L).requesterId(22L).status(SwapRequestStatus.PENDING).build();
        ReflectionTestUtils.setField(s, "id", 99L);
        given(changeRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(ChangeRequestStatus.OPEN), any()))
                .willReturn(List.of(c));
        given(swapRequestRepository.findPendingByTeam(eq(TEAM_ID), eq(SwapRequestStatus.PENDING), any()))
                .willReturn(List.of(s));
        given(nameResolverService.resolveUserDisplayNames(any()))
                .willReturn(Map.of(11L, "佐藤花子", 22L, "田中一郎"));

        PendingAggregate result = service.pendingForTeam(TEAM_ID, TEAM_SLUG, 3);

        assertThat(result.pendingCount()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        assertThat(result.items()).extracting(PendingAggregate.Item::requestedBy)
                .containsExactlyInAnyOrder("佐藤花子", "田中一郎");

        // id は合成文字列ではなく主キーの文字列（§3.3）
        PendingAggregate.Item change = result.items().stream()
                .filter(i -> "シフト変更依頼".equals(i.title())).findFirst().orElseThrow();
        PendingAggregate.Item swap = result.items().stream()
                .filter(i -> "シフト交代申請".equals(i.title())).findFirst().orElseThrow();
        assertThat(change.id()).isEqualTo("88");
        assertThat(swap.id()).isEqualTo("99");
        // 依頼IDとscheduleIdは別物。既存の変更依頼一覧と交代一覧へ到達させる。
        assertThat(change.detailRoute()).isEqualTo("/teams/dev-team/shifts/100/change-requests");
        assertThat(swap.detailRoute()).isEqualTo("/teams/dev-team/shifts?tab=swaps");
    }
}
