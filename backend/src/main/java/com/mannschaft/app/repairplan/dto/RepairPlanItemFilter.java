package com.mannschaft.app.repairplan.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 修繕計画項目 一覧絞り込みフィルタ（F08.8 Phase 1 案5）。
 *
 * <p>すべて null 許容。null のフィールドは絞り込みなし。</p>
 */
@Getter
@Builder
public class RepairPlanItemFilter {
    /** 対象年度。 */
    private final Integer plannedYear;
    /** カテゴリ完全一致。 */
    private final String category;
    /** ステータス（PLANNED / IN_PROGRESS / COMPLETED / DEFERRED / CANCELED）。 */
    private final String status;
}
