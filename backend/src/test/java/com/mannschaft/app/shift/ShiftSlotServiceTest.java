package com.mannschaft.app.shift;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftSlotService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    private ShiftScheduleRepository scheduleRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private com.mannschaft.app.shift.repository.ShiftAssignmentRepository assignmentRepository;

    @InjectMocks
    private ShiftSlotService shiftSlotService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long POSITION_ID = 50L;
    /** 操作者。本テストは認可の可否でなく CRUD 挙動の検証が目的のため SYSTEM_ADMIN で短絡させる */
    private static final Long ACTOR = 999L;

    /**
     * 認可を短絡させる（本テストの主眼は CRUD 挙動であり、per-scope 認可そのものは
     * {@code ShiftSlotScopeContractIT} で実 DB 越しに検証する）。
     *
     * <p>{@code lenient()} なのは、存在しない ID のケースでは {@code findSlotOrThrow} が
     * 認可判定より先に例外を投げ、本スタブが未使用になるため。</p>
     */
    @BeforeEach
    void setUpAuthz() {
        lenient().when(accessControlService.isSystemAdmin(ACTOR)).thenReturn(true);
    }

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
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID, ACTOR);

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
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID, ACTOR);

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
            ShiftSlotResponse result = shiftSlotService.getSlot(SLOT_ID, ACTOR);

            // Then
            assertThat(result.getScheduleId()).isEqualTo(SCHEDULE_ID);
        }

        @Test
        @DisplayName("シフト枠単体取得_存在しない_BusinessException")
        void シフト枠単体取得_存在しない_BusinessException() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.getSlot(SLOT_ID, ACTOR))
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
            ShiftSlotResponse result = shiftSlotService.createSlot(SCHEDULE_ID, req, ACTOR);

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
            ShiftSlotResponse result = shiftSlotService.createSlot(SCHEDULE_ID, req, ACTOR);

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
            List<ShiftSlotResponse> result = shiftSlotService.bulkCreateSlots(SCHEDULE_ID, req, ACTOR);

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
            ShiftSlotResponse result = shiftSlotService.updateSlot(SLOT_ID, req, ACTOR);

            // Then
            assertThat(result).isNotNull();
            verify(slotRepository).save(any(ShiftSlotEntity.class));
        }

        @Test
        @DisplayName("シフト枠更新_assignedUserIds設定_JSON配列として保存される")
        void シフト枠更新_assignedUserIds設定_JSON配列として保存される() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            ReflectionTestUtils.setField(entity, "id", SLOT_ID);
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, null, null, null, null, List.of(1L, 2L), null);
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willReturn(entity);
            given(assignmentRepository.findAllBySlotId(SLOT_ID)).willReturn(List.of());
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            // When
            ShiftSlotResponse result = shiftSlotService.updateSlot(SLOT_ID, req, ACTOR);

            // Then
            assertThat(result.getAssignedUserIds()).containsExactly(1L, 2L);
            assertThat(entity.getAssignedUserIds()).isEqualTo("[1,2]");
        }

        @Test
        @DisplayName("シフト枠更新_存在しない_BusinessException")
        void シフト枠更新_存在しない_BusinessException() {
            // Given
            UpdateShiftSlotRequest req = new UpdateShiftSlotRequest(
                    null, null, null, null, null, null, null);
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.updateSlot(SLOT_ID, req, ACTOR))
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
            shiftSlotService.updateSlot(SLOT_ID, req, ACTOR);

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
            shiftSlotService.patchSlotAssignments(SLOT_ID, request, ACTOR);

            // Then: 同一インスタンスが save に渡り id 保持
            ArgumentCaptor<ShiftSlotEntity> captor = ArgumentCaptor.forClass(ShiftSlotEntity.class);
            verify(slotRepository).save(captor.capture());
            ShiftSlotEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(SLOT_ID);
        }
    }

    // ========================================
    // 手動割当の操作履歴（CMP-260908-2117 AC-6 / AC-7）
    // ========================================

    @Nested
    @DisplayName("手動割当の操作履歴（CMP-260908-2117）")
    class ManualAssignmentHistory {

        private ShiftSlotEntity slotWithAssignments(String assignedUserIdsJson) {
            ShiftSlotEntity entity = createSlotEntity();
            ReflectionTestUtils.setField(entity, "id", SLOT_ID);
            ReflectionTestUtils.setField(entity, "version", 0L);
            ReflectionTestUtils.setField(entity, "assignedUserIds", assignedUserIdsJson);
            return entity;
        }

        @SuppressWarnings("unchecked")
        private List<com.mannschaft.app.shift.entity.ShiftAssignmentEntity> captureSaved() {
            ArgumentCaptor<List<com.mannschaft.app.shift.entity.ShiftAssignmentEntity>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(assignmentRepository).saveAll(captor.capture());
            return captor.getValue();
        }

        @Test
        @DisplayName("AC-6: 手動で割り当てると操作者付きの CONFIRMED 履歴行が記録される（run_id は NULL = 手動）")
        void 手動割当で履歴行が記録される() {
            ShiftSlotEntity existing = slotWithAssignments(null);
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(assignmentRepository.findAllBySlotId(SLOT_ID)).willReturn(List.of());
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            shiftSlotService.patchSlotAssignments(
                    SLOT_ID, new SlotAssignmentPatchRequest(List.of(101L), List.of(), 0), ACTOR);

            List<com.mannschaft.app.shift.entity.ShiftAssignmentEntity> saved = captureSaved();
            assertThat(saved).singleElement().satisfies(a -> {
                assertThat(a.getSlotId()).isEqualTo(SLOT_ID);
                assertThat(a.getUserId()).isEqualTo(101L);
                assertThat(a.getRunId()).isNull();
                assertThat(a.getAssignedBy()).isEqualTo(ACTOR);
                assertThat(a.getStatus()).isEqualTo(ShiftAssignmentStatus.CONFIRMED);
            });
        }

        @Test
        @DisplayName("AC-7: 手動で解除すると当該履歴行が REVOKED に遷移する（解除も履歴から追える）")
        void 手動解除で履歴行がREVOKEDになる() {
            ShiftSlotEntity existing = slotWithAssignments("[101]");
            com.mannschaft.app.shift.entity.ShiftAssignmentEntity history =
                    com.mannschaft.app.shift.entity.ShiftAssignmentEntity.builder()
                            .slotId(SLOT_ID)
                            .userId(101L)
                            .assignedBy(ACTOR)
                            .status(ShiftAssignmentStatus.CONFIRMED)
                            .build();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(assignmentRepository.findAllBySlotId(SLOT_ID)).willReturn(List.of(history));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            shiftSlotService.patchSlotAssignments(
                    SLOT_ID, new SlotAssignmentPatchRequest(List.of(), List.of(101L), 0), ACTOR);

            assertThat(captureSaved()).singleElement()
                    .extracting(com.mannschaft.app.shift.entity.ShiftAssignmentEntity::getStatus)
                    .isEqualTo(ShiftAssignmentStatus.REVOKED);
        }

        @Test
        @DisplayName("同じ人を再度割り当てても、有効な履歴行があれば二重に記録しない（冪等）")
        void 既に有効な履歴行があれば追加しない() {
            ShiftSlotEntity existing = slotWithAssignments(null);
            com.mannschaft.app.shift.entity.ShiftAssignmentEntity history =
                    com.mannschaft.app.shift.entity.ShiftAssignmentEntity.builder()
                            .slotId(SLOT_ID)
                            .userId(101L)
                            .assignedBy(ACTOR)
                            .status(ShiftAssignmentStatus.CONFIRMED)
                            .build();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(assignmentRepository.findAllBySlotId(SLOT_ID)).willReturn(List.of(history));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            shiftSlotService.patchSlotAssignments(
                    SLOT_ID, new SlotAssignmentPatchRequest(List.of(101L), List.of(), 0), ACTOR);

            verify(assignmentRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("割当が変化しない操作では履歴表を一切触らない")
        void 変化がなければ履歴表を触らない() {
            ShiftSlotEntity existing = slotWithAssignments("[101]");
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(existing));
            given(slotRepository.save(any(ShiftSlotEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(positionRepository.findById(POSITION_ID))
                    .willReturn(Optional.of(createPositionEntity()));

            shiftSlotService.patchSlotAssignments(
                    SLOT_ID, new SlotAssignmentPatchRequest(List.of(101L), List.of(), 0), ACTOR);

            verifyNoInteractions(assignmentRepository);
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
            shiftSlotService.deleteSlot(SLOT_ID, ACTOR);

            // Then
            verify(slotRepository).delete(entity);
        }

        @Test
        @DisplayName("シフト枠削除_存在しない_BusinessException")
        void シフト枠削除_存在しない_BusinessException() {
            // Given
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftSlotService.deleteSlot(SLOT_ID, ACTOR))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
