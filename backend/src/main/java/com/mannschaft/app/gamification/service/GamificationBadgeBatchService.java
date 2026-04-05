package com.mannschaft.app.gamification.service;

import com.mannschaft.app.gamification.AwardedBy;
import com.mannschaft.app.gamification.BadgeConditionType;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.entity.UserBadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import com.mannschaft.app.gamification.repository.PointTransactionQueryRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ゲーミフィケーション・バッジ評価バッチサービス。
 * 毎朝3:00 (Asia/Tokyo) にバッジ獲得条件を評価し、対象ユーザーにバッジを付与する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GamificationBadgeBatchService {

    private final GamificationConfigRepository gamificationConfigRepository;
    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final PointTransactionQueryRepository pointTransactionQueryRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * バッジ獲得条件を評価するバッチ処理。
     *
     * <p>処理フロー:</p>
     * <ol>
     *   <li>isEnabled=true の全GamificationConfigを取得</li>
     *   <li>各スコープの全アクティブバッジを取得</li>
     *   <li>conditionType = MANUAL のバッジはスキップ</li>
     *   <li>CUMULATIVE_COUNT / CONSECUTIVE_DAYS / MONTHLY_RANK / ATTENDANCE_RATE を評価</li>
     *   <li>条件を満たすユーザーに UserBadge を付与（重複チェック済み）</li>
     * </ol>
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "gamification_badge_evaluation", lockAtMostFor = "PT30M")
    @Transactional
    public void runBadgeEvaluation() {
        log.info("バッジ評価バッチ開始");

        List<GamificationConfigEntity> enabledConfigs = gamificationConfigRepository.findAll()
                .stream()
                .filter(c -> Boolean.TRUE.equals(c.getIsEnabled()))
                .toList();

        AtomicInteger processedCount = new AtomicInteger(0);
        AtomicInteger grantedCount = new AtomicInteger(0);

        for (GamificationConfigEntity config : enabledConfigs) {
            String scopeType = config.getScopeType();
            Long scopeId = config.getScopeId();

            List<BadgeEntity> activeBadges =
                    badgeRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                            scopeType, scopeId);

            for (BadgeEntity badge : activeBadges) {
                processedCount.incrementAndGet();

                if (BadgeConditionType.MANUAL == badge.getConditionType()) {
                    log.debug("MANUALバッジのためスキップ: badgeId={}, scopeType={}, scopeId={}",
                            badge.getId(), scopeType, scopeId);
                    continue;
                }

                int awarded = evaluateBadge(badge, scopeType, scopeId);
                grantedCount.addAndGet(awarded);
            }
        }

        log.info("バッジ評価バッチ完了: スコープ数={}, バッジ総数={}, 付与数={}",
                enabledConfigs.size(), processedCount.get(), grantedCount.get());
    }

    private int evaluateBadge(BadgeEntity badge, String scopeType, Long scopeId) {
        try {
            return switch (badge.getConditionType()) {
                case CUMULATIVE_COUNT -> evaluateCumulativeCount(badge, scopeType, scopeId);
                case CONSECUTIVE_DAYS -> evaluateConsecutiveDays(badge, scopeType, scopeId);
                case MONTHLY_RANK -> evaluateMonthlyRank(badge, scopeType, scopeId);
                case ATTENDANCE_RATE -> evaluateAttendanceRate(badge, scopeType, scopeId);
                case MANUAL -> 0;
            };
        } catch (Exception e) {
            log.error("バッジ評価中にエラー: badgeId={}, conditionType={}, scopeType={}, scopeId={}",
                    badge.getId(), badge.getConditionType(), scopeType, scopeId, e);
            return 0;
        }
    }

    // -------------------------------------------------------------------------
    // CUMULATIVE_COUNT: 期間内の EARN トランザクション総件数が conditionValue 以上
    // -------------------------------------------------------------------------

    private int evaluateCumulativeCount(BadgeEntity badge, String scopeType, Long scopeId) {
        LocalDate[] range = periodRange(badge.getConditionPeriod());
        String periodLabel = buildPeriodLabel(badge.getConditionPeriod());

        String sql = """
                SELECT user_id, COUNT(*) AS cnt
                FROM point_transactions
                WHERE scope_type = ? AND scope_id = ?
                  AND transaction_type = 'EARN'
                  AND earned_on BETWEEN ? AND ?
                GROUP BY user_id
                HAVING COUNT(*) >= ?
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                sql, scopeType, scopeId, range[0], range[1], badge.getConditionValue());

        int granted = 0;
        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("user_id")).longValue();
            if (!isAlreadyAwarded(badge, userId, periodLabel)) {
                awardBadge(badge, userId, periodLabel);
                granted++;
            }
        }
        log.debug("CUMULATIVE_COUNT評価完了: badgeId={}, 付与数={}", badge.getId(), granted);
        return granted;
    }

    // -------------------------------------------------------------------------
    // CONSECUTIVE_DAYS: DAILY_LOGIN の最大連続日数が conditionValue 以上
    // -------------------------------------------------------------------------

    private int evaluateConsecutiveDays(BadgeEntity badge, String scopeType, Long scopeId) {
        String sql = """
                SELECT user_id, earned_on
                FROM point_transactions
                WHERE scope_type = ? AND scope_id = ?
                  AND action_type = 'DAILY_LOGIN'
                  AND transaction_type = 'EARN'
                ORDER BY user_id, earned_on
                """;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, scopeType, scopeId);

        // user_id ごとに earned_on の日付を集約（重複排除のため SortedSet）
        Map<Long, SortedSet<LocalDate>> userDates = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("user_id")).longValue();
            LocalDate date = toLocalDate(row.get("earned_on"));
            userDates.computeIfAbsent(userId, k -> new TreeSet<>()).add(date);
        }

        // CONSECUTIVE_DAYS は通常 ALL_TIME の非繰返しバッジ
        String periodLabel = buildPeriodLabel(badge.getConditionPeriod());

        int granted = 0;
        for (Map.Entry<Long, SortedSet<LocalDate>> entry : userDates.entrySet()) {
            Long userId = entry.getKey();
            int maxStreak = maxConsecutiveDays(new ArrayList<>(entry.getValue()));
            if (maxStreak >= badge.getConditionValue()) {
                if (!isAlreadyAwarded(badge, userId, periodLabel)) {
                    awardBadge(badge, userId, periodLabel);
                    granted++;
                }
            }
        }
        log.debug("CONSECUTIVE_DAYS評価完了: badgeId={}, 付与数={}", badge.getId(), granted);
        return granted;
    }

    private int maxConsecutiveDays(List<LocalDate> sortedDates) {
        if (sortedDates.isEmpty()) return 0;
        int max = 1, current = 1;
        for (int i = 1; i < sortedDates.size(); i++) {
            if (sortedDates.get(i).equals(sortedDates.get(i - 1).plusDays(1))) {
                current++;
                if (current > max) max = current;
            } else {
                current = 1;
            }
        }
        return max;
    }

    // -------------------------------------------------------------------------
    // MONTHLY_RANK: 期間内ポイントランキング上位 conditionValue 位以内
    // -------------------------------------------------------------------------

    private int evaluateMonthlyRank(BadgeEntity badge, String scopeType, Long scopeId) {
        LocalDate[] range = periodRange(badge.getConditionPeriod());
        String periodLabel = buildPeriodLabel(badge.getConditionPeriod());

        // conditionValue = ランク閾値（例: 1 = 1位のみ, 3 = 3位以内）
        List<Map<String, Object>> topUsers = pointTransactionQueryRepository.findTopUsersByPeriod(
                scopeType, scopeId, range[0], range[1], badge.getConditionValue());

        int granted = 0;
        for (Map<String, Object> row : topUsers) {
            Long userId = ((Number) row.get("user_id")).longValue();
            if (!isAlreadyAwarded(badge, userId, periodLabel)) {
                awardBadge(badge, userId, periodLabel);
                granted++;
            }
        }
        log.debug("MONTHLY_RANK評価完了: badgeId={}, 付与数={}", badge.getId(), granted);
        return granted;
    }

    // -------------------------------------------------------------------------
    // ATTENDANCE_RATE: 期間内の出席必須スケジュール回答率が conditionValue% 以上
    // -------------------------------------------------------------------------

    private int evaluateAttendanceRate(BadgeEntity badge, String scopeType, Long scopeId) {
        if (!"TEAM".equals(scopeType) && !"ORGANIZATION".equals(scopeType)) {
            log.debug("ATTENDANCE_RATEはTEAM/ORGANIZATIONスコープのみ対象: badgeId={}, scopeType={}",
                    badge.getId(), scopeType);
            return 0;
        }

        LocalDate[] range = periodRange(badge.getConditionPeriod());
        String periodLabel = buildPeriodLabel(badge.getConditionPeriod());

        LocalDateTime fromDt = range[0].atStartOfDay();
        LocalDateTime toDt = range[1].atTime(23, 59, 59);

        // スコープ別のカラム名（TEAM → team_id, ORGANIZATION → organization_id）
        String scopeColumn = "TEAM".equals(scopeType) ? "team_id" : "organization_id";

        Integer totalSchedules = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM schedules WHERE " + scopeColumn + " = ?"
                        + " AND attendance_required = TRUE AND start_at BETWEEN ? AND ? AND deleted_at IS NULL",
                Integer.class, scopeId, fromDt, toDt);

        if (totalSchedules == null || totalSchedules == 0) {
            log.debug("出席必須スケジュールが0件のためスキップ: badgeId={}", badge.getId());
            return 0;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT sa.user_id, COUNT(DISTINCT sa.schedule_id) AS responded"
                        + " FROM schedule_attendances sa"
                        + " JOIN schedules s ON sa.schedule_id = s.id"
                        + " WHERE s." + scopeColumn + " = ?"
                        + " AND s.attendance_required = TRUE AND s.start_at BETWEEN ? AND ? AND s.deleted_at IS NULL"
                        + " GROUP BY sa.user_id",
                scopeId, fromDt, toDt);

        int requiredRate = badge.getConditionValue(); // e.g. 100 = 100%
        int granted = 0;

        for (Map<String, Object> row : rows) {
            Long userId = ((Number) row.get("user_id")).longValue();
            int responded = ((Number) row.get("responded")).intValue();
            int rate = responded * 100 / totalSchedules;

            if (rate >= requiredRate) {
                if (!isAlreadyAwarded(badge, userId, periodLabel)) {
                    awardBadge(badge, userId, periodLabel);
                    granted++;
                }
            }
        }
        log.debug("ATTENDANCE_RATE評価完了: badgeId={}, 付与数={}", badge.getId(), granted);
        return granted;
    }

    // -------------------------------------------------------------------------
    // 共通ヘルパー
    // -------------------------------------------------------------------------

    /**
     * バッジの重複付与チェック。
     * is_repeatable=true → 同一期間ラベルで既に付与済みか確認。
     * is_repeatable=false → 期間問わず1回でも付与済みか確認。
     */
    private boolean isAlreadyAwarded(BadgeEntity badge, Long userId, String periodLabel) {
        if (Boolean.TRUE.equals(badge.getIsRepeatable())) {
            return userBadgeRepository.existsByBadgeIdAndUserIdAndPeriodLabel(badge.getId(), userId, periodLabel);
        }
        return userBadgeRepository.existsByBadgeIdAndUserId(badge.getId(), userId);
    }

    private void awardBadge(BadgeEntity badge, Long userId, String periodLabel) {
        UserBadgeEntity userBadge = UserBadgeEntity.builder()
                .badgeId(badge.getId())
                .userId(userId)
                .earnedOn(LocalDate.now())
                .periodLabel(periodLabel)
                .awardedBy(AwardedBy.SYSTEM)
                .build();
        userBadgeRepository.save(userBadge);
        log.info("バッジ付与: userId={}, badgeId={}, conditionType={}, periodLabel={}",
                userId, badge.getId(), badge.getConditionType(), periodLabel);
    }

    /**
     * conditionPeriod から [from, to] の LocalDate 配列を返す。
     * null / "ALL_TIME" は 2000-01-01 〜 today を返す。
     */
    private LocalDate[] periodRange(String conditionPeriod) {
        LocalDate today = LocalDate.now();
        if (conditionPeriod == null || "ALL_TIME".equals(conditionPeriod)) {
            return new LocalDate[]{LocalDate.of(2000, 1, 1), today};
        }
        return switch (conditionPeriod) {
            case "MONTHLY" -> new LocalDate[]{
                    today.withDayOfMonth(1),
                    today.with(TemporalAdjusters.lastDayOfMonth())};
            case "WEEKLY" -> new LocalDate[]{
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))};
            case "YEARLY" -> new LocalDate[]{
                    today.withDayOfYear(1),
                    LocalDate.of(today.getYear(), 12, 31)};
            default -> new LocalDate[]{LocalDate.of(2000, 1, 1), today};
        };
    }

    /**
     * conditionPeriod から period_label 文字列を返す。
     * null / "ALL_TIME" は null を返す。
     */
    private String buildPeriodLabel(String conditionPeriod) {
        if (conditionPeriod == null || "ALL_TIME".equals(conditionPeriod)) {
            return null;
        }
        LocalDate today = LocalDate.now();
        return switch (conditionPeriod) {
            case "MONTHLY" -> today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
            case "WEEKLY" -> today.getYear() + "-W"
                    + String.format("%02d", today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            case "YEARLY" -> String.valueOf(today.getYear());
            default -> null;
        };
    }

    private LocalDate toLocalDate(Object value) {
        if (value instanceof java.sql.Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        throw new IllegalArgumentException("Unexpected date type: " + value.getClass());
    }
}
