package com.mannschaft.app.tournament.submission;

/**
 * 大会提出枠（tournament_submission_requirement）の対象範囲。
 *
 * <p>F08.7.1/06 §2 に準拠。提出枠を全参加チームに課すか、特定チームのみに課すかを表す。</p>
 */
public enum SubmissionTargetScope {
    /** 全参加チームが対象 */
    ALL_TEAMS,
    /** tournament_submission_requirement_target で明示した特定チームのみが対象 */
    SPECIFIC_TEAMS
}
