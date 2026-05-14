package com.mannschaft.app.residencestatus;

import com.mannschaft.app.common.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.16 居住実態管理・見守りのエラーコード定義（S3-B 追加分: 006〜）。
 * S3-A 分（001〜005）は main マージ後に統合する。
 */
@Getter
@RequiredArgsConstructor
public enum ResidenceStatusErrorCode implements ErrorCode {

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
