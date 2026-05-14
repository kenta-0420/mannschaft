package com.mannschaft.app.residencestatus;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.16 居住実態管理・見守りのエラーコード定義。
 */
@Getter
@RequiredArgsConstructor
public enum ResidenceStatusErrorCode implements ErrorCode {

    // ─── S3-A: 年次更新キャンペーン（001〜005） ───────────────
    ANNUAL_REVIEW_NOT_FOUND("RESIDENCE_STATUS_001", "年次更新キャンペーンが見つかりません", Severity.WARN),
    ANNUAL_REVIEW_ALREADY_CLOSED("RESIDENCE_STATUS_002", "年次更新キャンペーンはすでにクローズ済みです", Severity.WARN),
    ANNUAL_REVIEW_YEAR_CONFLICT("RESIDENCE_STATUS_003", "同じ年度のキャンペーンが既に存在します", Severity.WARN),
    ANNUAL_REVIEW_RESPONSE_NOT_FOUND("RESIDENCE_STATUS_004", "年次更新回答が見つかりません", Severity.WARN),
    RESIDENCE_STATE_INVALID("RESIDENCE_STATUS_005", "無効な居住状態です", Severity.WARN),

    // ─── S3-B: アクティビティ集計・ダッシュボード（006〜009） ──
    /** アクティビティスナップショットが見つからない */
    SNAPSHOT_NOT_FOUND("RESIDENCE_STATUS_006", "アクティビティスナップショットが見つかりません", Severity.WARN),

    /** 本人はアクティビティスコアを閲覧できない */
    SNAPSHOT_SELF_ACCESS_FORBIDDEN("RESIDENCE_STATUS_007",
            "本人はアクティビティスコアを閲覧できません（ADMIN/WATCHER のみ）", Severity.WARN),

    /** ダッシュボード閲覧権限がない（ADMIN/DEPUTY_ADMIN のみ） */
    DASHBOARD_ACCESS_FORBIDDEN("RESIDENCE_STATUS_008",
            "ダッシュボードは ADMIN/DEPUTY_ADMIN のみ閲覧できます", Severity.WARN),

    /** アクティビティスナップショット閲覧権限がない */
    SNAPSHOT_ACCESS_FORBIDDEN("RESIDENCE_STATUS_009",
            "アクティビティスナップショットの閲覧権限がありません", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
