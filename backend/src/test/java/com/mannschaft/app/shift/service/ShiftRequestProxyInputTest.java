package com.mannschaft.app.shift.service;

import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftRequestService} の代理入力ロジック単体テスト。
 * 通常入力・代理入力の2パターンを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftRequestService 代理入力テスト")
class ShiftRequestProxyInputTest {

    @Mock
    private ShiftRequestRepository requestRepository;

    @Mock
    private ShiftScheduleService scheduleService;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private ShiftRequestService shiftRequestService;

    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long REQUEST_ID = 300L;
    private static final Long TEAM_ID = 1L;
    private static final Long CONSENT_ID = 50L;
    private static final Long PROXY_RECORD_ID = 999L;

    private CreateShiftRequestRequest createRequest() {
        return new CreateShiftRequestRequest(
                SCHEDULE_ID, null, LocalDate.of(2026, 3, 2), "PREFERRED", "テスト");
    }

    private ShiftScheduleEntity createCollectingSchedule() {
        return ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("テストスケジュール")
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.COLLECTING)
                .requestDeadline(LocalDateTime.now().plusDays(7))
                .build();
    }

    private ShiftRequestEntity createSavedEntity() {
        ShiftRequestEntity entity = ShiftRequestEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .userId(USER_ID)
                .slotDate(LocalDate.of(2026, 3, 2))
                .preference(ShiftPreference.PREFERRED)
                .note("テスト")
                .build();
        callOnCreate(entity);
        return entity;
    }

    private ShiftRequestEntity createSavedEntityWithId(Long id) {
        // リフレクションで id をセット
        ShiftRequestEntity entity = createSavedEntity();
        try {
            java.lang.reflect.Field field = ShiftRequestEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception ignored) {
        }
        return entity;
    }

    private void callOnCreate(ShiftRequestEntity entity) {
        try {
            Method method = ShiftRequestEntity.class.getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(entity);
        } catch (Exception ignored) {
        }
    }

    // ========================================
    // 通常入力（isProxy=false）
    // ========================================

    @Nested
    @DisplayName("通常入力（isProxy=false）")
    class NormalInput {

        @Test
        @DisplayName("通常入力時_isProxyInputがfalseのまま保存される")
        void 通常入力時_isProxyInputがfalseのまま保存される() {
            // Given
            CreateShiftRequestRequest req = createRequest();
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity savedEntity = createSavedEntityWithId(REQUEST_ID);
            ShiftRequestResponse response = new ShiftRequestResponse(
                    REQUEST_ID, SCHEDULE_ID, USER_ID, null,
                    LocalDate.of(2026, 3, 2), "PREFERRED", "テスト", LocalDateTime.now());

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.empty());
            given(requestRepository.save(any(ShiftRequestEntity.class))).willReturn(savedEntity);
            given(proxyInputContext.isProxy()).willReturn(false);
            given(shiftMapper.toRequestResponse(savedEntity)).willReturn(response);

            // When
            shiftRequestService.submitRequest(req, USER_ID);

            // Then: 最初の1回のみ save が呼ばれ、proxyInputRecordRepository は呼ばれない
            verify(requestRepository, times(1)).save(any(ShiftRequestEntity.class));
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("通常入力時_isProxyInputフィールドはデフォルトでfalse")
        void 通常入力時_isProxyInputフィールドはデフォルトでfalse() {
            // Given
            CreateShiftRequestRequest req = createRequest();
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity savedEntity = createSavedEntityWithId(REQUEST_ID);
            ShiftRequestResponse response = new ShiftRequestResponse(
                    REQUEST_ID, SCHEDULE_ID, USER_ID, null,
                    LocalDate.of(2026, 3, 2), "PREFERRED", "テスト", LocalDateTime.now());

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.empty());

            ArgumentCaptor<ShiftRequestEntity> captor = ArgumentCaptor.forClass(ShiftRequestEntity.class);
            given(requestRepository.save(captor.capture())).willReturn(savedEntity);
            given(proxyInputContext.isProxy()).willReturn(false);
            given(shiftMapper.toRequestResponse(savedEntity)).willReturn(response);

            // When
            shiftRequestService.submitRequest(req, USER_ID);

            // Then: 保存時のエンティティは isProxyInput=false
            ShiftRequestEntity captured = captor.getValue();
            assertThat(captured.getIsProxyInput()).isFalse();
            assertThat(captured.getProxyInputRecordId()).isNull();
        }
    }

    // ========================================
    // 代理入力（isProxy=true）
    // ========================================

    @Nested
    @DisplayName("代理入力（isProxy=true）")
    class ProxyInput {

        @BeforeEach
        void setUpProxyContext() {
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            // orElseGet 内でのみ使われるため lenient スタブにする（冪等性テストでは呼ばれない）
            lenient().when(proxyInputContext.getSubjectUserId()).thenReturn(20L);
            lenient().when(proxyInputContext.getInputSource()).thenReturn("PAPER_FORM");
            lenient().when(proxyInputContext.getOriginalStorageLocation()).thenReturn("書類棚A-1");
        }

        @Test
        @DisplayName("代理入力時_isProxyInputがtrueでproxyInputRecordIdがセットされて保存される")
        void 代理入力時_isProxyInputがtrueでproxyInputRecordIdがセットされて保存される() {
            // Given
            CreateShiftRequestRequest req = createRequest();
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity firstSavedEntity = createSavedEntityWithId(REQUEST_ID);

            // 代理入力記録エンティティ
            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(20L)
                    .proxyUserId(USER_ID)
                    .featureScope("SHIFT_REQUEST")
                    .targetEntityType("SHIFT_REQUEST")
                    .targetEntityId(REQUEST_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("書類棚A-1")
                    .build();
            // id をセット
            try {
                java.lang.reflect.Field field = ProxyInputRecordEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(proxyRecord, PROXY_RECORD_ID);
            } catch (Exception ignored) {
            }

            // 2回目の save（代理フラグ付き更新）で返すエンティティ
            ShiftRequestEntity proxyFlaggedEntity = firstSavedEntity.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(PROXY_RECORD_ID)
                    .build();

            ShiftRequestResponse response = new ShiftRequestResponse(
                    REQUEST_ID, SCHEDULE_ID, USER_ID, null,
                    LocalDate.of(2026, 3, 2), "PREFERRED", "テスト", LocalDateTime.now());

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.empty());
            given(requestRepository.save(any(ShiftRequestEntity.class)))
                    .willReturn(firstSavedEntity)   // 1回目: 初回保存
                    .willReturn(proxyFlaggedEntity); // 2回目: 代理フラグ付き更新
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "SHIFT_REQUEST", REQUEST_ID))
                    .willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);
            given(shiftMapper.toRequestResponse(proxyFlaggedEntity)).willReturn(response);

            // When
            shiftRequestService.submitRequest(req, USER_ID);

            // Then: save が2回呼ばれる（初回保存 + 代理フラグ付き更新）
            verify(requestRepository, times(2)).save(any(ShiftRequestEntity.class));
            // Then: proxyInputRecordRepository.save が1回呼ばれる
            verify(proxyInputRecordRepository, times(1)).save(any(ProxyInputRecordEntity.class));
            // Then: 2回目の save で isProxyInput=true, proxyInputRecordId=PROXY_RECORD_ID
            ArgumentCaptor<ShiftRequestEntity> captor = ArgumentCaptor.forClass(ShiftRequestEntity.class);
            verify(requestRepository, times(2)).save(captor.capture());
            ShiftRequestEntity secondSaved = captor.getAllValues().get(1);
            assertThat(secondSaved.getIsProxyInput()).isTrue();
            assertThat(secondSaved.getProxyInputRecordId()).isEqualTo(PROXY_RECORD_ID);
        }

        @Test
        @DisplayName("代理入力時_冪等性チェックで既存レコードがあれば新規作成しない")
        void 代理入力時_冪等性チェックで既存レコードがあれば新規作成しない() {
            // Given
            CreateShiftRequestRequest req = createRequest();
            ShiftScheduleEntity schedule = createCollectingSchedule();
            ShiftRequestEntity firstSavedEntity = createSavedEntityWithId(REQUEST_ID);

            ProxyInputRecordEntity existingProxyRecord = ProxyInputRecordEntity.builder()
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(20L)
                    .proxyUserId(USER_ID)
                    .featureScope("SHIFT_REQUEST")
                    .targetEntityType("SHIFT_REQUEST")
                    .targetEntityId(REQUEST_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("書類棚A-1")
                    .build();
            try {
                java.lang.reflect.Field field = ProxyInputRecordEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(existingProxyRecord, PROXY_RECORD_ID);
            } catch (Exception ignored) {
            }

            ShiftRequestEntity proxyFlaggedEntity = firstSavedEntity.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(PROXY_RECORD_ID)
                    .build();

            ShiftRequestResponse response = new ShiftRequestResponse(
                    REQUEST_ID, SCHEDULE_ID, USER_ID, null,
                    LocalDate.of(2026, 3, 2), "PREFERRED", "テスト", LocalDateTime.now());

            given(scheduleService.findScheduleOrThrow(SCHEDULE_ID)).willReturn(schedule);
            given(requestRepository.findByScheduleIdAndUserIdAndSlotDate(
                    SCHEDULE_ID, USER_ID, LocalDate.of(2026, 3, 2)))
                    .willReturn(Optional.empty());
            given(requestRepository.save(any(ShiftRequestEntity.class)))
                    .willReturn(firstSavedEntity)
                    .willReturn(proxyFlaggedEntity);
            // 冪等性チェック: 既存レコードが見つかる
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "SHIFT_REQUEST", REQUEST_ID))
                    .willReturn(Optional.of(existingProxyRecord));
            given(shiftMapper.toRequestResponse(proxyFlaggedEntity)).willReturn(response);

            // When
            shiftRequestService.submitRequest(req, USER_ID);

            // Then: proxyInputRecordRepository.save は呼ばれない（既存レコードを使用）
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }
    }
}
