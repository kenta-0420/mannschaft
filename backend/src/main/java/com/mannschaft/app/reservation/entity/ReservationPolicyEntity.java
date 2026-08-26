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

    /**
     * 仮押さえ自動失効の既定時間数（24 時間・F03.4.5 §6.3 マスター確定）。
     *
     * <p>DDL の {@code pending_expire_hours INT NULL DEFAULT 24} と同値。ポリシー行を持たないチームの
     * フォールバック値としてバッチ・{@code getOrDefault} が共有する単一の正準定数。</p>
     */
    public static final int DEFAULT_PENDING_EXPIRE_HOURS = 24;

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

    /**
     * 仮押さえ(PENDING)の自動失効までの時間数（F03.4.5 §6.3・W2-6）。
     *
     * <p>{@code NULL} = 自動失効しない（管理者が明示的に無効化した状態）。
     * 値がある場合は 1〜168 の範囲（検証は {@code UpdateReservationSettingRequest} の
     * {@code @Min(1)}/{@code @Max(168)}）。既定は {@link #DEFAULT_PENDING_EXPIRE_HOURS}（24 時間）で、
     * DB 側も {@code INT NULL DEFAULT 24} のため新規行・既存行とも 24 に揃う。</p>
     *
     * <p><b>「行が存在しないチーム」の扱い</b>: {@code reservation_policies} は初回の設定変更で
     * 初めて行が作られるため、大半のチームは行を持たない。{@link ReservationPolicyService#getOrDefault}
     * が返す未永続エンティティも本フィールドの既定値（24）を持ち、失効バッチも行が無いチームを
     * 24 時間として扱う（{@code ReservationRepository.findExpirablePendingPrimaryRows} の
     * {@code COALESCE} 既定）。「GET は 24 と答えるのに実際は何も失効しない」という
     * 応答と挙動の乖離を作らないための一貫性である。</p>
     */
    @Column(name = "pending_expire_hours")
    @Builder.Default
    private Integer pendingExpireHours = DEFAULT_PENDING_EXPIRE_HOURS;

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
     * <p>{@code pendingExpireHours} だけは「値の設定」と「無効化（NULL 化）」を区別する必要があるため、
     * 部分更新セマンティクス（null=据え置き）では NULL へ戻せない。枠(slot)側の
     * {@code UpdateSlotRequest.clearApprovalMode} と同形で {@code clearPendingExpireHours} フラグを
     * 併用し、<b>clear=true を値指定より優先</b>する（両方指定は「無効化したい」意図が勝つ）。</p>
     *
     * @param approvalMode            承認モード（null の場合は据え置き）
     * @param cancelDeadlineHours     キャンセル締切時間（null の場合は据え置き）
     * @param remindBeforeHours       リマインド CSV（null の場合は据え置き）
     * @param pendingExpireHours      仮押さえ自動失効の時間数（null の場合は据え置き）
     * @param clearPendingExpireHours true の場合 {@code pendingExpireHours} を NULL（自動失効しない）へ戻す。
     *                                {@code pendingExpireHours} の指定より優先する
     */
    public void updatePolicy(ApprovalMode approvalMode, Integer cancelDeadlineHours, String remindBeforeHours,
                             Integer pendingExpireHours, Boolean clearPendingExpireHours) {
        if (approvalMode != null) {
            this.approvalMode = approvalMode;
        }
        if (cancelDeadlineHours != null) {
            this.cancelDeadlineHours = cancelDeadlineHours;
        }
        if (remindBeforeHours != null) {
            this.remindBeforeHours = remindBeforeHours;
        }
        // clear を先に評価すると値指定で上書きされてしまうため、clear を後段（優先）に置く。
        if (pendingExpireHours != null) {
            this.pendingExpireHours = pendingExpireHours;
        }
        if (Boolean.TRUE.equals(clearPendingExpireHours)) {
            this.pendingExpireHours = null;
        }
    }
}
