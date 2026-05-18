package com.mannschaft.app.mail.outbox;

import java.util.UUID;

/**
 * F09.18 メール配信 outbox の Service インターフェース。
 *
 * <p>呼び出し側ドメインが使う唯一のエントリポイント (設計書 §6.1)。
 * 呼び出し側のトランザクション内 ({@code @TransactionalEventListener(AFTER_COMMIT)} パターン推奨)
 * で {@link #enqueue} を呼ぶこと。</p>
 */
public interface EmailOutboxService {

    /**
     * メール送信を outbox に enqueue する。
     *
     * @param request 必須項目: templateKind, locale, toAddress, payloadVars, sourceDomain
     *                オプション: idempotencyKey (省略時は自動生成), userId, organizationId
     * @return outbox.id (UUIDv7)
     * @throws EmailOutboxValidationException EMAIL_OUTBOX_001..005
     */
    UUID enqueue(EmailOutboxRequest request);

    /**
     * 1 件処理する (Worker から REQUIRES_NEW で呼ばれる)。
     *
     * <p>設計書 §7.2 の processOne 完全実装:</p>
     * <ol>
     *   <li>findById で取得し、PENDING 以外なら early return</li>
     *   <li>SENDING に遷移して save</li>
     *   <li>payload 復号 → renderer → SES sendEmail</li>
     *   <li>成功: markSent + メトリクス</li>
     *   <li>SES 永久失敗: markDeadLetter + ErrorReport 起票 + SYSTEM_ADMIN プッシュ</li>
     *   <li>SES 一時失敗: applyBackoff (retry_count++)</li>
     * </ol>
     */
    void processOne(UUID id);
}
