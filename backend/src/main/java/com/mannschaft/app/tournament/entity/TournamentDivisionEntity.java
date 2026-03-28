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

/**
 * ディビジョン（1部、2部等）エンティティ。
 */
@Entity
@Table(name = "tournament_divisions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentDivisionEntity extends BaseEntity {

    @Column(nullable = false)
    private Long tournamentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer level = 1;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer promotionSlots = 0;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer relegationSlots = 0;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer playoffPromotionSlots = 0;

    private Integer maxParticipants;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    /**
     * ディビジョン情報を更新する。
     */
    public void update(String name, Integer level, Integer promotionSlots,
                       Integer relegationSlots, Integer playoffPromotionSlots,
                       Integer maxParticipants, Integer sortOrder) {
        this.name = name;
        this.level = level;
        this.promotionSlots = promotionSlots;
        this.relegationSlots = relegationSlots;
        this.playoffPromotionSlots = playoffPromotionSlots;
        this.maxParticipants = maxParticipants;
        this.sortOrder = sortOrder;
    }
}
