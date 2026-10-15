package com.mannschaft.app.mail.outbox;

/**
 * F09.18 メール配信 outbox の enqueue 時バリデーション例外。
 *
 * <p>エラーコード対応 (設計書 §6.3):</p>
 * <ul>
 *   <li>EMAIL_OUTBOX_001 — メールアドレスの形式が不正</li>
 *   <li>EMAIL_OUTBOX_002 — テンプレート種別が認識できない</li>
 *   <li>EMAIL_OUTBOX_003 — ペイロードサイズが上限 (8000 バイト) を超過</li>
 *   <li>EMAIL_OUTBOX_004 — 冪等キーの重複</li>
 *   <li>EMAIL_OUTBOX_005 — 状態遷移が不正</li>
 * </ul>
 */
public class EmailOutboxValidationException extends RuntimeException {

    private final String errorCode;

    public EmailOutboxValidationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public EmailOutboxValidationException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
