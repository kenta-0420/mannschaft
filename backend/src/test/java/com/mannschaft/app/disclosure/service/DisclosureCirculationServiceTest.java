package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.dto.CreateDocumentRequest;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.DisclosureOutputFormat;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartRequest;
import com.mannschaft.app.disclosure.dto.DisclosureCirculationStartResponse;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisclosureCirculationService} 単体テスト（F09.14 Phase 3-D）。
 *
 * <p>F05.2 直接呼出による電子印鑑承認回覧の起動 / IDOR ガード / 二重起動防止を網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureCirculationService 単体テスト")
class DisclosureCirculationServiceTest {

    @Mock private DisclosureExportRepository exportRepository;
    @Mock private CirculationService circulationService;

    private DisclosureCirculationService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureCirculationService(exportRepository, circulationService);
    }

    @Test
    @DisplayName("startCirculation: 回覧文書を作成し circulation_document_id を保存する")
    void startCirculation_success() throws Exception {
        DisclosureExportEntity export = export(100L, null);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(export));

        DocumentResponse created = documentResponse(555L, CirculationStatus.DRAFT);
        DocumentResponse activated = documentResponse(555L, CirculationStatus.ACTIVE);
        when(circulationService.createDocument(eq("ORGANIZATION"), eq(100L), eq(200L), any()))
                .thenReturn(created);
        when(circulationService.activateDocument("ORGANIZATION", 100L, 555L))
                .thenReturn(activated);
        when(exportRepository.save(any(DisclosureExportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(11L, 12L, 13L), "SEQUENTIAL", LocalDate.of(2026, 6, 1));

        DisclosureCirculationStartResponse res = service.startCirculation(100L, 7L, 200L, req);

        assertThat(res.exportId()).isEqualTo(7L);
        assertThat(res.circulationDocumentId()).isEqualTo(555L);
        assertThat(res.circulationStatus()).isEqualTo(CirculationStatus.ACTIVE);

        // CreateDocumentRequest が正しく組み立てられていること
        ArgumentCaptor<CreateDocumentRequest> reqCaptor =
                ArgumentCaptor.forClass(CreateDocumentRequest.class);
        verify(circulationService).createDocument(
                eq("ORGANIZATION"), eq(100L), eq(200L), reqCaptor.capture());
        CreateDocumentRequest captured = reqCaptor.getValue();
        assertThat(captured.getCirculationMode()).isEqualTo("SEQUENTIAL");
        assertThat(captured.getDueDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(captured.getRecipients()).hasSize(3);
        assertThat(captured.getTitle()).contains("exportId=7");

        // export.circulationDocumentId が更新されたこと
        assertThat(export.getCirculationDocumentId()).isEqualTo(555L);
        verify(exportRepository).save(export);
    }

    @Test
    @DisplayName("startCirculation: 出力履歴が見つからない場合は DISCLOSURE_001")
    void startCirculation_exportNotFound() {
        when(exportRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(1L), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> service.startCirculation(100L, 99L, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_001);
    }

    @Test
    @DisplayName("startCirculation: 他組織の出力履歴に対しては DISCLOSURE_002（IDOR ガード）")
    void startCirculation_idorRejected() throws Exception {
        // export は scopeId=999、URL の scopeId=100
        DisclosureExportEntity export = export(999L, null);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(export));

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(1L), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> service.startCirculation(100L, 7L, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);

        verify(circulationService, never()).createDocument(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("startCirculation: 既に circulation_document_id が設定済の場合は DISCLOSURE_003（二重起動防止）")
    void startCirculation_duplicateRejected() throws Exception {
        DisclosureExportEntity export = export(100L, 555L); // 既に紐付け済
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(export));

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(1L), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> service.startCirculation(100L, 7L, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_003);

        verify(circulationService, never()).createDocument(any(), anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("startCirculation: 不正な circulationMode は DISCLOSURE_004")
    void startCirculation_invalidMode() throws Exception {
        DisclosureExportEntity export = export(100L, null);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(export));

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(1L), "INVALID_MODE", null);

        assertThatThrownBy(() -> service.startCirculation(100L, 7L, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("startCirculation: 受信者リストが空 / null は DISCLOSURE_004")
    void startCirculation_emptyRecipients() {
        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(), "SIMULTANEOUS", null);

        assertThatThrownBy(() -> service.startCirculation(100L, 7L, 200L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_004);
    }

    @Test
    @DisplayName("startCirculation: 重複した受信者 ID は排除される")
    void startCirculation_recipientDeduplication() throws Exception {
        DisclosureExportEntity export = export(100L, null);
        when(exportRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(export));

        DocumentResponse created = documentResponse(555L, CirculationStatus.DRAFT);
        DocumentResponse activated = documentResponse(555L, CirculationStatus.ACTIVE);
        when(circulationService.createDocument(any(), anyLong(), anyLong(), any())).thenReturn(created);
        when(circulationService.activateDocument(any(), anyLong(), anyLong())).thenReturn(activated);
        when(exportRepository.save(any(DisclosureExportEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        DisclosureCirculationStartRequest req = new DisclosureCirculationStartRequest(
                List.of(11L, 12L, 11L, 13L, 12L), "SIMULTANEOUS", null);

        service.startCirculation(100L, 7L, 200L, req);

        ArgumentCaptor<CreateDocumentRequest> reqCaptor =
                ArgumentCaptor.forClass(CreateDocumentRequest.class);
        verify(circulationService).createDocument(any(), anyLong(), anyLong(), reqCaptor.capture());
        // 11/12/13 の 3 件に重複排除されること
        assertThat(reqCaptor.getValue().getRecipients()).hasSize(3);
    }

    // ----- ヘルパー -----

    private DisclosureExportEntity export(Long scopeId, Long circulationDocumentId) throws Exception {
        DisclosureExportEntity e = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION").scopeId(scopeId)
                .templateId(1L).templateCodeSnapshot("MLIT_STANDARD_2024")
                .templateVersionSnapshot("2024.1")
                .outputFormat(DisclosureOutputFormat.PDF)
                .sharedFileId(999L)
                .requesterUserId(200L)
                .recipientNote("○○仲介株式会社 山田様")
                .dataSnapshot("{}")
                .outputSha256("a".repeat(64))
                .circulationDocumentId(circulationDocumentId)
                .build();
        setEntityIdViaReflection(e, 7L);
        return e;
    }

    private DocumentResponse documentResponse(Long id, CirculationStatus status) {
        return DocumentResponse.builder()
                .id(id).scopeType("ORGANIZATION").scopeId(100L).createdBy(200L)
                .title("重要事項説明書 承認回覧（exportId=7）").body("本文")
                .circulationMode("SEQUENTIAL").sequentialCount(0)
                .status(status.name()).priority("NORMAL").stampDisplayStyle("STANDARD")
                .totalRecipientCount(3).stampedCount(0).attachmentCount(0).commentCount(0)
                .build();
    }

    private static void setEntityIdViaReflection(DisclosureExportEntity entity, Long id)
            throws Exception {
        Field f = DisclosureExportEntity.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(entity, id);
    }
}
