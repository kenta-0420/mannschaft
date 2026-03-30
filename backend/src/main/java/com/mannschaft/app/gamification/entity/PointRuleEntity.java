package com.mannschaft.app.gamification.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gamification.ActionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * ポイントルールエンティティ。
 */
@Entity
@Table(name = "point_rules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class PointRuleEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private ActionType actionType;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Integer points;

    @Column(nullable = false)
    @Builder.Default
    private Integer dailyLimit = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * ルールを更新する。
     */
    public void update(String name, Integer points, Integer dailyLimit, Boolean isActive) {
        this.name = name;
        this.points = points;
        this.dailyLimit = dailyLimit;
        this.isActive = isActive;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
