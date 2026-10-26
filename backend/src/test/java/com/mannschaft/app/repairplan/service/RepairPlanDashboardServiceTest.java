package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.repairplan.dto.RepairPlanDashboardResponse;
import com.mannschaft.app.repairplan.dto.RepairPlanUpcomingItemDto;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * F08.8 Phase 1: {@link RepairPlanDashboardService} の単体テスト。
 *
 * <p>検証範囲:</p>
 * <ul>
 *   <li>空状態（データ無し）でも 5 ペイン雛形 + viewer_role + widget_visibility が返ること</li>
 *   <li>upcoming_items が「今年〜+5 年」かつ {@code status} が PLANNED / IN_PROGRESS のみ抽出されること</li>
 *   <li>viewer_role が {@link RoleResolver} の戻り値そのまま反映されること</li>
 *   <li>不正な scopeType でビジネス例外がスローされること</li>
 *   <li>Phase 2 以降のペインは常に {@code null} で返ること</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RepairPlanDashboardService 単体テスト")
class RepairPlanDashboardServiceTest {

    @Mock
    private RepairPlanItemRepository itemRepository;

    @Mock
    private RoleResolver roleResolver;

    @InjectMocks
    private RepairPlanDashboardService dashboardService;

    private static final Long USER_ID = 1L;
    private static final Long SCOPE_ID = 100L;
    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORG = "ORGANIZATION";

    /**
     * テスト用に状態・年・カテゴリを指定して RepairPlanItem を生成する。
     */
    private RepairPlanItem buildItem(int year, String status, String title) {
        return RepairPlanItem.builder()
                .organizationId(10L)
                .scopeType(SCOPE_TYPE_TEAM)
                .scopeId(SCOPE_ID)
                .category("外壁")
                .title(title)
                .plannedYear(year)
                .plannedMonth(6)
                .estimatedAmount(15_000_000L)
                .cpiInflationBasisYear(LocalDate.now().getYear())
                .status(status)
                .createdBy(USER_ID)
                .version(0L)
                .build();
    }

    // ========================================
    // 空状態
    // ========================================

    @Nested
    @DisplayName("空状態（データ無し）")
    class EmptyState {

        @Test
        @DisplayName("項目 0 件でも upcoming_items は空配列、Phase 2 系ペインは null")
        void 空のデータでも構造を返す() {
            // Given
            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.MEMBER);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    SCOPE_TYPE_TEAM, SCOPE_ID)).willReturn(List.of());

            // When
            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            // Then
            assertThat(response).isNotNull();
            assertThat(response.getUpcomingItems()).isNotNull().isEmpty();
            assertThat(response.getSummaryCard()).isNull();
            assertThat(response.getDepletionForecast()).isNull();
            assertThat(response.getYearlyBalances()).isNull();
            assertThat(response.getGenerationMeters()).isNull();
            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.MEMBER);
            assertThat(response.getWidgetVisibility()).hasSize(5);
        }
    }

    // ========================================
    // upcoming_items のフィルタ
    // ========================================

    @Nested
    @DisplayName("upcoming_items のフィルタ条件")
    class UpcomingItemsFilter {

        @Test
        @DisplayName("「今年〜+5 年」の範囲外は除外、PLANNED / IN_PROGRESS のみ抽出")
        void 年度範囲とステータスで絞り込み() {
            // Given
            int currentYear = LocalDate.now().getYear();
            RepairPlanItem inRangePlanned = buildItem(currentYear + 1, "PLANNED", "塗装");
            RepairPlanItem inRangeInProgress = buildItem(currentYear + 5, "IN_PROGRESS", "屋根");
            RepairPlanItem inRangeCompleted = buildItem(currentYear + 2, "COMPLETED", "完了済み");
            RepairPlanItem inRangeCancelled = buildItem(currentYear + 3, "CANCELLED", "中止");
            RepairPlanItem beyondHorizon = buildItem(currentYear + 6, "PLANNED", "6 年後");
            RepairPlanItem past = buildItem(currentYear - 1, "PLANNED", "去年");

            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(List.of(
                            past,
                            inRangePlanned,
                            inRangeCompleted,
                            inRangeCancelled,
                            inRangeInProgress,
                            beyondHorizon
                    ));

            // When
            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            // Then: PLANNED と IN_PROGRESS の 2 件のみ、年度昇順
            List<RepairPlanUpcomingItemDto> upcoming = response.getUpcomingItems();
            assertThat(upcoming).hasSize(2);
            assertThat(upcoming.get(0).getTitle()).isEqualTo("塗装");
            assertThat(upcoming.get(0).getPlannedYear()).isEqualTo(currentYear + 1);
            assertThat(upcoming.get(0).getStatus()).isEqualTo("PLANNED");
            assertThat(upcoming.get(1).getTitle()).isEqualTo("屋根");
            assertThat(upcoming.get(1).getPlannedYear()).isEqualTo(currentYear + 5);
            assertThat(upcoming.get(1).getStatus()).isEqualTo("IN_PROGRESS");
        }

        @Test
        @DisplayName("ORGANIZATION スコープでも同様にフィルタされる")
        void 組織スコープでも年度フィルタが機能する() {
            // Given
            int currentYear = LocalDate.now().getYear();
            RepairPlanItem inRange = buildItem(currentYear, "PLANNED", "今年実施");
            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_ORG, SCOPE_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    SCOPE_TYPE_ORG, SCOPE_ID))
                    .willReturn(List.of(inRange));

            // When
            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_ORG, USER_ID);

            // Then
            assertThat(response.getUpcomingItems()).hasSize(1);
            assertThat(response.getUpcomingItems().get(0).getTitle()).isEqualTo("今年実施");
        }
    }

    // ========================================
    // viewer_role の伝搬
    // ========================================

    @Nested
    @DisplayName("viewer_role の判定")
    class ViewerRoleResolution {

        @Test
        @DisplayName("RoleResolver が ADMIN を返したら response.viewer_role も ADMIN")
        void ADMIN伝搬() {
            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    anyString(), anyLong())).willReturn(List.of());

            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            assertThat(response.getViewerRole()).isEqualTo(ViewerRole.ADMIN);
        }

        @Test
        @DisplayName("MEMBER 閲覧者は ITEMS ウィジェットが is_visible=true、Phase 2 系は false")
        void MEMBER可視性フラグ() {
            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.MEMBER);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    anyString(), anyLong())).willReturn(List.of());

            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            Map<String, WidgetVisibilityRowDto> byKey = response.getWidgetVisibility().stream()
                    .collect(Collectors.toMap(WidgetVisibilityRowDto::getWidgetKey, w -> w));
            assertThat(byKey.get("REPAIR_PLAN_ITEMS").isVisible()).isTrue();
            assertThat(byKey.get("REPAIR_PLAN_ITEMS").getMinRole()).isEqualTo(MinRole.MEMBER);
            assertThat(byKey.get("REPAIR_PLAN_SIMULATOR").isVisible()).isFalse();
            assertThat(byKey.get("REPAIR_PLAN_TIMELINE").isVisible()).isFalse();
            assertThat(byKey.get("REPAIR_PLAN_KANBAN").isVisible()).isFalse();
            assertThat(byKey.get("REPAIR_PLAN_HANDOVER").isVisible()).isFalse();
        }

        @Test
        @DisplayName("PUBLIC 閲覧者は ITEMS も is_visible=false")
        void PUBLICは全部非表示() {
            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.PUBLIC);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    anyString(), anyLong())).willReturn(List.of());

            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            Map<String, WidgetVisibilityRowDto> byKey = response.getWidgetVisibility().stream()
                    .collect(Collectors.toMap(WidgetVisibilityRowDto::getWidgetKey, w -> w));
            assertThat(byKey.get("REPAIR_PLAN_ITEMS").isVisible()).isFalse();
        }
    }

    // ========================================
    // 入力バリデーション
    // ========================================

    @Nested
    @DisplayName("入力バリデーション")
    class InputValidation {

        @Test
        @DisplayName("scopeType が不正なら BusinessException(COMMON_001)")
        void 不正scopeType_例外() {
            assertThatThrownBy(() -> dashboardService.get(SCOPE_ID, "PERSONAL", USER_ID))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> dashboardService.get(SCOPE_ID, "team", USER_ID))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> dashboardService.get(SCOPE_ID, null, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("scopeId が null なら IllegalArgumentException")
        void scopeId_null_例外() {
            assertThatThrownBy(() -> dashboardService.get(null, SCOPE_TYPE_TEAM, USER_ID))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("userId が null なら IllegalArgumentException")
        void userId_null_例外() {
            assertThatThrownBy(() -> dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ========================================
    // upcoming item DTO 変換
    // ========================================

    @Nested
    @DisplayName("upcoming item DTO 変換")
    class DtoMapping {

        @Test
        @DisplayName("Entity の主要フィールドが DTO に正しくマップされる")
        void Entity_DTO変換() {
            int currentYear = LocalDate.now().getYear();
            RepairPlanItem item = buildItem(currentYear, "PLANNED", "屋上防水");
            item.setEstimatedAmount(28_000_000L);
            item.setPlannedMonth(11);

            given(roleResolver.resolveViewerRole(USER_ID, SCOPE_TYPE_TEAM, SCOPE_ID))
                    .willReturn(ViewerRole.ADMIN);
            given(itemRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(
                    SCOPE_TYPE_TEAM, SCOPE_ID)).willReturn(List.of(item));

            RepairPlanDashboardResponse response =
                    dashboardService.get(SCOPE_ID, SCOPE_TYPE_TEAM, USER_ID);

            assertThat(response.getUpcomingItems()).hasSize(1);
            RepairPlanUpcomingItemDto dto = response.getUpcomingItems().get(0);
            // id は永続化前なので null（@GeneratedValue が PrePersist 時に採番）
            assertThat(dto.getCategory()).isEqualTo("外壁");
            assertThat(dto.getTitle()).isEqualTo("屋上防水");
            assertThat(dto.getPlannedYear()).isEqualTo(currentYear);
            assertThat(dto.getPlannedMonth()).isEqualTo(11);
            assertThat(dto.getEstimatedAmount()).isEqualTo(28_000_000L);
            assertThat(dto.getStatus()).isEqualTo("PLANNED");
        }
    }
}
