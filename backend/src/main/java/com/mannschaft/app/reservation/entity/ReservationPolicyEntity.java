package com.mannschaft.app.reservation.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reservation.ApprovalMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * チームごとの予約既定ポリシーエンティティ（1チーム1行）。
 *
 * <p>承認モード(approval_mode)の解決はチーム既定 ＋ 枠(slot)で上書きする方式（マスター御裁可）:
 * 「枠の値があればそれ／無ければチーム設定(本エンティティ)／それも無ければ AUTO」。
 * 本エンティティはそのチーム既定値を保持する。</p>
 *
 * <p>team_id は teams ドメインへのクロスドメイン参照のため FK なし、
 * インデックスのみで整合性をアプリ層で保証する（アーキ原則1）。</p>
 *
 * <p>{@code allow_public_reservation} を扱う {@code ReservationTeamSettingEntity} とは
 * 別テーブル（{@code reservation_policies}）として分離維持する。</p>
 *
 * <p>レコードが存在しないチームは {@link ReservationPolicyService#getOrDefault(Long)} が
 * 既定値（approvalMode=AUTO 等）の未永続エンティティを返す。</p>
 */
@Entity
@Table(name = "reservation_policies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReservationPolicyEntity extends UuidV7Entity {

    /** チームID（teams テーブルへのクロスドメイン参照・FK なし）。 */
    @Column(name = "team_id", nullable = false, unique = true)
    private Long teamId;

    /** 承認モードの既定値。AUTO=自動承認 / MANUAL=管理者の手動承認。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "approval_mode", nullable = false, length = 10)
    @Builder.Default
    private ApprovalMode approvalMode = ApprovalMode.AUTO;

    /** キャンセル受付の締切（予約開始の何時間前まで）。MVP では保持のみ。 */
    @Column(name = "cancel_deadline_hours", nullable = false)
    @Builder.Default
    private Integer cancelDeadlineHours = 24;

    /** リマインド送信タイミング（予約開始の何時間前か）の CSV。⑥リマインドで使用（保持のみ）。 */
    @Column(name = "remind_before_hours", nullable = false, length = 64)
    @Builder.Default
    private String remindBeforeHours = "24,1";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * ポリシー値を更新する。null を渡したフィールドは更新しない（部分更新）。
     *
     * @param approvalMode        承認モード（null の場合は据え置き）
     * @param cancelDeadlineHours キャンセル締切時間（null の場合は据え置き）
     * @param remindBeforeHours   リマインド CSV（null の場合は据え置き）
     */
    public void updatePolicy(ApprovalMode approvalMode, Integer cancelDeadlineHours, String remindBeforeHours) {
        if (approvalMode != null) {
            this.approvalMode = approvalMode;
        }
        if (cancelDeadlineHours != null) {
            this.cancelDeadlineHours = cancelDeadlineHours;
        }
        if (remindBeforeHours != null) {
            this.remindBeforeHours = remindBeforeHours;
        }
    }
}
