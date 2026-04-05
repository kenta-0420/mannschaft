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
 * カルテ作成テンプレートエンティティ。施術メニュー別の定型入力をプリセットする。
 */
@Entity
@Table(name = "chart_record_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartRecordTemplateEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 100)
    private String templateName;

    @Column(columnDefinition = "TEXT")
    private String chiefComplaint;

    @Column(columnDefinition = "TEXT")
    private String treatmentNote;

    @Column(columnDefinition = "TEXT")
    private String allergyInfo;

    @Column(columnDefinition = "JSON")
    private String defaultCustomFields;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * テンプレートを更新する。
     */
    public void update(String templateName, String chiefComplaint, String treatmentNote,
                       String allergyInfo, String defaultCustomFields, Integer sortOrder) {
        this.templateName = templateName;
        this.chiefComplaint = chiefComplaint;
        this.treatmentNote = treatmentNote;
        this.allergyInfo = allergyInfo;
        this.defaultCustomFields = defaultCustomFields;
        this.sortOrder = sortOrder;
    }
}
