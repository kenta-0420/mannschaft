package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.enums.RewardType;
import com.mannschaft.app.jobmatching.enums.VisibilityScope;
import com.mannschaft.app.jobmatching.enums.WorkLocationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 求人投稿エンティティ。F13.1 Phase 13.1.1 MVP。
 *
 * <p>論理削除（deleted_at）を {@link SQLRestriction} で自動除外する。
 * {@link Version} による楽観的ロックで同時編集の競合を防ぐ。</p>
 */
@Entity
@Table(name = "job_postings")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class JobPostingEntity extends BaseEntity {

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(length = 50)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(name = "work_location_type", nullable = false, length = 20)
    private WorkLocationType workLocationType;

    @Column(name = "work_address", length = 255)
    private String workAddress;

    @Column(name = "work_start_at", nullable = false)
    private LocalDateTime workStartAt;

    @Column(name = "work_end_at", nullable = false)
    private LocalDateTime workEndAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "reward_type", nullable = false, length = 20)
    private RewardType rewardType;

    @Column(name = "base_reward_jpy", nullable = false)
    private Integer baseRewardJpy;

    @Column(nullable = false)
    private Integer capacity;

    @Column(name = "application_deadline_at", nullable = false)
    private LocalDateTime applicationDeadlineAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_scope", nullable = false, length = 30)
    private VisibilityScope visibilityScope;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobPostingStatus status;

    @Column(name = "publish_at")
    private LocalDateTime publishAt;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 0;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /**
     * 求人の編集可能フィールドを部分更新する。引数が {@code null} のフィールドは「未指定＝変更なし」として現状を維持する。
     *
     * <p>managed entity を直接ミューテートするため主キー（id）・楽観ロック version を保持し、{@code save()} が
     * UPDATE になる。旧実装は {@code toBuilder()...build()} で別インスタンスを作り直していたが、
     * {@code @SuperBuilder(toBuilder = true)} は {@link BaseEntity} 継承の id を引き継がず id=null となり save が
     * INSERT 化する不具合があったため、本メソッドへ集約した。</p>
     *
     * <p>引数の値検証（報酬範囲・公開範囲・日時整合性・応募者ありの場合の不変フィールドチェック）は
     * 呼び出し側 Service の責務であり、本メソッドは値の差し替えのみを行う。</p>
     */
    public void applyUpdate(String title, String description, String category,
                            WorkLocationType workLocationType, String workAddress,
                            LocalDateTime workStartAt, LocalDateTime workEndAt,
                            RewardType rewardType, Integer baseRewardJpy, Integer capacity,
                            LocalDateTime applicationDeadlineAt, VisibilityScope visibilityScope,
                            LocalDateTime publishAt) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (category != null) {
            this.category = category;
        }
        if (workLocationType != null) {
            this.workLocationType = workLocationType;
        }
        if (workAddress != null) {
            this.workAddress = workAddress;
        }
        if (workStartAt != null) {
            this.workStartAt = workStartAt;
        }
        if (workEndAt != null) {
            this.workEndAt = workEndAt;
        }
        if (rewardType != null) {
            this.rewardType = rewardType;
        }
        if (baseRewardJpy != null) {
            this.baseRewardJpy = baseRewardJpy;
        }
        if (capacity != null) {
            this.capacity = capacity;
        }
        if (applicationDeadlineAt != null) {
            this.applicationDeadlineAt = applicationDeadlineAt;
        }
        if (visibilityScope != null) {
            this.visibilityScope = visibilityScope;
        }
        if (publishAt != null) {
            this.publishAt = publishAt;
        }
    }

    /**
     * 求人を公開（DRAFT → OPEN）する。
     */
    public void publish() {
        this.status = JobPostingStatus.OPEN;
    }

    /**
     * 求人を募集終了（OPEN → CLOSED）する。定員充足・締切通過時に使用する。
     */
    public void close() {
        this.status = JobPostingStatus.CLOSED;
    }

    /**
     * 求人をキャンセルする。
     */
    public void cancel() {
        this.status = JobPostingStatus.CANCELLED;
    }

    /**
     * 求人を論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 求人の論理削除を取り消す。
     */
    public void restore() {
        this.deletedAt = null;
    }
}
