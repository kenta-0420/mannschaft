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

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 試合ごとの個人成績エンティティ。
 */
@Entity
@Table(name = "tournament_match_player_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentMatchPlayerStatEntity extends BaseEntity {

    @Column(nullable = false)
    private Long matchId;

    @Column(nullable = false)
    private Long participantId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 30)
    private String statKey;

    private Integer valueInt;

    @Column(precision = 15, scale = 4)
    private BigDecimal valueDecimal;

    private LocalTime valueTime;

    /**
     * 値を更新する。
     */
    public void updateValue(Integer valueInt, BigDecimal valueDecimal, LocalTime valueTime) {
        this.valueInt = valueInt;
        this.valueDecimal = valueDecimal;
        this.valueTime = valueTime;
    }
}
