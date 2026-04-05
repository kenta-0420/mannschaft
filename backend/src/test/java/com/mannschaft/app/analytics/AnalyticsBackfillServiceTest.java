package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.dto.BackfillJobResponse;
import com.mannschaft.app.analytics.dto.BackfillRequest;
import com.mannschaft.app.analytics.service.AnalyticsBackfillService;
import com.mannschaft.app.analytics.service.DailyAggregationBatchService;
import com.mannschaft.app.analytics.service.MonthlyCohortBatchService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("AnalyticsBackfillService 単体テスト")
class AnalyticsBackfillServiceTest {

    @Mock private DailyAggregationBatchService dailyBatch;
    @Mock private MonthlyCohortBatchService cohortBatch;
    @Mock private NotificationService notificationService;
    @Mock private UserRoleRepository userRoleRepository;

    // ========== startBackfill ==========

    @Nested
    @DisplayName("startBackfill")
    class StartBackfill {

        @Test
        @DisplayName("正常系: RUNNINGレスポンス")
        void testStartBackfill_正常開始() {
            // Arrange
            AnalyticsBackfillService service = new AnalyticsBackfillService(dailyBatch, cohortBatch, notificationService, userRoleRepository);
            BackfillRequest request = new BackfillRequest(
                    LocalDate.of(2026, 1, 1),
                    LocalDate.of(2026, 1, 31),
                    List.of(BackfillTarget.REVENUE, BackfillTarget.USERS));

            // Act
            BackfillJobResponse result = service.startBackfill(request);

            // Assert
            assertThat(result.getStatus()).isEqualTo("RUNNING");
            assertThat(result.getFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(result.getTo()).isEqualTo(LocalDate.of(2026, 1, 31));
            assertThat(result.getTargets()).containsExactly("REVENUE", "USERS");
            assertThat(result.getJobId()).startsWith("backfill-");
        }

        @Test
        @DisplayName("異常系: from > to で例外")
        void testStartBackfill_fromがtoより後で例外() {
            // Arrange
            AnalyticsBackfillService service = new AnalyticsBackfillService(dailyBatch, cohortBatch, notificationService, userRoleRepository);
            BackfillRequest request = new BackfillRequest(
                    LocalDate.of(2026, 3, 31),
                    LocalDate.of(2026, 3, 1),
                    List.of(BackfillTarget.REVENUE));

            // Act / Assert
            assertThatThrownBy(() -> service.startBackfill(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_005"));
        }

        @Test
        @DisplayName("異常系: 6ヶ月超過で例外")
        void testStartBackfill_6ヶ月超過で例外() {
            // Arrange - 184日（6ヶ月=183日を超過）
            AnalyticsBackfillService service = new AnalyticsBackfillService(dailyBatch, cohortBatch, notificationService, userRoleRepository);
            BackfillRequest request = new BackfillRequest(
                    LocalDate.of(2025, 9, 1),
                    LocalDate.of(2026, 3, 31),
                    List.of(BackfillTarget.REVENUE));

            // Act / Assert
            assertThatThrownBy(() -> service.startBackfill(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_004"));
        }

        @Test
        @DisplayName("異常系: 同時実行で例外（ANALYTICS_003）")
        void testStartBackfill_同時実行で例外() throws Exception {
            // Arrange - リフレクションで running フラグを true に設定
            AnalyticsBackfillService service = new AnalyticsBackfillService(dailyBatch, cohortBatch, notificationService, userRoleRepository);
            java.lang.reflect.Field runningField = AnalyticsBackfillService.class.getDeclaredField("running");
            runningField.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean running =
                    (java.util.concurrent.atomic.AtomicBoolean) runningField.get(service);
            running.set(true);

            BackfillRequest request = new BackfillRequest(
                    LocalDate.of(2026, 2, 1),
                    LocalDate.of(2026, 2, 28),
                    List.of(BackfillTarget.USERS));

            // Act / Assert
            assertThatThrownBy(() -> service.startBackfill(request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_003"));
        }

        @Test
        @DisplayName("正常系: 183日ちょうどで成功")
        void testStartBackfill_183日ちょうどで成功() {
            // Arrange - ちょうど183日
            AnalyticsBackfillService service = new AnalyticsBackfillService(dailyBatch, cohortBatch, notificationService, userRoleRepository);
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = from.plusDays(182); // 183日間

            BackfillRequest request = new BackfillRequest(from, to, List.of(BackfillTarget.REVENUE));

            // Act
            BackfillJobResponse result = service.startBackfill(request);

            // Assert
            assertThat(result.getStatus()).isEqualTo("RUNNING");
        }
    }
}
