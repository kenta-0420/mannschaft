package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.FailedEventResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftBudgetFailedEventService} 単体テスト（Phase 10-β）。
 *
 * <p>カバレッジ:</p>
 * <ul>
 *   <li>recordFailure: PENDING で永続化される</li>
 *   <li>retry: BUDGET_ADMIN 成功 + executor 呼出 + 監査ログ</li>
 *   <li>retry: 終端ステータス (SUCCEEDED/MANUAL_RESOLVED) → 409</li>
 *   <li>retry: 別組織 → 404 (IDOR)</li>
 *   <li>markManualResolved: ステータス遷移 + 監査ログ</li>
 *   <li>list: status 絞り込み有 / 無</li>
 *   <li>list: BUDGET_VIEW 権限なし → 403</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftBudgetFailedEventService 単体テスト")
class ShiftBudgetFailedEventServiceTest {

    private static final Long ORG_ID = 1L;
    private static final Long OTHER_ORG_ID = 99L;
    private static final Long EVENT_ID = 50L;
    private static final Long USER_ID = 100L;

    @Mock
    private ShiftBudgetFailedEventRepository repository;
    @Mock
    private ShiftBudgetFeatureService featureService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ShiftBudgetRetryExecutor retryExecutor;

    private ShiftBudgetFailedEventService service;

    @BeforeEach
    void setUp() {
        service = new ShiftBudgetFailedEventService(
                repository, featureService, accessControlService, auditLogService,
                retryExecutor, new ObjectMapper());

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ShiftBudgetFailedEventEntity sample(ShiftBudgetFailedEventStatus status) {
        return ShiftBudgetFailedEventEntity.builder()
                .organizationId(ORG_ID)
                .eventType(ShiftBudgetFailedEventType.THRESHOLD_ALERT)
                .sourceId(42L)
                .payload("{}")
                .errorMessage("Test error")
                .retryCount(0)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("recordFailure: PENDING で永続化される")
    void recordFailure_正常系() {
        given(repository.save(any(ShiftBudgetFailedEventEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        ShiftBudgetFailedEventEntity result = service.recordFailure(
                ORG_ID,
                ShiftBudgetFailedEventType.THRESHOLD_ALERT,
                42L,
                Map.of("allocation_id", 42L),
                "RuntimeException: test");

        assertThat(result.getStatus()).isEqualTo(ShiftBudgetFailedEventStatus.PENDING);
        assertThat(result.getRetryCount()).isZero();
        assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(result.getEventType()).isEqualTo(ShiftBudgetFailedEventType.THRESHOLD_ALERT);
        assertThat(result.getPayload()).contains("allocation_id");
    }

    @Test
    @DisplayName("retry: BUDGET_ADMIN 成功で executor 呼出 + 監査ログ FAILED_EVENT_RETRIED")
    void retry_正常系() {
        ShiftBudgetFailedEventEntity entity = sample(ShiftBudgetFailedEventStatus.PENDING);
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID)).willReturn(Optional.of(entity));
        given(retryExecutor.execute(entity)).willReturn(true);

        FailedEventResponse result = service.retry(ORG_ID, EVENT_ID);

        assertThat(result).isNotNull();
        verify(retryExecutor).execute(entity);
        verify(auditLogService).record(eq("FAILED_EVENT_RETRIED"), eq(USER_ID),
                any(), any(), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("retry: SUCCEEDED 終端ステータス → FAILED_EVENT_NOT_RETRIABLE (409)")
    void retry_SUCCEEDEDは再実行不可() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID))
                .willReturn(Optional.of(sample(ShiftBudgetFailedEventStatus.SUCCEEDED)));

        assertThatThrownBy(() -> service.retry(ORG_ID, EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.FAILED_EVENT_NOT_RETRIABLE.getCode()));

        verify(retryExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("retry: MANUAL_RESOLVED 終端 → FAILED_EVENT_NOT_RETRIABLE (409)")
    void retry_MANUAL_RESOLVEDは再実行不可() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID))
                .willReturn(Optional.of(sample(ShiftBudgetFailedEventStatus.MANUAL_RESOLVED)));

        assertThatThrownBy(() -> service.retry(ORG_ID, EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.FAILED_EVENT_NOT_RETRIABLE.getCode()));

        verify(retryExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("retry: EXHAUSTED は運用判断で再実行可能（FAILED_EVENT_NOT_RETRIABLE を投げない）")
    void retry_EXHAUSTEDは再実行可能() {
        ShiftBudgetFailedEventEntity entity = sample(ShiftBudgetFailedEventStatus.EXHAUSTED);
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID)).willReturn(Optional.of(entity));
        given(retryExecutor.execute(entity)).willReturn(true);

        FailedEventResponse result = service.retry(ORG_ID, EVENT_ID);

        assertThat(result).isNotNull();
        verify(retryExecutor).execute(entity);
    }

    @Test
    @DisplayName("retry: 別組織の event ID → FAILED_EVENT_NOT_FOUND (IDOR 対策で 404)")
    void retry_別組織は404() {
        ShiftBudgetFailedEventEntity entity = ShiftBudgetFailedEventEntity.builder()
                .organizationId(OTHER_ORG_ID)  // 別組織
                .eventType(ShiftBudgetFailedEventType.THRESHOLD_ALERT)
                .payload("{}").retryCount(0)
                .status(ShiftBudgetFailedEventStatus.PENDING).build();
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.retry(ORG_ID, EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.FAILED_EVENT_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("retry: BUDGET_ADMIN 権限なし → BUDGET_ADMIN_REQUIRED (403)")
    void retry_権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_ADMIN");

        assertThatThrownBy(() -> service.retry(ORG_ID, EVENT_ID))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED.getCode()));

        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("markManualResolved: MANUAL_RESOLVED へ遷移 + 監査ログ FAILED_EVENT_RESOLVED")
    void markManualResolved_正常系() {
        ShiftBudgetFailedEventEntity entity = sample(ShiftBudgetFailedEventStatus.EXHAUSTED);
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findById(EVENT_ID)).willReturn(Optional.of(entity));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        FailedEventResponse result = service.markManualResolved(ORG_ID, EVENT_ID);

        assertThat(result.status()).isEqualTo(ShiftBudgetFailedEventStatus.MANUAL_RESOLVED.name());
        verify(auditLogService).record(eq("FAILED_EVENT_RESOLVED"), eq(USER_ID),
                any(), any(), eq(ORG_ID), any(), any(), any(), any());
    }

    @Test
    @DisplayName("list: status=null で全件取得 + BUDGET_VIEW 権限チェック")
    void list_status未指定で全件() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findByOrganizationId(eq(ORG_ID), any(Pageable.class)))
                .willReturn(List.of(sample(ShiftBudgetFailedEventStatus.PENDING),
                        sample(ShiftBudgetFailedEventStatus.EXHAUSTED)));

        List<FailedEventResponse> result = service.list(ORG_ID, null, 0, 20);

        assertThat(result).hasSize(2);
        verify(repository, times(1)).findByOrganizationId(eq(ORG_ID), any(Pageable.class));
        verify(repository, never()).findByOrganizationIdAndStatus(any(), any(), any());
    }

    @Test
    @DisplayName("list: status 指定で絞り込み取得")
    void list_status指定() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(repository.findByOrganizationIdAndStatus(eq(ORG_ID),
                eq(ShiftBudgetFailedEventStatus.EXHAUSTED), any(Pageable.class)))
                .willReturn(List.of(sample(ShiftBudgetFailedEventStatus.EXHAUSTED)));

        List<FailedEventResponse> result = service.list(
                ORG_ID, ShiftBudgetFailedEventStatus.EXHAUSTED, 0, 20);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("EXHAUSTED");
    }

    @Test
    @DisplayName("list: BUDGET_VIEW 権限なし → BUDGET_VIEW_REQUIRED (403)")
    void list_権限なし() {
        given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
        given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", "BUDGET_VIEW");

        assertThatThrownBy(() -> service.list(ORG_ID, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode().getCode())
                        .isEqualTo(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED.getCode()));
    }

    @Test
    @DisplayName("recordFailure: error_message 4000 文字超は切り詰め")
    void recordFailure_長すぎるエラーメッセージは切詰め() {
        given(repository.save(any(ShiftBudgetFailedEventEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        String longMsg = "x".repeat(5000);
        ArgumentCaptor<ShiftBudgetFailedEventEntity> cap =
                ArgumentCaptor.forClass(ShiftBudgetFailedEventEntity.class);
        service.recordFailure(ORG_ID, ShiftBudgetFailedEventType.THRESHOLD_ALERT, 1L,
                Map.of(), longMsg);

        verify(repository).save(cap.capture());
        assertThat(cap.getValue().getErrorMessage()).hasSize(4000);
    }
}
