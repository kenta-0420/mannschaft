package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AlertCondition;
import com.mannschaft.app.analytics.AlertMetric;
import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.ComparisonPeriod;
import com.mannschaft.app.analytics.RevenueSource;
import com.mannschaft.app.analytics.dto.AlertHistoryResponse;
import com.mannschaft.app.analytics.dto.AlertRuleResponse;
import com.mannschaft.app.analytics.dto.CreateAlertRuleRequest;
import com.mannschaft.app.analytics.dto.UpdateAlertRuleRequest;
import com.mannschaft.app.analytics.entity.AnalyticsAlertHistoryEntity;
import com.mannschaft.app.analytics.entity.AnalyticsAlertRuleEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.repository.AnalyticsAlertHistoryRepository;
import com.mannschaft.app.analytics.repository.AnalyticsAlertRuleRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * KPI アラートルールの管理と閾値評価。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AnalyticsAlertService {

    private final AnalyticsAlertRuleRepository ruleRepository;
    private final AnalyticsAlertHistoryRepository historyRepository;
    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;

    /** 全アラートルール（削除済み除外）を取得する。 */
    public List<AlertRuleResponse> getAllRules() {
        return ruleRepository.findByDeletedAtIsNull().stream()
                .map(this::toResponse)
                .toList();
    }

    /** アラートルールを作成する。 */
    @Transactional
    public AlertRuleResponse createRule(CreateAlertRuleRequest request, Long userId) {
        AnalyticsAlertRuleEntity entity = AnalyticsAlertRuleEntity.builder()
                .name(request.getName())
                .metric(request.getMetric())
                .condition(request.getCondition())
                .threshold(request.getThreshold())
                .comparisonPeriod(request.getComparisonPeriod())
                .notifyChannels(toJson(request.getNotifyChannels()))
                .consecutiveTriggers(request.getConsecutiveTriggers())
                .cooldownHours(request.getCooldownHours())
                .createdBy(userId)
                .build();
        return toResponse(ruleRepository.save(entity));
    }

    /** アラートルールを更新する（部分更新）。 */
    @Transactional
    public AlertRuleResponse updateRule(Long id, UpdateAlertRuleRequest request) {
        AnalyticsAlertRuleEntity entity = ruleRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(AnalyticsErrorCode.ANALYTICS_001));
        entity.updateFrom(
                request.getName(), request.getMetric(), request.getCondition(),
                request.getThreshold(), request.getComparisonPeriod(),
                request.getEnabled(),
                request.getNotifyChannels() != null ? toJson(request.getNotifyChannels()) : null,
                request.getConsecutiveTriggers(), request.getCooldownHours()
        );
        return toResponse(ruleRepository.save(entity));
    }

    /** アラートルールを論理削除する。 */
    @Transactional
    public void deleteRule(Long id) {
        AnalyticsAlertRuleEntity entity = ruleRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(AnalyticsErrorCode.ANALYTICS_001));
        entity.softDelete();
        ruleRepository.save(entity);
    }

    /** アラート発火履歴を返す（ページネーション）。 */
    public Page<AlertHistoryResponse> getHistory(LocalDate from, LocalDate to, Long ruleId, Pageable pageable) {
        LocalDateTime fromDt = from.atStartOfDay();
        LocalDateTime toDt = to.plusDays(1).atStartOfDay();
        Page<AnalyticsAlertHistoryEntity> page;
        if (ruleId != null) {
            page = historyRepository.findByRuleIdAndTriggeredAtBetweenOrderByTriggeredAtDesc(
                    ruleId, fromDt, toDt, pageable);
        } else {
            page = historyRepository.findByTriggeredAtBetweenOrderByTriggeredAtDesc(fromDt, toDt, pageable);
        }
        // ルール名を引くためルールを一括取得
        Map<Long, String> ruleNames = ruleRepository.findAll().stream()
                .collect(Collectors.toMap(AnalyticsAlertRuleEntity::getId, AnalyticsAlertRuleEntity::getName));
        return page.map(h -> toHistoryResponse(h, ruleNames));
    }

    /**
     * 日次バッチ完了後にアラートルールを評価する。
     * 各ルール独立で try-catch し、1ルールの失敗が他に影響しないようにする。
     */
    @Transactional
    public void evaluateAlerts(LocalDate date) {
        List<AnalyticsAlertRuleEntity> rules = ruleRepository.findByEnabledTrueAndDeletedAtIsNull();
        for (AnalyticsAlertRuleEntity rule : rules) {
            try {
                evaluateSingleRule(rule, date);
            } catch (Exception e) {
                log.warn("アラートルール評価失敗: ruleId={}, metric={}", rule.getId(), rule.getMetric(), e);
            }
        }
    }

    private void evaluateSingleRule(AnalyticsAlertRuleEntity rule, LocalDate date) {
        BigDecimal currentValue = getMetricValue(rule.getMetric(), date);
        if (currentValue == null) {
            return;
        }

        BigDecimal comparisonValue = getComparisonValue(rule.getMetric(), rule.getComparisonPeriod(), date);
        boolean triggered = false;
        BigDecimal changePct = null;

        switch (rule.getCondition()) {
            case ABOVE -> triggered = currentValue.compareTo(rule.getThreshold()) > 0;
            case BELOW -> triggered = currentValue.compareTo(rule.getThreshold()) < 0;
            case CHANGE_ABOVE, CHANGE_BELOW -> {
                if (comparisonValue != null && comparisonValue.compareTo(BigDecimal.ZERO) != 0) {
                    changePct = currentValue.subtract(comparisonValue)
                            .divide(comparisonValue, 4, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                    triggered = rule.getCondition() == AlertCondition.CHANGE_ABOVE
                            ? changePct.compareTo(rule.getThreshold()) > 0
                            : changePct.compareTo(rule.getThreshold()) < 0;
                }
            }
        }

        if (triggered) {
            AnalyticsAlertHistoryEntity history = AnalyticsAlertHistoryEntity.builder()
                    .ruleId(rule.getId())
                    .triggeredAt(LocalDateTime.now())
                    .metricValue(currentValue)
                    .thresholdValue(rule.getThreshold())
                    .comparisonValue(comparisonValue)
                    .changePct(changePct)
                    .build();

            // consecutive_triggers チェック
            boolean shouldNotify = checkConsecutiveTriggers(rule) && checkCooldown(rule);
            if (shouldNotify) {
                // 通知実行（F04.3 プッシュ / F09.6 メール 連携は将来実装）
                log.info("アラート通知: ruleId={}, metric={}, value={}",
                        rule.getId(), rule.getMetric(), currentValue);
                history = history.toBuilder().notified(true).build();
            }

            historyRepository.save(history);
        }
    }

    /** メトリック値を analytics_daily_* テーブルから取得する。 */
    private BigDecimal getMetricValue(AlertMetric metric, LocalDate date) {
        return switch (metric) {
            case NEW_USERS -> {
                var users = usersRepository.findByDate(date);
                yield users.map(u -> BigDecimal.valueOf(u.getNewUsers())).orElse(null);
            }
            case ACTIVE_USERS -> {
                var users = usersRepository.findByDate(date);
                yield users.map(u -> BigDecimal.valueOf(u.getActiveUsers())).orElse(null);
            }
            case MRR -> {
                LocalDate monthStart = date.withDayOfMonth(1);
                List<AnalyticsDailyRevenueEntity> records =
                        revenueRepository.findByDateBetweenOrderByDateAsc(monthStart, date);
                yield records.stream()
                        .filter(r -> r.getRevenueSource() == RevenueSource.MODULE_SUBSCRIPTION
                                || r.getRevenueSource() == RevenueSource.STORAGE_ADDON
                                || r.getRevenueSource() == RevenueSource.ORG_COUNT_BILLING)
                        .map(AnalyticsDailyRevenueEntity::getNetRevenue)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            default -> null; // 他メトリックは将来実装
        };
    }

    /** 比較基準値を取得する。 */
    private BigDecimal getComparisonValue(AlertMetric metric, ComparisonPeriod period, LocalDate date) {
        LocalDate compDate = switch (period) {
            case PREV_DAY -> date.minusDays(1);
            case PREV_WEEK -> date.minusWeeks(1);
            case PREV_MONTH -> date.minusMonths(1).getDayOfMonth() > date.lengthOfMonth()
                    ? date.minusMonths(1).withDayOfMonth(date.minusMonths(1).lengthOfMonth())
                    : date.minusMonths(1);
            case AVG_30D -> null; // 特殊処理
        };
        if (period == ComparisonPeriod.AVG_30D) {
            // 過去30日間の平均（将来実装）
            return null;
        }
        return compDate != null ? getMetricValue(metric, compDate) : null;
    }

    /** N回連続でトリガーされているか確認する。 */
    private boolean checkConsecutiveTriggers(AnalyticsAlertRuleEntity rule) {
        if (rule.getConsecutiveTriggers() <= 1) {
            return true;
        }
        List<AnalyticsAlertHistoryEntity> recent = historyRepository.findRecentByRuleId(
                rule.getId(), PageRequest.of(0, rule.getConsecutiveTriggers()));
        return recent.size() >= rule.getConsecutiveTriggers();
    }

    /** クールダウン期間が経過しているか確認する。 */
    private boolean checkCooldown(AnalyticsAlertRuleEntity rule) {
        Optional<AnalyticsAlertHistoryEntity> lastNotified =
                historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(rule.getId());
        if (lastNotified.isEmpty()) {
            return true;
        }
        LocalDateTime cooldownEnd = lastNotified.get().getTriggeredAt()
                .plusHours(rule.getCooldownHours());
        return LocalDateTime.now().isAfter(cooldownEnd);
    }

    private AlertRuleResponse toResponse(AnalyticsAlertRuleEntity e) {
        LocalDateTime lastTriggered = historyRepository
                .findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(e.getId())
                .map(AnalyticsAlertHistoryEntity::getTriggeredAt)
                .orElse(null);
        return new AlertRuleResponse(
                e.getId(), e.getName(), e.getMetric().name(), e.getCondition().name(),
                e.getThreshold(), e.getComparisonPeriod().name(), e.isEnabled(),
                parseJson(e.getNotifyChannels()), e.getConsecutiveTriggers(), e.getCooldownHours(),
                lastTriggered, e.getCreatedAt()
        );
    }

    private AlertHistoryResponse toHistoryResponse(AnalyticsAlertHistoryEntity h,
                                                   Map<Long, String> ruleNames) {
        return new AlertHistoryResponse(
                h.getId(), h.getRuleId(), ruleNames.getOrDefault(h.getRuleId(), ""),
                "", h.getTriggeredAt(), h.getMetricValue(), h.getThresholdValue(),
                h.getComparisonValue(), h.getChangePct(), h.isNotified()
        );
    }

    private String toJson(List<String> channels) {
        return "[" + channels.stream()
                .map(c -> "\"" + c + "\"")
                .collect(Collectors.joining(",")) + "]";
    }

    private List<String> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(json.replaceAll("[\\[\\]\"]", "").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
