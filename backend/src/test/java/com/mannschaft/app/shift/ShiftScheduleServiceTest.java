package com.mannschaft.app.shift;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.DomainEventPublisher;
import org.mockito.ArgumentCaptor;
import com.mannschaft.app.shift.dto.CreateShiftScheduleRequest;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftScheduleSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftScheduleRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftChangeRequestRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.mannschaft.app.shift.service.ShiftAutoAssignService;
import com.mannschaft.app.shift.service.ShiftScheduleService;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * {@link ShiftScheduleService}（tx 本体）の単体テスト。
 * シフトスケジュールのCRUD・ステータス遷移・複製を検証する。
 *
 * <p>認可（per-scope・越境の 404 隠蔽・SYSTEM_ADMIN）は tx の外の {@code ShiftScheduleFacade} へ移した
 * （CMP-260923-0954 W6a）。その単体検証は {@code ShiftScheduleFacadeTest}、応答契約は
 * {@code ShiftScheduleSlotFacadeContractIT} が持つ。本クラスは tx 本体が認可に依存しないこと
 * （Mock に認可クラスが無い）と、tx の中の読み直し・可視性の再判定を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftScheduleService 単体テスト")
class ShiftScheduleServiceTest {

    @Mock
    private ShiftScheduleRepository scheduleRepository;

    /**
     * CMP-260909-1445 で {@code ShiftScheduleService} に追加された依存。
     * ARCHIVED 遷移時に OPEN 変更依頼を自動 WITHDRAWN 化する（バッチ経路と副作用を揃える）ため、
     * mock を張らないと当該遷移テストが NPE で落ちる。
     */
    @Mock
    private ShiftChangeRequestRepository changeRequestRepository;

    @Mock
    private ShiftSlotRepository slotRepository;

    @Mock
    private ShiftRequestRepository requestRepository;

    @Mock
    private ShiftAssignmentRepository assignmentRepository;

    @Mock
    private ShiftPositionRepository positionRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private ShiftAutoAssignService autoAssignService;

    @Mock
    private DomainEventPublisher eventPublisher;

    /**
     * CMP-260909-1445 で {@code ShiftScheduleService} に追加された依存（{@code ClockConfig#wallClock}）。
     *
     * <p>ARCHIVED 遷移が {@code LocalDateTime.now(wallClock)} を評価するため、素の {@code @Mock}
     * だと {@code Clock#instant()} が null を返して NPE になる。固定 {@code Clock} を
     * {@code @Spy} で与えて実挙動を持たせる（{@code ClockConfig} の javadoc が
     * 「テストでは必ず固定 Clock を使用すること」と定めている）。</p>
     */
    @Spy
    private Clock wallClock = Clock.fixed(
            Instant.parse("2026-03-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    @InjectMocks
    private ShiftScheduleService shiftScheduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long SCHEDULE_ID = 100L;
    private static final Long USER_ID = 10L;

    /**
     * テスト用のシフト表エンティティ。
     *
     * <p>CMP-260826-2127（AC-13）: かつて {@code DRAFT} だったが、未公開シフト表の遮断により
     * 非管理者から見た DRAFT は「存在ごと秘匿（404）」になる。本クラスの正常系が固定したいのは
     * 「当該チームのメンバーが自チームのシフト表を読める」という日常の振る舞いであるため、
     * 期待値でなくフィクスチャ側を公開済みに直してある
     *（DRAFT に対する 404 は {@code ShiftUnpublishedScheduleVisibilityContractIT} が固定する）。</p>
     */
    private ShiftScheduleEntity createScheduleEntity() {
        return ShiftScheduleEntity.builder()
                .teamId(TEAM_ID)
                .title("3月第1週シフト")
                .periodType(ShiftPeriodType.WEEKLY)
                .startDate(LocalDate.of(2026, 3, 1))
                .endDate(LocalDate.of(2026, 3, 7))
                .status(ShiftScheduleStatus.PUBLISHED)
                .publishedAt(LocalDateTime.of(2026, 2, 20, 10, 0))
                .createdBy(USER_ID)
                .build();
    }

    private ShiftScheduleResponse createScheduleResponse() {
        return ShiftScheduleResponse.builder()
                .id(SCHEDULE_ID)
                .teamId(TEAM_ID)
                .content(new ShiftScheduleResponse.ShiftContentDto("3月第1週シフト", "WEEKLY", null))
                .period(new ShiftScheduleResponse.ShiftPeriodDto(
                        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7), null))
                .status(new ShiftScheduleResponse.ShiftStatusDto("DRAFT", null, null))
                .audit(new ShiftScheduleResponse.ShiftAuditDto(USER_ID, LocalDateTime.now(), LocalDateTime.now()))
                .build();
    }

    // ========================================
    // listSchedules
    // ========================================

    @Nested
    @DisplayName("listSchedules")
    class ListSchedules {

        @Test
        @DisplayName("チームのスケジュール一覧取得_正常_リスト返却")
        void チームのスケジュール一覧取得_正常_リスト返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findByTeamIdOrderByStartDateDesc(TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toScheduleResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftScheduleResponse> result = shiftScheduleService.listSchedules(TEAM_ID, false);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent().title()).isEqualTo("3月第1週シフト");
            verify(scheduleRepository).findByTeamIdOrderByStartDateDesc(TEAM_ID);
        }
    }

    // ========================================
    // listSchedulesByPeriod
    // ========================================

    @Nested
    @DisplayName("listSchedulesByPeriod")
    class ListSchedulesByPeriod {

        @Test
        @DisplayName("期間指定一覧取得_正常_フィルタ結果返却")
        void 期間指定一覧取得_正常_フィルタ結果返却() {
            // Given
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findByTeamIdAndStartDateBetweenOrderByStartDateDesc(TEAM_ID, from, to))
                    .willReturn(List.of(entity));
            given(shiftMapper.toScheduleResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<ShiftScheduleResponse> result =
                    shiftScheduleService.listSchedulesByPeriod(TEAM_ID, from, to, false);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // getSchedule
    // ========================================

    @Nested
    @DisplayName("getSchedule")
    class GetSchedule {

        @Test
        @DisplayName("スケジュール単体取得_正常_レスポンス返却")
        void スケジュール単体取得_正常_レスポンス返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.getSchedule(SCHEDULE_ID, false);

            // Then
            assertThat(result.getContent().title()).isEqualTo("3月第1週シフト");
        }

        @Test
        @DisplayName("スケジュール単体取得_存在しない_BusinessException")
        void スケジュール単体取得_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.getSchedule(SCHEDULE_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        }
    }

    // ========================================
    // resolveScope / 可視性の再判定（tx の中。認可は Facade の責務）
    // ========================================

    @Nested
    @DisplayName("resolveScope・可視性の再判定")
    class ScopeAndVisibility {

        @Test
        @DisplayName("resolveScope_チームIDと公開状態を返す")
        void resolveScope_チームIDと公開状態を返す() {
            ShiftScheduleEntity entity = createScheduleEntity();
            ReflectionTestUtils.setField(entity, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            var scope = shiftScheduleService.resolveScope(SCHEDULE_ID);

            assertThat(scope.scheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(scope.teamId()).isEqualTo(TEAM_ID);
            assertThat(scope.status()).isEqualTo(ShiftScheduleStatus.PUBLISHED);
            assertThat(scope.isHidden()).isFalse();
        }

        @Test
        @DisplayName("resolveScope_不在_SHIFT_SCHEDULE_NOT_FOUND")
        void resolveScope_不在_SHIFT_SCHEDULE_NOT_FOUND() {
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> shiftScheduleService.resolveScope(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
        }

        @Test
        @DisplayName("getSchedule_未公開は管理者側でなければ404（SHIFT_001）")
        void getSchedule_未公開は管理者側でなければ404() {
            ShiftScheduleEntity draft = ShiftScheduleEntity.builder()
                    .teamId(TEAM_ID).title("下書き").periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1)).endDate(LocalDate.of(2026, 3, 7))
                    .status(ShiftScheduleStatus.DRAFT).createdBy(USER_ID).build();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(draft));

            assertThatThrownBy(() -> shiftScheduleService.getSchedule(SCHEDULE_ID, false))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
            verify(shiftMapper, never()).toScheduleResponse(any());
        }

        @Test
        @DisplayName("getSchedule_未公開でも管理者側（privileged）は取得できる")
        void getSchedule_未公開でも管理者側は取得できる() {
            ShiftScheduleEntity draft = ShiftScheduleEntity.builder()
                    .teamId(TEAM_ID).title("下書き").periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 1)).endDate(LocalDate.of(2026, 3, 7))
                    .status(ShiftScheduleStatus.DRAFT).createdBy(USER_ID).build();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(draft));
            given(shiftMapper.toScheduleResponse(draft)).willReturn(response);

            assertThat(shiftScheduleService.getSchedule(SCHEDULE_ID, true)).isSameAs(response);
        }

        @Test
        @DisplayName("listSchedules_未公開は管理者側でなければ一覧から除外、管理者側は全量")
        void listSchedules_未公開は管理者側でなければ除外() {
            ShiftScheduleEntity published = createScheduleEntity();
            ShiftScheduleEntity draft = ShiftScheduleEntity.builder()
                    .teamId(TEAM_ID).title("下書き").periodType(ShiftPeriodType.WEEKLY)
                    .startDate(LocalDate.of(2026, 3, 8)).endDate(LocalDate.of(2026, 3, 14))
                    .status(ShiftScheduleStatus.DRAFT).createdBy(USER_ID).build();
            given(scheduleRepository.findByTeamIdOrderByStartDateDesc(TEAM_ID))
                    .willReturn(List.of(draft, published));
            given(shiftMapper.toScheduleResponseList(any())).willReturn(List.of());

            shiftScheduleService.listSchedules(TEAM_ID, false);
            shiftScheduleService.listSchedules(TEAM_ID, true);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ShiftScheduleEntity>> captor =
                    ArgumentCaptor.forClass((Class<List<ShiftScheduleEntity>>) (Class<?>) List.class);
            verify(shiftMapper, org.mockito.Mockito.times(2)).toScheduleResponseList(captor.capture());
            assertThat(captor.getAllValues().get(0)).containsExactly(published);
            assertThat(captor.getAllValues().get(1)).containsExactly(draft, published);
        }
    }

    // ========================================
    // createSchedule
    // ========================================

    @Nested
    @DisplayName("createSchedule")
    class CreateSchedule {

        @Test
        @DisplayName("スケジュール作成_正常_レスポンス返却")
        void スケジュール作成_正常_レスポンス返却() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "3月第1週シフト", "WEEKLY",
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                    null, null);
            ShiftScheduleEntity savedEntity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toScheduleResponse(savedEntity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID);

            // Then
            assertThat(result.getContent().title()).isEqualTo("3月第1週シフト");
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール作成_開始日が終了日より後_BusinessException")
        void スケジュール作成_開始日が終了日より後_BusinessException() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "無効スケジュール", null,
                    LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1),
                    null, null);

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_DATE_RANGE));
        }

        @Test
        @DisplayName("スケジュール作成_periodType未指定_デフォルトWEEKLY")
        void スケジュール作成_periodType未指定_デフォルトWEEKLY() {
            // Given
            CreateShiftScheduleRequest req = new CreateShiftScheduleRequest(
                    "デフォルト期間タイプ", null,
                    LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 7),
                    null, null);
            ShiftScheduleEntity savedEntity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toScheduleResponse(savedEntity)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.createSchedule(TEAM_ID, req, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }
    }

    // ========================================
    // updateSchedule
    // ========================================

    @Nested
    @DisplayName("updateSchedule")
    class UpdateSchedule {

        @Test
        @DisplayName("スケジュール更新_正常_更新後レスポンス返却")
        void スケジュール更新_正常_更新後レスポンス返却() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(entity);
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.updateSchedule(SCHEDULE_ID, req);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール更新_存在しない_BusinessException")
        void スケジュール更新_存在しない_BusinessException() {
            // Given
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スケジュール更新_不正な日付範囲_BusinessException")
        void スケジュール更新_不正な日付範囲_BusinessException() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    null, null, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1),
                    null, null, null);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.updateSchedule(SCHEDULE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_DATE_RANGE));
        }
    }

    // ========================================
    // ToBuilderUpdateRegression (行重複INSERT防止回帰テスト)
    // ========================================

    @Nested
    @DisplayName("ToBuilderUpdateRegression_ShiftSchedule")
    class ToBuilderUpdateRegressionShiftSchedule {

        /**
         * id 採番済みの existing entity を生成する。
         *
         * <p>{@link com.mannschaft.app.common.BaseEntity#id} は setter を持たないため
         * {@link ReflectionTestUtils} で採番済み状態を再現する（DB から findById で取得した
         * managed entity を模す）。
         */
        private ShiftScheduleEntity existingScheduleWithId() {
            ShiftScheduleEntity entity = createScheduleEntity();
            ReflectionTestUtils.setField(entity, "id", SCHEDULE_ID);
            return entity;
        }

        @Test
        @DisplayName("updateSchedule_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave")
        void updateSchedule_既存エンティティをUPDATE_id不変かつ同一インスタンスをsave() {
            // Given: findById で取得した id 採番済みの managed entity
            ShiftScheduleEntity existing = existingScheduleWithId();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    "更新後タイトル", null, null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(existing));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            shiftScheduleService.updateSchedule(SCHEDULE_ID, req);

            // Then: save に渡るのは findById で取得した「まさにその」managed entity
            // （toBuilder().build() で作り直した別インスタンスではない）。
            // id が保持されているので save は UPDATE になり、新規 INSERT（id=null）は起きない。
            ArgumentCaptor<ShiftScheduleEntity> captor = ArgumentCaptor.forClass(ShiftScheduleEntity.class);
            verify(scheduleRepository).save(captor.capture());
            ShiftScheduleEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);         // 同一インスタンス（新規作成でない）
            assertThat(saved.getId()).isEqualTo(SCHEDULE_ID); // id 欠落（INSERT 化）が起きていない
            // 部分更新が managed entity に反映されている
            assertThat(saved.getTitle()).isEqualTo("更新後タイトル");
            // 未指定フィールドは現値維持
            assertThat(saved.getPeriodType()).isEqualTo(ShiftPeriodType.WEEKLY);
            assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        @DisplayName("updateSchedule_periodType更新_enumが正しくセットされる")
        void updateSchedule_periodType更新_enumが正しくセットされる() {
            // Given
            ShiftScheduleEntity existing = existingScheduleWithId();
            UpdateShiftScheduleRequest req = new UpdateShiftScheduleRequest(
                    null, "MONTHLY", null, null, null, null, null);
            ShiftScheduleResponse response = createScheduleResponse();

            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(existing));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(shiftMapper.toScheduleResponse(any(ShiftScheduleEntity.class))).willReturn(response);

            // When
            shiftScheduleService.updateSchedule(SCHEDULE_ID, req);

            // Then: periodType が enum に解決されて managed entity に反映
            ArgumentCaptor<ShiftScheduleEntity> captor = ArgumentCaptor.forClass(ShiftScheduleEntity.class);
            verify(scheduleRepository).save(captor.capture());
            ShiftScheduleEntity saved = captor.getValue();
            assertThat(saved).isSameAs(existing);
            assertThat(saved.getId()).isEqualTo(SCHEDULE_ID);
            assertThat(saved.getPeriodType()).isEqualTo(ShiftPeriodType.MONTHLY);
        }
    }

    // ========================================
    // deleteSchedule
    // ========================================

    @Nested
    @DisplayName("deleteSchedule")
    class DeleteSchedule {

        @Test
        @DisplayName("スケジュール論理削除_正常_softDeleteが呼ばれる")
        void スケジュール論理削除_正常_softDeleteが呼ばれる() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.saveAndFlush(entity)).willReturn(entity);

            // When
            shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID);

            // Then
            org.mockito.InOrder deletionOrder = org.mockito.Mockito.inOrder(
                    assignmentRepository, requestRepository, slotRepository, scheduleRepository);
            deletionOrder.verify(scheduleRepository).findByIdForUpdate(SCHEDULE_ID);
            deletionOrder.verify(scheduleRepository).saveAndFlush(entity);
            deletionOrder.verify(assignmentRepository).softDeleteByScheduleId(SCHEDULE_ID);
            deletionOrder.verify(requestRepository).softDeleteByScheduleId(SCHEDULE_ID);
            deletionOrder.verify(slotRepository).softDeleteByScheduleId(SCHEDULE_ID);
        }

        @Test
        @DisplayName("子の更新失敗時は後続の削除と予算取消イベントを実行しない")
        void 子の更新失敗時は後続の削除と予算取消イベントを実行しない() {
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(assignmentRepository.softDeleteByScheduleId(SCHEDULE_ID))
                    .willThrow(new IllegalStateException("子の更新失敗"));

            assertThatThrownBy(() -> shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(IllegalStateException.class);

            verify(scheduleRepository).saveAndFlush(entity);
            verify(requestRepository, never()).softDeleteByScheduleId(any());
            verify(slotRepository, never()).softDeleteByScheduleId(any());
            org.mockito.Mockito.verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("スケジュール論理削除_存在しない_BusinessException")
        void スケジュール論理削除_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findByIdForUpdate(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.deleteSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // transitionStatus
    // ========================================

    @Nested
    @DisplayName("transitionStatus")
    class TransitionStatus {

        @Test
        @DisplayName("ステータス遷移_COLLECTING_正常")
        void ステータス遷移_COLLECTING_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "COLLECTING", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.COLLECTING);
            verify(scheduleRepository).save(entity);
        }

        @Test
        @DisplayName("ステータス遷移_ADJUSTING_正常")
        void ステータス遷移_ADJUSTING_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "ADJUSTING", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.ADJUSTING);
        }

        @Test
        @DisplayName("ステータス遷移_PUBLISHED_正常_publishedAt設定")
        void ステータス遷移_PUBLISHED_正常_publishedAt設定() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "PUBLISHED", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.PUBLISHED);
            assertThat(entity.getPublishedAt()).isNotNull();
            assertThat(entity.getPublishedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("ステータス遷移_ARCHIVED_正常")
        void ステータス遷移_ARCHIVED_正常() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(entity)).willReturn(entity);
            given(shiftMapper.toScheduleResponse(entity)).willReturn(response);

            // When
            shiftScheduleService.transitionStatus(SCHEDULE_ID, "ARCHIVED", USER_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(ShiftScheduleStatus.ARCHIVED);
        }

        @Test
        @DisplayName("ステータス遷移_DRAFT_BusinessException")
        void ステータス遷移_DRAFT_BusinessException() {
            // Given
            ShiftScheduleEntity entity = createScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.transitionStatus(SCHEDULE_ID, "DRAFT", USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(ShiftErrorCode.INVALID_SCHEDULE_STATUS));
        }
    }

    // ========================================
    // duplicateSchedule
    // ========================================

    @Nested
    @DisplayName("duplicateSchedule")
    class DuplicateSchedule {

        @Test
        @DisplayName("スケジュール複製_正常_DRAFTで新規作成")
        void スケジュール複製_正常_DRAFTで新規作成() {
            // Given
            ShiftScheduleEntity source = createScheduleEntity();
            ShiftScheduleEntity duplicate = createScheduleEntity();
            ShiftScheduleResponse response = createScheduleResponse();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(source));
            given(scheduleRepository.save(any(ShiftScheduleEntity.class))).willReturn(duplicate);
            given(shiftMapper.toScheduleResponse(duplicate)).willReturn(response);

            // When
            ShiftScheduleResponse result = shiftScheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(scheduleRepository).save(any(ShiftScheduleEntity.class));
        }

        @Test
        @DisplayName("スケジュール複製_存在しない_BusinessException")
        void スケジュール複製_存在しない_BusinessException() {
            // Given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> shiftScheduleService.duplicateSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // getScheduleSummary (Phase 11 第二陣 2-α)
    // ========================================

    @Nested
    @DisplayName("getScheduleSummary")
    class GetScheduleSummary {

        @Test
        @DisplayName("スケジュール存在_日付別ポジション別の充足状況を返す")
        void 正常_日付別ポジション別サマリ返却() {
            // Given: スケジュール + 2 日分 × ホール/キッチン 2 ポジションのスロット
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));

            // ポジション
            ShiftPositionEntity posHall = ShiftPositionEntity.builder().teamId(TEAM_ID).name("ホール").build();
            ReflectionTestUtils.setField(posHall, "id", 1L);
            ShiftPositionEntity posKitchen = ShiftPositionEntity.builder().teamId(TEAM_ID).name("キッチン").build();
            ReflectionTestUtils.setField(posKitchen, "id", 2L);
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of(posHall, posKitchen));

            // スロット
            ShiftSlotEntity s1 = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID).slotDate(LocalDate.of(2026, 3, 1))
                    .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(17, 0))
                    .positionId(1L).requiredCount(3)
                    // CMP-260908-2117 AC-3: 充足数は割当の正本（assigned_user_ids）から数える。
                    // 手動割当はこの列にしか書かれないため、旧実装（shift_assignments の
                    // CONFIRMED 件数）では手動で埋めた枠が「未充足」に見えていた。
                    .assignedUserIds("[50]").build();
            ReflectionTestUtils.setField(s1, "id", 1001L);
            ShiftSlotEntity s2 = ShiftSlotEntity.builder()
                    .scheduleId(SCHEDULE_ID).slotDate(LocalDate.of(2026, 3, 1))
                    .startTime(java.time.LocalTime.of(9, 0)).endTime(java.time.LocalTime.of(17, 0))
                    .positionId(2L).requiredCount(2).build();
            ReflectionTestUtils.setField(s2, "id", 1002L);
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of(s1, s2));

            // 希望（slot_date 単位の延べ件数 3 件）
            ShiftRequestEntity r1 = ShiftRequestEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(50L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.PREFERRED).build();
            ShiftRequestEntity r2 = ShiftRequestEntity.builder()
                    .scheduleId(SCHEDULE_ID).userId(51L).slotDate(LocalDate.of(2026, 3, 1))
                    .preference(ShiftPreference.AVAILABLE).build();
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of(r1, r2));

            // When
            ShiftScheduleSummaryResponse response =
                    shiftScheduleService.getScheduleSummary(SCHEDULE_ID);

            // Then
            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getSummaryByDate()).hasSize(1);
            ShiftScheduleSummaryResponse.DateSummary day = response.getSummaryByDate().get(0);
            assertThat(day.getDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(day.getTotalRequired()).isEqualTo(5);
            assertThat(day.getTotalConfirmed()).isEqualTo(1);
            assertThat(day.getTotalRequested()).isEqualTo(2);
            assertThat(day.getByPosition()).hasSize(2);
            assertThat(day.getByPosition()).extracting("positionName")
                    .containsExactly("ホール", "キッチン");
            assertThat(day.getByPosition().get(0).getConfirmed()).isEqualTo(1);
            assertThat(day.getByPosition().get(0).getRequired()).isEqualTo(3);
            assertThat(day.getByPosition().get(1).getConfirmed()).isZero();
            assertThat(day.getByPosition().get(1).getRequired()).isEqualTo(2);
        }

        @Test
        @DisplayName("スケジュール非存在_BusinessException")
        void 非存在_BusinessException() {
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> shiftScheduleService.getScheduleSummary(SCHEDULE_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("スロット 0 件_空リストを返す")
        void スロット0件_空リスト() {
            ShiftScheduleEntity schedule = createScheduleEntity();
            ReflectionTestUtils.setField(schedule, "id", SCHEDULE_ID);
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule));
            given(slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(requestRepository.findByScheduleIdOrderBySlotDateAsc(SCHEDULE_ID))
                    .willReturn(List.of());
            given(positionRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID))
                    .willReturn(List.of());

            ShiftScheduleSummaryResponse response =
                    shiftScheduleService.getScheduleSummary(SCHEDULE_ID);

            assertThat(response.getScheduleId()).isEqualTo(SCHEDULE_ID);
            assertThat(response.getSummaryByDate()).isEmpty();
        }

        // ========================================
        // per-scope 認可（Track2 第二陣 / 2026-05-29）
        // ========================================
    }
}
