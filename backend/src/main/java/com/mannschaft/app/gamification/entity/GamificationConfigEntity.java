package com.mannschaft.app.gamification.entity;

import com.mannschaft.app.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * ゲーミフィケーション設定エンティティ。
 */
@Entity
@Table(name = "gamification_configs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class GamificationConfigEntity extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String scopeType;

    @Column(nullable = false)
    private Long scopeId;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isEnabled = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isRankingEnabled = true;

    @Column(nullable = false)
    @Builder.Default
    private Integer rankingDisplayCount = 10;

    @Column
    private Byte pointResetMonth;

    @Version
    private Long version;

    /**
     * ゲーミフィケーション設定を更新する。
     */
    public void update(Boolean isEnabled, Boolean isRankingEnabled,
                       Integer rankingDisplayCount, Byte pointResetMonth) {
        this.isEnabled = isEnabled;
        this.isRankingEnabled = isRankingEnabled;
        this.rankingDisplayCount = rankingDisplayCount;
        this.pointResetMonth = pointResetMonth;
    }
}
