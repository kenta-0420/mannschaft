package com.mannschaft.app.moderation.service;

import com.mannschaft.app.moderation.ModerationExtMapper;
import com.mannschaft.app.moderation.dto.InternalNoteResponse;
import com.mannschaft.app.moderation.entity.ReportInternalNoteEntity;
import com.mannschaft.app.moderation.repository.ReportInternalNoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ReportInternalNoteService} の単体テスト。
 * 内部メモ追加・一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReportInternalNoteService 単体テスト")
class ReportInternalNoteServiceTest {

    @Mock
    private ReportInternalNoteRepository noteRepository;

    @Mock
    private ModerationExtMapper mapper;

    @InjectMocks
    private ReportInternalNoteService reportInternalNoteService;

    private static final Long REPORT_ID = 1L;
    private static final Long AUTHOR_ID = 100L;

    // ========================================
    // addNote
    // ========================================
    @Nested
    @DisplayName("addNote")
    class AddNote {

        @Test
        @DisplayName("正常系: 内部メモを追加できる")
        void 内部メモを追加できる() {
            // given
            ReportInternalNoteEntity saved = ReportInternalNoteEntity.builder()
                    .reportId(REPORT_ID).authorId(AUTHOR_ID).note("テストメモ").build();
            InternalNoteResponse expected = new InternalNoteResponse(1L, REPORT_ID, AUTHOR_ID,
                    "テストメモ", LocalDateTime.now());

            given(noteRepository.save(any(ReportInternalNoteEntity.class))).willReturn(saved);
            given(mapper.toInternalNoteResponse(any(ReportInternalNoteEntity.class))).willReturn(expected);

            // when
            InternalNoteResponse result = reportInternalNoteService.addNote(REPORT_ID, AUTHOR_ID, "テストメモ");

            // then
            assertThat(result).isEqualTo(expected);
            verify(noteRepository).save(any(ReportInternalNoteEntity.class));
        }
    }

    // ========================================
    // getNotes
    // ========================================
    @Nested
    @DisplayName("getNotes")
    class GetNotes {

        @Test
        @DisplayName("正常系: 内部メモ一覧を取得できる")
        void 内部メモ一覧を取得できる() {
            // given
            List<ReportInternalNoteEntity> entities = List.of(
                    ReportInternalNoteEntity.builder()
                            .reportId(REPORT_ID).authorId(AUTHOR_ID).note("メモ1").build());
            List<InternalNoteResponse> expected = List.of(
                    new InternalNoteResponse(1L, REPORT_ID, AUTHOR_ID, "メモ1", LocalDateTime.now()));

            given(noteRepository.findByReportIdOrderByCreatedAtAsc(REPORT_ID)).willReturn(entities);
            given(mapper.toInternalNoteResponseList(entities)).willReturn(expected);

            // when
            List<InternalNoteResponse> result = reportInternalNoteService.getNotes(REPORT_ID);

            // then
            assertThat(result).hasSize(1);
        }
    }
}
