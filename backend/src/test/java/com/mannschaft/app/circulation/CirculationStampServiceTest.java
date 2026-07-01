package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link CirculationStampService} の単体テスト。
 * 押印・スキップ・拒否・順次回覧の順序検証を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationStampService 単体テスト")
class CirculationStampServiceTest {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private CirculationMapper circulationMapper;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @InjectMocks
    private CirculationStampService circulationStampService;

    private static final Long DOCUMENT_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long SEAL_ID = 50L;

    private CirculationDocumentEntity createActiveDocument() {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType("TEAM").scopeId(1L).createdBy(1L)
                .title("テスト").body("本文").build();
        entity.activate();
        entity.updateRecipientCount(3);
        return entity;
    }

    private CirculationRecipientEntity createPendingRecipient() {
        return CirculationRecipientEntity.builder()
                .documentId(DOCUMENT_ID).userId(USER_ID).sortOrder(0).build();
    }

    @Nested
    @DisplayName("stamp")
    class Stamp {

        @Test
        @DisplayName("押印_正常_ステータスSTAMPED")
        void 押印_正常_ステータスSTAMPED() {
            // Given
            StampRequest request = new StampRequest(SEAL_ID, "CIRCLE", null, null);

            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();
            RecipientResponse response = new RecipientResponse(1L, DOCUMENT_ID, USER_ID, 0,
                    "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID)).willReturn(Optional.of(recipient));
            given(recipientRepository.save(recipient)).willReturn(recipient);
            given(circulationMapper.toRecipientResponse(recipient)).willReturn(response);

            // When
            circulationStampService.stamp(DOCUMENT_ID, USER_ID, request);

            // Then
            assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.STAMPED);
            assertThat(document.getStampedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("押印_文書がアクティブでない_BusinessException")
        void 押印_文書がアクティブでない_BusinessException() {
            // Given
            StampRequest request = new StampRequest(SEAL_ID, null, null, null);

            CirculationDocumentEntity document = CirculationDocumentEntity.builder()
                    .scopeType("TEAM").scopeId(1L).createdBy(1L)
                    .title("テスト").body("本文").build();

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));

            // When & Then
            assertThatThrownBy(() -> circulationStampService.stamp(DOCUMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_DOCUMENT_STATUS));
        }

        @Test
        @DisplayName("押印_既に押印済み_BusinessException")
        void 押印_既に押印済み_BusinessException() {
            // Given
            StampRequest request = new StampRequest(SEAL_ID, null, null, null);

            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();
            recipient.stamp(SEAL_ID, "CIRCLE", (short) 0, false);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID)).willReturn(Optional.of(recipient));

            // When & Then
            assertThatThrownBy(() -> circulationStampService.stamp(DOCUMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_RECIPIENT_STATUS));
        }
    }

    @Nested
    @DisplayName("skip")
    class Skip {

        @Test
        @DisplayName("スキップ_正常_ステータスSKIPPED")
        void スキップ_正常_ステータスSKIPPED() {
            // Given
            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();
            RecipientResponse response = new RecipientResponse(1L, DOCUMENT_ID, USER_ID, 0,
                    "SKIPPED", null, null, null, (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID)).willReturn(Optional.of(recipient));
            given(recipientRepository.save(recipient)).willReturn(recipient);
            given(circulationMapper.toRecipientResponse(recipient)).willReturn(response);

            // When
            circulationStampService.skip(DOCUMENT_ID, USER_ID);

            // Then
            assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("拒否_正常_ステータスREJECTED")
        void 拒否_正常_ステータスREJECTED() {
            // Given
            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();
            RecipientResponse response = new RecipientResponse(1L, DOCUMENT_ID, USER_ID, 0,
                    "REJECTED", null, null, null, (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID)).willReturn(Optional.of(recipient));
            given(recipientRepository.save(recipient)).willReturn(recipient);
            given(circulationMapper.toRecipientResponse(recipient)).willReturn(response);

            // When
            circulationStampService.reject(DOCUMENT_ID, USER_ID);

            // Then
            assertThat(recipient.getStatus()).isEqualTo(RecipientStatus.REJECTED);
        }
    }

    // ========================================
    // validateSequentialOrder — 押印順序検証（SEQUENTIAL / HYBRID / SIMULTANEOUS）
    // ========================================

    /**
     * 指定 sortOrder / status の受信者を生成する。id を明示して設定する。
     */
    private CirculationRecipientEntity recipient(Long id, Long userId, int sortOrder,
                                                 RecipientStatus status) {
        CirculationRecipientEntity r = CirculationRecipientEntity.builder()
                .documentId(DOCUMENT_ID).userId(userId).sortOrder(sortOrder).status(status).build();
        // BaseEntity の id を反映（純 Mockito・DB 未使用のため手動設定）
        org.springframework.test.util.ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    private CirculationDocumentEntity activeDocumentWithMode(CirculationMode mode, int recipientCount) {
        CirculationDocumentEntity entity = CirculationDocumentEntity.builder()
                .scopeType("TEAM").scopeId(1L).createdBy(1L)
                .title("テスト").body("本文")
                .circulationMode(mode)
                .build();
        // BaseEntity の id を DOCUMENT_ID に固定（validateSequentialOrder が document.getId() を使うため）
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", DOCUMENT_ID);
        entity.activate();
        entity.updateRecipientCount(recipientCount);
        return entity;
    }

    @Nested
    @DisplayName("validateSequentialOrder (HYBRID)")
    class HybridOrder {

        // あて先5人・N=2 を想定:
        //   sortOrder 0: userId=10 (先頭・順番)
        //   sortOrder 1: userId=11 (先頭・順番)
        //   sortOrder 2: userId=12,13,14 (残り・一斉)

        private StampRequest stampReq() {
            return new StampRequest(SEAL_ID, "CIRCLE", null, null);
        }

        @Test
        @DisplayName("AC-3: HYBRID_自分より前(sortOrder小)にPENDINGが居る_SEQUENTIAL_ORDER_VIOLATION")
        void HYBRID_前にPENDING_違反() {
            // sortOrder=1(userId=11) が押印しようとするが sortOrder=0(userId=10) がまだPENDING
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.HYBRID, 5);
            CirculationRecipientEntity stamper = recipient(2L, 11L, 1, RecipientStatus.PENDING);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 11L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.PENDING),
                            stamper,
                            recipient(3L, 12L, 2, RecipientStatus.PENDING),
                            recipient(4L, 13L, 2, RecipientStatus.PENDING),
                            recipient(5L, 14L, 2, RecipientStatus.PENDING)));

            assertThatThrownBy(() -> circulationStampService.stamp(DOCUMENT_ID, 11L, stampReq()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.SEQUENTIAL_ORDER_VIOLATION));
        }

        @Test
        @DisplayName("AC-4: HYBRID_同一sortOrder(N群)にPENDINGが居ても違反にならない(一斉)")
        void HYBRID_同一sortOrderPENDING_違反なし() {
            // sortOrder=2(userId=12) が押印。前段(sortOrder 0,1)は全員完了。
            // 同一 sortOrder=2 の userId=13,14 は PENDING でも一斉なのでブロックしない。
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.HYBRID, 5);
            CirculationRecipientEntity stamper = recipient(3L, 12L, 2, RecipientStatus.PENDING);
            RecipientResponse response = new RecipientResponse(3L, DOCUMENT_ID, 12L, 2,
                    "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 12L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.STAMPED),
                            recipient(2L, 11L, 1, RecipientStatus.STAMPED),
                            stamper,
                            recipient(4L, 13L, 2, RecipientStatus.PENDING),
                            recipient(5L, 14L, 2, RecipientStatus.PENDING)));
            given(recipientRepository.save(stamper)).willReturn(stamper);
            given(circulationMapper.toRecipientResponse(stamper)).willReturn(response);

            circulationStampService.stamp(DOCUMENT_ID, 12L, stampReq());

            assertThat(stamper.getStatus()).isEqualTo(RecipientStatus.STAMPED);
        }

        @Test
        @DisplayName("AC-5: HYBRID_N群の押印者は前段が1人でもPENDINGなら違反")
        void HYBRID_N群_前段PENDING_違反() {
            // sortOrder=2(userId=12) が押印しようとするが sortOrder=1(userId=11) がまだPENDING
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.HYBRID, 5);
            CirculationRecipientEntity stamper = recipient(3L, 12L, 2, RecipientStatus.PENDING);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 12L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.STAMPED),
                            recipient(2L, 11L, 1, RecipientStatus.PENDING),
                            stamper,
                            recipient(4L, 13L, 2, RecipientStatus.PENDING),
                            recipient(5L, 14L, 2, RecipientStatus.PENDING)));

            assertThatThrownBy(() -> circulationStampService.stamp(DOCUMENT_ID, 12L, stampReq()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.SEQUENTIAL_ORDER_VIOLATION));
        }

        @Test
        @DisplayName("AC-5: HYBRID_N群の押印者は前段が全員完了なら押印可")
        void HYBRID_N群_前段全完了_押印可() {
            // sortOrder=2(userId=12) が押印。前段(sortOrder 0,1)は全員完了(STAMPED/SKIPPED)。
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.HYBRID, 5);
            CirculationRecipientEntity stamper = recipient(3L, 12L, 2, RecipientStatus.PENDING);
            RecipientResponse response = new RecipientResponse(3L, DOCUMENT_ID, 12L, 2,
                    "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 12L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.STAMPED),
                            recipient(2L, 11L, 1, RecipientStatus.SKIPPED),
                            stamper,
                            recipient(4L, 13L, 2, RecipientStatus.PENDING),
                            recipient(5L, 14L, 2, RecipientStatus.PENDING)));
            given(recipientRepository.save(stamper)).willReturn(stamper);
            given(circulationMapper.toRecipientResponse(stamper)).willReturn(response);

            circulationStampService.stamp(DOCUMENT_ID, 12L, stampReq());

            assertThat(stamper.getStatus()).isEqualTo(RecipientStatus.STAMPED);
        }
    }

    @Nested
    @DisplayName("validateSequentialOrder (SEQUENTIAL/SIMULTANEOUS 回帰)")
    class SequentialSimultaneousRegression {

        private StampRequest stampReq() {
            return new StampRequest(SEAL_ID, "CIRCLE", null, null);
        }

        @Test
        @DisplayName("AC-6: SEQUENTIAL_前にPENDINGが居る_従来通りSEQUENTIAL_ORDER_VIOLATION")
        void SEQUENTIAL_前にPENDING_違反() {
            // sortOrder=1 が押印しようとするが sortOrder=0 がPENDING（全distinct sortOrder）
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.SEQUENTIAL, 3);
            CirculationRecipientEntity stamper = recipient(2L, 11L, 1, RecipientStatus.PENDING);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 11L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.PENDING),
                            stamper,
                            recipient(3L, 12L, 2, RecipientStatus.PENDING)));

            assertThatThrownBy(() -> circulationStampService.stamp(DOCUMENT_ID, 11L, stampReq()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.SEQUENTIAL_ORDER_VIOLATION));
        }

        @Test
        @DisplayName("AC-6: SEQUENTIAL_前が全員完了_従来通り押印可")
        void SEQUENTIAL_前が全完了_押印可() {
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.SEQUENTIAL, 3);
            CirculationRecipientEntity stamper = recipient(2L, 11L, 1, RecipientStatus.PENDING);
            RecipientResponse response = new RecipientResponse(2L, DOCUMENT_ID, 11L, 1,
                    "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 11L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(java.util.List.of(
                            recipient(1L, 10L, 0, RecipientStatus.STAMPED),
                            stamper,
                            recipient(3L, 12L, 2, RecipientStatus.PENDING)));
            given(recipientRepository.save(stamper)).willReturn(stamper);
            given(circulationMapper.toRecipientResponse(stamper)).willReturn(response);

            circulationStampService.stamp(DOCUMENT_ID, 11L, stampReq());

            assertThat(stamper.getStatus()).isEqualTo(RecipientStatus.STAMPED);
        }

        @Test
        @DisplayName("AC-6: SIMULTANEOUS_前にPENDINGが居ても素通り(順序検証しない)")
        void SIMULTANEOUS_前にPENDING_押印可() {
            CirculationDocumentEntity document = activeDocumentWithMode(CirculationMode.SIMULTANEOUS, 3);
            CirculationRecipientEntity stamper = recipient(2L, 11L, 1, RecipientStatus.PENDING);
            RecipientResponse response = new RecipientResponse(2L, DOCUMENT_ID, 11L, 1,
                    "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, 11L))
                    .willReturn(Optional.of(stamper));
            given(recipientRepository.save(stamper)).willReturn(stamper);
            given(circulationMapper.toRecipientResponse(stamper)).willReturn(response);

            circulationStampService.stamp(DOCUMENT_ID, 11L, stampReq());

            // SIMULTANEOUS は findByDocumentIdOrderBySortOrderAsc を呼ばずに素通りする
            assertThat(stamper.getStatus()).isEqualTo(RecipientStatus.STAMPED);
        }
    }
}
