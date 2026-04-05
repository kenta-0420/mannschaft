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

import java.time.LocalDateTime;

/**
 * 問診票・同意書エンティティ。
 */
@Entity
@Table(name = "chart_intake_forms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartIntakeFormEntity extends BaseEntity {

    @Column(nullable = false)
    private Long chartRecordId;

    @Column(nullable = false, length = 20)
    private String formType;

    @Column(nullable = false, columnDefinition = "JSON")
    private String content;

    private Long electronicSealId;

    private LocalDateTime signedAt;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isInitial = true;

    /**
     * 問診票の内容を更新する。
     */
    public void updateContent(String content) {
        this.content = content;
    }

    /**
     * 電子署名を記録する。
     */
    public void sign(Long electronicSealId) {
        this.electronicSealId = electronicSealId;
        this.signedAt = LocalDateTime.now();
    }
}
