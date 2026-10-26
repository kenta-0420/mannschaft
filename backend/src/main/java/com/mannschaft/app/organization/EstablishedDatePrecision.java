package com.mannschaft.app.organization;

/**
 * 設立年月日の精度を表す列挙型。
 * DB の ENUM('YEAR', 'YEAR_MONTH', 'FULL') に対応。
 */
public enum EstablishedDatePrecision {
    /** 年のみ（例: 2015-01-01 → 「2015年」として表示）*/
    YEAR,
    /** 年月（例: 2015-04-01 → 「2015年4月」として表示）*/
    YEAR_MONTH,
    /** 年月日（例: 2015-04-01 → 「2015年4月1日」として表示）*/
    FULL
}
