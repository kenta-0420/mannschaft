package com.mannschaft.app.forms.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.forms.SubmissionStatus;
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
 * フォーム提出エンティティ。ユーザーによるフォーム提出情報を管理する。
 */
@Entity
@Table(name = "form_submissions")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class FormSubmissionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long templateId;

    @Column(nullable = false, length = 20)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubmissionStatus status = SubmissionStatus.DRAFT;

    private Long submittedBy;

    private Long workflowRequestId;

    @Column(length = 500)
    private String pdfFileKey;

    @Column(nullable = false)
    @Builder.Default
    private Integer submissionCountForUser = 1;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * 提出を行う。
     */
    public void submit() {
        this.status = SubmissionStatus.SUBMITTED;
    }

    /**
     * 提出を承認する。
     */
    public void approve() {
        this.status = SubmissionStatus.APPROVED;
    }

    /**
     * 提出を却下する。
     */
    public void reject() {
        this.status = SubmissionStatus.REJECTED;
    }

    /**
     * 提出を差し戻す。
     */
    public void returnSubmission() {
        this.status = SubmissionStatus.RETURNED;
    }

    /**
     * 提出済みかどうかを判定する。
     *
     * @return SUBMITTED/APPROVED/REJECTED のいずれかの場合 true
     */
    public boolean isSubmitted() {
        return this.status == SubmissionStatus.SUBMITTED
                || this.status == SubmissionStatus.APPROVED
                || this.status == SubmissionStatus.REJECTED;
    }

    /**
     * 編集可能かどうかを判定する。
     *
     * @return DRAFT または RETURNED の場合 true
     */
    public boolean isEditable() {
        return this.status == SubmissionStatus.DRAFT
                || this.status == SubmissionStatus.RETURNED;
    }

    /**
     * PDFファイルキーを設定する。
     *
     * @param fileKey PDFファイルキー
     */
    public void setPdfFileKey(String fileKey) {
        this.pdfFileKey = fileKey;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
