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
import lombok.AllArgsConstructor;
import lombok.Builder;
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
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ConfirmableNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer totalRecipientCount = 0;

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
}
