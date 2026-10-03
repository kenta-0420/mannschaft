package com.mannschaft.app.mail.outbox;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * F09.18 メール配信 outbox エンティティ。
 *
 * <p>設計書 §4.2 の {@code email_outbox} テーブルに対応する。
 * 主キーは {@link UuidV7Entity} (BINARY(16) / UUIDv7)、
 * クロスドメイン FK は張らない (user_id / organization_id はインデックスのみ)。</p>
 *
 * <p>状態遷移は本 Entity 内の {@code mark*()} メソッド群で完結させる。
 * バックオフ遅延表 (§7.3) は {@link #applyBackoff(Throwable)} に内蔵。</p>
 */
@Entity
@Table(name = "email_outbox")
@Getter
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class EmailOutboxEntity extends UuidV7Entity {

    /** バックオフ遅延 (秒)。設計書 §7.3 の表に対応。インデックス = retry_count。 */
    private static final long[] BACKOFF_SECONDS = {
            10L,            // retry_count=0 → 次回 10 秒
            30L,            // retry_count=1 → 次回 30 秒
            120L,           // retry_count=2 → 次回 2 分
            600L,           // retry_count=3 → 次回 10 分
            1800L,          // retry_count=4 → 次回 30 分
            7200L           // retry_count=5 → 次回 2 時間
    };

    /** リトライ上限。{@link #applyBackoff} がこの値を超えると DEAD_LETTER に遷移。 */
    public static final int MAX_RETRY_COUNT = BACKOFF_SECONDS.length;

    /** last_error の最大長 (DDL の VARCHAR(512) と一致)。 */
    private static final int LAST_ERROR_MAX = 512;

    @Setter
    @Column(name = "template_kind", length = 64, nullable = false)
    private String templateKind;

    @Setter
    @Column(name = "locale", length = 8, nullable = false)
    private String locale;

    /** 暗号化済メールアドレス。VARBINARY(512)。 */
    @Setter
    @Column(name = "to_address", nullable = false, columnDefinition = "VARBINARY(512)")
    private byte[] toAddress;

    /** HMAC-SHA-256 ハッシュ。BINARY(32)。 */
    @Setter
    @Column(name = "to_address_hash", nullable = false, columnDefinition = "BINARY(32)")
    private byte[] toAddressHash;

    /** 暗号化済 payload JSON。VARBINARY(8192)。 */
    @Setter
    @Column(name = "payload_json", columnDefinition = "VARBINARY(8192)")
    private byte[] payloadJson;

    @Setter
    @Column(name = "source_domain", length = 32, nullable = false)
    private String sourceDomain;

    @Setter
    @Column(name = "source_event_id", length = 128)
    private String sourceEventId;

    @Setter
    @Column(name = "user_id")
    private Long userId;

    @Setter
    @Column(name = "organization_id")
    private Long organizationId;

    @Setter
    @Column(name = "idempotency_key", length = 32, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "ses_message_id", length = 64)
    private String sesMessageId;

    @Column(name = "last_error", length = LAST_ERROR_MAX)
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = EmailOutboxStatus.PENDING.name();
        }
        if (this.nextAttemptAt == null) {
            this.nextAttemptAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // -----------------------------------------------------------------------
    // ステータス遷移ヘルパー
    // -----------------------------------------------------------------------

    /** 取得直後に {@link EmailOutboxStatus#PENDING} → {@link EmailOutboxStatus#SENDING} へ。 */
    public void markSending() {
        this.status = EmailOutboxStatus.SENDING.name();
    }

    /** SES 送信成功時。{@link EmailOutboxStatus#SENT} に確定し、messageId と sent_at を記録。 */
    public void markSent(String sesMessageId) {
        this.status = EmailOutboxStatus.SENT.name();
        this.sesMessageId = sesMessageId;
        this.sentAt = LocalDateTime.now();
    }

    /**
     * 一時失敗時。{@code retry_count++} してバックオフ遅延を適用する。
     * 上限到達時は {@link EmailOutboxStatus#DEAD_LETTER} に遷移する。
     *
     * <p>本メソッドは設計書 §7.3 のリトライバックオフ表を内蔵する単一エントリポイント。
     * Service 層 / Worker からは本メソッドを呼ぶことで遅延戦略を一元化する。</p>
     */
    public void applyBackoff(Throwable ex) {
        this.lastError = truncateError(ex);
        if (this.retryCount >= MAX_RETRY_COUNT - 1) {
            // 5 回目失敗 → 6 を記録して DEAD_LETTER
            this.retryCount = this.retryCount + 1;
            this.status = EmailOutboxStatus.DEAD_LETTER.name();
            return;
        }
        long backoffSeconds = BACKOFF_SECONDS[this.retryCount];
        this.retryCount = this.retryCount + 1;
        this.nextAttemptAt = LocalDateTime.now().plusSeconds(backoffSeconds);
        this.status = EmailOutboxStatus.PENDING.name();
    }

    /** 永久失敗時。即 {@link EmailOutboxStatus#DEAD_LETTER} へ遷移する。 */
    public void markDeadLetter(Throwable ex) {
        this.status = EmailOutboxStatus.DEAD_LETTER.name();
        this.lastError = truncateError(ex);
    }

    /** バリデーション失敗 (復号失敗 / テンプレ不在等) 時。リトライ不可。 */
    public void markFailed(Throwable ex) {
        this.status = EmailOutboxStatus.FAILED.name();
        this.lastError = truncateError(ex);
    }

    /** SYSTEM_ADMIN の手動キャンセル。 */
    public void markCancelled() {
        this.status = EmailOutboxStatus.CANCELLED.name();
    }

    /**
     * DEAD_LETTER から SYSTEM_ADMIN 再キュー。retry_count はリセットしない (設計書 §5.再キューの注意点)。
     */
    public void markPendingForRetry() {
        this.status = EmailOutboxStatus.PENDING.name();
        this.nextAttemptAt = LocalDateTime.now();
    }

    /** Java 側で扱う enum 値を返す。DB 上は VARCHAR で保持。 */
    public EmailOutboxStatus getStatusAsEnum() {
        return EmailOutboxStatus.valueOf(this.status);
    }

    // -----------------------------------------------------------------------
    // ビルダー初期値ヘルパー
    // -----------------------------------------------------------------------

    /** enqueue 時の初期値設定。Builder からは呼べないので別途用意。 */
    public static EmailOutboxEntity prepareForEnqueue(EmailOutboxEntity entity) {
        if (entity.status == null) {
            entity.status = EmailOutboxStatus.PENDING.name();
        }
        if (entity.nextAttemptAt == null) {
            entity.nextAttemptAt = LocalDateTime.now();
        }
        return entity;
    }

    // -----------------------------------------------------------------------
    // 補助
    // -----------------------------------------------------------------------

    /** 設計書 §8.6 — クラス FQCN + メッセージ冒頭 500 文字を結合し、列長を超えれば切り詰め。 */
    private static String truncateError(Throwable ex) {
        if (ex == null) {
            return null;
        }
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String combined = ex.getClass().getName() + ": " + msg;
        if (combined.length() > LAST_ERROR_MAX) {
            return combined.substring(0, LAST_ERROR_MAX);
        }
        return combined;
    }

    /** Phase 18-a では未使用。将来 payload 検証 UT 等で参照される想定で残しておく。 */
    @SuppressWarnings("unused")
    private static int payloadSizeForTest(Map<String, String> ignored) {
        return -1;
    }
}
