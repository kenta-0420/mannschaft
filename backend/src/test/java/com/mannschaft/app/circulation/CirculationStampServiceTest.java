package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.BusinessException;
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
}
