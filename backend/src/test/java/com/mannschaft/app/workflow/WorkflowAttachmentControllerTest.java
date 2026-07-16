package com.mannschaft.app.workflow;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.workflow.controller.WorkflowCommentController;
import com.mannschaft.app.workflow.controller.WorkflowRequestMyController;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentRegisterRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.dto.WorkflowRequestResponse;
import com.mannschaft.app.workflow.service.WorkflowCommentService;
import com.mannschaft.app.workflow.service.WorkflowRequestAttachmentService;
import com.mannschaft.app.workflow.service.WorkflowRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F05.6 Phase 11 第二陣 2-γ で追加した 4 エンドポイントの Controller 単体テスト。
 *
 * <ul>
 *   <li>{@code POST /api/v1/workflow-requests/{id}/upload-url}</li>
 *   <li>{@code POST /api/v1/workflow-requests/{id}/attachments}</li>
 *   <li>{@code DELETE /api/v1/workflow-requests/{id}/attachments/{attachmentId}}</li>
 *   <li>{@code GET /api/v1/workflow-requests/me}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowAttachmentController + WorkflowRequestMyController 単体テスト (Phase 11 第二陣 2-γ)")
class WorkflowAttachmentControllerTest {

    @Mock
    private WorkflowCommentService commentService;

    @Mock
    private WorkflowRequestAttachmentService attachmentService;

    @Mock
    private WorkflowRequestService requestService;

    private WorkflowCommentController commentController;
    private WorkflowRequestMyController myController;

    private static final Long USER_ID = 10L;
    private static final Long REQUEST_ID = 200L;
    private static final Long ATTACHMENT_ID = 500L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
        commentController = new WorkflowCommentController(commentService, attachmentService);
        myController = new WorkflowRequestMyController(requestService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("POST /upload-url")
    class PresignUpload {

        @Test
        @DisplayName("Pre-signed URL 発行_200 OK_uploadUrl 返却")
        void presignUpload_OK() {
            // Given
            WorkflowAttachmentPresignRequest req = new WorkflowAttachmentPresignRequest(
                    "application/pdf", 1024L);
            WorkflowAttachmentPresignResponse response = new WorkflowAttachmentPresignResponse(
                    "https://r2.example.com/upload?sig=xyz",
                    "workflow-attachments/200/uuid.pdf", 900L);
            given(attachmentService.presignUpload(eq(REQUEST_ID), eq(USER_ID), any()))
                    .willReturn(response);

            // When
            ResponseEntity<ApiResponse<WorkflowAttachmentPresignResponse>> result =
                    commentController.presignUpload(REQUEST_ID, req);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody().getData().uploadUrl())
                    .isEqualTo("https://r2.example.com/upload?sig=xyz");
            verify(attachmentService).presignUpload(eq(REQUEST_ID), eq(USER_ID), any());
        }
    }

    @Nested
    @DisplayName("POST /attachments")
    class RegisterAttachment {

        @Test
        @DisplayName("添付登録_201 Created_レスポンス返却")
        void registerAttachment_Created() {
            // Given
            WorkflowAttachmentRegisterRequest req = new WorkflowAttachmentRegisterRequest(
                    "workflow-attachments/200/uuid.pdf", "領収書.pdf", 2048L);
            WorkflowAttachmentResponse response = new WorkflowAttachmentResponse(
                    ATTACHMENT_ID, REQUEST_ID,
                    "workflow-attachments/200/uuid.pdf", "領収書.pdf", 2048L, USER_ID, null);
            given(attachmentService.registerAttachment(eq(REQUEST_ID), eq(USER_ID), any()))
                    .willReturn(response);

            // When
            ResponseEntity<ApiResponse<WorkflowAttachmentResponse>> result =
                    commentController.registerAttachment(REQUEST_ID, req);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody().getData().getOriginalFilename()).isEqualTo("領収書.pdf");
        }
    }

    @Nested
    @DisplayName("DELETE /attachments/{attachmentId}")
    class DeleteAttachment {

        @Test
        @DisplayName("添付削除_204 No Content")
        void deleteAttachment_NoContent() {
            // When
            ResponseEntity<Void> result =
                    commentController.deleteAttachment(REQUEST_ID, ATTACHMENT_ID);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(attachmentService).deleteAttachment(REQUEST_ID, ATTACHMENT_ID, USER_ID);
        }
    }

    @Nested
    @DisplayName("GET /workflow-requests/me")
    class ListMyRequests {

        @Test
        @DisplayName("自分の申請一覧_200 OK_ページング返却")
        void listMyRequests_OK() {
            // Given
            WorkflowRequestResponse response = new WorkflowRequestResponse(REQUEST_ID, 1L,
                    "TEAM", 1L, "休暇申請", "PENDING", USER_ID, null, null,
                    null, null, null, null, null, null, List.of());
            Page<WorkflowRequestResponse> page = new PageImpl<>(List.of(response));
            given(requestService.listMyRequests(eq(USER_ID), eq("PENDING"), any()))
                    .willReturn(page);

            // When
            ResponseEntity<?> result = myController.listMyRequests("PENDING", 0, 20);

            // Then
            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(requestService).listMyRequests(eq(USER_ID), eq("PENDING"), any());
        }
    }
}
