package com.mannschaft.app.tournament.fee;

/**
 * 大会参加費（tournament_fee）の対象範囲。
 *
 * <p>F08.7.1/07 §2 に準拠。参加費を全参加チームに課すか、特定チームのみに課すかを表す。</p>
 */
public enum TournamentFeeTargetScope {
    /** 全参加チームが対象 */
    ALL_TEAMS,
    /** tournament_fee_target で明示した特定チームのみが対象 */
    SPECIFIC_TEAMS
}
