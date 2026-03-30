package com.mannschaft.app.incident;

import com.mannschaft.app.common.ErrorCode;

/**
 * インシデント・メンテナンス管理 機能のエラーコード。
 */
public enum IncidentErrorCode implements ErrorCode {

    INCIDENT_001("INCIDENT_001", "インシデントカテゴリが見つかりません", Severity.WARN),
    INCIDENT_002("INCIDENT_002", "インシデントが見つかりません", Severity.WARN),
    INCIDENT_003("INCIDENT_003", "アクセス権限がありません", Severity.WARN),
    INCIDENT_004("INCIDENT_004", "このステータスへの遷移は許可されていません", Severity.WARN),
    INCIDENT_005("INCIDENT_005", "バージョンが一致しません", Severity.WARN),
    INCIDENT_006("INCIDENT_006", "添付ファイルの上限（インシデント:5件、コメント:3件）に達しました", Severity.WARN),
    INCIDENT_007("INCIDENT_007", "コメントが見つかりません", Severity.WARN),
    INCIDENT_008("INCIDENT_008", "担当者が見つかりません", Severity.WARN),
    INCIDENT_009("INCIDENT_009", "メンテナンススケジュールが見つかりません", Severity.WARN),
    INCIDENT_010("INCIDENT_010", "無効なCRON式です", Severity.WARN);

    private final String code;
    private final String message;
    private final Severity severity;

    IncidentErrorCode(String code, String message, Severity severity) {
        this.code = code;
        this.message = message;
        this.severity = severity;
    }

    @Override
    public String getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public Severity getSeverity() {
        return severity;
    }
}
