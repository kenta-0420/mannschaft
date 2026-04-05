package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.AddRecipientsRequest;
import com.mannschaft.app.circulation.dto.AttachmentResponse;
import com.mannschaft.app.circulation.dto.CreateAttachmentRequest;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.entity.CirculationAttachmentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link CirculationService} の追加単体テスト。未テストメソッドをカバーする。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationService 追加単体テスト")
class CirculationServiceAdditionalTest {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private CirculationAttachmentRepository attachmentRepository;

    @Mock
    private CirculationMapper circulationMapper;

    @InjectMocks
    private CirculationService service;

    private static final Long DOCUMENT_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long RECIPIENT_ID = 50L;
    private static final Long ATTACHMENT_ID = 60L;
    private static final String SCOPE_TYPE = "TEAM";

    private CirculationDocumentEntity createDraft() {
        return CirculationDocumentEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                .title("回覧テスト").body("本文").build();
    }

    private DocumentResponse mockDocResponse() {
        return new DocumentResponse(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID, USER_ID,
                "回覧テスト", "本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                null, false, (short) 24, "STANDARD", 0, 0, null, 0, 0, null, null);
    }

    // ========================================
    // listDocuments
    // ========================================

    @Nested
    @DisplayName("listDocuments")
    class ListDocuments {

        @Test
        @DisplayName("正常系: ステータスフィルタなしで文書一覧が返却される")
        void 文書一覧_ステータスフィルタなし_正常() {
            CirculationDocumentEntity entity = createDraft();
            Page<CirculationDocumentEntity> page = new PageImpl<>(List.of(entity));
            given(documentRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), any())).willReturn(page);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockDocResponse());

            Page<DocumentResponse> result = service.listDocuments(
                    SCOPE_TYPE, SCOPE_ID, null, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: ステータスフィルタありで文書一覧が返却される")
        void 文書一覧_ステータスフィルタあり_正常() {
            CirculationDocumentEntity entity = createDraft();
            Page<CirculationDocumentEntity> page = new PageImpl<>(List.of(entity));
            given(documentRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), eq(CirculationStatus.DRAFT), any())).willReturn(page);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockDocResponse());

            Page<DocumentResponse> result = service.listDocuments(
                    SCOPE_TYPE, SCOPE_ID, "DRAFT", PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // createDocument
    // ========================================

    @Nested
    @DisplayName("createDocument")
    class CreateDocument {

        @Test
        @DisplayName("正常系: 文書が作成される")
        void 文書作成_正常() {
            CreateDocumentRequest request = new CreateDocumentRequest(
                    "新文書", "本文", null, null, null, null, null, null,
                    List.of(new RecipientEntry(50L, null)));
            CirculationDocumentEntity saved = createDraft();
            given(documentRepository.save(any())).willReturn(saved);
            given(recipientRepository.existsByDocumentIdAndUserId(any(), eq(50L))).willReturn(false);
            given(circulationMapper.toDocumentResponse(saved)).willReturn(mockDocResponse());

            DocumentResponse result = service.createDocument(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            assertThat(result).isNotNull();
            verify(recipientRepository).save(any(CirculationRecipientEntity.class));
        }

        @Test
        @DisplayName("異常系: 重複受信者でDUPLICATE_RECIPIENT例外")
        void 文書作成_重複受信者_例外() {
            CreateDocumentRequest request = new CreateDocumentRequest(
                    "新文書", "本文", null, null, null, null, null, null,
                    List.of(new RecipientEntry(50L, null)));
            CirculationDocumentEntity saved = createDraft();
            given(documentRepository.save(any())).willReturn(saved);
            given(recipientRepository.existsByDocumentIdAndUserId(any(), eq(50L))).willReturn(true);

            assertThatThrownBy(() -> service.createDocument(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DUPLICATE_RECIPIENT));
        }
    }

    // ========================================
    // listRecipients
    // ========================================

    @Nested
    @DisplayName("listRecipients")
    class ListRecipients {

        @Test
        @DisplayName("正常系: 受信者一覧が返却される")
        void 受信者一覧_正常() {
            CirculationRecipientEntity entity = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).sortOrder(0).build();
            RecipientResponse response = new RecipientResponse(RECIPIENT_ID, DOCUMENT_ID, USER_ID,
                    0, "PENDING", null, null, null, null, null, null, null);
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(List.of(entity));
            given(circulationMapper.toRecipientResponseList(any())).willReturn(List.of(response));

            List<RecipientResponse> result = service.listRecipients(DOCUMENT_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // addRecipients
    // ========================================

    @Nested
    @DisplayName("addRecipients")
    class AddRecipients {

        @Test
        @DisplayName("正常系: 受信者が追加される")
        void 受信者追加_正常() {
            CirculationDocumentEntity document = createDraft();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            given(recipientRepository.existsByDocumentIdAndUserId(any(), eq(55L))).willReturn(false);
            given(recipientRepository.countByDocumentId(any())).willReturn(1L);
            given(documentRepository.save(document)).willReturn(document);
            CirculationRecipientEntity recipientEntity = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(55L).sortOrder(0).build();
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(any()))
                    .willReturn(List.of(recipientEntity));
            given(circulationMapper.toRecipientResponseList(any())).willReturn(List.of());

            AddRecipientsRequest request = new AddRecipientsRequest(
                    List.of(new RecipientEntry(55L, 0)));
            service.addRecipients(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, request);

            verify(recipientRepository).save(any(CirculationRecipientEntity.class));
        }
    }

    // ========================================
    // removeRecipient
    // ========================================

    @Nested
    @DisplayName("removeRecipient")
    class RemoveRecipient {

        @Test
        @DisplayName("正常系: 受信者が削除される")
        void 受信者削除_正常() {
            CirculationDocumentEntity document = createDraft();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).sortOrder(0).build();
            given(recipientRepository.findById(RECIPIENT_ID))
                    .willReturn(Optional.of(recipient));
            given(recipientRepository.countByDocumentId(any())).willReturn(0L);
            given(documentRepository.save(document)).willReturn(document);

            service.removeRecipient(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, RECIPIENT_ID);

            verify(recipientRepository).delete(recipient);
        }

        @Test
        @DisplayName("異常系: 受信者不在でRECIPIENT_NOT_FOUND例外")
        void 受信者削除_不在_例外() {
            CirculationDocumentEntity document = createDraft();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            given(recipientRepository.findById(RECIPIENT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeRecipient(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, RECIPIENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.RECIPIENT_NOT_FOUND));
        }
    }

    // ========================================
    // listAttachments
    // ========================================

    @Nested
    @DisplayName("listAttachments")
    class ListAttachments {

        @Test
        @DisplayName("正常系: 添付ファイル一覧が返却される")
        void 添付ファイル一覧_正常() {
            CirculationAttachmentEntity entity = CirculationAttachmentEntity.builder()
                    .documentId(DOCUMENT_ID).fileKey("k").originalFilename("f.pdf")
                    .fileSize(100L).mimeType("application/pdf").build();
            AttachmentResponse response = new AttachmentResponse(ATTACHMENT_ID, DOCUMENT_ID,
                    "k", "f.pdf", 100L, "application/pdf", null);
            given(attachmentRepository.findByDocumentIdOrderByCreatedAtAsc(DOCUMENT_ID))
                    .willReturn(List.of(entity));
            given(circulationMapper.toAttachmentResponseList(any())).willReturn(List.of(response));

            List<AttachmentResponse> result = service.listAttachments(DOCUMENT_ID);

            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // addAttachment
    // ========================================

    @Nested
    @DisplayName("addAttachment")
    class AddAttachment {

        @Test
        @DisplayName("正常系: 添付ファイルが追加される")
        void 添付ファイル追加_正常() {
            CirculationDocumentEntity document = createDraft();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            CirculationAttachmentEntity saved = CirculationAttachmentEntity.builder()
                    .documentId(DOCUMENT_ID).fileKey("uploads/f.pdf").originalFilename("f.pdf")
                    .fileSize(2048L).mimeType("application/pdf").build();
            given(attachmentRepository.save(any())).willReturn(saved);
            given(documentRepository.save(document)).willReturn(document);
            AttachmentResponse response = new AttachmentResponse(ATTACHMENT_ID, DOCUMENT_ID,
                    "uploads/f.pdf", "f.pdf", 2048L, "application/pdf", null);
            given(circulationMapper.toAttachmentResponse(saved)).willReturn(response);

            CreateAttachmentRequest request = new CreateAttachmentRequest(
                    "uploads/f.pdf", "f.pdf", 2048L, "application/pdf");
            AttachmentResponse result = service.addAttachment(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, request);

            assertThat(result.getOriginalFilename()).isEqualTo("f.pdf");
            assertThat(document.getAttachmentCount()).isEqualTo(1);
        }
    }

    // ========================================
    // removeAttachment
    // ========================================

    @Nested
    @DisplayName("removeAttachment")
    class RemoveAttachment {

        @Test
        @DisplayName("正常系: 添付ファイルが削除される")
        void 添付ファイル削除_正常() {
            CirculationDocumentEntity document = createDraft();
            document.incrementAttachmentCount();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            CirculationAttachmentEntity attachment = CirculationAttachmentEntity.builder()
                    .documentId(DOCUMENT_ID).fileKey("k").originalFilename("f.pdf")
                    .fileSize(100L).mimeType("application/pdf").build();
            given(attachmentRepository.findByIdAndDocumentId(ATTACHMENT_ID, DOCUMENT_ID))
                    .willReturn(Optional.of(attachment));
            given(documentRepository.save(document)).willReturn(document);

            service.removeAttachment(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, ATTACHMENT_ID);

            verify(attachmentRepository).delete(attachment);
            assertThat(document.getAttachmentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("異常系: 添付ファイル不在でATTACHMENT_NOT_FOUND例外")
        void 添付ファイル削除_不在_例外() {
            CirculationDocumentEntity document = createDraft();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(document));
            given(attachmentRepository.findByIdAndDocumentId(ATTACHMENT_ID, DOCUMENT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.removeAttachment(
                    SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, ATTACHMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.ATTACHMENT_NOT_FOUND));
        }
    }

    // ========================================
    // listCreatedDocuments
    // ========================================

    @Nested
    @DisplayName("listCreatedDocuments")
    class ListCreatedDocuments {

        @Test
        @DisplayName("正常系: 自分が作成した文書一覧が返却される")
        void 作成文書一覧_正常() {
            CirculationDocumentEntity entity = createDraft();
            Page<CirculationDocumentEntity> page = new PageImpl<>(List.of(entity));
            given(documentRepository.findByCreatedByOrderByCreatedAtDesc(eq(USER_ID), any()))
                    .willReturn(page);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockDocResponse());

            Page<DocumentResponse> result = service.listCreatedDocuments(USER_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // activateDocument - SEQUENTIAL mode
    // ========================================

    @Nested
    @DisplayName("activateDocument SEQUENTIAL")
    class ActivateDocumentSequential {

        @Test
        @DisplayName("正常系: SEQUENTIALモードで公開するとsequentialCountが設定される")
        void SEQUENTIAL_公開_sequentialCount設定() {
            CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                    .title("文書").body("本文")
                    .circulationMode(CirculationMode.SEQUENTIAL)
                    .build();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(recipientRepository.countByDocumentId(DOCUMENT_ID)).willReturn(3L);
            CirculationDocumentEntity saved = CirculationDocumentEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                    .title("文書").body("本文")
                    .circulationMode(CirculationMode.SEQUENTIAL)
                    .sequentialCount(3)
                    .build();
            given(documentRepository.save(any())).willReturn(saved);
            given(circulationMapper.toDocumentResponse(any())).willReturn(mockDocResponse());

            service.activateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            verify(documentRepository).save(any());
        }
    }
}
