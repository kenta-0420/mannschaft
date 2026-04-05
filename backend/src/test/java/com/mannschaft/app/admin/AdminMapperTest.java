package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.BatchJobLogResponse;
import com.mannschaft.app.admin.dto.FeatureFlagResponse;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.admin.entity.NotificationDeliveryStatsEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdminMapper} の単体テスト。
 * MapStruct生成実装によるEntity→DTO変換を検証する。
 */
@DisplayName("AdminMapper 単体テスト")
class AdminMapperTest {

    private final AdminMapper mapper = Mappers.getMapper(AdminMapper.class);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 3, 27, 10, 0);

    // ========================================
    // FeatureFlagEntity → FeatureFlagResponse
    // ========================================

    @Nested
    @DisplayName("toFeatureFlagResponse")
    class ToFeatureFlagResponse {

        @Test
        @DisplayName("正常系: FeatureFlagEntityがFeatureFlagResponseに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            FeatureFlagEntity entity = FeatureFlagEntity.builder()
                    .flagKey("FEATURE_X")
                    .isEnabled(true)
                    .description("テスト機能フラグ")
                    .updatedBy(100L)
                    .build();

            // When
            FeatureFlagResponse response = mapper.toFeatureFlagResponse(entity);

            // Then
            assertThat(response.getFlagKey()).isEqualTo("FEATURE_X");
            assertThat(response.getIsEnabled()).isTrue();
            assertThat(response.getDescription()).isEqualTo("テスト機能フラグ");
            assertThat(response.getUpdatedBy()).isEqualTo(100L);
        }

        @Test
        @DisplayName("正常系: isEnabledがfalseの場合も正しく変換される")
        void 変換_isEnabledFalse_falseが返る() {
            // Given
            FeatureFlagEntity entity = FeatureFlagEntity.builder()
                    .flagKey("FEATURE_Y")
                    .isEnabled(false)
                    .build();

            // When
            FeatureFlagResponse response = mapper.toFeatureFlagResponse(entity);

            // Then
            assertThat(response.getFlagKey()).isEqualTo("FEATURE_Y");
            assertThat(response.getIsEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("toFeatureFlagResponseList")
    class ToFeatureFlagResponseList {

        @Test
        @DisplayName("正常系: リストが変換される")
        void 変換_リスト_全件変換() {
            // Given
            List<FeatureFlagEntity> entities = List.of(
                    FeatureFlagEntity.builder().flagKey("FLAG_A").isEnabled(true).build(),
                    FeatureFlagEntity.builder().flagKey("FLAG_B").isEnabled(false).build()
            );

            // When
            List<FeatureFlagResponse> responses = mapper.toFeatureFlagResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getFlagKey()).isEqualTo("FLAG_A");
            assertThat(responses.get(1).getFlagKey()).isEqualTo("FLAG_B");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<FeatureFlagResponse> responses = mapper.toFeatureFlagResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // MaintenanceScheduleEntity → MaintenanceScheduleResponse
    // ========================================

    @Nested
    @DisplayName("toMaintenanceScheduleResponse")
    class ToMaintenanceScheduleResponse {

        @Test
        @DisplayName("正常系: enumがString名に変換される")
        void 変換_正常_enumがString変換される() {
            // Given
            MaintenanceScheduleEntity entity = MaintenanceScheduleEntity.builder()
                    .title("定期メンテナンス")
                    .message("サーバーメンテナンス")
                    .mode(MaintenanceMode.MAINTENANCE)
                    .startsAt(NOW)
                    .endsAt(NOW.plusHours(2))
                    .status(MaintenanceStatus.SCHEDULED)
                    .createdBy(1L)
                    .build();

            // When
            MaintenanceScheduleResponse response = mapper.toMaintenanceScheduleResponse(entity);

            // Then
            assertThat(response.getTitle()).isEqualTo("定期メンテナンス");
            assertThat(response.getMessage()).isEqualTo("サーバーメンテナンス");
            assertThat(response.getMode()).isEqualTo("MAINTENANCE");
            assertThat(response.getStatus()).isEqualTo("SCHEDULED");
            assertThat(response.getStartsAt()).isEqualTo(NOW);
            assertThat(response.getEndsAt()).isEqualTo(NOW.plusHours(2));
            assertThat(response.getCreatedBy()).isEqualTo(1L);
        }

        @Test
        @DisplayName("正常系: READ_ONLYモードが正しく変換される")
        void 変換_READ_ONLYモード_String変換() {
            // Given
            MaintenanceScheduleEntity entity = MaintenanceScheduleEntity.builder()
                    .title("読み取り専用メンテナンス")
                    .message("メッセージ")
                    .mode(MaintenanceMode.READ_ONLY)
                    .startsAt(NOW)
                    .endsAt(NOW.plusHours(1))
                    .status(MaintenanceStatus.ACTIVE)
                    .createdBy(2L)
                    .build();

            // When
            MaintenanceScheduleResponse response = mapper.toMaintenanceScheduleResponse(entity);

            // Then
            assertThat(response.getMode()).isEqualTo("READ_ONLY");
            assertThat(response.getStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("正常系: COMPLETEDステータスが正しく変換される")
        void 変換_COMPLETEDステータス_String変換() {
            // Given
            MaintenanceScheduleEntity entity = MaintenanceScheduleEntity.builder()
                    .title("完了済みメンテ")
                    .message("完了")
                    .mode(MaintenanceMode.MAINTENANCE)
                    .startsAt(NOW.minusHours(2))
                    .endsAt(NOW.minusHours(1))
                    .status(MaintenanceStatus.COMPLETED)
                    .createdBy(1L)
                    .build();
            entity.changeStatus(MaintenanceStatus.COMPLETED);

            // When
            MaintenanceScheduleResponse response = mapper.toMaintenanceScheduleResponse(entity);

            // Then
            assertThat(response.getStatus()).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("toMaintenanceScheduleResponseList")
    class ToMaintenanceScheduleResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<MaintenanceScheduleEntity> entities = List.of(
                    MaintenanceScheduleEntity.builder()
                            .title("メンテ1").message("msg1")
                            .mode(MaintenanceMode.MAINTENANCE).startsAt(NOW).endsAt(NOW.plusHours(1))
                            .status(MaintenanceStatus.SCHEDULED).createdBy(1L).build(),
                    MaintenanceScheduleEntity.builder()
                            .title("メンテ2").message("msg2")
                            .mode(MaintenanceMode.READ_ONLY).startsAt(NOW).endsAt(NOW.plusHours(2))
                            .status(MaintenanceStatus.ACTIVE).createdBy(2L).build()
            );

            // When
            List<MaintenanceScheduleResponse> responses = mapper.toMaintenanceScheduleResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getTitle()).isEqualTo("メンテ1");
            assertThat(responses.get(0).getMode()).isEqualTo("MAINTENANCE");
            assertThat(responses.get(1).getTitle()).isEqualTo("メンテ2");
            assertThat(responses.get(1).getMode()).isEqualTo("READ_ONLY");
        }
    }

    // ========================================
    // BatchJobLogEntity → BatchJobLogResponse
    // ========================================

    @Nested
    @DisplayName("toBatchJobLogResponse")
    class ToBatchJobLogResponse {

        @Test
        @DisplayName("正常系: BatchJobLogEntityがBatchJobLogResponseに変換される")
        void 変換_正常_フィールドが正しくマップされる() {
            // Given
            BatchJobLogEntity entity = BatchJobLogEntity.builder()
                    .jobName("dailyCleanupJob")
                    .status(BatchJobStatus.RUNNING)
                    .startedAt(NOW)
                    .processedCount(0)
                    .build();

            // When
            BatchJobLogResponse response = mapper.toBatchJobLogResponse(entity);

            // Then
            assertThat(response.getJobName()).isEqualTo("dailyCleanupJob");
            assertThat(response.getStatus()).isEqualTo("RUNNING");
            assertThat(response.getStartedAt()).isEqualTo(NOW);
            assertThat(response.getProcessedCount()).isZero();
        }

        @Test
        @DisplayName("正常系: SUCCESSステータスが正しく変換される")
        void 変換_SUCCESSステータス_String変換() {
            // Given
            BatchJobLogEntity entity = BatchJobLogEntity.builder()
                    .jobName("reportJob")
                    .status(BatchJobStatus.RUNNING)
                    .startedAt(NOW)
                    .processedCount(0)
                    .build();
            entity.complete(500);

            // When
            BatchJobLogResponse response = mapper.toBatchJobLogResponse(entity);

            // Then
            assertThat(response.getStatus()).isEqualTo("SUCCESS");
            assertThat(response.getProcessedCount()).isEqualTo(500);
        }

        @Test
        @DisplayName("正常系: FAILEDステータスとエラーメッセージが変換される")
        void 変換_FAILEDステータス_エラーメッセージ含む() {
            // Given
            BatchJobLogEntity entity = BatchJobLogEntity.builder()
                    .jobName("importJob")
                    .status(BatchJobStatus.FAILED)
                    .startedAt(NOW)
                    .processedCount(0)
                    .errorMessage("接続タイムアウト")
                    .build();
            entity.fail("接続タイムアウト");

            // When
            BatchJobLogResponse response = mapper.toBatchJobLogResponse(entity);

            // Then
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getErrorMessage()).isEqualTo("接続タイムアウト");
        }
    }

    @Nested
    @DisplayName("toBatchJobLogResponseList")
    class ToBatchJobLogResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<BatchJobLogEntity> entities = List.of(
                    BatchJobLogEntity.builder()
                            .jobName("job1").status(BatchJobStatus.RUNNING)
                            .startedAt(NOW).processedCount(0).build(),
                    BatchJobLogEntity.builder()
                            .jobName("job2").status(BatchJobStatus.SUCCESS)
                            .startedAt(NOW).processedCount(100).build()
            );

            // When
            List<BatchJobLogResponse> responses = mapper.toBatchJobLogResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getJobName()).isEqualTo("job1");
            assertThat(responses.get(1).getJobName()).isEqualTo("job2");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<BatchJobLogResponse> responses = mapper.toBatchJobLogResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }

    // ========================================
    // NotificationDeliveryStatsEntity → NotificationStatsResponse
    // ========================================

    @Nested
    @DisplayName("toNotificationStatsResponse")
    class ToNotificationStatsResponse {

        @Test
        @DisplayName("正常系: EMAILチャネルが正しく変換される")
        void 変換_EMAIL_channelがString変換() {
            // Given
            NotificationDeliveryStatsEntity entity = NotificationDeliveryStatsEntity.builder()
                    .date(LocalDate.of(2026, 3, 27))
                    .channel(NotificationChannel.EMAIL)
                    .sentCount(100)
                    .deliveredCount(95)
                    .failedCount(3)
                    .bounceCount(2)
                    .build();

            // When
            NotificationStatsResponse response = mapper.toNotificationStatsResponse(entity);

            // Then
            assertThat(response.getDate()).isEqualTo(LocalDate.of(2026, 3, 27));
            assertThat(response.getChannel()).isEqualTo("EMAIL");
            assertThat(response.getSentCount()).isEqualTo(100);
            assertThat(response.getDeliveredCount()).isEqualTo(95);
            assertThat(response.getFailedCount()).isEqualTo(3);
            assertThat(response.getBounceCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("正常系: PUSHチャネルが正しく変換される")
        void 変換_PUSH_channelがString変換() {
            // Given
            NotificationDeliveryStatsEntity entity = NotificationDeliveryStatsEntity.builder()
                    .date(LocalDate.of(2026, 3, 1))
                    .channel(NotificationChannel.PUSH)
                    .sentCount(500)
                    .deliveredCount(480)
                    .failedCount(20)
                    .bounceCount(0)
                    .build();

            // When
            NotificationStatsResponse response = mapper.toNotificationStatsResponse(entity);

            // Then
            assertThat(response.getChannel()).isEqualTo("PUSH");
            assertThat(response.getSentCount()).isEqualTo(500);
            assertThat(response.getDeliveredCount()).isEqualTo(480);
            assertThat(response.getFailedCount()).isEqualTo(20);
            assertThat(response.getBounceCount()).isZero();
        }

        @Test
        @DisplayName("正常系: IN_APPチャネルが正しく変換される")
        void 変換_IN_APP_channelがString変換() {
            // Given
            NotificationDeliveryStatsEntity entity = NotificationDeliveryStatsEntity.builder()
                    .date(LocalDate.of(2026, 2, 1))
                    .channel(NotificationChannel.IN_APP)
                    .sentCount(50)
                    .deliveredCount(48)
                    .failedCount(2)
                    .bounceCount(0)
                    .build();

            // When
            NotificationStatsResponse response = mapper.toNotificationStatsResponse(entity);

            // Then
            assertThat(response.getChannel()).isEqualTo("IN_APP");
            assertThat(response.getSentCount()).isEqualTo(50);
        }
    }

    @Nested
    @DisplayName("toNotificationStatsResponseList")
    class ToNotificationStatsResponseList {

        @Test
        @DisplayName("正常系: 複数エンティティのリストが変換される")
        void 変換_複数_全件変換() {
            // Given
            List<NotificationDeliveryStatsEntity> entities = List.of(
                    NotificationDeliveryStatsEntity.builder()
                            .date(LocalDate.of(2026, 3, 27))
                            .channel(NotificationChannel.EMAIL)
                            .sentCount(100).deliveredCount(90).failedCount(5).bounceCount(5)
                            .build(),
                    NotificationDeliveryStatsEntity.builder()
                            .date(LocalDate.of(2026, 3, 27))
                            .channel(NotificationChannel.PUSH)
                            .sentCount(200).deliveredCount(195).failedCount(5).bounceCount(0)
                            .build()
            );

            // When
            List<NotificationStatsResponse> responses = mapper.toNotificationStatsResponseList(entities);

            // Then
            assertThat(responses).hasSize(2);
            assertThat(responses.get(0).getChannel()).isEqualTo("EMAIL");
            assertThat(responses.get(1).getChannel()).isEqualTo("PUSH");
        }

        @Test
        @DisplayName("正常系: 空リストの場合空リストが返る")
        void 変換_空リスト_空リスト返却() {
            // When
            List<NotificationStatsResponse> responses = mapper.toNotificationStatsResponseList(List.of());

            // Then
            assertThat(responses).isEmpty();
        }
    }
}
