package com.mannschaft.app.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 同期処理の個別結果。
 * clientId と紐付けてフロントエンドが各リクエストの成否を判定する。
 */
@Getter
@AllArgsConstructor
public class SyncResultItem {

    private final String clientId;
    private final String status;
    private final Long resourceId;
    private final Long conflictId;
    private final String message;

    /**
     * 成功結果を生成する。
     */
    public static SyncResultItem success(String clientId, Long resourceId) {
        return new SyncResultItem(clientId, "SUCCESS", resourceId, null, null);
    }

    /**
     * コンフリクト結果を生成する。
     */
    public static SyncResultItem conflict(String clientId, Long conflictId, String message) {
        return new SyncResultItem(clientId, "CONFLICT", null, conflictId, message);
    }

    /**
     * 失敗結果を生成する。
     */
    public static SyncResultItem failed(String clientId, String message) {
        return new SyncResultItem(clientId, "FAILED", null, null, message);
    }
}
