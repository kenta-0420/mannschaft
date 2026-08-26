package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.service.CirculationAccessGuard;
import com.mannschaft.app.circulation.dto.AdminSkipRecipientRequest;
import com.mannschaft.app.circulation.dto.RecipientResponse;
import com.mannschaft.app.circulation.dto.StampCorrectionRequest;
import com.mannschaft.app.circulation.dto.StampDelegationRequest;
import com.mannschaft.app.circulation.dto.StampDelegationResponse;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.entity.CirculationRecipientEntity;
import com.mannschaft.app.circulation.entity.CirculationStampCorrectionLogEntity;
import com.mannschaft.app.circulation.entity.CirculationStampDelegationEntity;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.circulation.repository.CirculationStampCorrectionLogRepository;
import com.mannschaft.app.circulation.repository.CirculationStampDelegationRepository;
import com.mannschaft.app.circulation.service.CirculationStampService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F05.2 Phase 11 第三陣 3-B の追加メソッド
 * （{@code correctStamp} / {@code delegateStamp} / {@code adminSkipRecipient}）の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationStampService 3-B 拡張単体テスト")
class CirculationStampService3BTest {

    @Mock private CirculationDocumentRepository documentRepository;
    @Mock private CirculationRecipientRepository recipientRepository;
    @Mock private CirculationMapper circulationMapper;
    @Mock private ProxyInputContext proxyInputContext;
    @Mock private ProxyInputRecordRepository proxyInputRecordRepository;
    @Mock private CirculationStampCorrectionLogRepository correctionLogRepository;
    @Mock private CirculationStampDelegationRepository delegationRepository;
    @Mock private AccessControlService accessControlService;
    @Mock private CirculationAccessGuard circulationAccessGuard;

    @InjectMocks
    private CirculationStampService service;

    private static final Long DOC_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long ADMIN_ID = 999L;
    private static final Long DELEGATEE_ID = 20L;

    @BeforeEach
    void wireRepositories() {
        // @Autowired(required=false) のフィールドは InjectMocks でも対象になるが
        // 名前一致しない場合に備えて明示注入。
        ReflectionTestUtils.setField(service, "correctionLogRepository", correctionLogRepository);
        ReflectionTestUtils.setField(service, "delegationRepository", delegationRepository);
    }

    private CirculationDocumentEntity activeDoc() {
        CirculationDocumentEntity doc = CirculationDocumentEntity.builder()
                .scopeType("TEAM").scopeId(1L).createdBy(1L)
                .title("title").body("body").build();
        doc.activate();
        doc.updateRecipientCount(3);
        return doc;
    }

    private CirculationRecipientEntity pending() {
        return CirculationRecipientEntity.builder()
                .documentId(DOC_ID).userId(USER_ID).sortOrder(0).build();
    }

    @Nested
    @DisplayName("correctStamp")
    class CorrectStamp {

        @Test
        @DisplayName("正常: STAMPED→PENDING に戻り訂正ログが保存される")
        void 正常_STAMPED_to_PENDING() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            rec.stamp(50L, "CIRCLE", (short) 5, false);
            doc.incrementStampedCount();

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(recipientRepository.save(rec)).willReturn(rec);
            given(circulationMapper.toRecipientResponse(rec))
                    .willReturn(new RecipientResponse(1L, DOC_ID, USER_ID, 0, "PENDING",
                            null, null, null, (short) 0, false, null, null));

            service.correctStamp(DOC_ID, USER_ID, new StampCorrectionRequest("理由"));

            assertThat(rec.getStatus()).isEqualTo(RecipientStatus.PENDING);
            assertThat(rec.getStampedAt()).isNull();
            assertThat(doc.getStampedCount()).isZero();
        }

        @Test
        @DisplayName("異常: 未押印で訂正不可")
        void 異常_未押印で訂正不可() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));

            assertThatThrownBy(() -> service.correctStamp(DOC_ID, USER_ID, new StampCorrectionRequest(null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.NOT_STAMPED_CANNOT_CORRECT));
        }

        @Test
        @DisplayName("異常: 押印後24h超過で訂正不可")
        void 異常_24h超過() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            rec.stamp(50L, "CIRCLE", (short) 0, false);
            // stampedAt を 25 時間前にずらす
            ReflectionTestUtils.setField(rec, "stampedAt", LocalDateTime.now().minusHours(25));
            doc.incrementStampedCount();

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));

            assertThatThrownBy(() -> service.correctStamp(DOC_ID, USER_ID, new StampCorrectionRequest(null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.CORRECTION_WINDOW_EXPIRED));
        }
    }

    @Nested
    @DisplayName("delegateStamp")
    class DelegateStamp {

        @Test
        @DisplayName("正常: 委任を新規作成")
        void 正常_委任新規作成() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(delegationRepository.findByDocumentIdAndDelegatorUserId(DOC_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(delegationRepository.save(any(CirculationStampDelegationEntity.class)))
                    .willAnswer(inv -> {
                        CirculationStampDelegationEntity e = inv.getArgument(0);
                        e.setId(java.util.UUID.randomUUID());
                        ReflectionTestUtils.setField(e, "createdAt", LocalDateTime.now());
                        return e;
                    });

            StampDelegationResponse resp = service.delegateStamp(DOC_ID, USER_ID,
                    new StampDelegationRequest(DELEGATEE_ID, "出張のため"));

            assertThat(resp.delegateeUserId()).isEqualTo(DELEGATEE_ID);
            assertThat(resp.status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("異常: 自分自身への委任は拒否")
        void 異常_自分自身への委任() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));

            assertThatThrownBy(() -> service.delegateStamp(DOC_ID, USER_ID,
                    new StampDelegationRequest(USER_ID, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.SELF_DELEGATION_NOT_ALLOWED));
        }

        @Test
        @DisplayName("異常: 既に有効な委任が存在")
        void 異常_重複委任() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            CirculationStampDelegationEntity existing = CirculationStampDelegationEntity.builder()
                    .documentId(DOC_ID).delegatorUserId(USER_ID).delegateeUserId(DELEGATEE_ID)
                    .build();

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(delegationRepository.findByDocumentIdAndDelegatorUserId(DOC_ID, USER_ID))
                    .willReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.delegateStamp(DOC_ID, USER_ID,
                    new StampDelegationRequest(DELEGATEE_ID, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DELEGATION_ALREADY_EXISTS));
        }
    }

    @Nested
    @DisplayName("adminSkipRecipient")
    class AdminSkipRecipient {

        @Test
        @DisplayName("正常: 受信者を SKIPPED に変更 + 監査メタ付与")
        void 正常_SKIPPED遷移() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(recipientRepository.save(rec)).willReturn(rec);
            given(recipientRepository.countByDocumentIdAndStatus(DOC_ID, RecipientStatus.PENDING)).willReturn(1L);
            given(circulationMapper.toRecipientResponse(rec))
                    .willReturn(new RecipientResponse(1L, DOC_ID, USER_ID, 0, "SKIPPED",
                            null, null, null, (short) 0, false, null, null));

            service.adminSkipRecipient(DOC_ID, USER_ID, ADMIN_ID,
                    new AdminSkipRecipientRequest("退職予定のためスキップ"));

            assertThat(rec.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            assertThat(rec.getSkipReason()).isEqualTo("退職予定のためスキップ");
            assertThat(rec.getSkippedBy()).isEqualTo(ADMIN_ID);
        }

        @Test
        @DisplayName("異常: 押印済み受信者は強制スキップ不可")
        void 異常_押印済みはスキップ不可() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            rec.stamp(50L, "CIRCLE", (short) 0, false);

            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));

            assertThatThrownBy(() -> service.adminSkipRecipient(DOC_ID, USER_ID, ADMIN_ID,
                    new AdminSkipRecipientRequest("理由")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.INVALID_RECIPIENT_STATUS));
        }

        @Test
        @DisplayName("認可: 非管理者は COMMON_002 で遮断され受信者は変更されない")
        void 認可_非管理者は遮断() {
            CirculationDocumentEntity doc = activeDoc(); // scope=TEAM/1L
            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(false);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(ADMIN_ID, 1L, "TEAM");

            assertThatThrownBy(() -> service.adminSkipRecipient(DOC_ID, USER_ID, ADMIN_ID,
                    new AdminSkipRecipientRequest("理由")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            // 認可で弾かれるため受信者状態の更新は行われない
            verify(recipientRepository, never()).save(any());
        }

        @Test
        @DisplayName("認可: SYSTEM_ADMIN は per-scope チェックを短絡して通過")
        void 認可_SYSTEM_ADMIN短絡() {
            CirculationDocumentEntity doc = activeDoc();
            CirculationRecipientEntity rec = pending();
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(true);
            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(recipientRepository.save(rec)).willReturn(rec);
            given(recipientRepository.countByDocumentIdAndStatus(DOC_ID, RecipientStatus.PENDING)).willReturn(1L);
            given(circulationMapper.toRecipientResponse(rec))
                    .willReturn(new RecipientResponse(1L, DOC_ID, USER_ID, 0, "SKIPPED",
                            null, null, null, (short) 0, false, null, null));

            service.adminSkipRecipient(DOC_ID, USER_ID, ADMIN_ID,
                    new AdminSkipRecipientRequest("理由"));

            assertThat(rec.getStatus()).isEqualTo(RecipientStatus.SKIPPED);
            // SYSTEM_ADMIN は per-scope チェックを呼ばない
            verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), eq("TEAM"));
        }

        @Test
        @DisplayName("認可: ORGANIZATION スコープでは org の管理者として per-scope チェックされる")
        void 認可_組織スコープ() {
            CirculationDocumentEntity doc = CirculationDocumentEntity.builder()
                    .scopeType("ORGANIZATION").scopeId(77L).createdBy(1L)
                    .title("t").body("b").build();
            doc.activate();
            doc.updateRecipientCount(3);
            CirculationRecipientEntity rec = pending();
            given(accessControlService.isSystemAdmin(ADMIN_ID)).willReturn(false);
            given(documentRepository.findById(DOC_ID)).willReturn(Optional.of(doc));
            given(recipientRepository.findByDocumentIdAndUserId(DOC_ID, USER_ID)).willReturn(Optional.of(rec));
            given(recipientRepository.save(rec)).willReturn(rec);
            given(recipientRepository.countByDocumentIdAndStatus(DOC_ID, RecipientStatus.PENDING)).willReturn(1L);
            given(circulationMapper.toRecipientResponse(rec))
                    .willReturn(new RecipientResponse(1L, DOC_ID, USER_ID, 0, "SKIPPED",
                            null, null, null, (short) 0, false, null, null));

            service.adminSkipRecipient(DOC_ID, USER_ID, ADMIN_ID,
                    new AdminSkipRecipientRequest("理由"));

            verify(accessControlService).checkAdminOrAbove(ADMIN_ID, 77L, "ORGANIZATION");
        }
    }
}
