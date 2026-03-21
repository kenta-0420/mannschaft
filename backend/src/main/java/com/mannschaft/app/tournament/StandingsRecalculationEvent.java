package com.mannschaft.app.tournament;

import org.springframework.context.ApplicationEvent;

/**
 * 順位表再計算イベント。試合結果の入力・変更時に発火する。
 */
public class StandingsRecalculationEvent extends ApplicationEvent {

    private final Long divisionId;
    private final Long tournamentId;

    public StandingsRecalculationEvent(Object source, Long divisionId, Long tournamentId) {
        super(source);
        this.divisionId = divisionId;
        this.tournamentId = tournamentId;
    }

    public Long getDivisionId() {
        return divisionId;
    }

    public Long getTournamentId() {
        return tournamentId;
    }
}
