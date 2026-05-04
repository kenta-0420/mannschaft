package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService.CreateData;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService.DuplicateData;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService.UpdateData;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 1 個人時間割サービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalTimetableService ユニットテスト")
class PersonalTimetableServiceTest {

    @Mock private PersonalTimetableRepository repository;
    @Mock private PersonalTimetablePeriodRepository periodRepository;
    @Mock private PersonalTimetableSlotRepository slotRepository;
    @Mock private com.mannschaft.app.timetable.personal.service.PersonalTimetablePeriodService periodService;
    @InjectMocks private PersonalTimetableService service;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long TIMETABLE_ID = 1L;

    private PersonalTimetableEntity draftEntity;
    private PersonalTimetableEntity activeEntity;
    private PersonalTimetableEntity archivedEntity;

    @BeforeEach
    void setUp() {
        draftEntity = PersonalTimetableEntity.builder()
                .userId(USER_ID)
                .name("テスト時間割")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.DRAFT)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        activeEntity = draftEntity.toBuilder().status(PersonalTimetableStatus.ACTIVE).build();
        archivedEntity = draftEntity.toBuilder().status(PersonalTimetableStatus.ARCHIVED).build();
    }

    // ============================================================
    // create
    // ============================================================
    @Nested
    @DisplayName("create")
    class Create {

        private CreateData baseCreateData() {
            return new CreateData(
                    "新時間割",
                    2026,
                    "前期",
                    LocalDate.of(2026, 4, 1),
                    LocalDate.of(2026, 9, 30),
                    PersonalTimetableVisibility.PRIVATE,
                    false,
                    null,
                    "学園生活のスタート",
                    null);
        }

        @Test
        @DisplayName("正常系: 個人時間割が DRAFT で作成される")
        void 作成_正常_DRAFTで保存される() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(0L);
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            PersonalTimetableEntity result = service.create(USER_ID, baseCreateData());

            assertThat(result.getStatus()).isEqualTo(PersonalTimetableStatus.DRAFT);
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getVisibility()).isEqualTo(PersonalTimetableVisibility.PRIVATE);
        }

        @Test
        @DisplayName("異常系: 上限到達で 409（PERSONAL_TIMETABLE_010）")
        void 作成_上限到達_例外() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(5L);

            assertThatThrownBy(() -> service.create(USER_ID, baseCreateData()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_010"));
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("異常系: effective_from > effective_until で 422")
        void 作成_期間逆転_例外() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(0L);

            CreateData bad = new CreateData("X", null, null,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1),
                    null, false, null, null, null);

            assertThatThrownBy(() -> service.create(USER_ID, bad))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_030"));
        }

        @Test
        @DisplayName("異常系: weekPatternEnabled=true なのに baseDate 未指定で 400")
        void 作成_週パターン基準日欠落_例外() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(0L);

            CreateData bad = new CreateData("X", null, null,
                    LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30),
                    null, true, null, null, null);

            assertThatThrownBy(() -> service.create(USER_ID, bad))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_031"));
        }

        @Test
        @DisplayName("異常系: weekPatternBaseDate が effective 範囲外で 400")
        void 作成_週パターン基準日_範囲外_例外() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(0L);

            CreateData bad = new CreateData("X", null, null,
                    LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30),
                    null, true, LocalDate.of(2026, 3, 1), null, null);

            assertThatThrownBy(() -> service.create(USER_ID, bad))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_032"));
        }

        @Test
        @DisplayName("正常系: visibility 未指定なら PRIVATE がデフォルト")
        void 作成_visibility未指定_PRIVATE() {
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(0L);
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            CreateData data = new CreateData("X", null, null,
                    LocalDate.of(2026, 4, 1), null,
                    null, false, null, null, null);

            PersonalTimetableEntity result = service.create(USER_ID, data);
            assertThat(result.getVisibility()).isEqualTo(PersonalTimetableVisibility.PRIVATE);
        }
    }

    // ============================================================
    // getMine（IDOR 対策）
    // ============================================================
    @Nested
    @DisplayName("getMine — 認可・IDOR")
    class GetMine {

        @Test
        @DisplayName("正常系: 自分の時間割を取得")
        void 自分の時間割_取得() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));

            PersonalTimetableEntity result = service.getMine(TIMETABLE_ID, USER_ID);
            assertThat(result).isSameAs(draftEntity);
        }

        @Test
        @DisplayName("異常系: 他人の時間割は 404 として扱う（IDOR 対策）")
        void 他人のID_見つからずに404() {
            // 他人のリソースは「自分のリソースではない」ため repository も空を返す
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, OTHER_USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMine(TIMETABLE_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_001"));
        }

        @Test
        @DisplayName("異常系: 存在しない ID も 404")
        void 不在ID_404() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(anyLong(), eq(USER_ID)))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getMine(99999L, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ============================================================
    // activate（期間重複の自動アーカイブ）
    // ============================================================
    @Nested
    @DisplayName("activate — ステータス遷移と期間重複の自動アーカイブ")
    class Activate {

        @Test
        @DisplayName("正常系: DRAFT→ACTIVE。重複なしならアーカイブ呼び出しなし")
        void 有効化_重複なし_自身のみACTIVE() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.findOverlappingActive(eq(USER_ID), any(), any(), any()))
                    .willReturn(List.of());
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            PersonalTimetableEntity result = service.activate(TIMETABLE_ID, USER_ID);
            assertThat(result.getStatus()).isEqualTo(PersonalTimetableStatus.ACTIVE);
            // 自身のみ save される（自動アーカイブ対象が空）
            verify(repository, times(1)).save(any(PersonalTimetableEntity.class));
        }

        @Test
        @DisplayName("正常系: 期間重複の既存 ACTIVE は自動 ARCHIVED 化される")
        void 有効化_重複ACTIVE_自動アーカイブ() {
            PersonalTimetableEntity overlapping = activeEntity.toBuilder()
                    .name("既存ACTIVE")
                    .build();
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.findOverlappingActive(eq(USER_ID), any(), any(), any()))
                    .willReturn(List.of(overlapping));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.activate(TIMETABLE_ID, USER_ID);

            // 既存 ACTIVE が ARCHIVED に変わって save、自身も save → 計 2 回
            assertThat(overlapping.getStatus()).isEqualTo(PersonalTimetableStatus.ARCHIVED);
            verify(repository, times(2)).save(any(PersonalTimetableEntity.class));
        }

        @Test
        @DisplayName("異常系: ACTIVE を activate するのは不正遷移（409）")
        void 有効化_既にACTIVE_例外() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(activeEntity));

            assertThatThrownBy(() -> service.activate(TIMETABLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_020"));
        }

        @Test
        @DisplayName("異常系: ARCHIVED を activate するのは不正遷移（409）")
        void 有効化_ARCHIVED_例外() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(archivedEntity));

            assertThatThrownBy(() -> service.activate(TIMETABLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ============================================================
    // archive / revertToDraft
    // ============================================================
    @Nested
    @DisplayName("archive / revertToDraft")
    class TransitionOthers {

        @Test
        @DisplayName("正常系: ACTIVE → ARCHIVED")
        void アーカイブ_正常() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(activeEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            PersonalTimetableEntity result = service.archive(TIMETABLE_ID, USER_ID);
            assertThat(result.getStatus()).isEqualTo(PersonalTimetableStatus.ARCHIVED);
        }

        @Test
        @DisplayName("異常系: DRAFT を archive するのは不正遷移（409）")
        void アーカイブ_DRAFT_例外() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));

            assertThatThrownBy(() -> service.archive(TIMETABLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_021"));
        }

        @Test
        @DisplayName("正常系: ARCHIVED → DRAFT")
        void 下書きに戻す_正常() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(archivedEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            PersonalTimetableEntity result = service.revertToDraft(TIMETABLE_ID, USER_ID);
            assertThat(result.getStatus()).isEqualTo(PersonalTimetableStatus.DRAFT);
        }

        @Test
        @DisplayName("異常系: ACTIVE を revertToDraft するのは不正遷移（409）")
        void 下書きに戻す_ACTIVE_例外() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(activeEntity));

            assertThatThrownBy(() -> service.revertToDraft(TIMETABLE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_022"));
        }
    }

    // ============================================================
    // delete / update / duplicate
    // ============================================================
    @Nested
    @DisplayName("delete / update / duplicate")
    class Mutations {

        @Test
        @DisplayName("delete 正常系: deleted_at がセットされる（論理削除）")
        void 削除_論理削除() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.delete(TIMETABLE_ID, USER_ID);
            assertThat(draftEntity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("update 正常系: name と notes が反映される")
        void 更新_正常() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            UpdateData data = new UpdateData(
                    "改名後", null, null, null, null, null, null, null, "更新メモ");
            PersonalTimetableEntity result = service.update(TIMETABLE_ID, USER_ID, data);

            assertThat(result.getName()).isEqualTo("改名後");
            assertThat(result.getNotes()).isEqualTo("更新メモ");
        }

        @Test
        @DisplayName("update 監査ログ: visibility 変更時に visibility_changed が記録される (Phase 5b)")
        void 更新_visibility変更_監査ログ記録() {
            AuditLogService auditLogService = org.mockito.Mockito.mock(AuditLogService.class);
            ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            UpdateData data = new UpdateData(
                    null, null, null, null, null,
                    PersonalTimetableVisibility.FAMILY_SHARED,
                    null, null, null);
            service.update(TIMETABLE_ID, USER_ID, data);

            verify(auditLogService, times(1)).record(
                    eq("personal_timetable.visibility_changed"),
                    eq(USER_ID), org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.contains("\"after\":\"FAMILY_SHARED\""));
        }

        @Test
        @DisplayName("update 監査ログ: visibility 変化なし時は記録されない (Phase 5b)")
        void 更新_visibility変化なし_監査ログ未記録() {
            AuditLogService auditLogService = org.mockito.Mockito.mock(AuditLogService.class);
            ReflectionTestUtils.setField(service, "auditLogService", auditLogService);

            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(draftEntity));
            given(repository.save(any(PersonalTimetableEntity.class))).willAnswer(inv -> inv.getArgument(0));

            UpdateData data = new UpdateData(
                    null, null, null, null, null,
                    PersonalTimetableVisibility.PRIVATE,
                    null, null, null);
            service.update(TIMETABLE_ID, USER_ID, data);

            verify(auditLogService, never()).record(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("duplicate 正常系: DRAFT として複製される。コピー名は接尾辞付き")
        void 複製_正常_DRAFT() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(activeEntity));
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(1L);
            given(repository.save(any(PersonalTimetableEntity.class)))
                    .willAnswer(inv -> {
                        PersonalTimetableEntity arg = inv.getArgument(0);
                        // saved entity 模擬: id を埋めない代わりにそのまま返す
                        return arg;
                    });
            given(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(any()))
                    .willReturn(List.of());
            given(slotRepository.findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(any()))
                    .willReturn(List.of());

            PersonalTimetableEntity result = service.duplicate(
                    TIMETABLE_ID, USER_ID,
                    new DuplicateData(null, null, null, null, null));

            assertThat(result.getStatus()).isEqualTo(PersonalTimetableStatus.DRAFT);
            assertThat(result.getName()).contains("(コピー)");
        }

        @Test
        @DisplayName("duplicate 異常系: 上限到達で 409")
        void 複製_上限到達_例外() {
            given(repository.findByIdAndUserIdAndDeletedAtIsNull(TIMETABLE_ID, USER_ID))
                    .willReturn(Optional.of(activeEntity));
            given(repository.countByUserIdAndDeletedAtIsNull(USER_ID)).willReturn(5L);

            assertThatThrownBy(() -> service.duplicate(
                    TIMETABLE_ID, USER_ID, new DuplicateData(null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PERSONAL_TIMETABLE_010"));
        }
    }
}
