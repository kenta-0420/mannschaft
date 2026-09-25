package com.mannschaft.app.mail.outbox;

import java.util.List;
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

    /**
     * CMP-260920-1040: 複数件のメール送信を JdbcTemplate による<b>多値 INSERT 1文</b>で outbox に登録する
     * （軍議第8版確定稿 §12。確認通知の fan-out チャンクが、受信者の数に比例しない INSERT 文数で
     * outbox 登録するために使う）。
     *
     * <p>既存の {@link #enqueue}（1件ずつ saveAndFlush するもの）は既存の呼び出し元のために残す。
     * 本メソッドは骨格のみ（試練B）。実装は出陣で行う。空リストは何もしない契約とする。</p>
     *
     * @param requests 送信リクエストのリスト
     * @return 作成した outbox.id (UUIDv7) のリスト（requests と同じ順序）
     */
    default List<UUID> enqueueAll(List<EmailOutboxRequest> requests) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
