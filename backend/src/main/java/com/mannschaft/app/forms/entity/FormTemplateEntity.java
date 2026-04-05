package com.mannschaft.app.forms.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.forms.FormStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * フォームテンプレートエンティティ。書類テンプレートの定義情報を管理する。
 */
@Entity
@Table(name = "form_templates")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FormTemplateEntity extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 500)
    private String description;

    @Column(length = 50)
    private String icon;

    @Column(length = 7)
    private String color;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private FormStatus status = FormStatus.DRAFT;

    @Column(nullable = false)
    @Builder.Default
    private Boolean requiresApproval = false;

    private Long workflowTemplateId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSealOnPdf = false;

    private LocalDateTime deadline;

    @Column(nullable = false)
    @Builder.Default
    private Boolean allowEditAfterSubmit = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean autoFillEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer maxSubmissionsPerUser = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private Long presetId;

    @Column(nullable = false)
    @Builder.Default
    private Integer submissionCount = 0;

    @Column(nullable = false)
    @Builder.Default
    private Integer targetCount = 0;

    private Long createdBy;

    private LocalDateTime publishedAt;

    private LocalDateTime closedAt;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * テンプレートを公開する。
     */
    public void publish() {
        this.status = FormStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * テンプレートを閉鎖する。
     */
    public void close() {
        this.status = FormStatus.CLOSED;
        this.closedAt = LocalDateTime.now();
    }

    /**
     * テンプレートをアーカイブする。
     */
    public void archive() {
        this.status = FormStatus.ARCHIVED;
    }

    /**
     * 公開可能かどうかを判定する。
     *
     * @return DRAFT ステータスの場合 true
     */
    public boolean isPublishable() {
        return this.status == FormStatus.DRAFT;
    }

    /**
     * 閉鎖可能かどうかを判定する。
     *
     * @return PUBLISHED ステータスの場合 true
     */
    public boolean isClosable() {
        return this.status == FormStatus.PUBLISHED;
    }

    /**
     * 提出回数をインクリメントする。
     */
    public void incrementSubmissionCount() {
        this.submissionCount++;
    }

    /**
     * 締切を過ぎているかどうかを判定する。
     *
     * @return 締切が設定されていて過ぎている場合 true
     */
    public boolean isDeadlinePassed() {
        return this.deadline != null && LocalDateTime.now().isAfter(this.deadline);
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
