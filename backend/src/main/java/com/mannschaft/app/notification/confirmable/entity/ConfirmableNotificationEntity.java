package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.membership.ScopeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知エンティティ。
 *
 * <p>チーム・組織メンバーへの確認要求を管理する。
 * 送信者は期限・優先度を設定し、受信者はAPP/TOKEN/BULKのいずれかで確認できる。</p>
 *
 * <p><b>ドメインルール</b>:
 * <ul>
 *   <li>{@code cancel()} — ACTIVE 状態のみキャンセル可能（Service 層でチェック）</li>
 *   <li>{@code complete()} — ACTIVE 状態のみ完了可能（全員確認またはバッチ起動）</li>
 *   <li>{@code expire()} — バッチジョブが deadline_at 超過を検知して呼び出す</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "confirmable_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ConfirmableNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 通知発生元種別（ポリモルフィック参照）。
     *
     * <p>DB 実体は {@code source_type VARCHAR(40) NOT NULL DEFAULT 'EMERGENCY_CLOSURE'}
     * （Java enum ではなく自由文字列で管理。MySQL ENUM ではない）。
     * 現状値: {@code EMERGENCY_CLOSURE} / {@code RECRUITMENT_LISTING} に加え、
     * F22.1 市の最終認証で {@code MARKET_FINALIZE} を使用する。</p>
     *
     * <p><b>⚠ 未知の値を握り潰さず安全に無視すること</b>: バッチ/UI は未知 source_type を
     * {@code IllegalArgumentException} で連鎖故障させない防御を持つこと（01_data_model §5 警告）。</p>
     */
    @Column(name = "source_type", nullable = false, length = 40)
    @Builder.Default
    private String sourceType = "EMERGENCY_CLOSURE";

    /**
     * 通知発生元のレコードID（ポリモルフィック参照・FK なし）。
     *
     * <p>{@code source_type='MARKET_FINALIZE'} のとき {@code recruitment_listings.id} を保持する。
     * 発生元ドメインをまたぐためクロスドメイン FK は張らない（CLAUDE.md 原則 1）。</p>
     */
    @Column(name = "source_id")
    private Long sourceId;

    /** スコープ種別（TEAM / ORGANIZATION） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ScopeType scopeType;

    /** スコープID（チームIDまたは組織ID） */
    @Column(nullable = false)
    private Long scopeId;

    /** 通知タイトル（最大200文字） */
    @Column(nullable = false, length = 200)
    private String title;

    /** 通知本文（任意） */
    @Column(columnDefinition = "TEXT")
    private String body;

    /** 優先度（NORMAL / HIGH / URGENT） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ConfirmableNotificationPriority priority = ConfirmableNotificationPriority.NORMAL;

    /** ステータス（ACTIVE / COMPLETED / EXPIRED / CANCELLED） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConfirmableNotificationStatus status = ConfirmableNotificationStatus.ACTIVE;

    /** 確認期限。NULL は無期限。 */
    @Column
    private LocalDateTime deadlineAt;

    /**
     * 1回目リマインド送信タイミング（分）。
     * NULL の場合はスコープ設定（ConfirmableNotificationSettingsEntity）を継承。
     */
    @Column
    private Integer firstReminderMinutes;

    /**
     * 2回目リマインド送信タイミング（分）。
     * NULL の場合はスコープ設定を継承。
     */
    @Column
    private Integer secondReminderMinutes;

    /** 確認ボタン遷移先URL（任意） */
    @Column(length = 500)
    private String actionUrl;

    /** キャンセル日時 */
    @Column
    private LocalDateTime cancelledAt;

    /** キャンセル実行者（削除時 NULL に設定） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private UserEntity cancelledBy;

    /** 完了日時 */
    @Column
    private LocalDateTime completedAt;

    /** 期限切れ日時（バッチが設定） */
    @Column
    private LocalDateTime expiredAt;

    /**
     * 受信者総数（受信者追加時に更新）。
     * 確認率計算の分母として使用。
     *
     * <p>CMP-260920-1040 以降、非同期経路では「受け付けた時点の見込み件数」ではなく
     * 「実際に作った受信者行の数」を表す（軍議第8版確定稿 §3.1）。チャンクごとに加算する。</p>
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer totalRecipientCount = 0;

    /**
     * CMP-260920-1040: 配信状態（軍議第8版確定稿 §9.1）。
     *
     * <p>既存行と同期経路の {@code send} は DELIVERED のままとする。
     * 非同期経路（宛先指定の fanout）は QUEUED から開始し、ワーカーが遷移させる。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 20)
    @Builder.Default
    private ConfirmableNotificationDeliveryStatus deliveryStatus = ConfirmableNotificationDeliveryStatus.DELIVERED;

    /**
     * CMP-260920-1040: ワーカーが作った受信者行の数（軍議第8版確定稿 §3.1）。
     * 同期経路では常に 0（total_recipient_count と重複しない）。
     */
    @Column(name = "delivered_count", nullable = false)
    @Builder.Default
    private Integer deliveredCount = 0;

    /**
     * CMP-260920-1040: 未確認件数のカウンタ（軍議第8版確定稿 §10.1）。
     *
     * <p><b>更新してよいのは、この行を {@code SELECT ... FOR UPDATE} でロックしている
     * トランザクションだけ</b>である（出陣で実装するワーカー・confirm・cancel 等がこの規約に従う）。
     * 完了判定は {@code unconfirmedCount == 0 && deliveryStatus == DELIVERED && totalRecipientCount > 0}
     * を、ロックした親の行の値だけで行う（受信者表への通常の COUNT は使わない）。</p>
     */
    @Column(name = "unconfirmed_count", nullable = false)
    @Builder.Default
    private Integer unconfirmedCount = 0;

    /**
     * 未確認者リストの公開範囲（HIDDEN / CREATOR_AND_ADMIN / ALL_MEMBERS）。
     *
     * <p>送信時にリクエスト値、または省略時はスコープ設定
     * （{@link ConfirmableNotificationSettingsEntity#getDefaultUnconfirmedVisibility()}）の値を
     * スナップショットする。後からスコープ設定を変更しても本値は不変。</p>
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private UnconfirmedVisibility unconfirmedVisibility = UnconfirmedVisibility.CREATOR_AND_ADMIN;

    /** 使用したテンプレートID（参照用。テンプレート削除後も記録を保持） */
    @Column
    private Long templateId;

    /** 作成者（退会時 NULL に設定） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private UserEntity createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // -------------------------------------------------------------------------
    // ドメインメソッド
    // -------------------------------------------------------------------------

    /**
     * 通知をキャンセルする。
     *
     * <p>送信者が通知を取り消した際に呼び出す。
     * ACTIVE 以外の状態からのキャンセル可否は Service 層でチェックすること。</p>
     *
     * @param cancelledBy キャンセル実行者
     */
    public void cancel(UserEntity cancelledBy) {
        this.status = ConfirmableNotificationStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelledBy = cancelledBy;
    }

    /**
     * 通知を完了状態にする。
     *
     * <p>全受信者の確認完了時、または管理者による手動完了時に呼び出す。</p>
     */
    public void complete() {
        this.status = ConfirmableNotificationStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 通知を期限切れ状態にする。
     *
     * <p>バッチジョブが {@code deadline_at} を超過した ACTIVE 通知に対して呼び出す。</p>
     */
    public void expire() {
        this.status = ConfirmableNotificationStatus.EXPIRED;
        this.expiredAt = LocalDateTime.now();
    }

    /**
     * 通知が受付中（ACTIVE）かどうかを判定する。
     *
     * @return ACTIVE の場合 true
     */
    public boolean isActive() {
        return this.status == ConfirmableNotificationStatus.ACTIVE;
    }

    /**
     * 受信者総数を更新する（受信者追加・除外時）。
     *
     * @param count 新しい受信者総数
     */
    public void updateTotalRecipientCount(int count) {
        this.totalRecipientCount = count;
    }

    /**
     * CMP-260920-1040: unconfirmed_count を加算する（軍議第8版確定稿 §10.1）。
     *
     * <p>この行を {@code findByIdForUpdate} でロックしているトランザクションからのみ呼ぶこと
     * （チャンクで受信者を作ったときの加算用）。</p>
     *
     * @param delta 加算する件数（マイナス不可）
     */
    public void addUnconfirmedCount(int delta) {
        this.unconfirmedCount = this.unconfirmedCount + delta;
    }

    /**
     * CMP-260920-1040: unconfirmed_count を 1 減らす（軍議第8版確定稿 §10.1）。
     *
     * <p>受信者行が未確認から確認済みへ実際に変わったとき、または除外されたときにだけ呼ぶこと。
     * 0 未満にはしない（二重減算の防御）。</p>
     */
    public void decrementUnconfirmedCount() {
        this.unconfirmedCount = Math.max(0, this.unconfirmedCount - 1);
    }

    /**
     * CMP-260920-1040: delivery_status を DELIVERING に遷移する（軍議第8版確定稿 §3.4）。
     *
     * <p>{@code findByIdForUpdate} でロックしているトランザクションからのみ呼ぶこと。</p>
     */
    public void markDelivering() {
        this.deliveryStatus = ConfirmableNotificationDeliveryStatus.DELIVERING;
    }

    /**
     * CMP-260920-1040: delivery_status を DELIVERED に遷移する（軍議第8版確定稿 §9.2）。
     */
    public void markDelivered() {
        this.deliveryStatus = ConfirmableNotificationDeliveryStatus.DELIVERED;
    }

    /**
     * CMP-260920-1040: delivery_status を PARTIALLY_FAILED に遷移する（軍議第8版確定稿 §3.4・§8.3）。
     *
     * <p>課金の猶予超過などで途中から配れなくなった場合に呼ぶ。一度 PARTIALLY_FAILED になったら
     * {@link #finishIfNotAlreadyTerminal()} 系の判定で上書きしないこと（呼び出し側の契約）。</p>
     */
    public void markPartiallyFailed() {
        this.deliveryStatus = ConfirmableNotificationDeliveryStatus.PARTIALLY_FAILED;
    }

    /**
     * CMP-260920-1040: delivery_status を STOPPED に遷移する（軍議第8版確定稿 §9.1・§9.2）。
     *
     * <p>打ち切った理由は本メソッドでは持たない。呼び出し側が親の status（CANCELLED / EXPIRED）を
     * 見て表示を出し分ける。</p>
     */
    public void markStopped() {
        this.deliveryStatus = ConfirmableNotificationDeliveryStatus.STOPPED;
    }

    /**
     * CMP-260920-1040: total_recipient_count / delivered_count を加算する（軍議第8版確定稿 §3.4 手順5）。
     *
     * <p>{@code findByIdForUpdate} でロックしているトランザクションからのみ呼ぶこと。</p>
     */
    public void addDeliveredCount(int delta) {
        this.totalRecipientCount = this.totalRecipientCount + delta;
        this.deliveredCount = this.deliveredCount + delta;
    }

    /**
     * CMP-260920-1040: 完了判定（軍議第8版確定稿 §10.1・§9.2）。
     *
     * <p>{@code unconfirmedCount == 0 && deliveryStatus == DELIVERED && totalRecipientCount > 0} を、
     * ロックした親の行の値だけで判定する。0 人（誰も配信していない）で「全員確認済み」を成立させない
     * （AC-54）。</p>
     */
    public boolean isReadyToComplete() {
        return this.unconfirmedCount == 0
                && this.deliveryStatus == ConfirmableNotificationDeliveryStatus.DELIVERED
                && this.totalRecipientCount > 0;
    }
}
