package com.mannschaft.app.recruitment.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * F03.11 募集型予約: 募集枠メインエンティティ。
 * Phase 1+5a で扱うステータスは DRAFT/OPEN/FULL/CLOSED/CANCELLED の5値。
 * AUTO_CANCELLED/COMPLETED は Phase 3 以降。
 */
@Entity
@Table(name = "recruitment_listings")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class RecruitmentListingEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentScopeType scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    private Long categoryId;

    private Long subcategoryId;

    private Long templateId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RecruitmentParticipationType participationType;

    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    @Column(nullable = false)
    private LocalDateTime applicationDeadline;

    @Column(nullable = false)
    private LocalDateTime autoCancelAt;

    @Column(nullable = false)
    private Integer capacity;

    @Column(nullable = false)
    private Integer minCapacity;

    @Column(nullable = false)
    @Builder.Default
    private Integer confirmedCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer waitlistCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer waitlistMax = 100;

    @Column(nullable = false)
    @Builder.Default
    private Boolean paymentEnabled = false;

    private Integer price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentVisibility visibility = RecruitmentVisibility.SCOPE_ONLY;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private RecruitmentListingStatus status = RecruitmentListingStatus.DRAFT;

    @Column(length = 200)
    private String location;

    private Long reservationLineId;

    @Column(length = 500)
    private String imageUrl;

    private Long cancellationPolicyId;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime cancelledAt;

    private Long cancelledBy;

    @Column(length = 200)
    private String cancelledReason;

    @Column(nullable = false)
    @Builder.Default
    private Integer participantCountCache = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer nextWaitlistPosition = 1;

    private LocalDateTime deletedAt;

    // ===========================================
    // ステータス遷移メソッド
    // ===========================================

    /** DRAFT → OPEN に遷移する。 */
    public void publish() {
        if (this.status != RecruitmentListingStatus.DRAFT) {
            throw new IllegalStateException("DRAFT 以外からは publish できません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.OPEN;
    }

    /** 主催者によるキャンセル。 */
    public void cancelByAdmin(Long actorUserId, String reason) {
        if (this.status == RecruitmentListingStatus.CANCELLED
                || this.status == RecruitmentListingStatus.AUTO_CANCELLED
                || this.status == RecruitmentListingStatus.COMPLETED) {
            throw new IllegalStateException("既に終了状態の募集はキャンセルできません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
        this.cancelledBy = actorUserId;
        this.cancelledReason = reason;
    }

    /** §5.4 バッチによる自動キャンセル（最小定員未達）。 */
    public void autoCancel() {
        if (this.status != RecruitmentListingStatus.OPEN && this.status != RecruitmentListingStatus.FULL) {
            throw new IllegalStateException("OPEN/FULL 以外は autoCancel できません: status=" + this.status);
        }
        this.status = RecruitmentListingStatus.AUTO_CANCELLED;
    }

    /** 論理削除を行う。 */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * テンプレートIDを紐付ける。
     * createFromTemplate() 時に、作成後にテンプレートIDを設定するために使用する。
     */
    public void assignTemplate(Long templateId) {
        this.templateId = templateId;
    }

    // ===========================================
    // 参加者カウント管理 (Service 層と連携)
    // ===========================================

    /**
     * 楽観的ロックの結果を反映する形で確定数を増やす。
     * Service層から原子クエリで status 更新済みの場合は再ロード後にこのメソッドで Java 側状態を同期する。
     */
    public void incrementConfirmed() {
        this.confirmedCount = this.confirmedCount + 1;
        if (this.status == RecruitmentListingStatus.OPEN
                && this.confirmedCount.intValue() >= this.capacity.intValue()) {
            this.status = RecruitmentListingStatus.FULL;
        }
        this.participantCountCache = this.participantCountCache + 1;
    }

    /**
     * 確定数を減らす。FULL 状態で空きが出れば OPEN に自動復帰 (§5.3)。
     */
    public void decrementConfirmed() {
        if (this.confirmedCount > 0) {
            this.confirmedCount = this.confirmedCount - 1;
        }
        if (this.status == RecruitmentListingStatus.FULL
                && this.confirmedCount.intValue() < this.capacity.intValue()) {
            this.status = RecruitmentListingStatus.OPEN;
        }
        if (this.participantCountCache > 0) {
            this.participantCountCache = this.participantCountCache - 1;
        }
    }

    /** キャンセル待ち数をインクリメントし、次の position 値を返す (§5.2 step8)。 */
    public int incrementWaitlistAndAcquirePosition() {
        if (this.waitlistCount.intValue() >= this.waitlistMax.intValue()) {
            throw new IllegalStateException("キャンセル待ち上限に達しています");
        }
        this.waitlistCount = this.waitlistCount + 1;
        int position = this.nextWaitlistPosition;
        this.nextWaitlistPosition = this.nextWaitlistPosition + 1;
        return position;
    }

    /** キャンセル待ち数をデクリメントする。 */
    public void decrementWaitlist() {
        if (this.waitlistCount > 0) {
            this.waitlistCount = this.waitlistCount - 1;
        }
    }

    // ===========================================
    // 編集 (§5.7)
    // ===========================================

    /**
     * 編集時の制約を強制しながら募集情報を更新する (§5.7)。
     * Service 層からも事前検証されるが、Entity 内でも防御的に再評価する。
     */
    public void updateForEdit(
            String title,
            String description,
            Long subcategoryId,
            LocalDateTime startAt,
            LocalDateTime endAt,
            LocalDateTime applicationDeadline,
            LocalDateTime autoCancelAt,
            Integer capacity,
            Integer minCapacity,
            Boolean paymentEnabled,
            Integer price,
            RecruitmentVisibility visibility,
            String location,
            Long reservationLineId,
            String imageUrl,
            Long cancellationPolicyId
    ) {
        if (this.status == RecruitmentListingStatus.COMPLETED
                || this.status == RecruitmentListingStatus.CANCELLED
                || this.status == RecruitmentListingStatus.AUTO_CANCELLED) {
            throw new IllegalStateException("終了済みの募集は編集できません: status=" + this.status);
        }
        if (capacity != null && capacity < this.confirmedCount) {
            throw new IllegalStateException("capacity を確定参加者数より少なく変更できません");
        }
        if (capacity != null && minCapacity != null && minCapacity > capacity) {
            throw new IllegalStateException("min_capacity > capacity は不正");
        }
        if (capacity != null && minCapacity == null && this.minCapacity > capacity) {
            throw new IllegalStateException("min_capacity > capacity は不正");
        }
        LocalDateTime effectiveStart = startAt != null ? startAt : this.startAt;
        LocalDateTime effectiveEnd = endAt != null ? endAt : this.endAt;
        LocalDateTime effectiveDeadline = applicationDeadline != null ? applicationDeadline : this.applicationDeadline;
        LocalDateTime effectiveAuto = autoCancelAt != null ? autoCancelAt : this.autoCancelAt;
        if (!effectiveStart.isBefore(effectiveEnd)) {
            throw new IllegalStateException("start_at < end_at が必要");
        }
        if (!effectiveDeadline.isBefore(effectiveStart)) {
            throw new IllegalStateException("application_deadline < start_at が必要");
        }
        if (effectiveAuto.isAfter(effectiveDeadline)) {
            throw new IllegalStateException("auto_cancel_at <= application_deadline が必要");
        }
        Boolean effectivePaymentEnabled = paymentEnabled != null ? paymentEnabled : this.paymentEnabled;
        Integer effectivePrice = price != null ? price : this.price;
        if (Boolean.TRUE.equals(effectivePaymentEnabled) && effectivePrice == null) {
            throw new IllegalStateException("決済を有効化する場合は料金が必要");
        }

        if (title != null) this.title = title;
        if (description != null) this.description = description;
        if (subcategoryId != null) this.subcategoryId = subcategoryId;
        if (startAt != null) this.startAt = startAt;
        if (endAt != null) this.endAt = endAt;
        if (applicationDeadline != null) this.applicationDeadline = applicationDeadline;
        if (autoCancelAt != null) this.autoCancelAt = autoCancelAt;
        if (capacity != null) this.capacity = capacity;
        if (minCapacity != null) this.minCapacity = minCapacity;
        if (paymentEnabled != null) this.paymentEnabled = paymentEnabled;
        if (price != null) this.price = price;
        if (visibility != null) this.visibility = visibility;
        if (location != null) this.location = location;
        if (reservationLineId != null) this.reservationLineId = reservationLineId;
        if (imageUrl != null) this.imageUrl = imageUrl;
        if (cancellationPolicyId != null) this.cancellationPolicyId = cancellationPolicyId;
    }
}
