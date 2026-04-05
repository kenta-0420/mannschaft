package com.mannschaft.app.resident.entity;

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
 * 居住者書類エンティティ。BaseEntityを使用せず独自IDとcreatedAtのみ持つ。
 */
@Entity
@Table(name = "resident_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ResidentDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long residentId;

    @Column(nullable = false, length = 30)
    private String documentType;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 500)
    private String s3Key;

    @Column(nullable = false)
    private Integer fileSize;

    @Column(nullable = false, length = 100)
    private String contentType;

    @Column(nullable = false)
    private Long uploadedBy;

    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();
}
