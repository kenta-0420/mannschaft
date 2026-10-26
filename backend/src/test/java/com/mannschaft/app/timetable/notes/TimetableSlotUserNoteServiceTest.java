package com.mannschaft.app.timetable.notes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.notes.dto.UpsertTimetableSlotUserNoteRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteFieldRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteRepository;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteService;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * F03.15 Phase 3 個人メモサービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableSlotUserNoteService ユニットテスト")
class TimetableSlotUserNoteServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long PERSONAL_TIMETABLE_ID = 5L;
    private static final Long PERSONAL_SLOT_ID = 11L;

    @Mock private TimetableSlotUserNoteRepository noteRepository;
    @Mock private TimetableSlotUserNoteFieldRepository fieldRepository;
    @Mock private PersonalTimetableSlotRepository personalSlotRepository;
    @Mock private PersonalTimetableRepository personalTimetableRepository;
    @Mock private TimetableSlotRepository teamSlotRepository;
    @Mock private TimetableRepository teamTimetableRepository;
    @Mock private UserRoleRepository userRoleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private TimetableSlotUserNoteService service;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new TimetableSlotUserNoteService(
                noteRepository, fieldRepository,
                personalSlotRepository, personalTimetableRepository,
                teamSlotRepository, teamTimetableRepository,
                userRoleRepository, objectMapper);
    }

    private void mockPersonalSlotOwned() {
        PersonalTimetableSlotEntity slot = PersonalTimetableSlotEntity.builder()
                .personalTimetableId(PERSONAL_TIMETABLE_ID)
                .dayOfWeek("MON").periodNumber(1)
                .subjectName("数学").autoSyncChanges(true).build();
        given(personalSlotRepository.findById(PERSONAL_SLOT_ID)).willReturn(Optional.of(slot));
        PersonalTimetableEntity tt = PersonalTimetableEntity.builder()
                .userId(USER_ID).name("私の時間割").build();
        given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                PERSONAL_TIMETABLE_ID, USER_ID)).willReturn(Optional.of(tt));
    }

    @Nested
    @DisplayName("upsert")
    class Upsert {

        @Test
        @DisplayName("正常系: 新規作成（PERSONAL）")
        void 正常系_新規作成() {
            mockPersonalSlotOwned();
            given(noteRepository.findByUserIdAndSlotKindAndSlotIdAndTargetDateIsNull(
                    USER_ID, TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID))
                    .willReturn(Optional.empty());
            given(noteRepository.save(any(TimetableSlotUserNoteEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            var req = new UpsertTimetableSlotUserNoteRequest(
                    TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID, null,
                    "予習する", null, "電卓", "メモ自由欄", null);
            var saved = service.upsert(USER_ID, req, null);
            assertThat(saved.getPreparation()).isEqualTo("予習する");
            assertThat(saved.getItemsToBring()).isEqualTo("電卓");
        }

        @Test
        @DisplayName("異常系: free_memo が10000字超で 422（コンテンツ検証で先行拒否）")
        void 異常系_free_memo文字数超過() {
            String big = "a".repeat(10_001);
            var req = new UpsertTimetableSlotUserNoteRequest(
                    TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID, null,
                    null, null, null, big, null);
            assertThatThrownBy(() -> service.upsert(USER_ID, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_FIELD_TOO_LONG);
        }

        @Test
        @DisplayName("異常系: <script> 含む Markdown は 422（コンテンツ検証で先行拒否）")
        void 異常系_XSS含有() {
            var req = new UpsertTimetableSlotUserNoteRequest(
                    TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID, null,
                    "<script>alert(1)</script>", null, null, null, null);
            assertThatThrownBy(() -> service.upsert(USER_ID, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_UNSAFE_MARKDOWN);
        }

        @Test
        @DisplayName("異常系: javascript: スキーム含有で 422（コンテンツ検証で先行拒否）")
        void 異常系_javascriptスキーム() {
            var req = new UpsertTimetableSlotUserNoteRequest(
                    TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID, null,
                    null, null, null, "[click](javascript:alert(1))", null);
            assertThatThrownBy(() -> service.upsert(USER_ID, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_UNSAFE_MARKDOWN);
        }

        @Test
        @DisplayName("異常系: PERSONAL の所有者ではないと 404")
        void 異常系_所有者外() {
            PersonalTimetableSlotEntity slot = PersonalTimetableSlotEntity.builder()
                    .personalTimetableId(PERSONAL_TIMETABLE_ID).dayOfWeek("MON").periodNumber(1)
                    .subjectName("数学").autoSyncChanges(true).build();
            given(personalSlotRepository.findById(PERSONAL_SLOT_ID)).willReturn(Optional.of(slot));
            given(personalTimetableRepository.findByIdAndUserIdAndDeletedAtIsNull(
                    PERSONAL_TIMETABLE_ID, USER_ID)).willReturn(Optional.empty());
            var req = new UpsertTimetableSlotUserNoteRequest(
                    TimetableSlotKind.PERSONAL, PERSONAL_SLOT_ID, null,
                    "X", null, null, null, null);
            assertThatThrownBy(() -> service.upsert(USER_ID, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_SLOT_NOT_OWNED);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 自分のメモを削除")
        void 正常系_削除() {
            TimetableSlotUserNoteEntity entity = TimetableSlotUserNoteEntity.builder()
                    .userId(USER_ID).slotKind(TimetableSlotKind.PERSONAL).slotId(PERSONAL_SLOT_ID)
                    .build();
            given(noteRepository.findByIdAndUserId(eq(7L), eq(USER_ID)))
                    .willReturn(Optional.of(entity));
            given(noteRepository.save(entity)).willReturn(entity);
            service.delete(7L, USER_ID);
            assertThat(entity.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系: 他人のメモを削除しようとすると 404")
        void 異常系_他人メモ() {
            given(noteRepository.findByIdAndUserId(eq(7L), eq(USER_ID)))
                    .willReturn(Optional.empty());
            assertThatThrownBy(() -> service.delete(7L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("findUpcoming")
    class Upcoming {

        @Test
        @DisplayName("異常系: from > to で 400")
        void 異常系_from_to逆転() {
            assertThatThrownBy(() -> service.findUpcoming(USER_ID,
                    java.time.LocalDate.of(2026, 5, 10),
                    java.time.LocalDate.of(2026, 5, 1)))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
