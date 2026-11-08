package com.mannschaft.app.common.jdbc;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/** JDBCのUTC瞬間を読み書きする呼出ごとに、可変Calendarを共有せず生成する。 */
public final class JdbcUtcCalendar {
    private JdbcUtcCalendar() { }

    public static Calendar fresh() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
    }
}
