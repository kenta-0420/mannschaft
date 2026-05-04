package com.mannschaft.app.timetable.notes;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.notes.controller.TimetableSlotUserNoteAttachmentController;
import com.mannschaft.app.timetable.notes.dto.AttachmentPresignResponse;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteAttachmentEntity;
import com.mannschaft.app.timetable.notes.service.TimetableSlotUserNoteAttachmentService;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TimetableSlotUserNoteAttachmentController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("TimetableSlotUserNoteAttachmentController 結合テスト")
class TimetableSlotUserNoteAttachmentControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long NOTE_ID = 7L;
    private static final Long ATTACH_ID = 50L;

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TimetableSlotUserNoteAttachmentService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    private TimetableSlotUserNoteAttachmentEntity sample() {
        TimetableSlotUserNoteAttachmentEntity e = TimetableSlotUserNoteAttachmentEntity.builder()
                .noteId(NOTE_ID).userId(USER_ID)
                .r2ObjectKey("user/100/timetable-notes/uuid.jpg")
                .originalFilename("a.jpg").mimeType("image/jpeg").sizeBytes(100L)
                .build();
        try {
            Field idField = e.getClass().getDeclaredField("id");
            idField.setAccessible(true); idField.set(e, ATTACH_ID);
            Field createdAtField = e.getClass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true); createdAtField.set(e, LocalDateTime.now());
        } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex); }
        return e;
    }

    @Test
    @DisplayName("POST presign 正常系: 200")
    void presign_200() throws Exception {
        given(service.presign(eq(NOTE_ID), eq(USER_ID), any()))
                .willReturn(new AttachmentPresignResponse(
                        "https://r2.example/up", "user/100/timetable-notes/abc.jpg", 300L));
        String body = """
                {"file_name":"photo.jpg","content_type":"image/jpeg","size_bytes":1024}
                """;
        mockMvc.perform(post("/api/v1/me/timetable-slot-notes/{id}/attachments/presign", NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.upload_url").value("https://r2.example/up"));
    }

    @Test
    @DisplayName("POST presign 異常系: クォータ超過で 429")
    void presign_429() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.ATTACHMENT_QUOTA_EXCEEDED))
                .given(service).presign(eq(NOTE_ID), eq(USER_ID), any());
        String body = """
                {"file_name":"x.png","content_type":"image/png","size_bytes":1024}
                """;
        mockMvc.perform(post("/api/v1/me/timetable-slot-notes/{id}/attachments/presign", NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("PERSONAL_TIMETABLE_084"));
    }

    @Test
    @DisplayName("POST confirm 正常系: 201")
    void confirm_201() throws Exception {
        given(service.confirm(eq(NOTE_ID), eq(USER_ID), any(), any())).willReturn(sample());
        String body = """
                {"r2_object_key":"user/100/timetable-notes/uuid.jpg",
                 "file_name":"a.jpg","content_type":"image/jpeg","size_bytes":100}
                """;
        mockMvc.perform(post("/api/v1/me/timetable-slot-notes/{id}/attachments/confirm", NOTE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(ATTACH_ID));
    }

    @Test
    @DisplayName("GET download-url 正常系: 200")
    void downloadUrl_200() throws Exception {
        given(service.generateDownloadUrl(eq(ATTACH_ID), eq(USER_ID)))
                .willReturn("https://r2.example/dl");
        mockMvc.perform(get("/api/v1/me/timetable-slot-notes/attachments/{id}/download-url", ATTACH_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.download_url").value("https://r2.example/dl"));
    }

    @Test
    @DisplayName("DELETE 正常系: 204")
    void delete_204() throws Exception {
        mockMvc.perform(delete("/api/v1/me/timetable-slot-notes/attachments/{id}", ATTACH_ID))
                .andExpect(status().isNoContent());
    }
}
