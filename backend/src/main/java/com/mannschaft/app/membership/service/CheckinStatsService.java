package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.CheckinType;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.dto.CheckinStatsResponse;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * チェックイン統計サービス。チーム/組織のチェックイン統計データを提供する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckinStatsService {

    private static final int MAX_STATS_DAYS = 90;
    private static final String[] DAY_NAMES = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private static final int TOP_MEMBERS_LIMIT = 10;

    private final MemberCardCheckinRepository checkinRepository;
    private final MemberCardRepository memberCardRepository;

    /**
     * チェックイン統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param from      開始日
     * @param to        終了日
     * @return 統計データ
     */
    public ApiResponse<CheckinStatsResponse> getStats(
            ScopeType scopeType, Long scopeId, LocalDate from, LocalDate to) {

        if (from == null || to == null) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_022);
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_STATS_DAYS) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_022);
        }

        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.atTime(LocalTime.MAX);
        long days = ChronoUnit.DAYS.between(from, to) + 1;

        // 総チェックイン数
        long totalCheckins = checkinRepository.countByScopeAndPeriod(scopeType, scopeId, fromDt, toDt);

        // ユニークメンバー数
        long uniqueMembers = checkinRepository.countUniqueMembersByScopeAndPeriod(
                scopeType, scopeId, fromDt, toDt);

        // 1日平均
        double averagePerDay = days > 0 ? (double) totalCheckins / days : 0;

        // チェックイン種別ごとの内訳
        Map<String, Long> typeBreakdown = new HashMap<>();
        checkinRepository.countByCheckinTypeByScopeAndPeriod(scopeType, scopeId, fromDt, toDt)
                .forEach(row -> typeBreakdown.put(((CheckinType) row[0]).name(), (Long) row[1]));

        // 曜日別
        String scopeTypeStr = scopeType.name();
        List<CheckinStatsResponse.DayOfWeekCount> byDayOfWeek = new ArrayList<>();
        checkinRepository.countByDayOfWeek(scopeTypeStr, scopeId, fromDt, toDt)
                .forEach(row -> {
                    int dow = ((Number) row[0]).intValue();
                    long count = ((Number) row[1]).longValue();
                    // MySQLのDAYOFWEEK: 1=SUN, 2=MON, ...
                    if (dow >= 1 && dow <= 7) {
                        byDayOfWeek.add(new CheckinStatsResponse.DayOfWeekCount(
                                DAY_NAMES[dow - 1], count));
                    }
                });

        // 時間帯別
        List<CheckinStatsResponse.HourCount> byHour = new ArrayList<>();
        checkinRepository.countByHour(scopeTypeStr, scopeId, fromDt, toDt)
                .forEach(row -> {
                    int hour = ((Number) row[0]).intValue();
                    long count = ((Number) row[1]).longValue();
                    byHour.add(new CheckinStatsResponse.HourCount(hour, count));
                });

        // トップメンバー
        List<CheckinStatsResponse.TopMember> topMembers = new ArrayList<>();
        List<Object[]> topData = checkinRepository.findTopMembersByScopeAndPeriod(
                scopeType, scopeId, fromDt, toDt);
        int limit = Math.min(topData.size(), TOP_MEMBERS_LIMIT);
        for (int i = 0; i < limit; i++) {
            Object[] row = topData.get(i);
            Long cardId = (Long) row[0];
            long count = (Long) row[1];
            memberCardRepository.findById(cardId).ifPresent(card ->
                    topMembers.add(new CheckinStatsResponse.TopMember(
                            card.getCardNumber(), card.getDisplayName(), count)));
        }

        // 場所別
        List<CheckinStatsResponse.LocationCount> byLocation = new ArrayList<>();
        checkinRepository.countByLocationByScopeAndPeriod(scopeType, scopeId, fromDt, toDt)
                .forEach(row -> byLocation.add(new CheckinStatsResponse.LocationCount(
                        (String) row[0], ((CheckinType) row[1]).name(), (Long) row[2])));

        CheckinStatsResponse response = new CheckinStatsResponse(
                new CheckinStatsResponse.Period(from, to),
                totalCheckins, uniqueMembers,
                Math.round(averagePerDay * 10.0) / 10.0,
                typeBreakdown, byDayOfWeek, byHour, topMembers, byLocation
        );

        return ApiResponse.of(response);
    }
}
