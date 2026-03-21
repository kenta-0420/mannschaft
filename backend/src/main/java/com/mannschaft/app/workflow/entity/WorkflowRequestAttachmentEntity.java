package com.mannschaft.app.workflow.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ワークフロー申請添付ファイルエンティティ。申請に添付されたファイル情報を管理する。
 */
@Entity
@Table(name = "workflow_request_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class WorkflowRequestAttachmentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long requestId;

    @Column(nullable = false, length = 500)
    private String fileKey;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private Long fileSize;

    private Long uploadedBy;
}
