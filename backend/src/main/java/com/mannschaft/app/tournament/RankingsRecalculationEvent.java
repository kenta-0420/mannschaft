package com.mannschaft.app.tournament;

import org.springframework.context.ApplicationEvent;

/**
 * 個人ランキング再計算イベント。個人成績の入力・変更時に発火する。
 */
public class RankingsRecalculationEvent extends ApplicationEvent {

    private final Long tournamentId;

    public RankingsRecalculationEvent(Object source, Long tournamentId) {
        super(source);
        this.tournamentId = tournamentId;
    }

    public Long getTournamentId() {
        return tournamentId;
    }
}
