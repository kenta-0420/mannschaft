package com.mannschaft.app.admin;

import com.mannschaft.app.admin.dto.CreateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.UpdateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.entity.MaintenanceScheduleEntity;
import com.mannschaft.app.admin.repository.MaintenanceScheduleRepository;
import com.mannschaft.app.admin.service.MaintenanceScheduleService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MaintenanceScheduleService} の単体テスト。
 * メンテナンススケジュールのCRUD・ステータス遷移を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MaintenanceScheduleService 単体テスト")
class MaintenanceScheduleServiceTest {

    @Mock
    private MaintenanceScheduleRepository repository;

    @Mock
    private AdminMapper adminMapper;

    @InjectMocks
    private MaintenanceScheduleService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 4, 1, 0, 0);
    private static final LocalDateTime ENDS_AT = LocalDateTime.of(2026, 4, 1, 6, 0);

    private MaintenanceScheduleEntity createScheduledEntity() {
        return MaintenanceScheduleEntity.builder()
                .title("定期メンテナンス")
                .message("サーバーメンテナンスを実施します")
                .mode(MaintenanceMode.MAINTENANCE)
                .startsAt(STARTS_AT)
                .endsAt(ENDS_AT)
                .status(MaintenanceStatus.SCHEDULED)
                .createdBy(USER_ID)
                .build();
    }

    private MaintenanceScheduleEntity createActiveEntity() {
        MaintenanceScheduleEntity entity = createScheduledEntity();
        entity.changeStatus(MaintenanceStatus.ACTIVE);
        return entity;
    }

    private MaintenanceScheduleResponse createScheduleResponse() {
        return new MaintenanceScheduleResponse(
                SCHEDULE_ID, "定期メンテナンス", "サーバーメンテナンスを実施します",
                "MAINTENANCE", STARTS_AT, ENDS_AT, "SCHEDULED", USER_ID, null, null);
    }

    // ========================================
    // getAllSchedules
    // ========================================

    @Nested
    @DisplayName("getAllSchedules")
    class GetAllSchedules {

        @Test
        @DisplayName("正常系: スケジュール一覧が返却される")
        void 取得_全件_一覧返却() {
            // Given
            List<MaintenanceScheduleEntity> entities = List.of(createScheduledEntity());
            List<MaintenanceScheduleResponse> responses = List.of(createScheduleResponse());
            given(repository.findByStatusInOrderByStartsAtDesc(any())).willReturn(entities);
            given(adminMapper.toMaintenanceScheduleResponseList(entities)).willReturn(responses);

            // When
            List<MaintenanceScheduleResponse> result = service.getAllSchedules();

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("定期メンテナンス");
        }
    }

    // ========================================
    // getSchedule
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("正常系: スケジュール詳細が返却される")
        void 取得_ID指定_詳細返却() {
            // Given
            MaintenanceScheduleEntity entity = createScheduledEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(adminMapper.toMaintenanceScheduleResponse(entity)).willReturn(response);

            // When
            MaintenanceScheduleResponse result = service.getSchedule(SCHEDULE_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("定期メンテナンス");
        }

        @Test
        @DisplayName("異常系: スケジュール不在でADMIN_003例外")
        void 取得_不在_例外() {
            // Given
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getSchedule(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_003"));
        }
    }

    // ========================================
    // createSchedule
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        @Test
        @DisplayName("正常系: スケジュールが作成される")
        void 作成_正常_スケジュール保存() {
            // Given
            CreateMaintenanceScheduleRequest req = new CreateMaintenanceScheduleRequest(
                    "定期メンテナンス", "説明", "MAINTENANCE", STARTS_AT, ENDS_AT);
            MaintenanceScheduleEntity savedEntity = createScheduledEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();

            given(repository.save(any(MaintenanceScheduleEntity.class))).willReturn(savedEntity);
            given(adminMapper.toMaintenanceScheduleResponse(savedEntity)).willReturn(response);

            // When
            MaintenanceScheduleResponse result = service.createSchedule(req, USER_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("定期メンテナンス");
            verify(repository).save(any(MaintenanceScheduleEntity.class));
        }

        @Test
        @DisplayName("正常系: modeがnullの場合MAINTENANCEがデフォルトセットされる")
        void 作成_modeNull_デフォルト値() {
            // Given
            CreateMaintenanceScheduleRequest req = new CreateMaintenanceScheduleRequest(
                    "メンテナンス", "説明", null, STARTS_AT, ENDS_AT);
            MaintenanceScheduleEntity savedEntity = createScheduledEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();

            given(repository.save(any(MaintenanceScheduleEntity.class))).willReturn(savedEntity);
            given(adminMapper.toMaintenanceScheduleResponse(savedEntity)).willReturn(response);

            // When
            service.createSchedule(req, USER_ID);

            // Then
            verify(repository).save(any(MaintenanceScheduleEntity.class));
        }

        @Test
        @DisplayName("異常系: 開始日時が終了日時以降でADMIN_005例外")
        void 作成_期間不正_例外() {
            // Given
            LocalDateTime invalidStartsAt = LocalDateTime.of(2026, 4, 2, 0, 0);
            LocalDateTime invalidEndsAt = LocalDateTime.of(2026, 4, 1, 0, 0);
            CreateMaintenanceScheduleRequest req = new CreateMaintenanceScheduleRequest(
                    "メンテナンス", "説明", "MAINTENANCE", invalidStartsAt, invalidEndsAt);

            // When / Then
            assertThatThrownBy(() -> service.createSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_005"));
        }

        @Test
        @DisplayName("異常系: 無効なモード文字列でADMIN_004例外")
        void 作成_無効モード_例外() {
            // Given
            CreateMaintenanceScheduleRequest req = new CreateMaintenanceScheduleRequest(
                    "メンテナンス", "説明", "INVALID_MODE", STARTS_AT, ENDS_AT);

            // When / Then
            assertThatThrownBy(() -> service.createSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_004"));
        }
    }

    // ========================================
    // updateSchedule
    // ========================================

    @Nested
    @DisplayName("updateSchedule")
    class UpdateSchedule {

        @Test
        @DisplayName("正常系: SCHEDULEDステータスのスケジュールが更新される")
        void 更新_SCHEDULED_スケジュール保存() {
            // Given
            UpdateMaintenanceScheduleRequest req = new UpdateMaintenanceScheduleRequest(
                    "更新タイトル", "更新メッセージ", "READ_ONLY", STARTS_AT, ENDS_AT);
            MaintenanceScheduleEntity entity = createScheduledEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();

            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(repository.save(entity)).willReturn(entity);
            given(adminMapper.toMaintenanceScheduleResponse(entity)).willReturn(response);

            // When
            MaintenanceScheduleResponse result = service.updateSchedule(SCHEDULE_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("異常系: ACTIVEステータスではADMIN_004例外")
        void 更新_ACTIVEステータス_例外() {
            // Given
            UpdateMaintenanceScheduleRequest req = new UpdateMaintenanceScheduleRequest(
                    "タイトル", "メッセージ", null, STARTS_AT, ENDS_AT);
            MaintenanceScheduleEntity entity = createActiveEntity();

            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_004"));
        }

        @Test
        @DisplayName("異常系: スケジュール不在でADMIN_003例外")
        void 更新_不在_例外() {
            // Given
            UpdateMaintenanceScheduleRequest req = new UpdateMaintenanceScheduleRequest(
                    "タイトル", "メッセージ", null, STARTS_AT, ENDS_AT);
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_003"));
        }
    }

    // ========================================
    // deleteSchedule
    // ========================================

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("正常系: SCHEDULEDステータスのスケジュールがキャンセルされる")
        void 削除_SCHEDULED_キャンセル() {
            // Given
            MaintenanceScheduleEntity entity = createScheduledEntity();
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteSchedule(SCHEDULE_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.CANCELLED);
            verify(repository).save(entity);
        }

        @Test
        @DisplayName("異常系: ACTIVEステータスではADMIN_004例外")
        void 削除_ACTIVEステータス_例外() {
            // Given
            MaintenanceScheduleEntity entity = createActiveEntity();
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.deleteSchedule(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_004"));
        }
    }

    // ========================================
    // activate
    // ========================================

    @Nested
    @DisplayName("activate")
    class Activate {

        @Test
        @DisplayName("正常系: SCHEDULEDステータスのスケジュールがACTIVEになる")
        void 開始_SCHEDULED_ACTIVE遷移() {
            // Given
            MaintenanceScheduleEntity entity = createScheduledEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();

            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(repository.save(entity)).willReturn(entity);
            given(adminMapper.toMaintenanceScheduleResponse(entity)).willReturn(response);

            // When
            MaintenanceScheduleResponse result = service.activate(SCHEDULE_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.ACTIVE);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: ACTIVEステータスではADMIN_004例外")
        void 開始_ACTIVEステータス_例外() {
            // Given
            MaintenanceScheduleEntity entity = createActiveEntity();
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.activate(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_004"));
        }
    }

    // ========================================
    // complete
    // ========================================

    @Nested
    @DisplayName("complete")
    class Complete {

        @Test
        @DisplayName("正常系: ACTIVEステータスのスケジュールがCOMPLETEDになる")
        void 完了_ACTIVE_COMPLETED遷移() {
            // Given
            MaintenanceScheduleEntity entity = createActiveEntity();
            MaintenanceScheduleResponse response = createScheduleResponse();

            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(repository.save(entity)).willReturn(entity);
            given(adminMapper.toMaintenanceScheduleResponse(entity)).willReturn(response);

            // When
            MaintenanceScheduleResponse result = service.complete(SCHEDULE_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(MaintenanceStatus.COMPLETED);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: SCHEDULEDステータスではADMIN_004例外")
        void 完了_SCHEDULEDステータス_例外() {
            // Given
            MaintenanceScheduleEntity entity = createScheduledEntity();
            given(repository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.complete(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ADMIN_004"));
        }
    }
}
