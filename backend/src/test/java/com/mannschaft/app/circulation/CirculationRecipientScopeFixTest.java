package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.AddRecipientsRequest;
import com.mannschaft.app.circulation.dto.RecipientEntry;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 回覧受信者の追加/削除における scope 解決バグ根治 + per-scope ADMIN/DEPUTY 認可の単体テスト。
 *
 * <p>背景: {@code CirculationRecipientController} が {@code SCOPE_TYPE("TEAM"), 0L} を
 * ハードコードで Service に渡していたため、{@code findDocumentOrThrow("TEAM", 0L, docId)} が
 * 実 teamId ≠ 0 でヒットせず必ず DOCUMENT_NOT_FOUND(404) になっていた（受信者の追加/削除が常時失敗）。
 * さらにこの追加/削除には認可が無く、認証さえあれば誰でも実行可能だった。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-②1: addRecipients が doc 由来 scope（0L でなく実 scopeId）で成功する</li>
 *   <li>AC-②2: removeRecipient も同様に doc 由来 scope で成功する</li>
 *   <li>AC-②3: 非 ADMIN/DEPUTY は per-scope 認可で COMMON_002 に弾かれ、受信者は変更されない</li>
 *   <li>AC-②4: 不在 docId は DOCUMENT_NOT_FOUND(404) が伝播する</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("回覧受信者 scope解決 + ADMIN/DEPUTY認可 単体テスト")
class CirculationRecipientScopeFixTest {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private CirculationMapper circulationMapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private CirculationService service;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    private static final Long DOCUMENT_ID = 100L;
    /** 実 scopeId（0L でないこと自体が回帰防止のポイント）。 */
    private static final Long REAL_SCOPE_ID = 1L;
    private static final Long ACTOR_ID = 10L;
    private static final Long RECIPIENT_ID = 50L;
    private static final String SCOPE_TYPE = "TEAM";

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(ACTOR_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private CirculationDocumentEntity teamDoc() {
        return CirculationDocumentEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(REAL_SCOPE_ID).createdBy(ACTOR_ID)
                .title("回覧テスト").body("本文").build();
    }

    @Nested
    @DisplayName("AC-②1 addRecipients")
    class AddRecipients {

        @Test
        @DisplayName("doc由来scope（実scopeId）で受信者が追加される（0L経路ではない）")
        void doc由来scopeで成功() {
            CirculationDocumentEntity document = teamDoc();
            // 実 scopeId で引く経路のみ stub。0L 経路（バグ）では引けず DOCUMENT_NOT_FOUND になる。
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.of(document));
            lenient().when(recipientRepository.existsByDocumentIdAndUserId(any(), eq(55L))).thenReturn(false);
            given(recipientRepository.countByDocumentId(any())).willReturn(1L);
            given(documentRepository.save(document)).willReturn(document);
            CirculationRecipientEntity rec = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(55L).sortOrder(0).build();
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(any()))
                    .willReturn(List.of(rec));
            given(circulationMapper.toRecipientResponseList(any())).willReturn(List.of());
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);

            AddRecipientsRequest request = new AddRecipientsRequest(
                    List.of(new RecipientEntry(55L, 0)));
            service.addRecipients(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, request);

            // 実 scopeId 経路で引かれたこと、0L 経路で引かれていないことを明示
            verify(documentRepository, atLeastOnce())
                    .findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID);
            verify(documentRepository, never())
                    .findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, 0L);
            verify(recipientRepository).save(any(CirculationRecipientEntity.class));
        }
    }

    @Nested
    @DisplayName("AC-②2 removeRecipient")
    class RemoveRecipient {

        @Test
        @DisplayName("doc由来scope（実scopeId）で受信者が削除される（0L経路ではない）")
        void doc由来scopeで成功() {
            CirculationDocumentEntity document = teamDoc();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.of(document));
            CirculationRecipientEntity recipient = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(ACTOR_ID).sortOrder(0).build();
            given(recipientRepository.findById(RECIPIENT_ID)).willReturn(Optional.of(recipient));
            given(recipientRepository.countByDocumentId(any())).willReturn(0L);
            given(documentRepository.save(document)).willReturn(document);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);

            service.removeRecipient(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, RECIPIENT_ID);

            verify(documentRepository, atLeastOnce())
                    .findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID);
            verify(documentRepository, never())
                    .findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, 0L);
            verify(recipientRepository).delete(recipient);
        }
    }

    @Nested
    @DisplayName("AC-②3 per-scope ADMIN/DEPUTY 認可")
    class Authorization {

        @Test
        @DisplayName("addRecipients: 非ADMIN/DEPUTYはCOMMON_002で遮断され受信者は変更されない")
        void addRecipients_非管理者は遮断() {
            CirculationDocumentEntity document = teamDoc();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.of(document));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ACTOR_ID, REAL_SCOPE_ID, SCOPE_TYPE);

            AddRecipientsRequest request = new AddRecipientsRequest(
                    List.of(new RecipientEntry(55L, 0)));
            assertThatThrownBy(() ->
                    service.addRecipients(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            verify(recipientRepository, never()).save(any());
        }

        @Test
        @DisplayName("removeRecipient: 非ADMIN/DEPUTYはCOMMON_002で遮断され受信者は削除されない")
        void removeRecipient_非管理者は遮断() {
            CirculationDocumentEntity document = teamDoc();
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.of(document));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ACTOR_ID, REAL_SCOPE_ID, SCOPE_TYPE);

            assertThatThrownBy(() ->
                    service.removeRecipient(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, RECIPIENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            verify(recipientRepository, never()).delete(any(CirculationRecipientEntity.class));
        }
    }

    @Nested
    @DisplayName("AC-②4 不在docIdは404伝播")
    class NotFound {

        @Test
        @DisplayName("addRecipients: 不在docIdでDOCUMENT_NOT_FOUNDが伝播する")
        void addRecipients_不在doc() {
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.empty());

            AddRecipientsRequest request = new AddRecipientsRequest(
                    List.of(new RecipientEntry(55L, 0)));
            assertThatThrownBy(() ->
                    service.addRecipients(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }

        @Test
        @DisplayName("removeRecipient: 不在docIdでDOCUMENT_NOT_FOUNDが伝播する")
        void removeRecipient_不在doc() {
            given(documentRepository.findByIdAndScopeTypeAndScopeId(DOCUMENT_ID, SCOPE_TYPE, REAL_SCOPE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.removeRecipient(SCOPE_TYPE, REAL_SCOPE_ID, DOCUMENT_ID, RECIPIENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }
    }
}
