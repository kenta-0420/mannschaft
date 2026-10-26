package com.mannschaft.app.repairplan.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * 修繕計画ダッシュボード統合レスポンス（F08.8 Phase 1）。
 *
 * <p>設計書 F08.8 §4 の {@code GET /api/v1/{scope}/{id}/repair-plan/dashboard} 5 ペイン統合 DTO。
 * Phase 1 では {@link #upcomingItems}（次 5 年の修繕予定）・{@link #viewerRole}・{@link #widgetVisibility}
 * のみを実装し、残りの 4 ペイン（summaryCard / depletionForecast / yearlyBalances / generationMeters）は
 * {@code null} を返却する。Phase 2 以降でシミュレーター・F08.6 残高ビューを統合して埋める。</p>
 *
 * <p>F02.2.1 ダッシュボードと同様に {@code @JsonInclude(NON_NULL)} で null ペインを JSON 出力から除外し、
 * フロントエンドは {@code widget_visibility[]} の {@code is_visible} フラグを見て表示制御する。</p>
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RepairPlanDashboardResponse {

    // ========================================
    // Phase 1 実装ペイン
    // ========================================

    /** 次 5 年（今年〜+5 年）の修繕予定項目（PLANNED / IN_PROGRESS のみ・年度昇順）。 */
    @JsonProperty("upcoming_items")
    private final List<RepairPlanUpcomingItemDto> upcomingItems;

    // ========================================
    // Phase 2 以降で実装するペイン（Phase 1 では常に null）
    // ========================================

    /** 信号機メーター（残高 / 推奨積立金 / 不足率）。Phase 2 で実装。 */
    @JsonProperty("summary_card")
    private final Map<String, Object> summaryCard;

    /** 積立金枯渇予測（破綻年・最低残高など）。Phase 2 で実装。 */
    @JsonProperty("depletion_forecast")
    private final Map<String, Object> depletionForecast;

    /** 年度別残高（収入・予定支出・残高）。Phase 2 で実装。 */
    @JsonProperty("yearly_balances")
    private final List<Map<String, Object>> yearlyBalances;

    /** 世代別メーター（10〜20 年区切りで誰がどれだけ負担するか）。Phase 2 で実装。 */
    @JsonProperty("generation_meters")
    private final List<Map<String, Object>> generationMeters;

    // ========================================
    // F02.2.1 準拠の可視性フィールド
    // ========================================

    /** 閲覧者の本スコープでのロール（SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / MEMBER / SUPPORTER / PUBLIC）。 */
    @JsonProperty("viewer_role")
    private final ViewerRole viewerRole;

    /** 各ペインの可視性フラグ（widget_key / min_role / is_visible）。 */
    @JsonProperty("widget_visibility")
    private final List<WidgetVisibilityRowDto> widgetVisibility;
}
