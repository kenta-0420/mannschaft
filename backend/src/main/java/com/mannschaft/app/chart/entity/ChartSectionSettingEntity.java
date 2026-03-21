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
 * カルテセクション設定エンティティ。チームごとにセクションの有効/無効を管理する。
 */
@Entity
@Table(name = "chart_section_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ChartSectionSettingEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 30)
    private String sectionType;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = true;

    /**
     * セクションの有効/無効を変更する。
     */
    public void updateEnabled(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }
}
