package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.MonthlyStatisticsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 出欠統計・CSV の認可ファサード（トランザクションの外）。
 *
 * <p>クラス全員分を返すため、閲覧権（V）の認可を業務トランザクションの外で済ませてから
 * {@link AttendanceStatisticsService}（TX）を呼ぶ（D-3T。先例: shift の ShiftRequestFacade）。
 * 本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class AttendanceStatisticsFacade {

    private final AttendanceStatisticsService statisticsService;
    private final SchoolAttendanceAccessPolicy policy;

    /** 担任向け月次出欠集計を取得する。認可: 閲覧権（V）。 */
    public MonthlyStatisticsResponse getMonthlyStatistics(Long teamId, int year, int month, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return statisticsService.getMonthlyStatistics(teamId, year, month);
    }

    /** 担任向け出欠 CSV を生成する。認可: 閲覧権（V）。 */
    public byte[] exportAttendanceCsv(Long teamId, LocalDate from, LocalDate to, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return statisticsService.exportAttendanceCsv(teamId, from, to);
    }
}
