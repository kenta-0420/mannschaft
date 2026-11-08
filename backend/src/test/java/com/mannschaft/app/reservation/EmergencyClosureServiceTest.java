package com.mannschaft.app.reservation;

import com.mannschaft.app.auth.service.UserService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosurePreviewResponse;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
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
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    private UserService userService;

    @Mock
    private EmailOutboxService emailOutboxService;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private EmergencyClosureRepository emergencyClosureRepository;

    @Mock
    private ReservationSlotService slotService;

    @Mock
    private EmergencyClosureConfirmationRepository confirmationRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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

    // ========================================
    // sendClosure の一括取消（CMP-260822-1730 AC-1730-6）
    // ========================================

    @Nested
    @DisplayName("sendClosure の一括取消は行ロック後の最新状態で判定する（AC-1730-6）")
    class SendClosureLocking {

        private com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest request(boolean cancel) {
            com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest r =
                    new com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest();
            r.setStartDate(TARGET_DATE);
            r.setEndDate(TARGET_DATE);
            r.setReason("休業");
            r.setSubject("休業");
            r.setMessageBody("休業");
            r.setCancelReservations(cancel);
            return r;
        }

        @Test
        @DisplayName("一括取消は対象idを行ロックで取り、ロック後にCANCELLEDの行は取消・枠減算・通知対象から外す")
        void ロック後に失効済みの行は取消も減算もしない() {
            ReservationSlotEntity s = slot(401L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(s));
            ReservationEntity active = reservationFor(401L);
            injectId(active, 9001L);
            ReservationEntity expired = ReservationEntity.builder().reservationSlotId(401L).lineId(1L)
                    .teamId(TEAM_ID).userId(777L).status(ReservationStatus.CANCELLED).build();
            injectId(expired, 9002L);
            given(reservationRepository.findIdsByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(9001L, 9002L));
            given(reservationRepository.findByIdInForUpdate(List.of(9001L, 9002L)))
                    .willReturn(List.of(active, expired));
            given(slotService.getSlotEntity(401L)).willReturn(s);
            given(emergencyClosureRepository.save(any())).willAnswer(inv -> {
                EmergencyClosureEntity e = inv.getArgument(0);
                injectId(e, 1L);
                return e;
            });

            var response = service.sendClosure(TEAM_ID, 1L, request(true));

            assertThat(active.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(expired.getCancelReason()).as("失効の取消を上書きしない").isNull();
            verify(slotService, org.mockito.Mockito.times(1)).decrementAndReopen(s);
            assertThat(response.getSentCount()).as("失効済みの利用者へ休業通知を重ねない").isEqualTo(1);
            verify(reservationRepository, never()).findByReservationSlotIdInAndStatusIn(any(), any());
        }

        @Test
        @DisplayName("取消しない通知のみの送信は行ロックを取らない")
        void 通知のみは行ロックを取らない() {
            ReservationSlotEntity s = slot(402L, LocalTime.of(10, 0), LocalTime.of(11, 0));
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of(s));
            given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                    .willReturn(List.of(reservationFor(402L)));
            given(emergencyClosureRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.sendClosure(TEAM_ID, 1L, request(false));

            verify(reservationRepository, never()).findByIdInForUpdate(any());
            verify(slotService, never()).decrementAndReopen(any());
        }
    }

    // ========================================
    // confirmClosure / 患者確認イベント発火
    // ========================================

    @Nested
    @DisplayName("confirmClosure / 患者確認イベント発火")
    class ConfirmClosure {

        private static final Long CLOSURE_ID = 500L;
        private static final Long USER_ID = 42L;

        @Test
        @DisplayName("正常系: 未確認→確認で EmergencyClosureConfirmedEvent が publish される")
        void 確認成功でイベントが発火する() {
            // Given: 未確認の確認追跡レコード + 対応する closure（teamId を保持）
            EmergencyClosureConfirmationEntity confirmation = EmergencyClosureConfirmationEntity.builder()
                    .emergencyClosureId(CLOSURE_ID)
                    .userId(USER_ID)
                    .reservationId(999L)
                    .appointmentAt(LocalDateTime.of(2026, 4, 8, 10, 0))
                    .build();
            EmergencyClosureEntity closure = EmergencyClosureEntity.builder()
                    .teamId(TEAM_ID)
                    .build();

            given(confirmationRepository.findByEmergencyClosureIdAndUserId(CLOSURE_ID, USER_ID))
                    .willReturn(Optional.of(confirmation));
            given(emergencyClosureRepository.findById(CLOSURE_ID)).willReturn(Optional.of(closure));

            // When
            service.confirmClosure(CLOSURE_ID, USER_ID);

            // Then: confirmed=true 保存後にイベントが publish される
            verify(confirmationRepository).save(confirmation);
            ArgumentCaptor<EmergencyClosureConfirmedEvent> captor =
                    ArgumentCaptor.forClass(EmergencyClosureConfirmedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());

            EmergencyClosureConfirmedEvent published = captor.getValue();
            assertThat(published.getClosureId()).isEqualTo(CLOSURE_ID);
            assertThat(published.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(published.getUserId()).isEqualTo(USER_ID);
            assertThat(published.getConfirmedAt()).isNotNull();
        }

        @Test
        @DisplayName("冪等: 既に確認済みなら save もイベント発火もしない")
        void 既に確認済みならイベントは発火しない() {
            // Given: 既に確認済み（confirmedAt セット済み）
            EmergencyClosureConfirmationEntity confirmation = EmergencyClosureConfirmationEntity.builder()
                    .emergencyClosureId(CLOSURE_ID)
                    .userId(USER_ID)
                    .reservationId(999L)
                    .appointmentAt(LocalDateTime.of(2026, 4, 8, 10, 0))
                    .confirmedAt(LocalDateTime.of(2026, 4, 7, 9, 0))
                    .build();

            given(confirmationRepository.findByEmergencyClosureIdAndUserId(CLOSURE_ID, USER_ID))
                    .willReturn(Optional.of(confirmation));

            // When
            service.confirmClosure(CLOSURE_ID, USER_ID);

            // Then: 二重確認は副作用なし
            verify(confirmationRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    // ========================================
    // 氏名解決（CMP-261030-0650）: auth の UserService 経由の一括取得（N+1 解消・越境依存の解消）
    // ========================================

    private static UserService.FullNameAndEmail contact(long id, String fullName) {
        return new UserService.FullNameAndEmail(fullName, "u" + id + "@example.com");
    }

    private ReservationEntity reservationOf(long slotId, long userId) {
        return ReservationEntity.builder()
                .reservationSlotId(slotId)
                .lineId(1L)
                .teamId(TEAM_ID)
                .userId(userId)
                .build();
    }

    private void givenSlotsAndReservations(List<ReservationEntity> reservations) {
        given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                eq(TEAM_ID), any(), any()))
                .willReturn(List.of(slot(300L, LocalTime.of(9, 0), LocalTime.of(10, 0))));
        given(reservationRepository.findByReservationSlotIdInAndStatusIn(any(), any()))
                .willReturn(reservations);
        // preview では使うが sendClosure では使わないため lenient にする
        org.mockito.Mockito.lenient().when(slotRepository.findAllById(any()))
                .thenReturn(List.of(slot(300L, LocalTime.of(9, 0), LocalTime.of(10, 0))));
    }

    private EmergencyClosureConfirmationEntity confirmationOf(long userId) {
        return EmergencyClosureConfirmationEntity.builder()
                .emergencyClosureId(700L)
                .userId(userId)
                .reservationId(900L + userId)
                .appointmentAt(LocalDateTime.of(2026, 4, 8, 10, 0))
                .build();
    }

    /** 一括取得の引数（取得対象 userId の集合）を捕まえる。モックの戻り値は引数に依存させる。 */
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Collection<Long>> idsCaptor() {
        return ArgumentCaptor.forClass(Collection.class);
    }

    /** 引数と無関係に固定の一覧を返さず、渡された ID のうち known に含まれるものだけを返す。 */
    private void givenUsers(Map<Long, UserService.FullNameAndEmail> known) {
        given(userService.getFullNamesAndEmails(any())).willAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            Map<Long, UserService.FullNameAndEmail> result = new LinkedHashMap<>();
            // 逆順で返して、呼び出し側が順序でなく userId で対応づけることを確かめる
            List<Long> reversed = new ArrayList<>(known.keySet());
            java.util.Collections.reverse(reversed);
            for (Long id : reversed) {
                if (ids.contains(id)) {
                    result.put(id, known.get(id));
                }
            }
            return result;
        });
    }

    @Nested
    @DisplayName("previewClosure / 氏名の一括解決")
    class PreviewNames {

        @Test
        @DisplayName("AC-11: 一括取得は重複排除済みの対象 userId 集合で1回だけ呼ぶ")
        void 一括取得は対象IDで1回だけ() {
            givenSlotsAndReservations(List.of(
                    reservationOf(300L, 1L), reservationOf(300L, 2L), reservationOf(300L, 1L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            ArgumentCaptor<Collection<Long>> captor = idsCaptor();
            verify(userService, times(1)).getFullNamesAndEmails(captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("AC-11: 対象0件のとき氏名取得は0回")
        void 対象0件なら取得しない() {
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    eq(TEAM_ID), any(), any())).willReturn(List.of());

            EmergencyClosurePreviewResponse res = service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            assertThat(res.getAffectedReservations()).isEmpty();
            verify(userService, never()).getFullNamesAndEmails(any());
        }

        @Test
        @DisplayName("AC-11: 一括取得の結果が逆順でも userId で氏名が対応する")
        void 逆順でも氏名が正しく対応する() {
            givenSlotsAndReservations(List.of(reservationOf(300L, 1L), reservationOf(300L, 2L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            EmergencyClosurePreviewResponse res = service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            assertThat(res.getAffectedReservations()).extracting("userId", "userFullName", "userEmail")
                    .containsExactly(
                            tuple(1L, "山田 太郎", "u1@example.com"),
                            tuple(2L, "鈴木 花子", "u2@example.com"));
        }

        @Test
        @DisplayName("AC-11: 同一ユーザーの予約が複数あっても行数を維持する")
        void 同一ユーザー複数予約でも行数を維持() {
            givenSlotsAndReservations(List.of(
                    reservationOf(300L, 1L), reservationOf(300L, 1L), reservationOf(300L, 1L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎")));

            EmergencyClosurePreviewResponse res = service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            assertThat(res.getAffectedReservations()).hasSize(3);
            assertThat(res.getAffectedReservations()).allMatch(a -> "山田 太郎".equals(a.getUserFullName()));
        }

        @Test
        @DisplayName("AC-10d/AC-11: 見つからないユーザーも行を落とさず氏名・メールは空文字、他の行は取り違えない")
        void 見つからないユーザーは空文字で行を維持() {
            givenSlotsAndReservations(List.of(
                    reservationOf(300L, 1L), reservationOf(300L, 99L), reservationOf(300L, 2L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            EmergencyClosurePreviewResponse res = service.previewClosure(TEAM_ID, TARGET_DATE, TARGET_DATE, null, null);

            assertThat(res.getAffectedReservations()).extracting("userId", "userFullName", "userEmail")
                    .containsExactly(
                            tuple(1L, "山田 太郎", "u1@example.com"),
                            tuple(99L, "", ""),
                            tuple(2L, "鈴木 花子", "u2@example.com"));
        }
    }

    @Nested
    @DisplayName("getConfirmations / 氏名の一括解決")
    class ConfirmationNames {

        private void givenClosure() {
            given(emergencyClosureRepository.findById(700L))
                    .willReturn(Optional.of(EmergencyClosureEntity.builder().teamId(TEAM_ID).build()));
        }

        @Test
        @DisplayName("AC-11: 一括取得は重複排除済みの対象 userId 集合で1回だけ呼ぶ")
        void 一括取得は対象IDで1回だけ() {
            givenClosure();
            given(confirmationRepository.findByEmergencyClosureId(700L))
                    .willReturn(List.of(confirmationOf(1L), confirmationOf(2L), confirmationOf(1L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            service.getConfirmations(TEAM_ID, 700L);

            ArgumentCaptor<Collection<Long>> captor = idsCaptor();
            verify(userService, times(1)).getFullNamesAndEmails(captor.capture());
            assertThat(captor.getValue()).containsExactlyInAnyOrder(1L, 2L);
        }

        @Test
        @DisplayName("AC-11: 対象0件のとき氏名取得は0回")
        void 対象0件なら取得しない() {
            givenClosure();
            given(confirmationRepository.findByEmergencyClosureId(700L)).willReturn(List.of());

            assertThat(service.getConfirmations(TEAM_ID, 700L)).isEmpty();

            verify(userService, never()).getFullNamesAndEmails(any());
        }

        @Test
        @DisplayName("AC-11: 一括取得の結果が逆順でも userId で氏名が対応する")
        void 逆順でも氏名が正しく対応する() {
            givenClosure();
            given(confirmationRepository.findByEmergencyClosureId(700L))
                    .willReturn(List.of(confirmationOf(1L), confirmationOf(2L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            var res = service.getConfirmations(TEAM_ID, 700L);

            assertThat(res).extracting("userId", "userFullName", "userEmail")
                    .containsExactly(
                            tuple(1L, "山田 太郎", "u1@example.com"),
                            tuple(2L, "鈴木 花子", "u2@example.com"));
        }

        @Test
        @DisplayName("AC-11: 同一ユーザーの確認行が複数あっても行数を維持する")
        void 同一ユーザー複数行でも行数を維持() {
            givenClosure();
            given(confirmationRepository.findByEmergencyClosureId(700L))
                    .willReturn(List.of(confirmationOf(1L), confirmationOf(1L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎")));

            assertThat(service.getConfirmations(TEAM_ID, 700L)).hasSize(2);
        }

        @Test
        @DisplayName("AC-10d/AC-11: 見つからないユーザーも行を落とさず空文字、他の行は取り違えない")
        void 見つからないユーザーは空文字で行を維持() {
            givenClosure();
            given(confirmationRepository.findByEmergencyClosureId(700L))
                    .willReturn(List.of(confirmationOf(1L), confirmationOf(99L), confirmationOf(2L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));

            var res = service.getConfirmations(TEAM_ID, 700L);

            assertThat(res).extracting("userId", "userFullName", "userEmail")
                    .containsExactly(
                            tuple(1L, "山田 太郎", "u1@example.com"),
                            tuple(99L, "", ""),
                            tuple(2L, "鈴木 花子", "u2@example.com"));
        }
    }

    @Nested
    @DisplayName("sendClosure / メール送信経路の氏名・宛先解決")
    class SendClosureMail {

        private CreateEmergencyClosureRequest request() {
            CreateEmergencyClosureRequest r = new CreateEmergencyClosureRequest();
            r.setStartDate(TARGET_DATE);
            r.setEndDate(TARGET_DATE);
            r.setReason("設備点検");
            r.setSubject("臨時休業のお知らせ");
            r.setMessageBody("本文");
            r.setCancelReservations(false);
            return r;
        }

        @Test
        @DisplayName("重複予約と見つからないユーザーが混ざっても、取得ID集合・宛先・enqueue件数・sentCountが正しい")
        void 重複と欠損が混ざる送信() {
            // ユーザー1は予約2件（重複）、ユーザー2は1件、ユーザー99は退会等で見つからない
            givenSlotsAndReservations(List.of(
                    reservationOf(300L, 1L), reservationOf(300L, 1L),
                    reservationOf(300L, 99L), reservationOf(300L, 2L)));
            givenUsers(Map.of(1L, contact(1L, "山田 太郎"), 2L, contact(2L, "鈴木 花子")));
            given(emergencyClosureRepository.save(any())).willAnswer(inv -> {
                EmergencyClosureEntity e = inv.getArgument(0);
                injectId(e, 700L);
                return e;
            });

            EmergencyClosureResponse response = service.sendClosure(TEAM_ID, 5L, request());

            // 取得対象は重複排除済みの {1, 99, 2}
            ArgumentCaptor<Collection<Long>> idsCaptor = idsCaptor();
            verify(userService, times(1)).getFullNamesAndEmails(idsCaptor.capture());
            assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 99L, 2L);

            // メールは見つかったユーザー2人にだけ、ユーザーごとに1通
            ArgumentCaptor<EmailOutboxRequest> mailCaptor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService, times(2)).enqueue(mailCaptor.capture());
            assertThat(mailCaptor.getAllValues())
                    .extracting(EmailOutboxRequest::userId, EmailOutboxRequest::toAddress)
                    .containsExactlyInAnyOrder(
                            tuple(1L, "u1@example.com"),
                            tuple(2L, "u2@example.com"));

            // 従来どおり、送信件数は見つからないユーザーも含む重複排除済みの対象数（3）
            assertThat(response.getSentCount()).isEqualTo(3);
            ArgumentCaptor<EmergencyClosureEntity> savedCaptor = ArgumentCaptor.forClass(EmergencyClosureEntity.class);
            verify(emergencyClosureRepository).save(savedCaptor.capture());
            assertThat(savedCaptor.getValue().getSentCount()).isEqualTo(3);
        }
    }
}
