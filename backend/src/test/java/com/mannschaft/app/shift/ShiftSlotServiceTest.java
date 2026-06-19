package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftSlotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftSlotService} の単体テスト。
 * シフト枠のCRUD・一括作成・シリアライズを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftSlotService 単体テスト")
class ShiftSlotServiceTest {

    @Mock
    private ShiftSlotRepository slotRepository;

    @Mock
    private ShiftPositionRepository positionRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private ShiftSlotService shiftSlotService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long POSITION_ID = 50L;

    private ShiftSlotEntity createSlotEntity() {
        return ShiftSlotEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .slotDate(LocalDate.of(2026, 3, 2))
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(17, 0))
                .positionId(POSITION_ID)
                .requiredCount(2)
                .build();
    }

    private ShiftPositionEntity createPositionEntity() {
        return ShiftPositionEntity.builder()
                .teamId(1L)
                .name("キッチン")
                .build();
    }

    // ========================================
    // listSlots
    // ========================================

    @Nested
    @DisplayName("listSlots")
    class ListSlots {

        @Test
        @DisplayName("シフト枠一覧取得_正常_リスト返却")
        void シフト枠一覧取得_正常_リスト返却() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of(entity));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPosition().positionName()).isEqualTo("キッチン");
        }

        @Test
        @DisplayName("シフト枠一覧取得_positionIdがnull_positionNameがnull")
        void シフト枠一覧取得_positionIdがnull_positionNameがnull() {
            // Given
            ShiftSlotEntity entity = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .slotDate(LocalDate.of(2026, 3, 2))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .positionId(null)
                    .requiredCount(1)
                    .build();
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of(entity));

            // When
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPosition().positionName()).isNull();
        }
    }

    // ========================================
    // getSlot
    // ========================================

    @Nested
    @DisplayName("getSlot")
    class GetSlot {

        @Test
        @DisplayName("シフト枠単体取得_正常_レスポンス返却")
        void シフト枠単体取得_正常_レスポンス返却() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            ShiftSlotResponse result = shiftSlotService.getSlot(SLOT_ID);

            // Then
            assertThat(result.getScheduleId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("シフト枠単体取得_存在しない_BusinessException")
        void シフト枠単体取得_存在しない_BusinessException() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.getSlot(SLOT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
        }
    }

    // ========================================
    // createSlot
    // ========================================

    @Nested
    @DisplayName("createSlot")
    class CreateSlot {

        @Test
        @DisplayName("シフト枠作成_正常_レスポンス返却")
        void シフト枠作成_正常_レスポンス返却() {
            // Given
            CreateShiftSlotRequest req = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(9, 0), LocalTime.of(17, 0),
                    POSITION_ID, 2, "午前シフト");
            ShiftSlotEntity savedEntity = createSlotEntity();
            given(slotRepository.save(any(ShiftSlotEntity.class))).willReturn(savedEntity);
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            ShiftSlotResponse result = shiftSlotService.createSlot(SCHEDULE_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ShiftSlotEntity.class));
        }

        @Test
        @DisplayName("シフト枠作成_requiredCount未指定_デフォルト1")
        void シフト枠作成_requiredCount未指定_デフォルト1() {
            // Given
            CreateShiftSlotRequest req = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(9, 0), LocalTime.of(17, 0),
                    null, null, null);
            ShiftSlotEntity savedEntity = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID)
                    .slotDate(LocalDate.of(2026, 3, 2))
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(17, 0))
                    .requiredCount(1)
                    .build();
            given(slotRepository.save(any(ShiftSlotEntity.class))).willReturn(savedEntity);

            // When
            ShiftSlotResponse result = shiftSlotService.createSlot(SCHEDULE_ID, req);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // bulkCreateSlots
    // ========================================

    @Nested
    @DisplayName("bulkCreateSlots")
    class BulkCreateSlots {

        @Test
        @DisplayName("シフト枠一括作成_正常_複数枠返却")
        void シフト枠一括作成_正常_複数枠返却() {
            // Given
            CreateShiftSlotRequest slot1 = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(9, 0), LocalTime.of(13, 0),
                    POSITION_ID, 1, null);
            CreateShiftSlotRequest slot2 = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(13, 0), LocalTime.of(17, 0),
                    POSITION_ID, 1, null);
            BulkCreateShiftSlotRequest req = new BulkCreateShiftSlotRequest(List.of(slot1, slot2));

            ShiftSlotEntity savedEntity = createSlotEntity();
            given(slotRepository.saveAll(anyList())).willReturn(List.of(savedEntity, savedEntity));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            List<ShiftSlotResponse> result = shiftSlotService.bulkCreateSlots(SCHEDULE_ID, req);

            // Then
            assertThat(result).hasSize(2);
            verify(slotRepository).saveAll(anyList());
        }
    }

    // ========================================
    // updateSlot
    // ========================================

    @Nested
    @DisplayName("updateSlot")
    class UpdateSlot {

        @Test
        @DisplayName("シフト枠更新_正常_更新後レスポンス返却")
        void シフト枠更新_正常_更新後レスポンス返却() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, LocalTime.of(10, 0), null, null, 3, null, "更新メモ");
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willReturn(entity);
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            ShiftSlotResponse result = shiftSlotService.updateSlot(SLOT_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ShiftSlotEntity.class));
        }

        @Test
        @DisplayName("シフト枠更新_assignedUserIds設定_シリアライズ呼ばれる")
        void シフト枠更新_assignedUserIds設定_シリアライズ呼ばれる() throws JsonProcessingException {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, null, null, null, null, List.of(1L, 2L), null);
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(objectMapper.writeValueAsString(List.of(1L, 2L))).willReturn("[1,2]");
            given(slotRepository.save(any(ShiftSlotEntity.class))).willReturn(entity);
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            ShiftSlotResponse result = shiftSlotService.updateSlot(SLOT_ID, req);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("シフト枠更新_存在しない_BusinessException")
        void シフト枠更新_存在しない_BusinessException() {
            // Given
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, null, null, null, null, null, null);
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.updateSlot(SLOT_ID, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // ToBuilderUpdateRegression (行重複INSERT防止回帰テスト)
    // ========================================

    @Nested
    @DisplayName("ToBuilderUpdateRegression_ShiftSlot")
    class ToBuilderUpdateRegressionShiftSlot {

        /**
         * id 採番済みの existing entity を生成する。
         *
         * <p>{@link com.mannschaft.app.common.BaseEntity#id} は setter を持たないため
         * {@link ReflectionTestUtils} で採番済み状態を再現する（DB から findById で取得した
         * managed entity を模す）。
         */
        private ShiftSlotEntity existingSlotWithId() {
            ShiftSlotEntity entity = createSlotEntity();
            ReflectionTestUtils.setField(entity, "id", SLOT_ID);
            return entity;
        }

        @Test
        @DisplayName("updateSlot_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave")
        void updateSlot_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave() {
            // Given: findById で取得した id 採番済みの managed entity
            ShiftSlotEntity existing = existingSlotWithId();
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, LocalTime.of(10, 0), null, null, 3, null, "更新メモ");

            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            shiftSlotService.updateSlot(SLOT_ID, req);

            // Then: save に渡るのは findById で取得した「まさにその」managed entity
            // （toBuilder().build() で作り直した別インスタンスではない）。
            // id が保持されているので save は UPDATE になり、新規 INSERT（id=null）は起きない。
            ArgumentCaptor<ShiftSlotEntity> captor = ArgumentCaptor.forClass(ShiftSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            ShiftSlotEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);       // 同一インスタンス（新規作成でない）
            assertThat(saved.getId()).isEqualTo(SLOT_ID); // id 欠落（INSERT 化）が起きていない
            // 部分更新が managed entity に反映されている
            assertThat(saved.getStartTime()).isEqualTo(LocalTime.of(10, 0));
            assertThat(saved.getRequiredCount()).isEqualTo(3);
            assertThat(saved.getNote()).isEqualTo("更新メモ");
            // 未指定フィールドは現値維持
            assertThat(saved.getSlotDate()).isEqualTo(LocalDate.of(2026, 3, 2));
        }

        @Test
        @DisplayName("patchSlotAssignments_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave")
        void patchSlotAssignments_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave() {
            // Given
            ShiftSlotEntity existing = existingSlotWithId();
            ReflectionTestUtils.setField(existing, "version", 0L);
            SlotAssignmentPatchRequest request =
                    new SlotAssignmentPatchRequest(List.of(101L), List.of(), 0);

            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            shiftSlotService.patchSlotAssignments(SLOT_ID, request);

            // Then: 同一インスタンスが save に渡り id 保持
            ArgumentCaptor<ShiftSlotEntity> captor = ArgumentCaptor.forClass(ShiftSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            ShiftSlotEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(SLOT_ID);
        }
    }

    // ========================================
    // deleteSlot
    // ========================================

    @Nested
    @DisplayName("deleteSlot")
    class DeleteSlot {

        @Test
        @DisplayName("シフト枠削除_正常_deleteが呼ばれる")
        void シフト枠削除_正常_deleteが呼ばれる() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));

            // When
            shiftSlotService.deleteSlot(SLOT_ID);

            // Then
            verify(slotRepository).delete(entity);
        }

        @Test
        @DisplayName("シフト枠削除_存在しない_BusinessException")
        void シフト枠削除_存在しない_BusinessException() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.deleteSlot(SLOT_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
