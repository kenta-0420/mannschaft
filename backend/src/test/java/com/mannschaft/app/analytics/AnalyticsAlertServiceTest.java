package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.dto.AlertRuleResponse;
import com.mannschaft.app.analytics.dto.CreateAlertRuleRequest;
import com.mannschaft.app.analytics.dto.UpdateAlertRuleRequest;
import com.mannschaft.app.analytics.entity.AnalyticsAlertHistoryEntity;
import com.mannschaft.app.analytics.entity.AnalyticsAlertRuleEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.repository.AnalyticsAlertHistoryRepository;
import com.mannschaft.app.analytics.repository.AnalyticsAlertRuleRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.service.AnalyticsAlertService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsAlertService 単体テスト")
class AnalyticsAlertServiceTest {

    @Mock private AnalyticsAlertRuleRepository ruleRepository;
    @Mock private AnalyticsAlertHistoryRepository historyRepository;
    @Mock private AnalyticsDailyRevenueRepository revenueRepository;
    @Mock private AnalyticsDailyUsersRepository usersRepository;
    @InjectMocks private AnalyticsAlertService service;

    private AnalyticsAlertRuleEntity buildRule(Long id, String name, AlertMetric metric,
                                                AlertCondition condition, BigDecimal threshold) {
        return AnalyticsAlertRuleEntity.builder()
                .name(name)
                .metric(metric)
                .condition(condition)
                .threshold(threshold)
                .comparisonPeriod(ComparisonPeriod.PREV_DAY)
                .notifyChannels("[\"PUSH\"]")
                .consecutiveTriggers(1)
                .cooldownHours(24)
                .createdBy(1L)
                .build();
    }

    // ========== getAllRules ==========

    @Nested
    @DisplayName("getAllRules")
    class GetAllRules {

        @Test
        @DisplayName("正常系: ルール一覧取得")
        void testGetAllRules_正常取得() {
            // Arrange
            AnalyticsAlertRuleEntity rule = buildRule(1L, "MRRアラート",
                    AlertMetric.MRR, AlertCondition.BELOW, new BigDecimal("50000"));
            given(ruleRepository.findByDeletedAtIsNull()).willReturn(List.of(rule));
            given(historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(any()))
                    .willReturn(Optional.empty());

            // Act
            List<AlertRuleResponse> result = service.getAllRules();

            // Assert
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("MRRアラート");
        }
    }

    // ========== createRule ==========

    @Nested
    @DisplayName("createRule")
    class CreateRule {

        @Test
        @DisplayName("正常系: ルール作成")
        void testCreateRule_正常作成() {
            // Arrange
            CreateAlertRuleRequest request = new CreateAlertRuleRequest(
                    "MRR低下アラート", AlertMetric.MRR, AlertCondition.BELOW,
                    new BigDecimal("50000"), ComparisonPeriod.PREV_DAY,
                    List.of("PUSH", "EMAIL"), 1, 24);

            AnalyticsAlertRuleEntity saved = buildRule(1L, "MRR低下アラート",
                    AlertMetric.MRR, AlertCondition.BELOW, new BigDecimal("50000"));
            given(ruleRepository.save(any(AnalyticsAlertRuleEntity.class))).willReturn(saved);
            given(historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(any()))
                    .willReturn(Optional.empty());

            // Act
            AlertRuleResponse result = service.createRule(request, 1L);

            // Assert
            assertThat(result.getName()).isEqualTo("MRR低下アラート");
            assertThat(result.getMetric()).isEqualTo("MRR");
            verify(ruleRepository).save(any(AnalyticsAlertRuleEntity.class));
        }
    }

    // ========== updateRule ==========

    @Nested
    @DisplayName("updateRule")
    class UpdateRule {

        @Test
        @DisplayName("正常系: 部分更新（nameのみ変更）")
        void testUpdateRule_部分更新() {
            // Arrange
            AnalyticsAlertRuleEntity existing = buildRule(1L, "旧名",
                    AlertMetric.MRR, AlertCondition.BELOW, new BigDecimal("50000"));
            given(ruleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(existing));
            given(ruleRepository.save(any(AnalyticsAlertRuleEntity.class))).willReturn(existing);
            given(historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(any()))
                    .willReturn(Optional.empty());

            UpdateAlertRuleRequest request = new UpdateAlertRuleRequest(
                    "新名", null, null, null, null, null, null, null, null);

            // Act
            AlertRuleResponse result = service.updateRule(1L, request);

            // Assert
            assertThat(result).isNotNull();
            verify(ruleRepository).save(any(AnalyticsAlertRuleEntity.class));
        }

        @Test
        @DisplayName("異常系: 存在しないIDで例外")
        void testUpdateRule_存在しないIDで例外() {
            // Arrange
            given(ruleRepository.findByIdAndDeletedAtIsNull(999L)).willReturn(Optional.empty());
            UpdateAlertRuleRequest request = new UpdateAlertRuleRequest(
                    "名前", null, null, null, null, null, null, null, null);

            // Act / Assert
            assertThatThrownBy(() -> service.updateRule(999L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_001"));
        }
    }

    // ========== deleteRule ==========

    @Nested
    @DisplayName("deleteRule")
    class DeleteRule {

        @Test
        @DisplayName("正常系: 論理削除（deletedAt設定）")
        void testDeleteRule_論理削除() {
            // Arrange
            AnalyticsAlertRuleEntity entity = buildRule(1L, "テスト",
                    AlertMetric.MRR, AlertCondition.BELOW, new BigDecimal("50000"));
            given(ruleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.of(entity));
            given(ruleRepository.save(any(AnalyticsAlertRuleEntity.class))).willReturn(entity);

            // Act
            service.deleteRule(1L);

            // Assert
            verify(ruleRepository).save(any(AnalyticsAlertRuleEntity.class));
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 既に削除済み（findByIdAndDeletedAtIsNull で見つからない）で例外")
        void testDeleteRule_削除済みで例外() {
            // Arrange
            given(ruleRepository.findByIdAndDeletedAtIsNull(1L)).willReturn(Optional.empty());

            // Act / Assert
            assertThatThrownBy(() -> service.deleteRule(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_001"));
        }
    }

    // ========== evaluateAlerts ==========

    @Nested
    @DisplayName("evaluateAlerts")
    class EvaluateAlerts {

        @Test
        @DisplayName("正常系: ABOVE条件でトリガー")
        void testEvaluateAlerts_ABOVE条件でトリガー() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 3, 15);
            AnalyticsAlertRuleEntity rule = AnalyticsAlertRuleEntity.builder()
                    .name("アクティブユーザー上限")
                    .metric(AlertMetric.ACTIVE_USERS)
                    .condition(AlertCondition.ABOVE)
                    .threshold(new BigDecimal("100"))
                    .comparisonPeriod(ComparisonPeriod.PREV_DAY)
                    .notifyChannels("[\"PUSH\"]")
                    .consecutiveTriggers(1)
                    .cooldownHours(24)
                    .createdBy(1L)
                    .build();

            given(ruleRepository.findByEnabledTrueAndDeletedAtIsNull()).willReturn(List.of(rule));

            // アクティブユーザーが200 > 閾値100 → トリガー
            AnalyticsDailyUsersEntity usersEntity = AnalyticsDailyUsersEntity.builder()
                    .date(date).activeUsers(200).build();
            given(usersRepository.findByDate(date)).willReturn(Optional.of(usersEntity));

            // 比較用（前日）
            AnalyticsDailyUsersEntity prevUsersEntity = AnalyticsDailyUsersEntity.builder()
                    .date(date.minusDays(1)).activeUsers(90).build();
            given(usersRepository.findByDate(date.minusDays(1))).willReturn(Optional.of(prevUsersEntity));

            // consecutive & cooldown
            given(historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(any()))
                    .willReturn(Optional.empty());

            // Act
            service.evaluateAlerts(date);

            // Assert - 履歴が保存されること
            verify(historyRepository).save(any(AnalyticsAlertHistoryEntity.class));
        }

        @Test
        @DisplayName("正常系: CHANGE_BELOW条件でトリガー")
        void testEvaluateAlerts_CHANGE_BELOW条件でトリガー() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 3, 15);
            AnalyticsAlertRuleEntity rule = AnalyticsAlertRuleEntity.builder()
                    .name("新規ユーザー減少")
                    .metric(AlertMetric.NEW_USERS)
                    .condition(AlertCondition.CHANGE_BELOW)
                    .threshold(new BigDecimal("-20")) // -20%以下でトリガー
                    .comparisonPeriod(ComparisonPeriod.PREV_DAY)
                    .notifyChannels("[\"PUSH\"]")
                    .consecutiveTriggers(1)
                    .cooldownHours(24)
                    .createdBy(1L)
                    .build();

            given(ruleRepository.findByEnabledTrueAndDeletedAtIsNull()).willReturn(List.of(rule));

            // 当日: newUsers=50, 前日: newUsers=100 → 変化率 = -50%
            AnalyticsDailyUsersEntity current = AnalyticsDailyUsersEntity.builder()
                    .date(date).newUsers(50).build();
            AnalyticsDailyUsersEntity previous = AnalyticsDailyUsersEntity.builder()
                    .date(date.minusDays(1)).newUsers(100).build();
            given(usersRepository.findByDate(date)).willReturn(Optional.of(current));
            given(usersRepository.findByDate(date.minusDays(1))).willReturn(Optional.of(previous));

            given(historyRepository.findTopByRuleIdAndNotifiedTrueOrderByTriggeredAtDesc(any()))
                    .willReturn(Optional.empty());

            // Act
            service.evaluateAlerts(date);

            // Assert
            verify(historyRepository).save(any(AnalyticsAlertHistoryEntity.class));
        }

        @Test
        @DisplayName("正常系: 条件未満でトリガーなし")
        void testEvaluateAlerts_条件未満でトリガーなし() {
            // Arrange
            LocalDate date = LocalDate.of(2026, 3, 15);
            AnalyticsAlertRuleEntity rule = AnalyticsAlertRuleEntity.builder()
                    .name("アクティブユーザー上限")
                    .metric(AlertMetric.ACTIVE_USERS)
                    .condition(AlertCondition.ABOVE)
                    .threshold(new BigDecimal("500"))
                    .comparisonPeriod(ComparisonPeriod.PREV_DAY)
                    .notifyChannels("[\"PUSH\"]")
                    .consecutiveTriggers(1)
                    .cooldownHours(24)
                    .createdBy(1L)
                    .build();

            given(ruleRepository.findByEnabledTrueAndDeletedAtIsNull()).willReturn(List.of(rule));

            // アクティブユーザー 200 < 閾値500 → トリガーしない
            AnalyticsDailyUsersEntity usersEntity = AnalyticsDailyUsersEntity.builder()
                    .date(date).activeUsers(200).build();
            given(usersRepository.findByDate(date)).willReturn(Optional.of(usersEntity));

            // Act
            service.evaluateAlerts(date);

            // Assert - 履歴が保存されないこと
            verify(historyRepository, org.mockito.Mockito.never()).save(any(AnalyticsAlertHistoryEntity.class));
        }
    }
}
