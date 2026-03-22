package com.mannschaft.app.schedule.entity;

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
 * スケジュール行事カテゴリエンティティ。チーム・組織スコープのカテゴリを管理する。
 */
@Entity
@Table(name = "schedule_event_categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ScheduleEventCategoryEntity extends BaseEntity {

    private Long teamId;

    private Long organizationId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 7)
    @Builder.Default
    private String color = "#3B82F6";

    @Column(length = 50)
    private String icon;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isDayOffCategory = false;

    private Integer sortOrder;

    /**
     * チームスコープかどうかを判定する。
     *
     * @return teamId が設定されている場合 true
     */
    public boolean isTeamScope() {
        return this.teamId != null;
    }

    /**
     * 組織スコープかどうかを判定する。
     *
     * @return organizationId が設定されている場合 true
     */
    public boolean isOrganizationScope() {
        return this.organizationId != null;
    }
}
