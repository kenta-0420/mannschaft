package com.mannschaft.app.notification.confirmable.entity;

import com.mannschaft.app.auth.entity.UserEntity;
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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * F04.9 確認通知受信者エンティティ。
 *
 * <p>確認通知に対する各受信者の確認状態・リマインド送信状況を管理する。</p>
 *
 * <p><b>リマインド判定ロジック</b>（バッチジョブが使用）:
 * <ul>
 *   <li>{@code needsFirstReminder()} — 1回目リマインド送信要否</li>
 *   <li>{@code needsSecondReminder()} — 2回目リマインド送信要否</li>
 * </ul>
 * </p>
 */
@Entity
@Table(
        name = "confirmable_notification_recipients",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_cnr_notification_user",
                columnNames = {"confirmable_notification_id", "user_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ConfirmableNotificationRecipientEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 対象の確認通知 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmable_notification_id", nullable = false)
    private ConfirmableNotificationEntity confirmableNotification;

    /** 受信者ユーザー */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    /**
     * URLトークン確認用UUID（36文字）。
     * メール・LINE経由でのトークン確認に使用する。
     */
    @Column(nullable = false, length = 36, unique = true)
    private String confirmToken;

    /** 確認済みフラグ */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isConfirmed = false;

    /** 確認日時 */
    @Column
    private LocalDateTime confirmedAt;

    /** 確認経路（APP / TOKEN / BULK） */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ConfirmedVia confirmedVia;

    /**
     * 実際に適用された1回目リマインド送信タイミング（分）。
     * 通知作成時に通知設定またはスコープ設定から解決された値を保持する。
     */
    @Column
    private Integer resolvedFirstReminderMinutes;

    /**
     * 実際に適用された2回目リマインド送信タイミング（分）。
     * 通知作成時に通知設定またはスコープ設定から解決された値を保持する。
     */
    @Column
    private Integer resolvedSecondReminderMinutes;

    /** 1回目リマインド送信日時 */
    @Column
    private LocalDateTime firstReminderSentAt;

    /** 2回目リマインド送信日時 */
    @Column
    private LocalDateTime secondReminderSentAt;

    /**
     * 除外日時。管理者が受信者を確認免除した日時。
     * NULL の場合は除外されていない。
     */
    @Column
    private LocalDateTime excludedAt;

    /** 除外を実行した管理者（退会時 NULL に設定） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excluded_by")
    private UserEntity excludedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    // -------------------------------------------------------------------------
    // ドメインメソッド
    // -------------------------------------------------------------------------

    /**
     * 確認操作を記録する。
     *
     * @param via 確認経路（APP / TOKEN / BULK）
     */
    public void confirm(ConfirmedVia via) {
        this.isConfirmed = true;
        this.confirmedAt = LocalDateTime.now();
        this.confirmedVia = via;
    }

    /**
     * 1回目リマインド送信完了を記録する。
     */
    public void markFirstReminderSent() {
        this.firstReminderSentAt = LocalDateTime.now();
    }

    /**
     * 2回目リマインド送信完了を記録する。
     */
    public void markSecondReminderSent() {
        this.secondReminderSentAt = LocalDateTime.now();
    }

    /**
     * 受信者を除外（確認免除）する。
     *
     * @param excludedBy 除外を実行した管理者
     */
    public void markExcluded(UserEntity excludedBy) {
        this.excludedAt = LocalDateTime.now();
        this.excludedBy = excludedBy;
    }

    /**
     * 除外済みかどうかを判定する。
     *
     * @return 除外済みの場合 true
     */
    public boolean isExcluded() {
        return this.excludedAt != null;
    }

    /**
     * 1回目リマインドが必要かどうかを判定する（バッチジョブ用）。
     *
     * <p>条件:
     * <ul>
     *   <li>未確認</li>
     *   <li>除外されていない</li>
     *   <li>1回目リマインドが未送信</li>
     *   <li>{@code resolvedFirstReminderMinutes} が設定されている</li>
     *   <li>通知送信時刻 + resolvedFirstReminderMinutes &lt;= now（経過時間チェック）</li>
     *   <li>deadline がある場合は期限前</li>
     * </ul>
     * </p>
     *
     * @param now 現在日時
     * @return リマインドが必要な場合 true
     */
    public boolean needsFirstReminder(LocalDateTime now) {
        if (Boolean.TRUE.equals(this.isConfirmed)) return false;
        if (isExcluded()) return false;
        if (this.firstReminderSentAt != null) return false;
        if (this.resolvedFirstReminderMinutes == null) return false;

        // 通知送信時刻（confirmable_notification.created_at）+ 設定分数を経過していなければスキップ
        // これにより、送信直後のバッチ実行でリマインドが誤送信されることを防ぐ
        LocalDateTime triggerTime = this.confirmableNotification.getCreatedAt()
                .plusMinutes(this.resolvedFirstReminderMinutes);
        if (now.isBefore(triggerTime)) return false;

        // deadline がある場合は期限前のみリマインド対象
        LocalDateTime deadline = this.confirmableNotification.getDeadlineAt();
        if (deadline != null && now.isAfter(deadline)) return false;

        return true;
    }

    /**
     * 2回目リマインドが必要かどうかを判定する（バッチジョブ用）。
     *
     * <p>条件:
     * <ul>
     *   <li>未確認</li>
     *   <li>除外されていない</li>
     *   <li>2回目リマインドが未送信</li>
     *   <li>1回目リマインドが送信済み</li>
     *   <li>{@code resolvedSecondReminderMinutes} が設定されている</li>
     *   <li>1回目送信時刻 + resolvedSecondReminderMinutes &lt;= now（経過時間チェック）</li>
     *   <li>deadline がある場合は期限前</li>
     * </ul>
     * </p>
     *
     * @param now 現在日時
     * @return リマインドが必要な場合 true
     */
    public boolean needsSecondReminder(LocalDateTime now) {
        if (Boolean.TRUE.equals(this.isConfirmed)) return false;
        if (isExcluded()) return false;
        if (this.secondReminderSentAt != null) return false;
        if (this.firstReminderSentAt == null) return false;
        if (this.resolvedSecondReminderMinutes == null) return false;

        // 1回目リマインド送信時刻 + 設定分数を経過していなければスキップ
        LocalDateTime triggerTime = this.firstReminderSentAt
                .plusMinutes(this.resolvedSecondReminderMinutes);
        if (now.isBefore(triggerTime)) return false;

        // deadline がある場合は期限前のみリマインド対象
        LocalDateTime deadline = this.confirmableNotification.getDeadlineAt();
        if (deadline != null && now.isAfter(deadline)) return false;

        return true;
    }
}
