package com.mannschaft.app.timetable.notes;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.notes.dto.CreateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.dto.UpdateTimetableSlotUserNoteFieldRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteFieldEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteFieldRepository;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteFieldService;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 3 カスタムメモ項目サービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableSlotUserNoteFieldService ユニットテスト")
class TimetableSlotUserNoteFieldServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long FIELD_ID = 11L;

    @Mock private TimetableSlotUserNoteFieldRepository repository;
    @InjectMocks private TimetableSlotUserNoteFieldService service;

    private TimetableSlotUserNoteFieldEntity field() {
        return TimetableSlotUserNoteFieldEntity.builder()
                .userId(USER_ID).label("演習問題").placeholder("内容").sortOrder(0).maxLength(2000)
                .build();
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: 新規作成（max_length 既定 2000）")
        void 正常系_新規作成() {
            given(repository.countByUserId(USER_ID)).willReturn(0L);
            given(repository.existsByUserIdAndLabel(USER_ID, "演習問題")).willReturn(false);
            given(repository.save(any(TimetableSlotUserNoteFieldEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            var req = new CreateTimetableSlotUserNoteFieldRequest("演習問題", "プレースホルダ", 1, 2000);
            var saved = service.create(USER_ID, req);
            assertThat(saved.getLabel()).isEqualTo("演習問題");
            assertThat(saved.getMaxLength()).isEqualTo(2000);
        }

        @Test
        @DisplayName("異常系: 上限到達（10件超）で 409")
        void 異常系_上限到達() {
            given(repository.countByUserId(USER_ID)).willReturn(10L);
            var req = new CreateTimetableSlotUserNoteFieldRequest("X", null, 0, 2000);
            assertThatThrownBy(() -> service.create(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_FIELD_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: ラベル重複で 409")
        void 異常系_ラベル重複() {
            given(repository.countByUserId(USER_ID)).willReturn(2L);
            given(repository.existsByUserIdAndLabel(USER_ID, "演習")).willReturn(true);
            var req = new CreateTimetableSlotUserNoteFieldRequest("演習", null, 0, 2000);
            assertThatThrownBy(() -> service.create(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_FIELD_LABEL_DUPLICATED);
        }

        @Test
        @DisplayName("異常系: 不正な max_length（999）で 422")
        void 異常系_不正なmaxLength() {
            given(repository.countByUserId(USER_ID)).willReturn(0L);
            given(repository.existsByUserIdAndLabel(USER_ID, "X")).willReturn(false);
            var req = new CreateTimetableSlotUserNoteFieldRequest("X", null, 0, 999);
            assertThatThrownBy(() -> service.create(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.NOTE_FIELD_INVALID_MAX_LENGTH);
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: ラベル変更")
        void 正常系_ラベル変更() {
            given(repository.findByIdAndUserId(FIELD_ID, USER_ID))
                    .willReturn(Optional.of(field()));
            given(repository.existsByUserIdAndLabelAndIdNot(USER_ID, "新ラベル", FIELD_ID))
                    .willReturn(false);
            given(repository.save(any(TimetableSlotUserNoteFieldEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            var req = new UpdateTimetableSlotUserNoteFieldRequest("新ラベル", null, null, null);
            var updated = service.update(FIELD_ID, USER_ID, req);
            assertThat(updated.getLabel()).isEqualTo("新ラベル");
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 削除（既存メモの値はサーバ側残置）")
        void 正常系_削除() {
            var entity = field();
            given(repository.findByIdAndUserId(FIELD_ID, USER_ID)).willReturn(Optional.of(entity));
            service.delete(FIELD_ID, USER_ID);
            verify(repository).delete(entity);
        }
    }
}
