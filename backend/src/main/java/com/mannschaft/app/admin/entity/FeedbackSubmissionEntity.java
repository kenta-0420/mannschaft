package com.mannschaft.app.admin.entity;

import com.mannschaft.app.admin.FeedbackStatus;
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

import java.time.LocalDateTime;

/**
 * フィードバック投稿エンティティ（目安箱）。
 */
@Entity
@Table(name = "feedback_submissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FeedbackSubmissionEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    private Long scopeId;

    @Column(nullable = false, length = 20)
    private String category;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isAnonymous = false;

    @Column(nullable = false)
    private Long submittedBy;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FeedbackStatus status = FeedbackStatus.OPEN;

    @Column(columnDefinition = "TEXT")
    private String adminResponse;

    private Long respondedBy;

    private LocalDateTime respondedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isPublicResponse = false;

    /**
     * 管理者が回答する。
     *
     * @param adminResponse    回答文
     * @param respondedBy      回答者ID
     * @param isPublicResponse 公開回答かどうか
     */
    public void respond(String adminResponse, Long respondedBy, Boolean isPublicResponse) {
        this.adminResponse = adminResponse;
        this.respondedBy = respondedBy;
        this.respondedAt = LocalDateTime.now();
        this.isPublicResponse = isPublicResponse;
        this.status = FeedbackStatus.RESPONDED;
    }

    /**
     * ステータスを変更する。
     *
     * @param newStatus 新しいステータス
     */
    public void changeStatus(FeedbackStatus newStatus) {
        this.status = newStatus;
    }
}
