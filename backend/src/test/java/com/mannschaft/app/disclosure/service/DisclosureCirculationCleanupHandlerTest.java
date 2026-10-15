package com.mannschaft.app.disclosure.service;

import com.mannschaft.app.circulation.event.CirculationDocumentDeletedEvent;
import com.mannschaft.app.disclosure.entity.DisclosureExportEntity;
import com.mannschaft.app.disclosure.repository.DisclosureExportRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link DisclosureCirculationCleanupHandler} の単体テスト（F09.14 Phase 4-C）。
 *
 * <p>F05.2 が {@link CirculationDocumentDeletedEvent} を発行した際に、
 * {@code disclosure_exports.circulation_document_id} が NULL 化されることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DisclosureCirculationCleanupHandler 単体テスト")
class DisclosureCirculationCleanupHandlerTest {

    @Mock
    private DisclosureExportRepository exportRepository;

    @InjectMocks
    private DisclosureCirculationCleanupHandler handler;

    private static final Long CIRCULATION_DOCUMENT_ID = 1234L;

    private DisclosureExportEntity exportLinkedTo(Long circDocId) {
        DisclosureExportEntity entity = DisclosureExportEntity.builder()
                .scopeType("ORGANIZATION")
                .scopeId(1L)
                .build();
        entity.linkCirculationDocument(circDocId);
        return entity;
    }

    @Test
    @DisplayName("イベント受信_正常_該当行が1件で参照NULL化")
    void イベント受信_正常_該当行が1件で参照NULL化() {
        // Given
        DisclosureExportEntity export = exportLinkedTo(CIRCULATION_DOCUMENT_ID);
        given(exportRepository.findByCirculationDocumentIdAndDeletedAtIsNull(CIRCULATION_DOCUMENT_ID))
                .willReturn(List.of(export));

        // When
        handler.onCirculationDocumentDeleted(new CirculationDocumentDeletedEvent(CIRCULATION_DOCUMENT_ID));

        // Then: circulation_document_id が NULL 化されている
        assertThat(export.getCirculationDocumentId()).isNull();
        ArgumentCaptor<List<DisclosureExportEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(exportRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
    }

    @Test
    @DisplayName("イベント受信_正常_該当行が複数で全件NULL化")
    void イベント受信_正常_該当行が複数で全件NULL化() {
        // Given: 同じ回覧文書を参照する出力履歴が 3 件存在
        DisclosureExportEntity e1 = exportLinkedTo(CIRCULATION_DOCUMENT_ID);
        DisclosureExportEntity e2 = exportLinkedTo(CIRCULATION_DOCUMENT_ID);
        DisclosureExportEntity e3 = exportLinkedTo(CIRCULATION_DOCUMENT_ID);
        given(exportRepository.findByCirculationDocumentIdAndDeletedAtIsNull(CIRCULATION_DOCUMENT_ID))
                .willReturn(List.of(e1, e2, e3));

        // When
        handler.onCirculationDocumentDeleted(new CirculationDocumentDeletedEvent(CIRCULATION_DOCUMENT_ID));

        // Then: 全件 NULL 化
        assertThat(e1.getCirculationDocumentId()).isNull();
        assertThat(e2.getCirculationDocumentId()).isNull();
        assertThat(e3.getCirculationDocumentId()).isNull();
        verify(exportRepository, times(1)).saveAll(List.of(e1, e2, e3));
    }

    @Test
    @DisplayName("イベント受信_正常_該当行なし時はsaveAllを呼ばない")
    void イベント受信_正常_該当行なし時はsaveAllを呼ばない() {
        // Given
        given(exportRepository.findByCirculationDocumentIdAndDeletedAtIsNull(CIRCULATION_DOCUMENT_ID))
                .willReturn(List.of());

        // When
        handler.onCirculationDocumentDeleted(new CirculationDocumentDeletedEvent(CIRCULATION_DOCUMENT_ID));

        // Then: 例外なく終了し、saveAll は呼ばれない
        verify(exportRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("イベント受信_リポジトリ例外_握りつぶしてログ警告のみ")
    void イベント受信_リポジトリ例外_握りつぶしてログ警告のみ() {
        // Given: リポジトリが例外をスロー
        given(exportRepository.findByCirculationDocumentIdAndDeletedAtIsNull(CIRCULATION_DOCUMENT_ID))
                .willThrow(new RuntimeException("DB error"));

        // When/Then: 例外が呼出側に伝播しないこと（@TransactionalEventListener の REQUIRES_NEW
        // により F05.2 削除トランザクションを阻害しない設計を担保）
        handler.onCirculationDocumentDeleted(new CirculationDocumentDeletedEvent(CIRCULATION_DOCUMENT_ID));

        verify(exportRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    @DisplayName("イベント受信_documentIdがNULL_早期returnでクエリせず")
    void イベント受信_documentIdがNULL_早期returnでクエリせず() {
        // When
        handler.onCirculationDocumentDeleted(new CirculationDocumentDeletedEvent(null));

        // Then: null 早期 return ガードによりリポジトリ呼出なし
        verify(exportRepository, never())
                .findByCirculationDocumentIdAndDeletedAtIsNull(org.mockito.ArgumentMatchers.anyLong());
        verify(exportRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }
}
