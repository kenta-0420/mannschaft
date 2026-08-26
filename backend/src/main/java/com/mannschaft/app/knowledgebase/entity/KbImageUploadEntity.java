package com.mannschaft.app.knowledgebase.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * ナレッジベース画像アップロードエンティティ。
 * 論理削除なし。
 */
@Entity
@Table(name = "kb_image_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class KbImageUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column
    private Long kbPageId;

    @Column(nullable = false)
    private Long uploaderId;

    @Column(name = "s3_key", nullable = false, length = 500, unique = true)
    private String s3Key;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
