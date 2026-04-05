package com.mannschaft.app.moderation.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.moderation.AppealStatus;
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

import java.time.LocalDateTime;

/**
 * 異議申立てエンティティ。トークンベースの異議申立てフローを管理する。
 */
@Entity
@Table(name = "moderation_appeals")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ModerationAppealEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long reportId;

    @Column(nullable = false)
    private Long actionId;

    @Column(nullable = false, length = 64, unique = true)
    private String appealToken;

    @Column(nullable = false)
    private LocalDateTime appealTokenExpiresAt;

    @Column(columnDefinition = "TEXT")
    private String appealReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AppealStatus status = AppealStatus.INVITED;

    private Long reviewedBy;

    @Column(columnDefinition = "TEXT")
    private String reviewNote;

    private LocalDateTime reviewedAt;

    /**
     * 異議申立て理由を送信する（INVITED→PENDING）。
     *
     * @param reason 申立て理由
     */
    public void submit(String reason) {
        this.appealReason = reason;
        this.status = AppealStatus.PENDING;
    }

    /**
     * 異議申立てをレビューする。
     *
     * @param reviewerId レビュアーのユーザーID
     * @param note       レビューメモ
     * @param newStatus  新しいステータス（ACCEPTED or REJECTED）
     */
    public void review(Long reviewerId, String note, AppealStatus newStatus) {
        this.reviewedBy = reviewerId;
        this.reviewNote = note;
        this.status = newStatus;
        this.reviewedAt = LocalDateTime.now();
    }
}
