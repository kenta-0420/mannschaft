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
    /** 他居住者の residentRegistryId を指定した越境も同一コードに畳んで存在秘匿する（BOLA 対策） */
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
            "アクティビティスナップショットの閲覧権限がありません", Severity.WARN),

    // ─── S3-C: 見守り委員訪問 + 横展開安否確認（010〜017） ────────────────
    /** MONITORING_CONSENT 誓約が見つからない */
    MONITORING_CONSENT_NOT_FOUND("RESIDENCE_STATUS_010",
            "MONITORING_CONSENT 誓約が見つかりません", Severity.WARN),

    /** MONITORING_CONSENT 誓約が撤回済み */
    MONITORING_CONSENT_REVOKED("RESIDENCE_STATUS_011",
            "MONITORING_CONSENT 誓約が撤回済みです", Severity.WARN),

    /** 訪問記録が見つからない */
    MONITORING_VISIT_NOT_FOUND("RESIDENCE_STATUS_012",
            "訪問記録が見つかりません", Severity.WARN),

    /** 訪問記録は作成から 24 時間を超えているため更新できない */
    MONITORING_VISIT_UPDATE_EXPIRED("RESIDENCE_STATUS_013",
            "訪問記録は作成から 24 時間を超えているため更新できません", Severity.WARN),

    /** 横展開安否確認が見つからない */
    ORG_WIDE_SAFETY_CHECK_NOT_FOUND("RESIDENCE_STATUS_014",
            "横展開安否確認が見つかりません", Severity.WARN),

    /** 安否確認セッション作成に失敗した */
    SAFETY_CHECK_CREATION_FAILED("RESIDENCE_STATUS_015",
            "安否確認セッション作成に失敗しました", Severity.WARN),

    /** 委員会が見つからない */
    COMMITTEE_NOT_FOUND("RESIDENCE_STATUS_016",
            "委員会が見つかりません", Severity.WARN),

    /** 訪問記録を作成するには WATCHER ロールが必要 */
    INVALID_VISITOR_ROLE("RESIDENCE_STATUS_017",
            "訪問記録を作成するには WATCHER ロールが必要です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;
}
