package com.mannschaft.app.repairplan.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.repairplan.dto.RepairPlanDashboardResponse;
import com.mannschaft.app.repairplan.dto.RepairPlanUpcomingItemDto;
import com.mannschaft.app.repairplan.entity.RepairPlanItem;
import com.mannschaft.app.repairplan.repository.RepairPlanItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 修繕計画ダッシュボードサービス（F08.8 Phase 1）。
 *
 * <p>設計書 F08.8 §4 の {@code GET /api/v1/{scope}/{id}/repair-plan/dashboard} 5 ペイン統合 DTO を
 * 組み立てる。Phase 1 では「次 5 年の修繕予定」のみデータを返し、
 * 残り 4 ペイン（summaryCard / depletionForecast / yearlyBalances / generationMeters）は null。</p>
 *
 * <p>認可は呼び出し側 Controller で {@link AccessControlService#checkMembership} 実施済みの前提。
 * 本サービスでは追加で {@link RoleResolver} を通じて {@code viewer_role} を解決し、
 * {@code widget_visibility[]} を組み立てる（F02.2.1 と同じパターン）。</p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RepairPlanDashboardService {

    private static final Set<String> ALLOWED_SCOPE_TYPES = Set.of("TEAM", "ORGANIZATION");

    /** PLANNED / IN_PROGRESS の修繕計画項目のみ「次 5 年の修繕予定」に含める。 */
    private static final Set<String> UPCOMING_STATUSES = Set.of("PLANNED", "IN_PROGRESS");

    /** 「次 5 年」の幅。今年（含む）から +5 年（含む）の 6 暦年。 */
    private static final int UPCOMING_HORIZON_YEARS = 5;

    private final RepairPlanItemRepository itemRepository;
    private final RoleResolver roleResolver;

    /**
     * 修繕計画ダッシュボード（Phase 1 雛形）を取得する。
     *
     * @param scopeId    スコープ ID（チーム ID または組織 ID）
     * @param scopeType  スコープ種別。{@code "TEAM"} または {@code "ORGANIZATION"}（大文字単数形）
     * @param userId     認証済みユーザー ID（viewer_role 解決用）
     * @return Phase 1 で対応した {@code upcoming_items} ・ {@code viewer_role} ・
     *         {@code widget_visibility} を含む統合 DTO
     */
    public RepairPlanDashboardResponse get(Long scopeId, String scopeType, Long userId) {
        if (scopeId == null) {
            throw new IllegalArgumentException("scopeId must not be null");
        }
        if (scopeType == null || !ALLOWED_SCOPE_TYPES.contains(scopeType)) {
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }

        ViewerRole viewerRole = roleResolver.resolveViewerRole(userId, scopeType, scopeId);

        List<RepairPlanUpcomingItemDto> upcomingItems = loadUpcomingItems(scopeType, scopeId);

        List<WidgetVisibilityRowDto> widgetVisibility = buildPhase1WidgetVisibility(viewerRole);

        return RepairPlanDashboardResponse.builder()
                .upcomingItems(upcomingItems)
                .summaryCard(null)
                .depletionForecast(null)
                .yearlyBalances(null)
                .generationMeters(null)
                .viewerRole(viewerRole)
                .widgetVisibility(widgetVisibility)
                .build();
    }

    /**
     * 次 5 年（今年〜+5 年）の修繕計画項目を取得する（status PLANNED / IN_PROGRESS のみ・年度昇順）。
     */
    private List<RepairPlanUpcomingItemDto> loadUpcomingItems(String scopeType, Long scopeId) {
        int currentYear = LocalDate.now().getYear();
        int upperYear = currentYear + UPCOMING_HORIZON_YEARS;

        List<RepairPlanItem> all = itemRepository
                .findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByPlannedYearAsc(scopeType, scopeId);

        return all.stream()
                .filter(it -> it.getPlannedYear() != null
                        && it.getPlannedYear() >= currentYear
                        && it.getPlannedYear() <= upperYear)
                .filter(it -> UPCOMING_STATUSES.contains(it.getStatus()))
                .sorted(Comparator
                        .comparing(RepairPlanItem::getPlannedYear,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(RepairPlanItem::getPlannedMonth,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::toUpcomingItemDto)
                .toList();
    }

    private RepairPlanUpcomingItemDto toUpcomingItemDto(RepairPlanItem entity) {
        return RepairPlanUpcomingItemDto.builder()
                .id(entity.getId())
                .category(entity.getCategory())
                .title(entity.getTitle())
                .plannedYear(entity.getPlannedYear())
                .plannedMonth(entity.getPlannedMonth())
                .estimatedAmount(entity.getEstimatedAmount())
                .status(entity.getStatus())
                .build();
    }

    /**
     * Phase 1 のウィジェット可視性配列を組み立てる。
     *
     * <p>Phase 1 で実データを返すのは {@code REPAIR_PLAN_ITEMS} のみ。
     * 残りのウィジェット（SIMULATOR / TIMELINE / KANBAN / HANDOVER）は Phase 2 以降で実装するため、
     * 本 Phase ではフロントエンドが「未対応」を判別できるよう {@code is_visible=false} で返す。</p>
     *
     * <p>min_role は設計書 §2.4 「ダッシュボード閲覧」の表に従い、すべて {@link MinRole#MEMBER}
     * を採用する（理事 / 一般組合員に閲覧を許可、サポーターは匿名化表示なので Phase 1 では非表示扱い）。</p>
     */
    private List<WidgetVisibilityRowDto> buildPhase1WidgetVisibility(ViewerRole viewerRole) {
        boolean canSeeItems = viewerRole.isAtLeast(MinRole.MEMBER);
        return List.of(
                WidgetVisibilityRowDto.builder()
                        .widgetKey("REPAIR_PLAN_ITEMS")
                        .minRole(MinRole.MEMBER)
                        .isVisible(canSeeItems)
                        .build(),
                WidgetVisibilityRowDto.builder()
                        .widgetKey("REPAIR_PLAN_SIMULATOR")
                        .minRole(MinRole.MEMBER)
                        .isVisible(false)
                        .build(),
                WidgetVisibilityRowDto.builder()
                        .widgetKey("REPAIR_PLAN_TIMELINE")
                        .minRole(MinRole.MEMBER)
                        .isVisible(false)
                        .build(),
                WidgetVisibilityRowDto.builder()
                        .widgetKey("REPAIR_PLAN_KANBAN")
                        .minRole(MinRole.MEMBER)
                        .isVisible(false)
                        .build(),
                WidgetVisibilityRowDto.builder()
                        .widgetKey("REPAIR_PLAN_HANDOVER")
                        .minRole(MinRole.MEMBER)
                        .isVisible(false)
                        .build()
        );
    }
}
