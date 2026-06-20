package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReminderRequest;
import com.mannschaft.app.reservation.dto.ReminderResponse;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.service.ReservationReminderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationReminderService} の単体テスト。
 * リマインダーのCRUD・送信処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationReminderService 単体テスト")
class ReservationReminderServiceTest {

    @Mock
    private ReservationReminderRepository reminderRepository;

    @Mock
    private ReservationMapper reservationMapper;

    private ReservationReminderService service;

    /** 送信判定の基準時刻を固定する Clock（直書き禁止・テストで境界を固定）。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 4, 1, 12, 0);
    private final Clock fixedClock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new ReservationReminderService(reminderRepository, reservationMapper, fixedClock);
    }

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long RESERVATION_ID = 1L;
    private static final Long REMINDER_ID = 10L;
    private static final LocalDateTime REMIND_AT = LocalDateTime.of(2026, 4, 1, 9, 0);

    private ReservationReminderEntity createReminderEntity() {
        return ReservationReminderEntity.builder()
                .reservationId(RESERVATION_ID)
                .remindAt(REMIND_AT)
                .build();
    }

    /**
     * @PrePersist の onCreate() をリフレクションで呼び出す。
     * ReservationReminderEntity は BaseEntity を継承しないため独自の onCreate を持つ。
     */
    private ReminderResponse createReminderResponse() {
        return new ReminderResponse(
                REMINDER_ID, RESERVATION_ID, REMIND_AT, "PENDING", null, null);
    }

    // ========================================
    // listReminders
    // ========================================

    @Nested
    @DisplayName("listReminders")
    class ListReminders {

        @Test
        @DisplayName("正常系: 予約のリマインダー一覧が返却される")
        void リマインダー一覧_正常() {
            // Given
            List<ReservationReminderEntity> entities = List.of(createReminderEntity());
            List<ReminderResponse> responses = List.of(createReminderResponse());
            given(reminderRepository.findByReservationIdOrderByRemindAtAsc(RESERVATION_ID)).willReturn(entities);
            given(reservationMapper.toReminderResponseList(entities)).willReturn(responses);

            // When
            List<ReminderResponse> result = service.listReminders(RESERVATION_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getReservationId()).isEqualTo(RESERVATION_ID);
        }

        @Test
        @DisplayName("正常系: リマインダーが0件の場合空リストが返却される")
        void リマインダー一覧_空() {
            // Given
            given(reminderRepository.findByReservationIdOrderByRemindAtAsc(RESERVATION_ID))
                    .willReturn(List.of());
            given(reservationMapper.toReminderResponseList(List.of())).willReturn(List.of());

            // When
            List<ReminderResponse> result = service.listReminders(RESERVATION_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createReminder
    // ========================================

    @Nested
    @DisplayName("createReminder")
    class CreateReminder {

        @Test
        @DisplayName("正常系: リマインダーが作成される")
        void リマインダー作成_正常() {
            // Given
            CreateReminderRequest request = new CreateReminderRequest(REMIND_AT);
            ReminderResponse response = createReminderResponse();

            given(reminderRepository.countByReservationId(RESERVATION_ID)).willReturn(0L);
            given(reminderRepository.save(any(ReservationReminderEntity.class))).willAnswer(invocation -> {
                ReservationReminderEntity arg = invocation.getArgument(0);
                // @PrePersist をシミュレーション
                try {
                    Method onCreate = ReservationReminderEntity.class.getDeclaredMethod("onCreate");
                    onCreate.setAccessible(true);
                    onCreate.invoke(arg);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return arg;
            });
            given(reservationMapper.toReminderResponse(any(ReservationReminderEntity.class))).willReturn(response);

            // When
            ReminderResponse result = service.createReminder(RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            verify(reminderRepository).save(any(ReservationReminderEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存2件の状態でリマインダーが作成される(上限内)")
        void リマインダー作成_上限ギリギリ() {
            // Given
            CreateReminderRequest request = new CreateReminderRequest(REMIND_AT);
            ReservationReminderEntity savedEntity = createReminderEntity();
            ReminderResponse response = createReminderResponse();

            given(reminderRepository.countByReservationId(RESERVATION_ID)).willReturn(2L);
            given(reminderRepository.save(any(ReservationReminderEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toReminderResponse(savedEntity)).willReturn(response);

            // When
            ReminderResponse result = service.createReminder(RESERVATION_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: リマインダーが上限(3件)を超える場合MAX_REMINDERS_EXCEEDEDエラー")
        void リマインダー作成_上限超過() {
            // Given
            CreateReminderRequest request = new CreateReminderRequest(REMIND_AT);
            given(reminderRepository.countByReservationId(RESERVATION_ID)).willReturn(3L);

            // When / Then
            assertThatThrownBy(() -> service.createReminder(RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MAX_REMINDERS_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: リマインダーが上限を大きく超える場合もMAX_REMINDERS_EXCEEDEDエラー")
        void リマインダー作成_上限大幅超過() {
            // Given
            CreateReminderRequest request = new CreateReminderRequest(REMIND_AT);
            given(reminderRepository.countByReservationId(RESERVATION_ID)).willReturn(10L);

            // When / Then
            assertThatThrownBy(() -> service.createReminder(RESERVATION_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.MAX_REMINDERS_EXCEEDED);
        }
    }

    // ========================================
    // cancelReminder
    // ========================================

    @Nested
    @DisplayName("cancelReminder")
    class CancelReminder {

        @Test
        @DisplayName("正常系: リマインダーがキャンセルされる")
        void リマインダーキャンセル_正常() {
            // Given
            ReservationReminderEntity entity = createReminderEntity();
            given(reminderRepository.findById(REMINDER_ID)).willReturn(Optional.of(entity));

            // When
            service.cancelReminder(REMINDER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
            verify(reminderRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: リマインダーが存在しない場合REMINDER_NOT_FOUNDエラー")
        void リマインダーキャンセル_存在しない() {
            // Given
            given(reminderRepository.findById(REMINDER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.cancelReminder(REMINDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.REMINDER_NOT_FOUND);
        }
    }

    // ========================================
    // findDueReminders
    // ========================================

    @Nested
    @DisplayName("findDueReminders")
    class FindDueReminders {

        @Test
        @DisplayName("正常系: PENDING かつ remind_at 到来済みリマインダーを取得する（送信・マークはしない）")
        void 取得_正常() {
            // Given
            ReservationReminderEntity entity = createReminderEntity();
            given(reminderRepository.findByStatusAndRemindAtBefore(
                    eq(ReminderStatus.PENDING), eq(NOW)))
                    .willReturn(List.of(entity));

            // When
            List<ReservationReminderEntity> result = service.findDueReminders();

            // Then: 取得のみ。SENT へのマーク・save は行わない（送信は別バッチが担う）
            assertThat(result).hasSize(1);
            assertThat(entity.getStatus()).isEqualTo(ReminderStatus.PENDING);
            assertThat(entity.getSentAt()).isNull();
            verify(reminderRepository, never()).save(any(ReservationReminderEntity.class));
        }

        @Test
        @DisplayName("正常系: 注入 Clock の now を基準時刻として渡す（直書き禁止）")
        void 取得_Clock基準() {
            // Given
            given(reminderRepository.findByStatusAndRemindAtBefore(
                    eq(ReminderStatus.PENDING), eq(NOW)))
                    .willReturn(List.of());

            // When
            service.findDueReminders();

            // Then: 固定 Clock の NOW がそのまま渡る
            verify(reminderRepository).findByStatusAndRemindAtBefore(ReminderStatus.PENDING, NOW);
        }

        @Test
        @DisplayName("正常系: 対象 0 件なら空リスト")
        void 取得_対象なし() {
            // Given
            given(reminderRepository.findByStatusAndRemindAtBefore(
                    eq(ReminderStatus.PENDING), eq(NOW)))
                    .willReturn(List.of());

            // When
            List<ReservationReminderEntity> result = service.findDueReminders();

            // Then
            assertThat(result).isEmpty();
        }
    }
}
