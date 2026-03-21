package com.mannschaft.app.service.entity;

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
 * チーム単位のサービス履歴機能設定エンティティ。
 */
@Entity
@Table(name = "service_record_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ServiceRecordSettingsEntity extends BaseEntity {

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDashboardEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isReactionEnabled = false;

    /**
     * 設定を更新する。
     */
    public void update(Boolean isDashboardEnabled, Boolean isReactionEnabled) {
        this.isDashboardEnabled = isDashboardEnabled;
        this.isReactionEnabled = isReactionEnabled;
    }
}
