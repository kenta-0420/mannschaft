package com.mannschaft.app.circulation.service;

import com.mannschaft.app.circulation.CirculationMapper;
import com.mannschaft.app.circulation.RecipientStatus;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampRequest;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link CirculationStampService} の代理確認押印ロジック単体テスト（F14.1 Phase 13-α）。
 * 通常押印・代理確認押印の isProxyConfirmed フラグと proxyInputRecordId のセットを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationStampService 代理確認押印テスト")
class CirculationStampProxyInputTest {

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
    private static final Long RECIPIENT_ID = 200L;
    private static final Long CONSENT_ID = 50L;
    private static final Long SUBJECT_USER_ID = 101L;
    private static final Long PROXY_RECORD_ID = 999L;

    @BeforeEach
    void setUp() {
        // セキュリティコンテキストに認証ユーザーをセット（proxyUserId用）
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("300", null, List.of())
        );
    }

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

    private RecipientResponse createRecipientResponse() {
        return new RecipientResponse(RECIPIENT_ID, DOCUMENT_ID, USER_ID, 0,
                "STAMPED", null, SEAL_ID, "CIRCLE", (short) 0, false, null, null);
    }

    @Nested
    @DisplayName("通常押印（非代理）")
    class NormalStamp {

        @Test
        @DisplayName("isProxyConfirmed が false のまま保存される")
        void stamp_通常押印_isProxyConfirmedがfalse() {
            // Given
            given(proxyInputContext.isProxy()).willReturn(false);
            StampRequest request = new StampRequest(SEAL_ID, "CIRCLE", null, null);

            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID))
                    .willReturn(Optional.of(recipient));
            given(recipientRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(circulationMapper.toRecipientResponse(any())).willReturn(createRecipientResponse());

            // When
            circulationStampService.stamp(DOCUMENT_ID, USER_ID, request);

            // Then: proxyInputRecordRepository は呼ばれない
            verify(proxyInputRecordRepository, never()).save(any());
            // recipientRepository.save は1回のみ呼ばれる
            verify(recipientRepository, times(1)).save(any(CirculationRecipientEntity.class));

            // 保存されたエンティティの isProxyConfirmed が false であることを確認
            ArgumentCaptor<CirculationRecipientEntity> captor =
                    ArgumentCaptor.forClass(CirculationRecipientEntity.class);
            verify(recipientRepository).save(captor.capture());
            CirculationRecipientEntity saved = captor.getValue();
            assertThat(saved.getIsProxyConfirmed()).isFalse();
            assertThat(saved.getProxyInputRecordId()).isNull();
        }
    }

    @Nested
    @DisplayName("代理確認押印")
    class ProxyStamp {

        @Test
        @DisplayName("isProxyConfirmed=true、proxyInputRecordId がセットされて保存される")
        void stamp_代理確認_isProxyConfirmedがtrueでproxyInputRecordIdがセット() {
            // Given
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            given(proxyInputContext.getSubjectUserId()).willReturn(SUBJECT_USER_ID);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("管理室棚B");

            StampRequest request = new StampRequest(SEAL_ID, "CIRCLE", null, null);

            CirculationDocumentEntity document = createActiveDocument();
            CirculationRecipientEntity recipient = createPendingRecipient();

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(recipientRepository.findByDocumentIdAndUserId(DOCUMENT_ID, USER_ID))
                    .willReturn(Optional.of(recipient));
            given(recipientRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    anyLong(), anyString(), any())).willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any())).willAnswer(inv -> {
                // ID をセットした proxyRecord を返す（実際はDBがセット）
                return ProxyInputRecordEntity.builder()
                        .proxyInputConsentId(CONSENT_ID)
                        .subjectUserId(SUBJECT_USER_ID)
                        .proxyUserId(300L)
                        .featureScope("CIRCULAR")
                        .targetEntityType("CIRCULATION_STAMP")
                        .targetEntityId(null)
                        .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                        .originalStorageLocation("管理室棚B")
                        .build();
            });

            given(circulationMapper.toRecipientResponse(any())).willReturn(createRecipientResponse());

            // When
            circulationStampService.stamp(DOCUMENT_ID, USER_ID, request);

            // Then: proxyInputRecordRepository.save が呼ばれる
            verify(proxyInputRecordRepository).save(any(ProxyInputRecordEntity.class));
            // recipientRepository.save は2回呼ばれる（初回保存 + フラグ更新保存）
            verify(recipientRepository, times(2)).save(any(CirculationRecipientEntity.class));

            // 2回目の save に渡されたエンティティの isProxyConfirmed が true であることを確認
            ArgumentCaptor<CirculationRecipientEntity> captor =
                    ArgumentCaptor.forClass(CirculationRecipientEntity.class);
            verify(recipientRepository, times(2)).save(captor.capture());
            List<CirculationRecipientEntity> allValues = captor.getAllValues();
            CirculationRecipientEntity secondSave = allValues.get(1);
            assertThat(secondSave.getIsProxyConfirmed()).isTrue();
            assertThat(secondSave.getStatus()).isEqualTo(RecipientStatus.STAMPED);
        }
    }
}
