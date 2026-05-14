package com.mannschaft.app.residencestatus.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 居住実態ダッシュボード集計 DTO（F09.16 S3-B）。
 *
 * <p>組織単位のリスクスコア分布・未反応者数・進行中年次キャンペーン数を保持する。
 * 参照権限: ADMIN / DEPUTY_ADMIN のみ。
 */
@Data
@Builder
public class ResidenceStatusDashboardDto {

    private Long organizationId;

    /** 全居住者数（当日スナップショット保有者数） */
    private int totalResidents;

    /** ハイリスク件数（スコア 70 以上） */
    private int highRiskCount;

    /** ミドルリスク件数（スコア 40-69） */
    private int midRiskCount;

    /** ローリスク件数（スコア 0-39） */
    private int lowRiskCount;

    /** 30日無ログイン件数（未反応者推定） */
    private int unresponsiveCount;

    /** 進行中の年次キャンペーン数 */
    private int openAnnualReviewCount;

    /** 集計時刻（Redis キャッシュ有効時はキャッシュ生成時刻を示す） */
    private LocalDateTime generatedAt;
}
