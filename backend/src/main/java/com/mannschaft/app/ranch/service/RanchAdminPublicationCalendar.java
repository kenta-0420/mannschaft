package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;

import java.time.DayOfWeek;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/** 保存成功replayの後でのみ使う、次UTC週以降の公開時刻境界。 */
public final class RanchAdminPublicationCalendar {
    private RanchAdminPublicationCalendar() { }
    public static void requireFutureWeek(Instant effectiveAt, Instant now) {
        try {
            var at = effectiveAt.atOffset(ZoneOffset.UTC);
            var next = now.atOffset(ZoneOffset.UTC).toLocalDate()
                    .with(TemporalAdjusters.next(DayOfWeek.MONDAY)).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (at.getYear() > 9999 || at.getDayOfWeek() != DayOfWeek.MONDAY || at.getHour() != 0
                    || at.getMinute() != 0 || at.getSecond() != 0 || at.getNano() != 0 || effectiveAt.isBefore(next)) {
                throw new BusinessException(RanchErrorCode.RANCH_006);
            }
        } catch (DateTimeException exception) {
            throw new BusinessException(RanchErrorCode.RANCH_006);
        }
    }
}
