package com.mannschaft.app.circulation;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.circulation.dto.ExportRequestResponse;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.circulation.dto.ExportStatusResponse;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.repository.CirculationStampCorrectionLogRepository;
import com.mannschaft.app.circulation.service.CirculationExportAsyncExecutor;
import com.mannschaft.app.circulation.service.CirculationExportService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.common.storage.StorageService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F05.2 Phase 11 第四陣 4-C: {@link CirculationExportService} 単体テスト。
 *
 * <p>非同期実行部分は {@link CirculationExportAsyncExecutor} に分離されているため、
 * 本テストでは Mock で置き換え、実行を検証する。
 * Executor 本体の挙動は {@link Async} ネストクラスで別途検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationExportService 単体テスト")
class CirculationExportServiceTest {

    private static final Long DOCUMENT_ID = 100L;
    private static final Long CREATOR_ID = 10L;
    private static final Long RECIPIENT_ID = 20L;
    private static final Long STRANGER_ID = 99L;

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private CirculationExportAsyncExecutor asyncExecutor;

    @Mock
    private DomainEventPublisher eventPublisher;

    @InjectMocks
    private CirculationExportService exportService;

    @Test
    @DisplayName("COMPLETED 文書 + NOT_GENERATED → 202 受付 + PENDING に遷移")
    void requestExport_completedNotGenerated_returnsAccepted() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.NOT_GENERATED);
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Object result = exportService.requestExport(DOCUMENT_ID, CREATOR_ID);

        assertThat(result).isInstanceOf(ExportRequestResponse.class);
        ExportRequestResponse resp = (ExportRequestResponse) result;
        assertThat(resp.documentId()).isEqualTo(DOCUMENT_ID);
        assertThat(resp.status()).isEqualTo("GENERATING");
        assertThat(entity.getExportStatus()).isEqualTo(CirculationExportStatus.PENDING);
        verify(documentRepository, times(1)).save(any());
        verify(asyncExecutor, times(1)).generateAsync(DOCUMENT_ID);
    }

    @Test
    @DisplayName("COMPLETED 文書 + COMPLETED エクスポート → URL 入り ExportStatusResponse を返す（Controller が 302）")
    void requestExport_alreadyCompleted_returnsStatusWithUrl() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.COMPLETED);
        ReflectionTestUtils.setField(entity, "exportFileKey", "circulation/exports/100/key.pdf");
        ReflectionTestUtils.setField(entity, "exportCompletedAt", LocalDateTime.now().minusHours(1));

        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
        given(storageService.generateDownloadUrl(eq("circulation/exports/100/key.pdf"), any(Duration.class)))
                .willReturn("https://r2.example.com/signed-url");

        Object result = exportService.requestExport(DOCUMENT_ID, CREATOR_ID);

        assertThat(result).isInstanceOf(ExportStatusResponse.class);
        ExportStatusResponse resp = (ExportStatusResponse) result;
        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.url()).isEqualTo("https://r2.example.com/signed-url");
        verify(documentRepository, never()).save(any());
    }

    @Test
    @DisplayName("非 COMPLETED 文書は CIRCULATION_021 を投げる")
    void requestExport_nonCompletedDocument_throwsCirculation021() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.ACTIVE,
                CirculationExportStatus.NOT_GENERATED);
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> exportService.requestExport(DOCUMENT_ID, CREATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CirculationErrorCode.EXPORT_NOT_AVAILABLE_NON_COMPLETED.getMessage());
    }

    @Test
    @DisplayName("作成者でも受信者でもないユーザーは認可エラー")
    void requestExport_unauthorizedUser_throws() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.NOT_GENERATED);
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
        given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, STRANGER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> exportService.requestExport(DOCUMENT_ID, STRANGER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("PENDING 中の再要求は二重起動せず GENERATING を返す")
    void requestExport_pendingAlready_doesNotRestart() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.PENDING);
        ReflectionTestUtils.setField(entity, "exportRequestedAt", LocalDateTime.now().minusSeconds(5));
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

        Object result = exportService.requestExport(DOCUMENT_ID, CREATOR_ID);

        assertThat(result).isInstanceOf(ExportRequestResponse.class);
        verify(documentRepository, never()).save(any());
        verify(asyncExecutor, never()).generateAsync(any());
    }

    @Test
    @DisplayName("getExportStatus: NOT_GENERATED は CIRCULATION_022 を投げる")
    void getStatus_notGenerated_throws() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.NOT_GENERATED);
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> exportService.getExportStatus(DOCUMENT_ID, CREATOR_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CirculationErrorCode.EXPORT_NOT_REQUESTED.getMessage());
    }

    @Test
    @DisplayName("getExportStatus: COMPLETED は url 入り ExportStatusResponse を返す")
    void getStatus_completed_returnsUrl() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.COMPLETED);
        ReflectionTestUtils.setField(entity, "exportFileKey", "circulation/exports/100/key.pdf");
        ReflectionTestUtils.setField(entity, "exportCompletedAt", LocalDateTime.now());

        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
        given(storageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://r2.example.com/signed");

        ExportStatusResponse resp = exportService.getExportStatus(DOCUMENT_ID, CREATOR_ID);

        assertThat(resp.status()).isEqualTo("COMPLETED");
        assertThat(resp.url()).isEqualTo("https://r2.example.com/signed");
        assertThat(resp.completedAt()).isNotNull();
    }

    @Test
    @DisplayName("受信者ユーザーはエクスポート要求可能")
    void requestExport_recipientUser_allowed() {
        CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                CirculationExportStatus.NOT_GENERATED);
        CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                .documentId(DOCUMENT_ID)
                .userId(RECIPIENT_ID)
                .sortOrder(0)
                .status(RecipientStatus.STAMPED)
                .build();
        given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
        given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, RECIPIENT_ID))
                .willReturn(Optional.of(recipient));
        given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        Object result = exportService.requestExport(DOCUMENT_ID, RECIPIENT_ID);

        assertThat(result).isInstanceOf(ExportRequestResponse.class);
        assertThat(entity.getExportStatus()).isEqualTo(CirculationExportStatus.PENDING);
        verify(asyncExecutor, times(1)).generateAsync(DOCUMENT_ID);
    }

    // ─────────────────────────────────────────────
    // ヘルパー
    // ─────────────────────────────────────────────

    private CirculationDocumentEntity baseEntity(CirculationStatus status,
                                                 CirculationExportStatus exportStatus) {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .createdBy(CREATOR_ID)
                .title("テスト回覧")
                .body("本文")
                .status(status)
                .exportStatus(exportStatus)
                .build();
        ReflectionTestUtils.setField(entity, "id", DOCUMENT_ID);
        return entity;
    }

    // ─────────────────────────────────────────────
    // CirculationExportAsyncExecutor 単体テスト
    // ─────────────────────────────────────────────

    /**
     * 非同期実行ユニット {@link CirculationExportAsyncExecutor} の単体テスト。
     * Service から分離されたため、本ネストクラスで個別検証する。
     */
    @Nested
    @DisplayName("CirculationExportAsyncExecutor 単体テスト")
    @ExtendWith(MockitoExtension.class)
    class AsyncExecutorTest {

        @Mock
        private CirculationDocumentRepository documentRepository;

        @Mock
        private CirculationRecipientRepository recipientRepository;

        @Mock
        private CirculationStampCorrectionLogRepository correctionLogRepository;

        @Mock
        private PdfGeneratorService pdfGeneratorService;

        @Mock
        private StorageService storageService;

        @Mock
        private UserRepository userRepository;

        @Mock
        private DomainEventPublisher eventPublisher;

        @InjectMocks
        private CirculationExportAsyncExecutor asyncExecutor;

        @BeforeEach
        void injectOptionalFields() {
            ReflectionTestUtils.setField(asyncExecutor, "userRepository", userRepository);
        }

        @Test
        @DisplayName("generateAsync 成功時: PDF を R2 にアップロードし COMPLETED 遷移")
        void generateAsync_success_uploadsAndCompletes() {
            CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                    CirculationExportStatus.PENDING);
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(List.of());
            given(pdfGeneratorService.generateFromTemplate(eq("pdf/circulation-export"), any()))
                    .willReturn(new byte[]{1, 2, 3});

            asyncExecutor.generateAsync(DOCUMENT_ID);

            verify(storageService, times(1)).upload(anyString(), any(byte[].class), eq("application/pdf"));
            assertThat(entity.getExportStatus()).isEqualTo(CirculationExportStatus.COMPLETED);
            assertThat(entity.getExportFileKey()).startsWith("circulation/exports/100/");
            assertThat(entity.getExportCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("generateAsync 失敗時: FAILED 遷移しエラーメッセージを保存")
        void generateAsync_failure_marksFailed() {
            CirculationDocumentEntity entity = baseEntity(CirculationStatus.COMPLETED,
                    CirculationExportStatus.PENDING);
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(documentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(List.of());
            given(pdfGeneratorService.generateFromTemplate(anyString(), any()))
                    .willThrow(new RuntimeException("PDF テンプレ生成失敗"));

            asyncExecutor.generateAsync(DOCUMENT_ID);

            assertThat(entity.getExportStatus()).isEqualTo(CirculationExportStatus.FAILED);
            assertThat(entity.getExportErrorMessage()).contains("PDF テンプレ生成失敗");
        }
    }
}
