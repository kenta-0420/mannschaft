package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.service.ReservationSlotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationSlotService} の単体テスト。
 * 予約スロットのCRUD・状態管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationSlotService 単体テスト")
class ReservationSlotServiceTest {

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationMapper reservationMapper;

    @InjectMocks
    private ReservationSlotService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long SLOT_ID = 10L;
    private static final Long STAFF_USER_ID = 50L;
    private static final Long CREATED_BY = 100L;
    private static final LocalDate SLOT_DATE = LocalDate.of(2026, 4, 1);
    private static final LocalTime START_TIME = LocalTime.of(10, 0);
    private static final LocalTime END_TIME = LocalTime.of(11, 0);

    private ReservationSlotEntity createSlotEntity() {
        return ReservationSlotEntity.builder()
                .teamId(TEAM_ID)
                .staffUserId(STAFF_USER_ID)
                .title("テストスロット")
                .slotDate(SLOT_DATE)
                .startTime(START_TIME)
                .endTime(END_TIME)
                .price(new BigDecimal("1000"))
                .note("テストメモ")
                .createdBy(CREATED_BY)
                .build();
    }

    private ReservationSlotResponse createSlotResponse() {
        return ReservationSlotResponse.builder()
                .id(SLOT_ID)
                .teamId(TEAM_ID)
                .staffUserId(STAFF_USER_ID)
                .basic(new ReservationSlotResponse.SlotBasicDto("テストスロット", SLOT_DATE, START_TIME, END_TIME))
                .status(new ReservationSlotResponse.SlotStatusDto("AVAILABLE", 0, false, null, "テストメモ"))
                .recurrence(new ReservationSlotResponse.RecurrenceDto(null, null))
                .pricing(new ReservationSlotResponse.SlotPricingDto(new BigDecimal("1000")))
                .policy(new ReservationSlotResponse.SlotPolicyDto(null))
                .audit(new ReservationSlotResponse.SlotAuditDto(CREATED_BY, null, null))
                .build();
    }

    // ========================================
    // listSlots
    // ========================================

    @Nested
    @DisplayName("listSlots")
    class ListSlots {

        @Test
        @DisplayName("正常系: チームのスロット一覧を日付範囲で取得する")
        void スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(TEAM_ID, from, to))
                    .willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listSlots(TEAM_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBasic().title()).isEqualTo("テストスロット");
        }
    }

    // ========================================
    // listAvailableSlots
    // ========================================

    @Nested
    @DisplayName("listAvailableSlots")
    class ListAvailableSlots {

        @Test
        @DisplayName("正常系: 利用可能なスロット一覧を取得する")
        void 利用可能スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    TEAM_ID, SlotStatus.AVAILABLE, from, to)).willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listAvailableSlots(TEAM_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getSlot
    // ========================================

    @Nested
    @DisplayName("getSlot")
    class GetSlot {

        @Test
        @DisplayName("正常系: スロット詳細が返却される")
        void スロット詳細_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.getSlot(TEAM_ID, SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getBasic().title()).isEqualTo("テストスロット");
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロット詳細_存在しない() {
            // Given
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getSlot(TEAM_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }
    }

    // ========================================
    // createSlot
    // ========================================

    @Nested
    @DisplayName("createSlot")
    class CreateSlot {

        @Test
        @DisplayName("正常系: スロットが作成される")
        void スロット作成_正常() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "新スロット", SLOT_DATE, START_TIME, END_TIME,
                    null, new BigDecimal("2000"), "メモ", null);
            ReservationSlotEntity savedEntity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toSlotResponse(savedEntity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: approvalMode=MANUAL を指定すると枠の上書きとして entity に保存される")
        void スロット作成_承認モード上書き() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "手動承認枠", SLOT_DATE, START_TIME, END_TIME,
                    null, null, null, ApprovalMode.MANUAL);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then: 保存される entity の approvalMode が MANUAL であること
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("正常系: approvalMode 未指定（null）なら entity は NULL のまま（チーム既定を継承）")
        void スロット作成_承認モード未指定は継承() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "継承枠", SLOT_DATE, START_TIME, END_TIME,
                    null, null, null, null);
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.createSlot(TEAM_ID, request, CREATED_BY);

            // Then: approvalMode は NULL（継承）
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isNull();
        }

        @Test
        @DisplayName("異常系: 開始時刻が終了時刻以降の場合INVALID_TIME_RANGEエラー")
        void スロット作成_時刻逆転() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正スロット", SLOT_DATE,
                    LocalTime.of(14, 0), LocalTime.of(10, 0),
                    null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("異常系: 開始時刻と終了時刻が同一の場合INVALID_TIME_RANGEエラー")
        void スロット作成_時刻同一() {
            // Given
            CreateSlotRequest request = new CreateSlotRequest(
                    STAFF_USER_ID, "不正スロット", SLOT_DATE,
                    LocalTime.of(10, 0), LocalTime.of(10, 0),
                    null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createSlot(TEAM_ID, request, CREATED_BY))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }
    }

    // ========================================
    // updateSlot
    // ========================================

    @Nested
    @DisplayName("updateSlot")
    class UpdateSlot {

        @Test
        @DisplayName("正常系: スロットが部分更新される")
        void スロット更新_正常() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "更新後タイトル", null, null, null, null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(entity);
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class))).willReturn(response);

            // When
            ReservationSlotResponse result = service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: 時間帯を含む更新が正常に処理される")
        void スロット更新_時間帯変更() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, LocalTime.of(9, 0), LocalTime.of(12, 0), null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class))).willReturn(entity);
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class))).willReturn(response);

            // When
            ReservationSlotResponse result = service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 更新時の時刻が逆転している場合INVALID_TIME_RANGEエラー")
        void スロット更新_時刻逆転() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, LocalTime.of(14, 0), LocalTime.of(10, 0), null, null, null, null);
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));

            // When / Then
            assertThatThrownBy(() -> service.updateSlot(TEAM_ID, SLOT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        @Test
        @DisplayName("正常系: approvalMode=MANUAL 指定で枠の上書きが設定される")
        void スロット更新_承認モード上書き設定() {
            // Given
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, ApprovalMode.MANUAL, null);
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }

        @Test
        @DisplayName("正常系: clearApprovalMode=true で上書きが解除され NULL（チーム既定継承）に戻る")
        void スロット更新_承認モード上書き解除() {
            // Given: 既に MANUAL で上書きされている枠
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, null, null, null, null, null, null, null, true);
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .slotDate(SLOT_DATE)
                    .startTime(START_TIME)
                    .endTime(END_TIME)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then: approvalMode は NULL に戻る
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isNull();
        }

        @Test
        @DisplayName("正常系: approvalMode/clearApprovalMode いずれも未指定なら既存の上書き値が据え置かれる")
        void スロット更新_承認モード据え置き() {
            // Given: MANUAL で上書き済みの枠を、approvalMode 非指定で更新
            UpdateSlotRequest request = new UpdateSlotRequest(
                    null, "タイトルだけ変更", null, null, null, null, null, null, null);
            ReservationSlotEntity entity = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .slotDate(SLOT_DATE)
                    .startTime(START_TIME)
                    .endTime(END_TIME)
                    .approvalMode(ApprovalMode.MANUAL)
                    .build();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ReservationSlotEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(reservationMapper.toSlotResponse(any(ReservationSlotEntity.class)))
                    .willReturn(createSlotResponse());

            // When
            service.updateSlot(TEAM_ID, SLOT_ID, request);

            // Then: 据え置き（MANUAL のまま）
            ArgumentCaptor<ReservationSlotEntity> captor = ArgumentCaptor.forClass(ReservationSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            assertThat(captor.getValue().getApprovalMode()).isEqualTo(ApprovalMode.MANUAL);
        }
    }

    // ========================================
    // deleteSlot
    // ========================================

    @Nested
    @DisplayName("deleteSlot")
    class DeleteSlot {

        @Test
        @DisplayName("正常系: active予約のないスロットは論理削除される")
        void スロット削除_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(false);

            // When
            service.deleteSlot(TEAM_ID, SLOT_ID);

            // Then
            verify(slotRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロット削除_存在しない() {
            // Given
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteSlot(TEAM_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: active予約が紐づくスロットの削除はSLOT_HAS_ACTIVE_RESERVATIONS（409）で拒否され、論理削除されない")
        void スロット削除_active予約あり() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.deleteSlot(TEAM_ID, SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);

            // オーファン化防止: 削除（save）は呼ばれない
            verify(slotRepository, never()).save(any(ReservationSlotEntity.class));
        }

        @Test
        @DisplayName("正常系: ガードが参照する active ステータスは PENDING / CONFIRMED の2件のみ")
        void スロット削除_ガード対象ステータス() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(reservationRepository.existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), anyList()))
                    .willReturn(false);

            // When
            service.deleteSlot(TEAM_ID, SLOT_ID);

            // Then: 終端状態（CANCELLED/COMPLETED/NO_SHOW）は削除を妨げないことを、
            //       ガードに渡されるステータス集合で検証する
            ArgumentCaptor<List<ReservationStatus>> captor = ArgumentCaptor.forClass(List.class);
            verify(reservationRepository).existsByReservationSlotIdAndStatusIn(eq(SLOT_ID), captor.capture());
            assertThat(captor.getValue())
                    .containsExactlyInAnyOrder(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);
        }
    }

    // ========================================
    // closeSlot
    // ========================================

    @Nested
    @DisplayName("closeSlot")
    class CloseSlot {

        @Test
        @DisplayName("正常系: スロットがクローズされる")
        void スロットクローズ_正常() {
            // Given
            CloseSlotRequest request = new CloseSlotRequest("メンテナンス");
            ReservationSlotEntity entity = createSlotEntity();
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.closeSlot(TEAM_ID, SLOT_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(entity);
        }
    }

    // ========================================
    // reopenSlot
    // ========================================

    @Nested
    @DisplayName("reopenSlot")
    class ReopenSlot {

        @Test
        @DisplayName("正常系: スロットが再開される")
        void スロット再開_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            entity.close("メンテナンス");
            ReservationSlotResponse response = createSlotResponse();

            given(slotRepository.findByIdAndTeamId(SLOT_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toSlotResponse(entity)).willReturn(response);

            // When
            ReservationSlotResponse result = service.reopenSlot(TEAM_ID, SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(entity);
        }
    }

    // ========================================
    // listSlotsByStaff
    // ========================================

    @Nested
    @DisplayName("listSlotsByStaff")
    class ListSlotsByStaff {

        @Test
        @DisplayName("正常系: 担当者のスロット一覧が返却される")
        void 担当者スロット一覧_正常() {
            // Given
            LocalDate from = SLOT_DATE;
            LocalDate to = SLOT_DATE.plusDays(7);
            List<ReservationSlotEntity> entities = List.of(createSlotEntity());
            List<ReservationSlotResponse> responses = List.of(createSlotResponse());
            given(slotRepository.findByStaffUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                    STAFF_USER_ID, from, to)).willReturn(entities);
            given(reservationMapper.toSlotResponseList(entities)).willReturn(responses);

            // When
            List<ReservationSlotResponse> result = service.listSlotsByStaff(STAFF_USER_ID, from, to);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getSlotEntity
    // ========================================

    @Nested
    @DisplayName("getSlotEntity")
    class GetSlotEntity {

        @Test
        @DisplayName("正常系: スロットエンティティが返却される")
        void スロットエンティティ取得_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));

            // When
            ReservationSlotEntity result = service.getSlotEntity(SLOT_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("異常系: スロットが存在しない場合SLOT_NOT_FOUNDエラー")
        void スロットエンティティ取得_存在しない() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getSlotEntity(SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.SLOT_NOT_FOUND);
        }
    }

    // ========================================
    // incrementAndCheckFull
    // ========================================

    @Nested
    @DisplayName("incrementAndCheckFull")
    class IncrementAndCheckFull {

        @Test
        @DisplayName("正常系: 予約数がインクリメントされる")
        void インクリメント_正常() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();

            // When
            service.incrementAndCheckFull(entity);

            // Then
            assertThat(entity.getBookedCount()).isEqualTo(1);
            verify(slotRepository).save(entity);
        }
    }

    // ========================================
    // decrementAndReopen
    // ========================================

    @Nested
    @DisplayName("decrementAndReopen")
    class DecrementAndReopen {

        @Test
        @DisplayName("正常系: AVAILABLEスロットの予約数がデクリメントされる")
        void デクリメント_AVAILABLE() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            entity.incrementBookedCount();

            // When
            service.decrementAndReopen(entity);

            // Then
            assertThat(entity.getBookedCount()).isEqualTo(0);
            assertThat(entity.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            verify(slotRepository).save(entity);
        }

        @Test
        @DisplayName("正常系: FULLスロットがデクリメント後にAVAILABLEに戻る")
        void デクリメント_FULL_から_AVAILABLE() {
            // Given
            ReservationSlotEntity entity = createSlotEntity();
            entity.incrementBookedCount();
            entity.markFull();

            // When
            service.decrementAndReopen(entity);

            // Then
            assertThat(entity.getSlotStatus()).isEqualTo(SlotStatus.AVAILABLE);
            verify(slotRepository).save(entity);
        }
    }
}
