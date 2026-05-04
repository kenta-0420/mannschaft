package com.mannschaft.app.timetable.notes;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.timetable.notes.dto.AttachmentConfirmRequest;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignRequest;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteAttachmentService;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteService;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 3 メモ添付ファイルサービスのユニットテスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimetableSlotUserNoteAttachmentService ユニットテスト")
class TimetableSlotUserNoteAttachmentServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long NOTE_ID = 7L;

    @Mock private TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    @Mock private TimetableSlotUserNoteService noteService;
    @Mock private R2StorageService r2StorageService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private TimetableSlotUserNoteAttachmentService service;

    private TimetableSlotUserNoteEntity note;

    @BeforeEach
    void setUp() {
        note = TimetableSlotUserNoteEntity.builder()
                .userId(USER_ID).slotKind(TimetableSlotKind.PERSONAL).slotId(11L).build();
    }

    @Nested
    @DisplayName("presign")
    class Presign {

        @Test
        @DisplayName("正常系: 署名URL発行")
        void 正常系_発行() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            given(attachmentRepository.countByNoteIdAndDeletedAtIsNull(any())).willReturn(0L);
            given(attachmentRepository.sumSizeBytesByUser(USER_ID)).willReturn(0L);
            given(r2StorageService.generateUploadUrl(anyString(), eq("image/jpeg"), any(Duration.class)))
                    .willReturn(new PresignedUploadResult("https://r2.example/up", "key", 300L));

            var req = new AttachmentPresignRequest("photo.jpg", "image/jpeg", 1_000L);
            var resp = service.presign(NOTE_ID, USER_ID, req);
            assertThat(resp.uploadUrl()).startsWith("https://r2.example");
            assertThat(resp.r2ObjectKey()).startsWith("user/" + USER_ID + "/timetable-notes/");
            assertThat(resp.r2ObjectKey()).endsWith(".jpg");
        }

        @Test
        @DisplayName("異常系: サイズ 5MB 超で 422")
        void 異常系_サイズ超過() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            var req = new AttachmentPresignRequest("big.jpg", "image/jpeg", 6L * 1024 * 1024);
            assertThatThrownBy(() -> service.presign(NOTE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.ATTACHMENT_SIZE_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: 未許可 MIME（svg+xml）で 422")
        void 異常系_未許可MIME() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            var req = new AttachmentPresignRequest("a.svg", "image/svg+xml", 1L);
            assertThatThrownBy(() -> service.presign(NOTE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.ATTACHMENT_UNSUPPORTED_TYPE);
        }

        @Test
        @DisplayName("異常系: 上限5件到達で 409")
        void 異常系_件数上限() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            given(attachmentRepository.countByNoteIdAndDeletedAtIsNull(any())).willReturn(5L);
            var req = new AttachmentPresignRequest("x.png", "image/png", 100L);
            assertThatThrownBy(() -> service.presign(NOTE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.ATTACHMENT_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("異常系: クォータ100MB超過で 429")
        void 異常系_クォータ超過() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            given(attachmentRepository.countByNoteIdAndDeletedAtIsNull(any())).willReturn(0L);
            given(attachmentRepository.sumSizeBytesByUser(USER_ID)).willReturn(99L * 1024 * 1024);
            var req = new AttachmentPresignRequest("x.png", "image/png", 2L * 1024 * 1024);
            assertThatThrownBy(() -> service.presign(NOTE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            PersonalTimetableErrorCode.ATTACHMENT_QUOTA_EXCEEDED);
        }
    }

    @Nested
    @DisplayName("confirm")
    class Confirm {

        @Test
        @DisplayName("異常系: r2_object_key が他ユーザーの prefix で 404")
        void 異常系_keyプレフィックス偽装() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            var req = new AttachmentConfirmRequest("user/999/timetable-notes/uuid.jpg");
            var orig = new AttachmentPresignRequest("a.jpg", "image/jpeg", 100L);
            assertThatThrownBy(() -> service.confirm(NOTE_ID, USER_ID, req, orig))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("正常系: 冪等性 — 既存と同じ key の confirm は重複 INSERT しない")
        void 冪等性() {
            given(noteService.getMine(NOTE_ID, USER_ID)).willReturn(note);
            String key = "user/" + USER_ID + "/timetable-notes/uuid.jpg";
            TimetableSlotUserNoteAttachmentEntity existing = TimetableSlotUserNoteAttachmentEntity.builder()
                    .noteId(NOTE_ID).userId(USER_ID).r2ObjectKey(key)
                    .originalFilename("a.jpg").mimeType("image/jpeg").sizeBytes(100L).build();
            given(attachmentRepository.findByR2ObjectKey(key)).willReturn(Optional.of(existing));

            var req = new AttachmentConfirmRequest(key);
            var orig = new AttachmentPresignRequest("a.jpg", "image/jpeg", 100L);
            var saved = service.confirm(NOTE_ID, USER_ID, req, orig);
            assertThat(saved).isSameAs(existing);
            verify(attachmentRepository, never()).save(any(TimetableSlotUserNoteAttachmentEntity.class));
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: 自分の添付を論理削除")
        void 正常系_論理削除() {
            TimetableSlotUserNoteAttachmentEntity entity = TimetableSlotUserNoteAttachmentEntity.builder()
                    .noteId(NOTE_ID).userId(USER_ID).r2ObjectKey("user/100/timetable-notes/x.jpg")
                    .originalFilename("x").mimeType("image/jpeg").sizeBytes(1L).build();
            given(attachmentRepository.findByIdAndUserId(50L, USER_ID))
                    .willReturn(Optional.of(entity));
            service.delete(50L, USER_ID);
            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }
}
