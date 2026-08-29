package com.mannschaft.app.workflow;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentPresignResponse;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentRegisterRequest;
import com.mannschaft.app.workflow.dto.WorkflowAttachmentResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestAttachmentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestAttachmentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.service.WorkflowRequestAttachmentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link WorkflowRequestAttachmentService} の単体テスト。
 *
 * <p>F05.6 Phase 11 第二陣 2-γ で追加した Pre-signed URL 発行・添付登録・添付削除の挙動を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRequestAttachmentService 単体テスト")
class WorkflowRequestAttachmentServiceTest {

    @Mock
    private WorkflowRequestAttachmentRepository attachmentRepository;

    @Mock
    private WorkflowRequestRepository requestRepository;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private StorageAclService storageAclService;

    @InjectMocks
    private WorkflowRequestAttachmentService attachmentService;

    private static final Long REQUEST_ID = 200L;
    private static final Long ATTACHMENT_ID = 500L;
    private static final Long USER_ID = 10L;

    private WorkflowRequestEntity requestEntity;

    @BeforeEach
    void setUp() {
        requestEntity = WorkflowRequestEntity.builder()
                .templateId(1L).scopeType("TEAM").scopeId(1L).title("テスト申請")
                .requestedBy(USER_ID).build();
        ReflectionTestUtils.setField(requestEntity, "id", REQUEST_ID);
    }

    @Nested
    @DisplayName("presignUpload")
    class PresignUpload {

        @Test
        @DisplayName("Pre-signed URL 発行_正常_uploadUrl と fileKey が返る")
        void Pre_signed_URL発行_正常_uploadUrlとfileKeyが返る() {
            // Given
            WorkflowAttachmentPresignRequest req = new WorkflowAttachmentPresignRequest(
                    "application/pdf", 1024L);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));
            given(r2StorageService.generateUploadUrl(anyString(), eq("application/pdf"), any(Duration.class)))
                    .willAnswer(invocation -> new PresignedUploadResult(
                            "https://r2.example.com/upload?sig=xyz",
                            invocation.getArgument(0),
                            900L));

            // When
            WorkflowAttachmentPresignResponse result =
                    attachmentService.presignUpload(REQUEST_ID, USER_ID, req);

            // Then
            assertThat(result.uploadUrl()).isEqualTo("https://r2.example.com/upload?sig=xyz");
            assertThat(result.fileKey()).startsWith("workflow-attachments/" + REQUEST_ID + "/");
            assertThat(result.fileKey()).endsWith(".pdf");
            assertThat(result.expiresInSeconds()).isEqualTo(900L);
        }

        @Test
        @DisplayName("Pre-signed URL 発行_申請が存在しない_BusinessException")
        void Pre_signed_URL発行_申請が存在しない_BusinessException() {
            // Given
            WorkflowAttachmentPresignRequest req = new WorkflowAttachmentPresignRequest(
                    "application/pdf", 1024L);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> attachmentService.presignUpload(REQUEST_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.REQUEST_NOT_FOUND));
        }

        @Test
        @DisplayName("Pre-signed URL 発行_許可外contentType_BusinessException")
        void Pre_signed_URL発行_許可外contentType_BusinessException() {
            // Given
            WorkflowAttachmentPresignRequest req = new WorkflowAttachmentPresignRequest(
                    "application/x-msdownload", 1024L);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));

            // When & Then
            assertThatThrownBy(() -> attachmentService.presignUpload(REQUEST_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_FIELD_VALUE));
            verify(r2StorageService, never()).generateUploadUrl(anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("registerAttachment")
    class RegisterAttachment {

        @Test
        @DisplayName("添付登録_正常_レスポンス返却")
        void 添付登録_正常_レスポンス返却() {
            // Given
            String fileKey = "workflow-attachments/" + REQUEST_ID + "/abc.pdf";
            WorkflowAttachmentRegisterRequest req = new WorkflowAttachmentRegisterRequest(
                    fileKey, "領収書.pdf", 2048L);
            WorkflowRequestAttachmentEntity saved = WorkflowRequestAttachmentEntity.builder()
                    .requestId(REQUEST_ID).fileKey(fileKey).originalFilename("領収書.pdf")
                    .fileSize(2048L).uploadedBy(USER_ID).build();
            WorkflowAttachmentResponse response = new WorkflowAttachmentResponse(
                    ATTACHMENT_ID, REQUEST_ID, fileKey, "領収書.pdf", 2048L, USER_ID, null);

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));
            given(attachmentRepository.save(any(WorkflowRequestAttachmentEntity.class))).willReturn(saved);
            given(workflowMapper.toAttachmentResponse(saved)).willReturn(response);

            // When
            WorkflowAttachmentResponse result =
                    attachmentService.registerAttachment(REQUEST_ID, USER_ID, req);

            // Then
            assertThat(result.getFileKey()).isEqualTo(fileKey);
            assertThat(result.getOriginalFilename()).isEqualTo("領収書.pdf");
        }

        @Test
        @DisplayName("添付登録_fileKey prefix 不一致_BusinessException")
        void 添付登録_fileKey_prefix不一致_BusinessException() {
            // Given
            WorkflowAttachmentRegisterRequest req = new WorkflowAttachmentRegisterRequest(
                    "workflow-attachments/999/abc.pdf", "領収書.pdf", 2048L);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));

            // When & Then
            assertThatThrownBy(() -> attachmentService.registerAttachment(REQUEST_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.INVALID_FIELD_VALUE));
            verify(attachmentRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteAttachment")
    class DeleteAttachment {

        @Test
        @DisplayName("添付削除_正常_R2削除とDB削除が実行される")
        void 添付削除_正常_R2削除とDB削除が実行される() {
            // Given
            WorkflowRequestAttachmentEntity entity = WorkflowRequestAttachmentEntity.builder()
                    .requestId(REQUEST_ID)
                    .fileKey("workflow-attachments/" + REQUEST_ID + "/abc.pdf")
                    .originalFilename("a.pdf").fileSize(1L).build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));
            given(attachmentRepository.findByIdAndRequestId(ATTACHMENT_ID, REQUEST_ID))
                    .willReturn(Optional.of(entity));

            // When
            attachmentService.deleteAttachment(REQUEST_ID, ATTACHMENT_ID, USER_ID);

            // Then
            verify(r2StorageService).delete(entity.getFileKey());
            verify(attachmentRepository).delete(entity);
        }

        @Test
        @DisplayName("添付削除_存在しない_BusinessException")
        void 添付削除_存在しない_BusinessException() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestEntity));
            given(attachmentRepository.findByIdAndRequestId(ATTACHMENT_ID, REQUEST_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> attachmentService.deleteAttachment(REQUEST_ID, ATTACHMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.ATTACHMENT_NOT_FOUND));
        }
    }
}
