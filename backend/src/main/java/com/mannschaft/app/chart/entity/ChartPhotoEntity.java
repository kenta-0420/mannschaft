package com.mannschaft.app.chart.entity;

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
 * カルテ写真エンティティ。S3に保存しCloudFront署名付きURLでのみアクセス可能。
 */
@Entity
@Table(name = "chart_photos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartPhotoEntity extends BaseEntity {

    @Column(nullable = false)
    private Long chartRecordId;

    @Column(nullable = false, length = 20)
    private String photoType;

    @Column(nullable = false, length = 500)
    private String s3Key;

    @Column(nullable = false, length = 300)
    private String originalFilename;

    @Column(nullable = false)
    private Integer fileSizeBytes;

    @Column(nullable = false, length = 50)
    private String contentType;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSharedToCustomer = false;
}
