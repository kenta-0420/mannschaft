package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.CirculationMode;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatsResponse;
import com.mannschaft.app.circulation.dto.UpdateDocumentRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.event.CirculationDocumentDeletedEvent;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link CirculationService} の単体テスト。
 * 文書CRUD・受信者管理・添付ファイル管理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationService 単体テスト")
class CirculationServiceTest {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private CirculationAttachmentRepository attachmentRepository;

    @Mock
    private CirculationMapper circulationMapper;

    /** F13 Phase 5-a: presignAttachmentUpload メソッド追加に伴い @Mock 追加（他テストへの影響なし）。 */
    @Mock
    private R2StorageService r2StorageService;

    /** F09.14 Phase 4-C: deleteDocument 時のイベント発行検証用。 */
    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private CirculationService circulationService;

    private static final Long DOCUMENT_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final String SCOPE_TYPE = "TEAM";

    private CirculationDocumentEntity createDraftDocument() {
        return CirculationDocumentEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                .title("回覧テスト").body("回覧本文").build();
    }

    private CirculationDocumentEntity createActiveDocument() {
        CirculationDocumentEntity entity = createDraftDocument();
        entity.activate();
        entity.updateRecipientCount(3);
        return entity;
    }

    private DocumentResponse createDocumentResponse() {
        return new DocumentResponse(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID, USER_ID,
                "回覧テスト", "回覧本文", "SIMULTANEOUS", 0, "DRAFT", "NORMAL",
                null, false, (short) 24, "STANDARD", 0, 0, null, 0, 0,
                null, null);
    }

    @Nested
    @DisplayName("getDocument")
    class GetDocument {

        @Test
        @DisplayName("文書詳細取得_正常_レスポンス返却")
        void 文書詳細取得_正常_レスポンス返却() {
            // Given
            CirculationDocumentEntity entity = createDraftDocument();
            DocumentResponse response = createDocumentResponse();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(circulationMapper.toDocumentResponse(entity)).willReturn(response);

            // When
            DocumentResponse result = circulationService.getDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("回覧テスト");
        }

        @Test
        @DisplayName("文書詳細取得_存在しない_BusinessException")
        void 文書詳細取得_存在しない_BusinessException() {
            // Given
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> circulationService.getDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updateDocument")
    class UpdateDocument {

        @Test
        @DisplayName("文書更新_DRAFT状態_正常に更新")
        void 文書更新_DRAFT状態_正常に更新() {
            // Given
            UpdateDocumentRequest request = new UpdateDocumentRequest("更新タイトル", null, null, null, null, null, null);

            CirculationDocumentEntity entity = createDraftDocument();
            DocumentResponse response = createDocumentResponse();

            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(documentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(response);

            // When
            DocumentResponse result = circulationService.updateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("文書更新_ACTIVE状態_BusinessException")
        void 文書更新_ACTIVE状態_BusinessException() {
            // Given
            UpdateDocumentRequest request = new UpdateDocumentRequest("更新タイトル", null, null, null, null, null, null);

            CirculationDocumentEntity entity = createActiveDocument();

            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> circulationService.updateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_DOCUMENT_STATUS));
        }
    }

    @Nested
    @DisplayName("activateDocument")
    class ActivateDocument {

        @Test
        @DisplayName("文書公開_正常_ACTIVE状態に遷移")
        void 文書公開_正常_ACTIVE状態に遷移() {
            // Given
            CirculationDocumentEntity entity = createDraftDocument();
            DocumentResponse response = createDocumentResponse();

            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(recipientRepository.countByDocumentId(DOCUMENT_ID)).willReturn(3L);
            given(documentRepository.save(any(CirculationDocumentEntity.class))).willReturn(entity);
            given(circulationMapper.toDocumentResponse(any(CirculationDocumentEntity.class))).willReturn(response);

            // When
            circulationService.activateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.ACTIVE);
        }

        @Test
        @DisplayName("文書公開_受信者なし_BusinessException")
        void 文書公開_受信者なし_BusinessException() {
            // Given
            CirculationDocumentEntity entity = createDraftDocument();

            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(recipientRepository.countByDocumentId(DOCUMENT_ID)).willReturn(0L);

            // When & Then
            assertThatThrownBy(() -> circulationService.activateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.EMPTY_RECIPIENTS));
        }
    }

    @Nested
    @DisplayName("cancelDocument")
    class CancelDocument {

        @Test
        @DisplayName("文書キャンセル_正常_CANCELLED状態に遷移")
        void 文書キャンセル_正常_CANCELLED状態に遷移() {
            // Given
            CirculationDocumentEntity entity = createActiveDocument();
            DocumentResponse response = createDocumentResponse();

            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(documentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(response);

            // When
            circulationService.cancelDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then
            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.CANCELLED);
        }
    }

    @Nested
    @DisplayName("deleteDocument")
    class DeleteDocument {

        @Test
        @DisplayName("文書削除_正常_論理削除実行")
        void 文書削除_正常_論理削除実行() {
            // Given
            CirculationDocumentEntity entity = createDraftDocument();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            circulationService.deleteDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(documentRepository).save(entity);
        }

        @Test
        @DisplayName("文書削除_正常_CirculationDocumentDeletedEvent発行")
        void 文書削除_正常_イベント発行() {
            // Given
            CirculationDocumentEntity entity = createDraftDocument();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When
            circulationService.deleteDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then: F09.14 Phase 4-C — DisclosureCirculationCleanupHandler 等の購読側のために
            // CirculationDocumentDeletedEvent(documentId) が発行されること
            ArgumentCaptor<CirculationDocumentDeletedEvent> captor =
                    ArgumentCaptor.forClass(CirculationDocumentDeletedEvent.class);
            verify(applicationEventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().documentId()).isEqualTo(DOCUMENT_ID);
        }
    }

    @Nested
    @DisplayName("getStats")
    class GetStats {

        @Test
        @DisplayName("統計取得_正常_カウント集計")
        void 統計取得_正常_カウント集計() {
            // Given
            given(documentRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, CirculationStatus.DRAFT)).willReturn(2L);
            given(documentRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, CirculationStatus.ACTIVE)).willReturn(3L);
            given(documentRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, CirculationStatus.COMPLETED)).willReturn(5L);
            given(documentRepository.countByScopeTypeAndScopeIdAndStatus(SCOPE_TYPE, SCOPE_ID, CirculationStatus.CANCELLED)).willReturn(1L);

            // When
            DocumentStatsResponse result = circulationService.getStats(SCOPE_TYPE, SCOPE_ID);

            // Then
            assertThat(result.getTotal()).isEqualTo(11L);
            assertThat(result.getDraft()).isEqualTo(2L);
            assertThat(result.getActive()).isEqualTo(3L);
        }
    }

    @Nested
    @DisplayName("activateDocument - toBuilder 廃止・id 保持回帰テスト")
    class ActivateDocumentToBuilderRegression {

        @Test
        @DisplayName("activateDocument_SEQUENTIAL → save に渡るのが findById の同一インスタンスかつ id 保持")
        void activateDocument_sequential_savesOriginalInstanceWithIdPreserved() throws Exception {
            // Given: SEQUENTIAL モードの DRAFT 文書（id をリフレクションでセット）
            CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                    .title("回覧テスト").body("回覧本文")
                    .circulationMode(CirculationMode.SEQUENTIAL)
                    .build();
            java.lang.reflect.Field idField = findField(entity.getClass(), "id");
            idField.setAccessible(true);
            idField.set(entity, DOCUMENT_ID);

            DocumentResponse response = createDocumentResponse();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(recipientRepository.countByDocumentId(DOCUMENT_ID)).willReturn(3L);
            given(documentRepository.save(any())).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(response);

            // When
            circulationService.activateDocument(SCOPE_TYPE, SCOPE_ID, DOCUMENT_ID);

            // Then: save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
            ArgumentCaptor<CirculationDocumentEntity> captor =
                    ArgumentCaptor.forClass(CirculationDocumentEntity.class);
            verify(documentRepository).save(captor.capture());
            assertThat(captor.getValue()).isSameAs(entity);
            assertThat(captor.getValue().getId()).isEqualTo(DOCUMENT_ID);
            assertThat(captor.getValue().getStatus()).isEqualTo(CirculationStatus.ACTIVE);
            assertThat(captor.getValue().getSequentialCount()).isEqualTo(3);
        }

        private java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
            while (clazz != null) {
                try {
                    return clazz.getDeclaredField(name);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new NoSuchFieldException(name);
        }
    }
}
