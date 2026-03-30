package com.mannschaft.app.gamification.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.BadgeType;
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
 * バッジエンティティ。
 */
@Entity
@Table(name = "badges")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class BadgeEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BadgeType badgeType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private BadgeConditionType conditionType;

    @Column
    private Integer conditionValue;

    @Column(length = 50)
    private String conditionPeriod;

    @Column(length = 10)
    private String iconEmoji;

    @Column(length = 200)
    private String iconKey;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isSystem = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRepeatable = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Version
    private Long version;

    private LocalDateTime deletedAt;

    /**
     * バッジを更新する。
     */
    public void update(String name, Integer conditionValue, String conditionPeriod,
                       String iconEmoji, String iconKey, Boolean isRepeatable, Boolean isActive) {
        this.name = name;
        this.conditionValue = conditionValue;
        this.conditionPeriod = conditionPeriod;
        this.iconEmoji = iconEmoji;
        this.iconKey = iconKey;
        this.isRepeatable = isRepeatable;
        this.isActive = isActive;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
