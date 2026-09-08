package com.mannschaft.app.payment.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

/**
 * 柱③-B（CMP-260901-1538）PR-3: {@code membership_payer_withdrawal_cancellations}（V204）。
 *
 * <p>払い手（payer）の退会に伴う継続課金の期末解約について、<b>サブスク単位の処理状態</b>を永続化する。
 * 設計書 {@code docs/architecture/billing_payer_handover_design.md} §6.1。</p>
 *
 * <h2>なぜ必要か</h2>
 * <ol>
 *   <li><b>再試行の拾い直し（Codex 検分1巡目 P1-1）</b>: 退会イベントは永続化されない Spring の
 *       インメモリイベントで、ハンドラは共有 {@code event-pool} 上の非同期処理である。Stripe 失敗・
 *       投入拒否・commit 直後のプロセス停止では処理そのものが失われ、退会者への課金継続を
 *       ログ監視だけに委ねることになる。行として残すことで PR-4 の夜次バッチが
 *       {@code PENDING}/{@code FAILED} を機械的に拾える。</li>
 *   <li><b>退会取消時の復旧対象の判別（同 P1-3）</b>: {@code membership_subscriptions.cancel_at_period_end}
 *       は boolean であり、<b>本人が退会前に明示解約した契約</b>と<b>退会処理が自動予約した契約</b>を
 *       区別できない。単純に payer の全予約を解除すると前者まで復活させてしまう。この表が「由来」の正本。</li>
 * </ol>
 *
 * <p>クロスドメイン FK は張らない（{@code payer_user_id} は auth ドメインへの論理参照）。
 * {@code subscription_id} も同一 payment ドメイン内だが、履歴表として物理削除に追随させないため FK 無し。
 * 新規テーブルのため {@link UuidV7Entity}（UUIDv7 主キー）を継承する（CLAUDE.md 原則6）。</p>
 *
 * <p>日時は「世界のどこで見ても同じ1点」であるため
 * {@code docs/architecture/datetime_policy_utc_instant_vs_wallclock.md} に従い {@link Instant} で持つ
 * （{@code DateTimeAndZoneGuardTest} 番人対応）。</p>
 */
@Entity
@Table(name = "membership_payer_withdrawal_cancellations",
        // test プロファイルは ddl-auto=create（Flyway 無効）でスキーマを Entity から起こすため、
        // UNIQUE をここにも宣言しないと IT だけ「1サブスク1行」の物理防衛が効かない状態になる。
        uniqueConstraints = @UniqueConstraint(name = "uk_mpwc_subscription", columnNames = "subscription_id"))
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class MembershipPayerWithdrawalCancellationEntity extends UuidV7Entity {

    /** {@code membership_subscriptions.id} への論理参照。1サブスクにつき1行（UNIQUE）。 */
    @Column(name = "subscription_id", nullable = false, columnDefinition = "BINARY(16)")
    private UUID subscriptionId;

    /** 退会申請した払い手（{@code users.id} への論理参照）。 */
    @Column(name = "payer_user_id", nullable = false)
    private Long payerUserId;

    /**
     * 退会試行の世代（処理時点の {@code users.deleted_at}）。
     *
     * <p>「どの退会申請に属する作業行か」を一意に指す（Codex 検分2巡目 P1-1）。退会は取り消して
     * 再度申請できるため、同じサブスクの行が別の退会試行で再利用される。世代を刻んでおかないと、
     * 遅延して届いた古い退会イベントの処理結果と、現在進行中の退会の処理結果を区別できない。</p>
     */
    @Column(name = "withdrawal_attempt_at", nullable = false)
    private Instant withdrawalAttemptAt;

    /** 予約時点の Stripe Subscription ID（未連結なら null）。 */
    @Column(name = "stripe_subscription_id", length = 255)
    private String stripeSubscriptionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private MembershipPayerWithdrawalCancellationStatus status;

    /** 試行回数。PR-4 の再試行バッチが上限判定に使う。 */
    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    /** 直近の失敗理由（再試行の切り分け用・PII は含めない）。 */
    @Column(name = "last_error", length = 1000)
    private String lastError;

    /** Stripe と DB の双方で期末解約予約が確定した瞬間。 */
    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    /** 退会取消により予約を解除した瞬間。null の {@code SUCCEEDED} 行だけが復旧対象。 */
    @Column(name = "restored_at")
    private Instant restoredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * 予約に着手する（試行回数を1つ進め、前回の失敗理由・復旧記録を消して PENDING へ戻す）。
     *
     * <p>世代を上書きするのは、退会 → 取消 → 再退会で同じ行を再利用するためである（1サブスク1行の
     * UNIQUE を保ったまま、行が常に「最新の退会試行」を指すようにする）。</p>
     */
    public void markAttempt(Instant withdrawalAttemptAt, String stripeSubscriptionId) {
        this.withdrawalAttemptAt = withdrawalAttemptAt;
        this.stripeSubscriptionId = stripeSubscriptionId;
        this.status = MembershipPayerWithdrawalCancellationStatus.PENDING;
        this.attemptCount = (this.attemptCount == null ? 0 : this.attemptCount) + 1;
        this.lastError = null;
        this.scheduledAt = null;
        this.restoredAt = null;
    }

    /** 復旧に着手した（Stripe 呼び出しの<b>前</b>に刻む。以降は非終端として照合・再試行の対象）。 */
    public void markRestoring() {
        this.status = MembershipPayerWithdrawalCancellationStatus.RESTORING;
        this.lastError = null;
    }

    /** 本人の明示操作により由来が上書きされた（終端・復旧対象外）。 */
    public void markSuperseded() {
        this.status = MembershipPayerWithdrawalCancellationStatus.SUPERSEDED;
    }

    /** この行が復旧に着手してよい状態か（{@code SUCCEEDED} か、着手済みで未確定の {@code RESTORING}）。 */
    public boolean isRestorable() {
        return this.status == MembershipPayerWithdrawalCancellationStatus.SUCCEEDED
                || this.status == MembershipPayerWithdrawalCancellationStatus.RESTORING;
    }

    /** Stripe・DB 双方の確定を記録する。 */
    public void markSucceeded(Instant scheduledAt) {
        this.status = MembershipPayerWithdrawalCancellationStatus.SUCCEEDED;
        this.scheduledAt = scheduledAt;
        this.lastError = null;
    }

    /** 失敗を記録する（再試行対象として残す）。 */
    public void markFailed(String error) {
        this.status = MembershipPayerWithdrawalCancellationStatus.FAILED;
        this.lastError = truncate(error);
    }

    /** 退会取消による復旧の確定を記録する（終端）。 */
    public void markRestored(Instant restoredAt) {
        this.status = MembershipPayerWithdrawalCancellationStatus.RESTORED;
        this.restoredAt = restoredAt;
        this.lastError = null;
    }

    /** {@code last_error} は VARCHAR(1000)。スタックトレース混入で INSERT ごと落とさないため機械的に切る。 */
    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1000 ? error : error.substring(0, 1000);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = MembershipPayerWithdrawalCancellationStatus.PENDING;
        }
        if (this.attemptCount == null) {
            this.attemptCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
