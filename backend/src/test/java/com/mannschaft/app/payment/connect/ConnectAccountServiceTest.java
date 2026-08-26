package com.mannschaft.app.payment.connect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.payment.connect.dto.ConnectStatusResponse;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkRequest;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkResponse;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * Connect onboarding / 状態照会サービスの単体テスト。
 *
 * <p>T3 USER 他人指定拒否（本人固定）/ T4 TEAM/ORG 非権限 403 / T5 status IDOR 404 /
 * T7 connect_account scope 所有権 を検証する。Stripe 実通信は {@link StripePaymentProvider}
 * モックで遮断する（IF 越し）。認可は {@link AccessControlService} モックで判定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectAccountService 単体テスト（T3/T4/T5/T7）")
class ConnectAccountServiceTest {

    @Mock private ConnectAccountRepository connectAccountRepository;
    @Mock private StripePaymentProvider stripePaymentProvider;
    @Mock private AccessControlService accessControlService;
    @Mock private com.mannschaft.app.payment.escrow.EscrowTransactionRepository escrowTransactionRepository;
    @Mock private com.mannschaft.app.payment.escrow.EscrowLifecycleService escrowLifecycleService;

    private ConnectAccountService service;

    private static final Long ME = 100L;

    @BeforeEach
    void setUp() {
        PayeeScopeResolver resolver = new PayeeScopeResolver();
        ObjectMapper objectMapper = new ObjectMapper();
        service = new ConnectAccountService(
                connectAccountRepository, stripePaymentProvider, accessControlService,
                resolver, objectMapper, escrowTransactionRepository, escrowLifecycleService);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(ME), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ConnectAccountEntity account(ScopeKind kind, Long scopeId) {
        return ConnectAccountEntity.builder()
                .scopeKind(kind)
                .scopeId(scopeId)
                .stripeAccountId("acct_existing")
                .onboardingStatus(OnboardingStatus.ONBOARDING)
                .chargesEnabled(false)
                .payoutsEnabled(false)
                .country("JP")
                .defaultCurrency("JPY")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("T3: USER で他人の scopeId を指定しても本人(ME)に固定される")
    void t3_userScopeIdIgnoredFixedToSelf() {
        // 他人(999)の userId を指定しても、解決される scope は本人(ME)でなければならない
        OnboardingLinkRequest request = new OnboardingLinkRequest(
                ScopeKind.USER, 999L, "https://app/return", "https://app/refresh");

        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, ME))
                .willReturn(Optional.empty());
        given(stripePaymentProvider.createConnectAccount("JP", ScopeKind.USER, ME))
                .willReturn("acct_new");
        given(connectAccountRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(stripePaymentProvider.createAccountLink(eq("acct_new"), any(), any()))
                .willReturn(new StripePaymentProvider.AccountLinkInfo(
                        "https://connect.stripe.com/setup/x", LocalDateTime.now().plusHours(1)));

        OnboardingLinkResponse response = service.createOnboardingLink(request);

        // 本人 ME で口座を探し、本人 ME で Stripe アカウント作成（他人 999 は使われない）
        verify(connectAccountRepository).findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, ME);
        verify(stripePaymentProvider).createConnectAccount("JP", ScopeKind.USER, ME);
        verify(stripePaymentProvider, never()).createConnectAccount("JP", ScopeKind.USER, 999L);
        assertThat(response.onboardingUrl()).isEqualTo("https://connect.stripe.com/setup/x");
        // PCI 禁則の最低限: acct は本人専用レスポンスのみで返る（status は acct を返さない・別テスト）
        assertThat(response.stripeAccountId()).isEqualTo("acct_new");
    }

    @Test
    @DisplayName("T4: TEAM 非権限 → 403（checkPermission 例外を伝播）")
    void t4_teamNonAdminForbidden() {
        OnboardingLinkRequest request = new OnboardingLinkRequest(
                ScopeKind.TEAM, 555L, "https://app/return", "https://app/refresh");
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService)
                .checkPermission(eq(ME), eq(555L), eq("TEAM"), any());

        assertThatThrownBy(() -> service.createOnboardingLink(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(stripePaymentProvider, never()).createConnectAccount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("T4: ORG 非権限 → 403（checkAdminOrHasPermission 例外を伝播）")
    void t4_orgNonAdminForbidden() {
        OnboardingLinkRequest request = new OnboardingLinkRequest(
                ScopeKind.ORG, 777L, "https://app/return", "https://app/refresh");
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService)
                .checkAdminOrHasPermission(eq(ME), eq(777L), eq("ORGANIZATION"), any());

        assertThatThrownBy(() -> service.createOnboardingLink(request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(stripePaymentProvider, never()).createConnectAccount(any(), any(), anyLong());
    }

    @Test
    @DisplayName("T5: status 照会で認可は通るが口座未作成 → 404 秘匿（IDOR）")
    void t5_statusNotFoundHidden() {
        // TEAM 555 の権限はあるが、まだ口座を作っていない（無関係 scope と同じ扱い＝404）
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.TEAM, 555L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getStatus(ScopeKind.TEAM, 555L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("T5: 無関係 scope（非権限）の status 要求 → 認可で 403（存在を漏らさない）")
    void t5_statusForbiddenForUnrelatedScope() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService)
                .checkPermission(eq(ME), eq(888L), eq("TEAM"), any());

        assertThatThrownBy(() -> service.getStatus(ScopeKind.TEAM, 888L))
                .isInstanceOf(BusinessException.class);
        // 認可で弾くため口座検索に到達しない（他主体の acct を露出しない）
        verify(connectAccountRepository, never())
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(eq(ScopeKind.TEAM), eq(888L));
    }

    @Test
    @DisplayName("T7: USER status は本人(ME)の口座のみ返す（他人 scopeId は本人へ固定）")
    void t7_userStatusOwnAccountOnly() {
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, ME))
                .willReturn(Optional.of(account(ScopeKind.USER, ME)));

        // 他人(999)の userId を要求しても本人(ME)の口座が照合される
        ConnectStatusResponse response = service.getStatus(ScopeKind.USER, 999L);

        verify(connectAccountRepository).findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, ME);
        verify(connectAccountRepository, never())
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, 999L);
        assertThat(response.scopeId()).isEqualTo(ME);
        assertThat(response.scopeKind()).isEqualTo(ScopeKind.USER);
    }

    @Test
    @DisplayName("既存 acct 流用時は onboarding を ONBOARDING に戻し新規作成しない")
    void existingAccountReused() {
        OnboardingLinkRequest request = new OnboardingLinkRequest(
                ScopeKind.USER, null, "https://app/return", "https://app/refresh");
        ConnectAccountEntity existing = account(ScopeKind.USER, ME);
        existing.setOnboardingStatus(OnboardingStatus.RESTRICTED);
        given(connectAccountRepository.findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.USER, ME))
                .willReturn(Optional.of(existing));
        given(connectAccountRepository.save(any())).willAnswer(i -> i.getArgument(0));
        given(stripePaymentProvider.createAccountLink(eq("acct_existing"), any(), any()))
                .willReturn(new StripePaymentProvider.AccountLinkInfo(
                        "https://connect.stripe.com/setup/y", LocalDateTime.now().plusHours(1)));

        OnboardingLinkResponse response = service.createOnboardingLink(request);

        verify(stripePaymentProvider, never()).createConnectAccount(any(), any(), anyLong());
        assertThat(response.onboardingStatus()).isEqualTo(OnboardingStatus.ONBOARDING);
        assertThat(response.stripeAccountId()).isEqualTo("acct_existing");
    }

    // ── F22.1 第三陣: account.updated による HELD 昇格 ──

    @Test
    @DisplayName("第三陣: payouts_enabled false→true で HELD escrow を各件昇格へ委譲する")
    void accountUpdated_promotesHeldEscrowsOnPayoutsEnabled() {
        ConnectAccountEntity acct = account(ScopeKind.TEAM, 42L);
        acct.setPayoutsEnabled(false); // 旧値 false
        UUID acctId = UUID.fromString("019607a0-0000-7000-8000-0000000000c1");
        acct.setId(acctId);
        given(connectAccountRepository.findByStripeAccountId("acct_existing"))
                .willReturn(Optional.of(acct));
        given(connectAccountRepository.save(any())).willAnswer(i -> i.getArgument(0));

        UUID e1 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        var held1 = heldEscrow(e1, acctId);
        var held2 = heldEscrow(e2, acctId);
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatus(
                eq(acctId), eq(com.mannschaft.app.payment.escrow.EscrowStatus.HELD)))
                .willReturn(List.of(held1, held2));

        service.applyAccountUpdated("acct_existing", true, true, List.of());

        // 鏡像更新は壊さず（payouts_enabled=true 反映）、HELD escrow を各件昇格へ委譲する。
        assertThat(acct.getPayoutsEnabled()).isTrue();
        verify(escrowLifecycleService).promoteHeldEscrow(e1);
        verify(escrowLifecycleService).promoteHeldEscrow(e2);
    }

    @Test
    @DisplayName("第三陣: payouts_enabled が変化しない（true→true）と昇格しない")
    void accountUpdated_noPromotionWhenPayoutsUnchanged() {
        ConnectAccountEntity acct = account(ScopeKind.TEAM, 42L);
        acct.setPayoutsEnabled(true); // 旧値 true
        given(connectAccountRepository.findByStripeAccountId("acct_existing"))
                .willReturn(Optional.of(acct));
        given(connectAccountRepository.save(any())).willAnswer(i -> i.getArgument(0));

        service.applyAccountUpdated("acct_existing", true, true, List.of());

        verify(escrowTransactionRepository, never())
                .findByPayeeConnectAccountIdAndStatus(any(), any());
        verify(escrowLifecycleService, never()).promoteHeldEscrow(any());
    }

    @Test
    @DisplayName("第三陣: 1 件の昇格失敗でも他件の昇格は継続する（個別失敗分離）")
    void accountUpdated_promotionFailureDoesNotStopOthers() {
        ConnectAccountEntity acct = account(ScopeKind.TEAM, 42L);
        acct.setPayoutsEnabled(false);
        UUID acctId = UUID.fromString("019607a0-0000-7000-8000-0000000000c2");
        acct.setId(acctId);
        given(connectAccountRepository.findByStripeAccountId("acct_existing"))
                .willReturn(Optional.of(acct));
        given(connectAccountRepository.save(any())).willAnswer(i -> i.getArgument(0));

        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        given(escrowTransactionRepository.findByPayeeConnectAccountIdAndStatus(
                eq(acctId), eq(com.mannschaft.app.payment.escrow.EscrowStatus.HELD)))
                .willReturn(List.of(heldEscrow(bad, acctId), heldEscrow(good, acctId)));
        doThrow(new RuntimeException("stripe down"))
                .when(escrowLifecycleService).promoteHeldEscrow(bad);

        service.applyAccountUpdated("acct_existing", true, true, List.of());

        // bad で例外でも good は昇格される（握りつぶさず継続）。
        verify(escrowLifecycleService).promoteHeldEscrow(good);
    }

    private com.mannschaft.app.payment.escrow.EscrowTransactionEntity heldEscrow(UUID id, UUID payeeAccountId) {
        var e = com.mannschaft.app.payment.escrow.EscrowTransactionEntity.builder()
                .sourceKind(com.mannschaft.app.payment.escrow.EscrowSourceKind.RECRUITMENT)
                .sourceId(1L).sourceParticipantId(2L)
                .captureMode(com.mannschaft.app.payment.escrow.EscrowCaptureMode.MANUAL)
                .payerScopeKind(ScopeKind.USER).payerScopeId(9L)
                .payeeKind(ScopeKind.TEAM).payeeConnectAccountId(payeeAccountId)
                .faceAmount(10_000L).amount(10_250L).applicationFeeAmount(500L)
                .currency("JPY").status(com.mannschaft.app.payment.escrow.EscrowStatus.HELD)
                .build();
        e.setId(id);
        return e;
    }
}
