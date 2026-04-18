package com.mannschaft.app.committee.entity;

import com.mannschaft.app.common.BaseEntity;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 委員会エンティティ。組織内の委員会情報を管理する。
 */
@Entity
@Table(name = "committees")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class CommitteeEntity extends BaseEntity {

    /** 所属組織ID */
    @Column(nullable = false)
    private Long organizationId;

    /** 委員会名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 説明 */
    @Column(length = 500)
    private String description;

    /** 目的タグ */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CommitteePurposeTag purposeTag;

    /** 活動開始日 */
    private LocalDate startDate;

    /** 活動終了日 */
    private LocalDate endDate;

    /** ステータス */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CommitteeStatus status = CommitteeStatus.DRAFT;

    /** 組織メンバーへの公開範囲 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CommitteeVisibility visibilityToOrg = CommitteeVisibility.NAME_ONLY;

    /** デフォルト確認モード */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ConfirmationMode defaultConfirmationMode = ConfirmationMode.OPTIONAL;

    /** デフォルトでお知らせ通知を有効にするか */
    @Column(nullable = false)
    @Builder.Default
    private boolean defaultAnnouncementEnabled = true;

    /** デフォルト配信範囲 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    @Builder.Default
    private DistributionScope defaultDistributionScope = DistributionScope.COMMITTEE_ONLY;

    /** アーカイブ日時 */
    private LocalDateTime archivedAt;

    /** 作成者ユーザーID */
    private Long createdBy;

    /** 論理削除日時 */
    private LocalDateTime deletedAt;

    /**
     * ステータスを更新する。
     */
    public void updateStatus(CommitteeStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * アーカイブ日時を設定する。
     */
    public void setArchivedAt(LocalDateTime archivedAt) {
        this.archivedAt = archivedAt;
    }

    /**
     * 論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
