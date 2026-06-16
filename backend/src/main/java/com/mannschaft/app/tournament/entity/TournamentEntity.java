package com.mannschaft.app.tournament.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.tournament.LeagueRoundType;
import com.mannschaft.app.tournament.TournamentFormat;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.TournamentVisibility;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 大会・リーグのインスタンスエンティティ。
 */
@Entity
@Table(name = "tournaments")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class TournamentEntity extends BaseEntity {

    @Column(nullable = false)
    private Long organizationId;

    private Long templateId;

    private Long previousTournamentId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TournamentFormat format;

    /**
     * 競技種別（F08.10 多競技対応・🟡-1a）。{@code match.domain.Sport} の列挙名を文字列で保持する
     * （SOCCER/FUTSAL/BASKETBALL/VOLLEYBALL/SHOGI/GO/FIGURE_SKATING/GYMNASTICS）。
     *
     * <p>match ドメインの enum を entity に直接型として持たず String で保存することで、
     * モジュラーモノリス境界（ドメイン境界の原則）を侵さない。値の妥当性検証
     * （{@code Sport.valueOf} 相当）は DTO/Service 層で行う。系統B の直接スコア入力
     * （{@code recordTournamentScore}）はこの値を canonical match の sport に充てる。</p>
     *
     * <p>後方互換: 既存大会・未指定時は {@code SOCCER}（DDL DEFAULT・Builder.Default 共に SOCCER）。</p>
     */
    @Column(nullable = false, length = 32)
    @Builder.Default
    private String sport = "SOCCER";

    @Column(length = 50)
    private String season;

    private LocalDate startDate;

    private LocalDate endDate;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer winPoints = 3;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer drawPoints = 1;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer lossPoints = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasDraw = true;

    @Column(nullable = false)
    @Builder.Default
    private Boolean hasSets = false;

    @Column(columnDefinition = "TINYINT UNSIGNED")
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private LeagueRoundType leagueRoundType = LeagueRoundType.SINGLE;

    @Column(nullable = false, columnDefinition = "TINYINT UNSIGNED")
    @Builder.Default
    private Integer knockoutLegs = 1;

    // F08.7 順位UI Wave0: 新規大会の既定は PUBLIC（マスター裁可済）。
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TournamentVisibility visibility = TournamentVisibility.PUBLIC;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private TournamentStatus status = TournamentStatus.DRAFT;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime deletedAt;

    /**
     * 大会情報を更新する。
     */
    public void update(String name, String description, TournamentFormat format,
                       String sport, String season, LocalDate startDate, LocalDate endDate,
                       Integer winPoints, Integer drawPoints, Integer lossPoints,
                       Boolean hasDraw, Boolean hasSets, Integer setsToWin,
                       Boolean hasExtraTime, Boolean hasPenalties, String scoreUnitLabel,
                       String bonusPointRules, LeagueRoundType leagueRoundType,
                       Integer knockoutLegs, TournamentVisibility visibility) {
        this.name = name;
        this.description = description;
        this.format = format;
        this.sport = sport;
        this.season = season;
        this.startDate = startDate;
        this.endDate = endDate;
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
        this.leagueRoundType = leagueRoundType;
        this.knockoutLegs = knockoutLegs;
        this.visibility = visibility;
    }

    /**
     * ステータスを変更する。
     */
    public void changeStatus(TournamentStatus newStatus) {
        this.status = newStatus;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
