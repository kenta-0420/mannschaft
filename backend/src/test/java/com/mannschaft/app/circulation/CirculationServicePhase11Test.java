package com.mannschaft.app.circulation;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.dto.DocumentStatusResponse;
import com.mannschaft.app.circulation.dto.ForceCompleteBatchResponse;
import com.mannschaft.app.circulation.dto.RemindResponse;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.repository.CirculationAttachmentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 11 第三陣 3-A で追加した {@link CirculationService} 機能の単体テスト。
 *
 * <ul>
 *   <li>{@code forceCompleteDocument} - 強制完了</li>
 *   <li>{@code forceCompleteBatch} - 一括強制完了</li>
 *   <li>{@code remindDocument} - 手動リマインド</li>
 *   <li>{@code duplicateDocument} - 複製</li>
 *   <li>{@code getDocumentStatus} - 受信者押印状況一覧</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationService Phase 11 第三陣 3-A 単体テスト")
class CirculationServicePhase11Test {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationRecipientRepository recipientRepository;

    @Mock
    private CirculationAttachmentRepository attachmentRepository;

    @Mock
    private CirculationMapper circulationMapper;

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AccessControlService accessControlService;

    /** Issue #2715 CMP-055 lot C-5/C-6: newly added i18n dependencies. */
    @Mock private com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    @InjectMocks
    private CirculationService circulationService;

    /**
     * Issue #2715 CMP-055 lot C-5/C-6: the bare MessageSource mock would return null for
     * title/body. Return the supplied default message so existing assertions keep working.
     */
    @org.junit.jupiter.api.BeforeEach
    void stubI18nMessageSource() {
        org.mockito.Mockito.lenient().when(userLocaleCache.getLocales(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Map.of());
        org.mockito.Mockito.lenient().when(messageSource.getMessage(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(2));
    }

    /**
     * {@code auditLogService} は {@code CirculationService} 側で
     * {@code @Autowired(required = false)} の非 final フィールドとして定義されているため、
     * Mockito の {@code @InjectMocks} はコンストラクタ注入を優先しフィールド注入が
     * スキップされるケースがある。{@link ReflectionTestUtils#setField} で確実に注入する。
     */
    @BeforeEach
    void injectOptionalFields() {
        ReflectionTestUtils.setField(circulationService, "auditLogService", auditLogService);
        // Issue #2715 CMP-055: same @InjectMocks constructor-injection caveat applies to the
        // newly added i18n dependencies, so force them in explicitly.
        ReflectionTestUtils.setField(circulationService, "userLocaleCache", userLocaleCache);
        ReflectionTestUtils.setField(circulationService, "messageSource", messageSource);
    }

    private static final Long DOCUMENT_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long ACTOR_ID = 99L;
    private static final String SCOPE_TYPE = "TEAM";

    private CirculationDocumentEntity buildDraft() {
        return CirculationDocumentEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                .title("テスト回覧").body("本文").build();
    }

    private CirculationDocumentEntity buildActive() {
        CirculationDocumentEntity e = buildDraft();
        e.activate();
        e.updateRecipientCount(3);
        return e;
    }

    private DocumentResponse mockResponse() {
        return DocumentResponse.builder()
                .id(DOCUMENT_ID).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).createdBy(USER_ID)
                .title("テスト回覧").body("本文").circulationMode("SIMULTANEOUS").sequentialCount(0)
                .status("ACTIVE").priority("NORMAL").stampDisplayStyle("STANDARD")
                .totalRecipientCount(3).stampedCount(0).attachmentCount(0).commentCount(0)
                .build();
    }

    // ─────────────────────────────────────────────
    // forceCompleteDocument
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("forceCompleteDocument")
    class ForceComplete {

        @Test
        @DisplayName("ACTIVE文書_強制完了_COMPLETEDに遷移して監査ログ発火")
        void ACTIVE文書_強制完了_正常() {
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(documentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockResponse());

            DocumentResponse result = circulationService.forceCompleteDocument(DOCUMENT_ID, ACTOR_ID);

            assertThat(result).isNotNull();
            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.COMPLETED);
            verify(auditLogService).record(
                    eq("CIRCULATION_FORCE_COMPLETED"), eq(ACTOR_ID), any(), any(), any(),
                    any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("DRAFT文書_強制完了不可_INVALID_DOCUMENT_STATUS")
        void DRAFT文書_強制完了不可() {
            CirculationDocumentEntity entity = buildDraft();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> circulationService.forceCompleteDocument(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_DOCUMENT_STATUS));
        }
    }

    // ─────────────────────────────────────────────
    // forceCompleteBatch
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("forceCompleteBatch")
    class ForceCompleteBatch {

        @Test
        @DisplayName("3件一括_全成功_succeeded3件_failed0件")
        void 一括強制完了_全成功() {
            CirculationDocumentEntity e1 = buildActive();
            CirculationDocumentEntity e2 = buildActive();
            CirculationDocumentEntity e3 = buildActive();
            given(documentRepository.findById(101L)).willReturn(Optional.of(e1));
            given(documentRepository.findById(102L)).willReturn(Optional.of(e2));
            given(documentRepository.findById(103L)).willReturn(Optional.of(e3));
            given(documentRepository.save(any())).willReturn(e1);
            given(circulationMapper.toDocumentResponse(any())).willReturn(mockResponse());

            ForceCompleteBatchResponse result = circulationService.forceCompleteBatch(
                    List.of(101L, 102L, 103L), ACTOR_ID);

            assertThat(result.getSucceeded()).hasSize(3);
            assertThat(result.getFailed()).isEmpty();
        }

        @Test
        @DisplayName("DRAFTを含む_部分成功_failed1件")
        void 一括強制完了_部分失敗() {
            CirculationDocumentEntity active = buildActive();
            CirculationDocumentEntity draft = buildDraft();
            given(documentRepository.findById(201L)).willReturn(Optional.of(active));
            given(documentRepository.findById(202L)).willReturn(Optional.of(draft));
            given(documentRepository.save(any())).willReturn(active);
            given(circulationMapper.toDocumentResponse(any())).willReturn(mockResponse());

            ForceCompleteBatchResponse result = circulationService.forceCompleteBatch(
                    List.of(201L, 202L), ACTOR_ID);

            assertThat(result.getSucceeded()).containsExactly(201L);
            assertThat(result.getFailed()).hasSize(1);
            assertThat(result.getFailed().get(0).getErrorCode()).isEqualTo("CIRCULATION_005");
        }

        @Test
        @DisplayName("空配列_EMPTY_BATCH例外")
        void 一括強制完了_空配列() {
            assertThatThrownBy(() -> circulationService.forceCompleteBatch(List.of(), ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.EMPTY_BATCH));
        }
    }

    // ─────────────────────────────────────────────
    // remindDocument
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("remindDocument")
    class Remind {

        @Test
        @DisplayName("ACTIVE文書_PENDING2件_remindedCount2件")
        void 手動リマインド_正常() {
            // Issue #2834 / CMP-056 横展開: remindDocument は業務TX内で受信者数（PENDING件数）を
            // カウントしてイベントを publish するだけに留め、通知の解決・組み立て・配送は
            // AFTER_COMMIT の CirculationReminderNotificationListener に委譲する。
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(recipientRepository.countByDocumentIdAndStatus(DOCUMENT_ID, RecipientStatus.PENDING))
                    .willReturn(2L);

            RemindResponse result = circulationService.remindDocument(DOCUMENT_ID, ACTOR_ID);

            assertThat(result.getRemindedCount()).isEqualTo(2);
            verify(applicationEventPublisher).publishEvent(
                    any(com.mannschaft.app.circulation.event.CirculationReminderNotificationEvent.class));
        }

        @Test
        @DisplayName("DRAFT文書_リマインド不可_INVALID_DOCUMENT_STATUS")
        void 手動リマインド_DRAFT不可() {
            CirculationDocumentEntity entity = buildDraft();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> circulationService.remindDocument(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_DOCUMENT_STATUS));
        }
    }

    // ─────────────────────────────────────────────
    // duplicateDocument
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("duplicateDocument")
    class Duplicate {

        @Test
        @DisplayName("複製_新規DRAFT作成_受信者コピー_タイトル末尾コピー付与")
        void 複製_正常() {
            CirculationDocumentEntity source = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(source));

            CirculationRecipientEntity r1 = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(40L).sortOrder(0).build();
            CirculationRecipientEntity r2 = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(41L).sortOrder(1).build();
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(List.of(r1, r2));

            CirculationDocumentEntity newEntity = buildDraft();
            given(documentRepository.save(any(CirculationDocumentEntity.class))).willReturn(newEntity);
            given(circulationMapper.toDocumentResponse(any())).willReturn(mockResponse());

            DocumentResponse result = circulationService.duplicateDocument(DOCUMENT_ID, ACTOR_ID);

            assertThat(result).isNotNull();
            verify(recipientRepository, times(2)).save(any(CirculationRecipientEntity.class));
        }

        @Test
        @DisplayName("複製_元文書なし_DOCUMENT_NOT_FOUND")
        void 複製_元文書なし() {
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> circulationService.duplicateDocument(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }
    }

    // ─────────────────────────────────────────────
    // getDocumentStatus
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("getDocumentStatus")
    class Status {

        @Test
        @DisplayName("受信者2件_押印状況返却_sortOrder保持")
        void 押印状況一覧_正常() {
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));

            CirculationRecipientEntity r1 = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(50L).sortOrder(0).build();
            CirculationRecipientEntity r2 = CirculationRecipientEntity.builder()
                    .documentId(DOCUMENT_ID).userId(51L).sortOrder(1).build();
            given(recipientRepository.findByDocumentIdOrderBySortOrderAsc(DOCUMENT_ID))
                    .willReturn(List.of(r1, r2));
            given(userRepository.findMemberSummaryById(50L)).willReturn(Optional.empty());
            given(userRepository.findMemberSummaryById(51L)).willReturn(Optional.empty());

            DocumentStatusResponse result = circulationService.getDocumentStatus(DOCUMENT_ID, ACTOR_ID);

            assertThat(result.getDocumentId()).isEqualTo(DOCUMENT_ID);
            assertThat(result.getDocumentStatus()).isEqualTo("ACTIVE");
            assertThat(result.getRecipients()).hasSize(2);
            assertThat(result.getRecipients().get(0).getUserId()).isEqualTo(50L);
            assertThat(result.getRecipients().get(0).getStampStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("文書なし_DOCUMENT_NOT_FOUND")
        void 押印状況一覧_文書なし() {
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> circulationService.getDocumentStatus(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }
    }

    // ─────────────────────────────────────────────
    // per-scope 認可（2026-05-29 fixup）
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("per-scope 認可")
    class ScopeAuthorization {

        @Test
        @DisplayName("非管理者は強制完了が COMMON_002 で遮断され COMPLETED に遷移しない")
        void 非管理者_強制完了遮断() {
            CirculationDocumentEntity entity = buildActive(); // scope=TEAM/1L
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> circulationService.forceCompleteDocument(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.ACTIVE);
            verify(documentRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("当該スコープの管理者は強制完了を通過")
        void 管理者_強制完了通過() {
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
            // checkAdminOrAbove は void mock = 通過
            given(documentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockResponse());

            circulationService.forceCompleteDocument(DOCUMENT_ID, ACTOR_ID);

            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.COMPLETED);
            verify(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, "TEAM");
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は per-scope チェックを短絡して通過")
        void SYSTEM_ADMIN短絡() {
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(documentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toDocumentResponse(entity)).willReturn(mockResponse());

            circulationService.forceCompleteDocument(DOCUMENT_ID, ACTOR_ID);

            assertThat(entity.getStatus()).isEqualTo(CirculationStatus.COMPLETED);
            verify(accessControlService, org.mockito.Mockito.never())
                    .checkAdminOrAbove(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("getDocumentStatus も非管理者を COMMON_002 で遮断")
        void getStatus_非管理者遮断() {
            CirculationDocumentEntity entity = buildActive();
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ACTOR_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> circulationService.getDocumentStatus(DOCUMENT_ID, ACTOR_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    // ─────────────────────────────────────────────
    // 文書一覧の所属ゲート（F00 漏洩根治）
    //
    // GET /api/v1/teams/{teamId}/circulations（org 版含む）が認可ゲート皆無で、
    // 認証済みなら非会員でも他チームの回覧タイトル/作成者/押印数を列挙できる
    // F00 漏洩を根治する。listDocuments の冒頭で
    // accessControlService.checkMembershipOrDescendant(..., includeSupporters=true)
    // を通し、非所属（COMMON_002）を弾く。
    // ─────────────────────────────────────────────

    @Nested
    @DisplayName("listDocuments の所属ゲート")
    class ListDocumentsAuthorization {

        @Test
        @DisplayName("AC-1: 非所属ユーザーは一覧取得が COMMON_002 で遮断される（文書は引かれない）")
        void 一覧_非所属は弾かれる() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                // 非所属は所属ゲートで COMMON_002
                doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                        .when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq("TEAM"), eq(true));

                assertThatThrownBy(() -> circulationService.listDocuments(
                        "TEAM", SCOPE_ID, null, PageRequest.of(0, 10)))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                                .isEqualTo(CommonErrorCode.COMMON_002));

                // ゲートで弾かれるため文書取得には到達しない
                verify(documentRepository, org.mockito.Mockito.never())
                        .findByScopeTypeAndScopeIdOrderByCreatedAtDesc(anyString(), anyLong(), any());
            }
        }

        @Test
        @DisplayName("AC-2: 所属者は通過し、ゲートは includeSupporters=true で呼ばれる")
        void 一覧_所属者は通過() {
            try (MockedStatic<SecurityUtils> securityUtils = mockStatic(SecurityUtils.class)) {
                securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                // 所属者は no-op（通過）
                doNothing().when(accessControlService)
                        .checkMembershipOrDescendant(anyLong(), eq(SCOPE_ID), eq("TEAM"), eq(true));

                CirculationDocumentEntity entity = buildActive();
                Page<CirculationDocumentEntity> page = new PageImpl<>(List.of(entity));
                given(documentRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                        eq("TEAM"), eq(SCOPE_ID), any())).willReturn(page);
                given(circulationMapper.toDocumentResponse(entity)).willReturn(mockResponse());
                // userRepository は createdByName 充填で呼ばれる（解決不要なら empty）
                given(userRepository.findMemberSummaryById(anyLong())).willReturn(Optional.empty());

                Page<DocumentResponse> result = circulationService.listDocuments(
                        "TEAM", SCOPE_ID, null, PageRequest.of(0, 10));

                assertThat(result.getContent()).hasSize(1);
                // 応援者も許可する includeSupporters=true で呼ばれること
                verify(accessControlService)
                        .checkMembershipOrDescendant(USER_ID, SCOPE_ID, "TEAM", true);
            }
        }
    }
}
