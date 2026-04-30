package com.mannschaft.app.reservation;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.dto.EmergencyClosurePreviewResponse;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.EmergencyClosureService;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureService} の単体テスト。
 *
 * <p>本テストの主目的は<strong>部分時間帯休業の境界判定</strong>と
 * <strong>{@code validateTimeRange} のバリデーションロジック</strong>の網羅検証である。
 * 重複判定式 {@code slot.startTime < endTime AND slot.endTime > startTime}
 * （境界含まずの open interval）が要件どおり動作することをスロット境界ケースで確認する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureService 単体テスト")
class EmergencyClosureServiceTest {

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private EmergencyClosureRepository emergencyClosureRepository;

    @Mock
    private ReservationSlotService slotService;

    @Mock
    private EmergencyClosureConfirmationRepository confirmationRepository;

    @InjectMocks
    private EmergencyClosureService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 4, 8);

    /**
     * テスト用のスロットを作成する。
     * BaseEntity の id は protected setter がないため、リフレクションで強制設定する
     * （単体テストにおいては Repository モックの戻り値として ID を持たせるためにのみ使用）。
     */
    private ReservationSlotEntity slot(long id, LocalTime start, LocalTime end) {
        ReservationSlotEntity entity = ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .slotDate(TARGET_DATE)
                .startTime(start)
                .endTime(end)
                .build();
        injectId(entity, id);
        return entity;
    }

    private ReservationEntity reservationFor(long slotId) {
        return ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(1L)
                .teamId(TEAM_ID)
                .userId(100L + slotId)
                .build();
    }

    /**
     * BaseEntity の private id フィールドにリフレクションで値を設定する。
     * @InjectMocks 経由のサービステストで Repository 戻り値を擬似する目的でのみ使用。
     */
    private void injectId(Object entity, long id) {
        try {
            Class<?> clazz = entity.getClass();
            while (clazz != null && !"BaseEntity".equals(clazz.getSimpleName())) {
                clazz = clazz.getSuperclass();
            }
            if (clazz == null) {
                throw new IllegalStateException("BaseEntity が見つかりません");
            }
            Field idField = clazz.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        }
        catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ID 注入に失敗しました", e);
        }
    }

    // ========================================
    // validateTimeRange の境界条件
    // （public な previewClosure 経由でテストする）
    // ========================================

    @Nested
    @DisplayName("validateTimeRange / 時刻バリデーション")
    class ValidateTimeRange {

        @Test
        @DisplayName("正常系: startTime / endTime とも null（終日休業）")
        void 終日休業はバリデーション通過() {
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of());

            assertThatCode(() -> service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("正常系: 09:00〜11:00 はバリデーション通過")
        void 正常な時間帯はバリデーション通過() {
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of());

            assertThatCode(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(9, 0), LocalTime.of(11, 0)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: startTime のみ指定")
        void 開始時刻のみ指定はエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(9, 0), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: endTime のみ指定")
        void 終了時刻のみ指定はエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, null, LocalTime.of(11, 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: startTime == endTime")
        void 開始時刻と終了時刻が同一はエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(10, 0), LocalTime.of(10, 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: startTime > endTime")
        void 開始時刻が終了時刻より後ろはエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(11, 0), LocalTime.of(9, 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: 開始時刻の分が0でない（HH:00 以外）")
        void 開始時刻の分がゼロでないとエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(9, 30), LocalTime.of(11, 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: 終了時刻の分が0でない（HH:00 以外）")
        void 終了時刻の分がゼロでないとエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(9, 0), LocalTime.of(11, 30)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: 秒が0でない")
        void 秒がゼロでないとエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, LocalTime.of(9, 0, 30), LocalTime.of(11, 0)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_TIME_RANGE);
        }
    }

    // ========================================
    // validateDateRange の境界条件
    // ========================================

    @Nested
    @DisplayName("validateDateRange / 日付バリデーション")
    class ValidateDateRange {

        @Test
        @DisplayName("異常系: startDate > endDate")
        void 開始日が終了日より後ろはエラー() {
            assertThatThrownBy(() -> service.previewClosure(
                    TEAM_ID, LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 8), null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_DATE_RANGE);
        }

        @Test
        @DisplayName("正常系: startDate == endDate（単日）")
        void 同一日はバリデーション通過() {
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of());

            assertThatCode(() -> service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null))
                    .doesNotThrowAnyException();
        }
    }

    // ========================================
    // 時間帯フィルタの境界判定
    // 休業時間帯 09:00〜11:00 を基準に各スロットの境界条件を検証する
    // 重複判定式: slot.startTime < endTime AND slot.endTime > startTime（境界含まず）
    // ========================================

    @Nested
    @DisplayName("findActiveReservations / 時間帯重複判定")
    class TimeRangeOverlap {

        private static final LocalTime CLOSURE_START = LocalTime.of(9, 0);
        private static final LocalTime CLOSURE_END = LocalTime.of(11, 0);

        @Test
        @DisplayName("対象外: 休業時間より完全に前のスロット（08:00〜09:00、終端が境界に接する）")
        void 完全に前のスロットは対象外() {
            // Given: 8:00〜9:00 のスロット。slot.endTime(9:00) > closureStart(9:00) は false → 対象外
            ReservationSlotEntity beforeSlot = slot(101L, LocalTime.of(8, 0), LocalTime.of(9, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(beforeSlot));

            // When
            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            // Then: フィルタで全件除外され、Repository への問い合わせが発生しない
            assertThat(response.getAffectedCount()).isZero();
            verify(reservationRepository, never()).findByReservationSlotIdInAndStatusIn(any(), any());
        }

        @Test
        @DisplayName("対象外: 休業時間より完全に後のスロット（11:00〜12:00、始端が境界に接する）")
        void 完全に後のスロットは対象外() {
            // Given: 11:00〜12:00 のスロット。slot.startTime(11:00) < closureEnd(11:00) は false → 対象外
            ReservationSlotEntity afterSlot = slot(102L, LocalTime.of(11, 0), LocalTime.of(12, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(afterSlot));

            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            assertThat(response.getAffectedCount()).isZero();
            verify(reservationRepository, never()).findByReservationSlotIdInAndStatusIn(any(), any());
        }

        @Test
        @DisplayName("対象: 休業時間内に完全に収まるスロット（09:30〜10:30）")
        void 完全包含されるスロットは対象() {
            // Given
            ReservationSlotEntity insideSlot = slot(103L, LocalTime.of(9, 30), LocalTime.of(10, 30));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(insideSlot));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(reservationFor(103L)));

            // When
            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            // Then: スロット 103 のみが Repository に渡る
            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactly(103L);
            assertThat(response.getAffectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("対象: 休業時間の前から侵入するスロット（08:00〜10:00、部分重複）")
        void 前から重なるスロットは対象() {
            ReservationSlotEntity overlapStart = slot(104L, LocalTime.of(8, 0), LocalTime.of(10, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(overlapStart));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(reservationFor(104L)));

            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactly(104L);
            assertThat(response.getAffectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("対象: 休業時間の後ろまで延びるスロット（10:00〜12:00、部分重複）")
        void 後ろに重なるスロットは対象() {
            ReservationSlotEntity overlapEnd = slot(105L, LocalTime.of(10, 0), LocalTime.of(12, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(overlapEnd));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(reservationFor(105L)));

            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactly(105L);
            assertThat(response.getAffectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("対象: 休業時間を完全に包含するスロット（07:00〜13:00）")
        void 完全に包含するスロットは対象() {
            ReservationSlotEntity wrappingSlot = slot(106L, LocalTime.of(7, 0), LocalTime.of(13, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(wrappingSlot));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(reservationFor(106L)));

            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactly(106L);
            assertThat(response.getAffectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("混在: 5スロット中、対象のみが Repository に渡る")
        void 複数スロット混在で対象のみ抽出される() {
            // Given: 対象3件 / 対象外2件
            ReservationSlotEntity beforeBoundary = slot(201L, LocalTime.of(8, 0), LocalTime.of(9, 0));   // 対象外
            ReservationSlotEntity overlapStart = slot(202L, LocalTime.of(8, 0), LocalTime.of(10, 0));    // 対象
            ReservationSlotEntity inside = slot(203L, LocalTime.of(9, 30), LocalTime.of(10, 30));        // 対象
            ReservationSlotEntity overlapEnd = slot(204L, LocalTime.of(10, 0), LocalTime.of(12, 0));     // 対象
            ReservationSlotEntity afterBoundary = slot(205L, LocalTime.of(11, 0), LocalTime.of(12, 0));  // 対象外

            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(beforeBoundary, overlapStart, inside, overlapEnd, afterBoundary));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(
                            reservationFor(202L), reservationFor(203L), reservationFor(204L)));

            // When
            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            // Then: 対象スロットID 3件のみが Repository に渡る
            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(202L, 203L, 204L);
            assertThat(response.getAffectedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("終日休業: 時間帯フィルタなしで全スロットが対象")
        void 終日休業は時間帯フィルタなしで全スロット対象() {
            // Given: 早朝も深夜もすべて含まれる
            ReservationSlotEntity earlyMorning = slot(301L, LocalTime.of(6, 0), LocalTime.of(7, 0));
            ReservationSlotEntity midDay = slot(302L, LocalTime.of(12, 0), LocalTime.of(13, 0));
            ReservationSlotEntity lateNight = slot(303L, LocalTime.of(20, 0), LocalTime.of(21, 0));

            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any()))
                    .willReturn(List.of(earlyMorning, midDay, lateNight));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(
                            reservationFor(301L), reservationFor(302L), reservationFor(303L)));

            // When: startTime/endTime とも null（終日休業）
            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            // Then: 全スロットが対象
            ArgumentCaptor<List<Long>> captor = slotIdCaptor();
            verify(reservationRepository).findByReservationSlotIdInAndStatusIn(captor.capture(), any());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(301L, 302L, 303L);
            assertThat(response.getAffectedCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("スロットがゼロ件のとき Repository への問い合わせが発生しない")
        void スロットゼロ件時は問い合わせなし() {
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of());

            EmergencyClosurePreviewResponse response = service.previewClosure(
                    TEAM_ID, TARGET_DATE, TARGET_DATE, CLOSURE_START, CLOSURE_END);

            assertThat(response.getAffectedCount()).isZero();
            verify(reservationRepository, never()).findByReservationSlotIdInAndStatusIn(any(), any());
        }

        /**
         * Mockito の ArgumentCaptor を List&lt;Long&gt; 用に生成するヘルパー（unchecked 警告を局所化）。
         */
        @SuppressWarnings("unchecked")
        private ArgumentCaptor<List<Long>> slotIdCaptor() {
            return ArgumentCaptor.forClass(List.class);
        }
    }
}
