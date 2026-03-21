package com.mannschaft.app.proxyvote.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.proxyvote.AttachmentTargetType;
import com.mannschaft.app.proxyvote.AttachmentType;
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

/**
 * 添付ファイルエンティティ。セッション・議案共通のポリモーフィック参照。
 */
@Entity
@Table(name = "proxy_vote_attachments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ProxyVoteAttachmentEntity extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AttachmentTargetType targetType;

    @Column(nullable = false)
    private Long targetId;

    @Column(nullable = false, length = 500)
    private String fileKey;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private Integer fileSize;

    @Column(nullable = false, length = 100)
    private String mimeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AttachmentType attachmentType = AttachmentType.DOCUMENT;

    @Column(nullable = false)
    @Builder.Default
    private Short sortOrder = 0;

    @Column(nullable = false)
    private Long uploadedBy;
}
