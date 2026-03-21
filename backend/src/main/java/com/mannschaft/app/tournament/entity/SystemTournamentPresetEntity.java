package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * SYSTEM_ADMIN管理の競技別プリセットテンプレートエンティティ。
 */
@Entity
@Table(name = "system_tournament_presets")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class SystemTournamentPresetEntity extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String sportCategory;

    @Column(length = 500)
    private String description;

    @Column(length = 30)
    private String icon;

    @Column(nullable = false, columnDefinition = "JSON")
    private String supportedFormats;

    @Column(nullable = false)
    @Builder.Default
    private Integer winPoints = 3;

    @Column(nullable = false)
    @Builder.Default
    private Integer drawPoints = 1;

    @Column(nullable = false)
    @Builder.Default
    private Integer lossPoints = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasDraw = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasSets = false;

    private Integer setsToWin;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasExtraTime = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasPenalties = false;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String scoreUnitLabel = "点";

    @Column(columnDefinition = "JSON")
    private String bonusPointRules;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    private LocalDateTime deletedAt;

    /**
     * プリセットを更新する。
     */
    public void update(String name, String sportCategory, String description, String icon,
                       String supportedFormats, Integer winPoints, Integer drawPoints,
                       Integer lossPoints, Boolean hasDraw, Boolean hasSets, Integer setsToWin,
                       Boolean hasExtraTime, Boolean hasPenalties, String scoreUnitLabel,
                       String bonusPointRules, Integer sortOrder) {
        this.name = name;
        this.sportCategory = sportCategory;
        this.description = description;
        this.icon = icon;
        this.supportedFormats = supportedFormats;
        this.winPoints = winPoints;
        this.drawPoints = drawPoints;
        this.lossPoints = lossPoints;
        this.hasDraw = hasDraw;
        this.hasSets = hasSets;
        this.setsToWin = setsToWin;
        this.hasExtraTime = hasExtraTime;
        this.hasPenalties = hasPenalties;
        this.scoreUnitLabel = scoreUnitLabel;
        this.bonusPointRules = bonusPointRules;
        this.sortOrder = sortOrder;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
