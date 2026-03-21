package com.mannschaft.app.tournament;

/**
 * タイブレーク順位決定基準。
 */
public enum TiebreakerCriteria {
    POINTS,
    SCORE_DIFFERENCE,
    SCORE_FOR,
    HEAD_TO_HEAD_POINTS,
    HEAD_TO_HEAD_SCORE_DIFFERENCE,
    WINS,
    SET_RATIO,
    POINT_RATIO,
    LOSSES,
    DRAWS
}
