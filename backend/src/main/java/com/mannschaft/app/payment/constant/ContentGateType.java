package com.mannschaft.app.payment.constant;

import java.util.Set;

/**
 * コンテンツゲートの許容コンテンツ種別定数。
 * <p>
 * ENUM ではなく VARCHAR(50) + アプリ層バリデーション方式を採用し、
 * 新モジュール追加時に DB ALTER TABLE なしで拡張可能にする。
 */
public final class ContentGateType {

    public static final String POST = "POST";
    public static final String FILE = "FILE";
    public static final String ANNOUNCEMENT = "ANNOUNCEMENT";
    public static final String SCHEDULE = "SCHEDULE";
    // Phase 4+: VIDEO, CHAT_MESSAGE を追加（DB マイグレーション不要）

    public static final Set<String> SUPPORTED = Set.of(POST, FILE, ANNOUNCEMENT, SCHEDULE);

    private ContentGateType() {
        // インスタンス化禁止
    }

    /**
     * 指定された値がサポートされているコンテンツ種別かどうかを判定する。
     *
     * @param value コンテンツ種別
     * @return サポートされている場合 true
     */
    public static boolean isSupported(String value) {
        return SUPPORTED.contains(value);
    }
}
