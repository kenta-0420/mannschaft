package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: 参加者レコード。
 * BaseEntity は継承しない (テーブルは applied_at / status_changed_at の対を持ち
 * created_at / updated_at は無いため)。
 *
 * active_subject_key は DB 側 STORED 生成カラムなので Java では更新しない。
 */
@Entity
@Table(name = "recruitment_participants")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentParticipantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long listingId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentParticipantType participantType;

    private Long userId;

    private Long teamId;

    @Column(nullable = false)
    private Long appliedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentParticipantStatus status = RecruitmentParticipantStatus.APPLIED;

    private Integer waitlistPosition;

    @Column(length = 500)
    private String note;

    @Column(nullable = false)
    private LocalDateTime appliedAt;

    @Column(nullable = false)
    private LocalDateTime statusChangedAt;

    private Long cancelledBy;

    private LocalDateTime deletedAt;

    /** active_subject_key は DB 生成カラム。Java からは読み取り専用。 */
    @Column(name = "active_subject_key", insertable = false, updatable = false, length = 100)
    private String activeSubjectKey;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.appliedAt == null) this.appliedAt = now;
        if (this.statusChangedAt == null) this.statusChangedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.statusChangedAt = LocalDateTime.now();
    }

    // ===========================================
    // ステータス遷移メソッド
    // ===========================================

    /** APPLIED → CONFIRMED に確定する。 */
    public void confirm() {
        this.status = RecruitmentParticipantStatus.CONFIRMED;
    }

    /** APPLIED → WAITLISTED に切り替える (満員時)。 */
    public void waitlist(int position) {
        this.status = RecruitmentParticipantStatus.WAITLISTED;
        this.waitlistPosition = position;
    }

    /** WAITLISTED → CONFIRMED の自動昇格 (Phase 3 用、Phase 1 では未使用)。 */
    public void promoteToConfirmed() {
        if (this.status != RecruitmentParticipantStatus.WAITLISTED) {
            throw new IllegalStateException("WAITLISTED 以外からは promote できません: status=" + this.status);
        }
        this.status = RecruitmentParticipantStatus.CONFIRMED;
        this.waitlistPosition = null;
    }

    /** ユーザー本人によるキャンセル。 */
    public void cancelByUser() {
        if (this.status != RecruitmentParticipantStatus.CONFIRMED
                && this.status != RecruitmentParticipantStatus.WAITLISTED
                && this.status != RecruitmentParticipantStatus.APPLIED) {
            throw new IllegalStateException("CONFIRMED/WAITLISTED/APPLIED 以外はキャンセルできません: status=" + this.status);
        }
        this.status = RecruitmentParticipantStatus.CANCELLED;
        this.cancelledBy = this.userId != null ? this.userId : this.appliedBy;
    }

    /** 管理者によるキャンセル。 */
    public void cancelByAdmin(Long actorUserId) {
        this.status = RecruitmentParticipantStatus.CANCELLED;
        this.cancelledBy = actorUserId;
    }

    /** 出席チェック (Phase 1 で実装する管理者操作)。 */
    public void markAttended() {
        if (this.status != RecruitmentParticipantStatus.CONFIRMED) {
            throw new IllegalStateException("CONFIRMED でない参加者は出席チェックできません: status=" + this.status);
        }
        this.status = RecruitmentParticipantStatus.ATTENDED;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
