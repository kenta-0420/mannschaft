package com.mannschaft.app.directmail.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * ダイレクトメール画像アップロードエンティティ。メール本文に埋め込む画像を管理する。
 */
@Entity
@Table(name = "direct_mail_image_uploads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class DirectMailImageUploadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long mailLogId;

    @Column(nullable = false, length = 500)
    private String s3Key;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false)
    private Integer fileSize;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private Long uploadedBy;

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * メールログIDを紐付ける。
     */
    public void associateMailLog(Long mailLogId) {
        this.mailLogId = mailLogId;
    }
}
