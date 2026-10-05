package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftSlotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ShiftSlotService}（tx 本体）の単体テスト。
 * シフト枠のCRUD・一括作成・シリアライズを検証する。
 *
 * <p>認可（per-scope・越境の 404 隠蔽・SYSTEM_ADMIN）は tx の外の {@code ShiftSlotFacade} へ移した
 * （CMP-260923-0954 W6a）。その単体検証は {@code ShiftSlotFacadeTest}、応答契約は
 * {@code ShiftScheduleSlotFacadeContractIT} が持つ。本クラスは tx 本体が認可に依存しないこと（Mock に認可クラスが無い）、
 * 認可の後の tx の中の親スケジュールの読み直し（FOR UPDATE）と、対象リソースの不在コード（K5）を固定する。</p>
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
    private com.mannschaft.app.shift.repository.ShiftAssignmentRepository assignmentRepository;

    @Mock
    private ShiftRequestRepository requestRepository;

    @Mock
    private Clock wallClock;

    @InjectMocks
    private ShiftSlotService shiftSlotService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 100L;
    private static final Long SLOT_ID = 200L;
    private static final Long POSITION_ID = 50L;
    /** 操作者（割当履歴の assigned_by に使う）。認可は Facade の責務で、本テストでは判定しない。 */
    private static final Long ACTOR = 999L;

    /**
     * 親スケジュールが実在する体で応答する。
     *
     * <p>{@code lenient()} なのは、存在しない ID のケースでは親の読み直しより先に例外を投げ、本スタブが
     * 未使用になるため。書き込みは tx の中で親を {@code findByIdForUpdate} で読み直す（認可の後・K6）。</p>
     */
    @BeforeEach
    void setUpParentSchedule() {
        lenient().when(wallClock.instant()).thenReturn(Instant.parse("2026-09-29T12:00:00Z"));
        lenient().when(wallClock.getZone()).thenReturn(ZoneId.of("Asia/Tokyo"));
        lenient().when(scheduleRepository.findById(SCHEDULE_ID)).thenReturn(Optional.of(
                com.mannschaft.app.shift.entity.ShiftScheduleEntity.builder()
                        .teamId(1L)
                        .build()));
        lenient().when(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).thenReturn(Optional.of(
                com.mannschaft.app.shift.entity.ShiftScheduleEntity.builder().teamId(1L).build()));
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
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID, true);

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
            List<ShiftSlotResponse> result = shiftSlotService.listSlots(SCHEDULE_ID, true);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getPosition().positionName()).isNull();
        }
    }

    // ========================================
    // scope 解決・不在コード（K5）・可視性の再判定
    // ========================================

    @Nested
    @DisplayName("scope 解決・不在コード・可視性")
    class ScopeAndNotFoundCodes {

        private ShiftScheduleEntity schedule(ShiftScheduleStatus status, LocalDateTime publishedAt) {
            ShiftScheduleEntity entity = ShiftScheduleEntity.builder()
                    .teamId(1L).title("t").periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1)).endDate(LocalDate.of(2026, 3, 7))
                    .status(status).publishedAt(publishedAt).createdBy(ACTOR).build();
            ReflectionTestUtils.setField(entity, "id", SCHEDULE_ID);
            return entity;
        }

        private void assertCode(Runnable action, ShiftErrorCode expected) {
            assertThatThrownBy(action::run)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(expected));
        }

        @Test
        @DisplayName("resolveScheduleScope_チームIDと公開状態を返す・不在は SHIFT_001")
        void resolveScheduleScope() {
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(
                    schedule(ShiftScheduleStatus.PUBLISHED, LocalDateTime.of(2026, 2, 20, 10, 0))));
            var scope = shiftSlotService.resolveScheduleScope(SCHEDULE_ID);
            assertThat(scope.teamId()).isEqualTo(1L);
            assertThat(scope.isHidden()).isFalse();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());
            assertCode(() -> shiftSlotService.resolveScheduleScope(SCHEDULE_ID),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }

        @Test
        @DisplayName("resolveSlotTeamId_枠の不在も親スケジュールの不在も SHIFT_002（K5）")
        void resolveSlotTeamIdは枠起点の不在コード() {
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(createSlotEntity()));
            assertThat(shiftSlotService.resolveSlotTeamId(SLOT_ID)).isEqualTo(1L);

            // 親だけ不在
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());
            assertCode(() -> shiftSlotService.resolveSlotTeamId(SLOT_ID), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);

            // 枠が不在
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.empty());
            assertCode(() -> shiftSlotService.resolveSlotTeamId(SLOT_ID), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
        }

        @Test
        @DisplayName("枠の更新・割当・削除は、親だけ不在でも SHIFT_002 で DB を変えない（K5）")
        void 枠起点の書き込みは親不在でSHIFT_002() {
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(createSlotEntity()));
            given(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).willReturn(Optional.empty());

            assertCode(() -> shiftSlotService.updateSlot(SLOT_ID,
                    new UpdateShiftSlotRequest(null, null, null, null, null, null, "メモ"), ACTOR),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            assertCode(() -> shiftSlotService.patchSlotAssignments(SLOT_ID,
                    new SlotAssignmentPatchRequest(List.of(1L), List.of(), 0), ACTOR),
                    ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);
            assertCode(() -> shiftSlotService.deleteSlot(SLOT_ID), ShiftErrorCode.SHIFT_SLOT_NOT_FOUND);

            verify(slotRepository, never()).save(any(ShiftSlotEntity.class));
            verify(slotRepository, never()).softDeleteById(any());
            verifyNoInteractions(assignmentRepository, requestRepository);
        }

        @Test
        @DisplayName("枠の作成・一括作成は、親スケジュールの不在で SHIFT_001 で DB を変えない")
        void スケジュール起点の作成は親不在でSHIFT_001() {
            given(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).willReturn(Optional.empty());
            CreateShiftSlotRequest slot = new CreateShiftSlotRequest(
                    LocalDate.of(2026, 3, 2), LocalTime.of(9, 0), LocalTime.of(17, 0), POSITION_ID, 1, null);

            assertCode(() -> shiftSlotService.createSlot(SCHEDULE_ID, slot),
                    ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            assertCode(() -> shiftSlotService.bulkCreateSlots(SCHEDULE_ID,
                    new BulkCreateShiftSlotRequest(List.of(slot))), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);

            verify(slotRepository, never()).save(any(ShiftSlotEntity.class));
            verify(slotRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("listSlots_未公開は管理者側でなければ SHIFT_001、COLLECTING は割当を伏せ、管理者側は伏せない")
        void listSlotsは可視性を再判定する() {
            ShiftSlotEntity slot = createSlotEntity();
            ReflectionTestUtils.setField(slot, "assignedUserIds", "[7]");
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of(slot));
            given(positionRepository.findById(POSITION_ID)).willReturn(Optional.of(createPositionEntity()));

            // 未公開
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(schedule(ShiftScheduleStatus.DRAFT, null)));
            assertCode(() -> shiftSlotService.listSlots(SCHEDULE_ID, false), ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
            assertThat(shiftSlotService.listSlots(SCHEDULE_ID, true).get(0).getAssignedUserIds())
                    .containsExactly(7L);

            // 希望収集中: 割当だけ伏せる
            given(scheduleRepository.findById(SCHEDULE_ID))
                    .willReturn(Optional.of(schedule(ShiftScheduleStatus.COLLECTING, null)));
            ShiftSlotResponse masked = shiftSlotService.listSlots(SCHEDULE_ID, false).get(0);
            assertThat(masked.getAssignedUserIds()).isEmpty();
            assertThat(masked.isAssignmentMasked()).isTrue();
            assertThat(shiftSlotService.listSlots(SCHEDULE_ID, true).get(0).getAssignedUserIds())
                    .containsExactly(7L);
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
            InOrder order = inOrder(scheduleRepository, slotRepository);
            order.verify(scheduleRepository).findByIdForUpdate(SCHEDULE_ID);
            order.verify(slotRepository).save(any(ShiftSlotEntity.class));
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
        @DisplayName("シフト枠削除_正常_割当と枠指定希望と枠が論理削除される")
        void シフト枠削除_正常_子を連鎖論理削除する() {
            // Given
            ShiftSlotEntity entity = createSlotEntity();
            given(slotRepository.findById(SLOT_ID)).willReturn(Optional.of(entity));
            given(slotRepository.softDeleteById(SLOT_ID))
                    .willReturn(1);

            // When
            shiftSlotService.deleteSlot(SLOT_ID);

            // Then
            verify(slotRepository).softDeleteById(SLOT_ID);
            verify(assignmentRepository).softDeleteBySlotId(SLOT_ID);
            verify(requestRepository).softDeleteBySlotId(SLOT_ID);
            verify(slotRepository, never()).delete(entity);
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
