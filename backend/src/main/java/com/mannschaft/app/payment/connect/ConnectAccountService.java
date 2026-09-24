package com.mannschaft.app.payment.connect;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.payment.connect.event.ConnectPayoutsEnabledEvent;
import com.mannschaft.app.payment.connect.dto.ConnectStatusResponse;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkRequest;
import com.mannschaft.app.payment.connect.dto.OnboardingLinkResponse;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * F22.1 謝礼決済: Connect onboarding / 状態照会サービス。
 *
 * <p>認可は本サービス層で {@link AccessControlService} を用いて行う（設計書 03 §3 マトリクス厳守）:
 * USER は {@link SecurityUtils#getCurrentUserId()} 本人固定（scopeId 無視）、
 * TEAM は {@code checkPermission(...,"TEAM",...)}、ORG は {@code checkAdminOrHasPermission(...,"ORGANIZATION",...)}。
 * IDOR は scope 所有権照合で防ぎ、不一致は 404 秘匿する（03 §4）。</p>
 *
 * <p>本 Phase（P2-a）の範囲は onboarding-link 発行・status 取得・account.updated 鏡像更新まで。
 * 与信（P2-b）・払出/返金（P2-c）は範囲外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConnectAccountService {

    /**
     * TEAM/ORG scope の管理者判定に用いる権限名（札の謝礼設定と同等の管理権限）。
     *
     * <p>本権限は {@code V183.20260813045816__add_manage_recruitments_permission.sql} で
     * {@code permissions} へ登録し ADMIN へ {@code is_default=1} 付与する。カタログに行が無いと
     * TEAM 経路（{@link com.mannschaft.app.common.AccessControlService#checkPermission}）の判定が
     * 成立せず、チーム受取の onboarding が誰にも通らない。TEAM と ORG で呼び分ける理由・
     * DEPUTY_ADMIN へ {@code role_permissions} 行を作ってはならない不変条件は
     * {@link com.mannschaft.app.payment.escrow.ConnectChargeService} の同名定数の javadoc に集約している。</p>
     */
    static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    /** 国コード（JP 固定・01 §1）。 */
    static final String DEFAULT_COUNTRY = "JP";

    private final ConnectAccountRepository connectAccountRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final AccessControlService accessControlService;
    private final PayeeScopeResolver payeeScopeResolver;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Connect onboarding リンクを発行する（設計書 02 §2.1）。
     *
     * @param request onboarding リンク発行リクエスト
     * @return onboarding URL を含むレスポンス
     */
    public OnboardingLinkResponse createOnboardingLink(OnboardingLinkRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ResolvedScope scope = authorizeAndResolveScope(request.scopeKind(), request.scopeId(), currentUserId);

        ConnectAccountEntity account = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scope.scopeKind(), scope.scopeId())
                .map(existing -> {
                    // 既存 acct を流用し onboarding を再開（02 §2.1 処理2）
                    existing.setOnboardingStatus(OnboardingStatus.ONBOARDING);
                    return existing;
                })
                .orElseGet(() -> {
                    String stripeAccountId = stripePaymentProvider.createConnectAccount(
                            DEFAULT_COUNTRY, scope.scopeKind(), scope.scopeId());
                    return ConnectAccountEntity.builder()
                            .scopeKind(scope.scopeKind())
                            .scopeId(scope.scopeId())
                            .organizationId(scope.organizationId())
                            .stripeAccountId(stripeAccountId)
                            .onboardingStatus(OnboardingStatus.ONBOARDING)
                            .chargesEnabled(false)
                            .payoutsEnabled(false)
                            .country(DEFAULT_COUNTRY)
                            .defaultCurrency("JPY")
                            .build();
                });
        account = connectAccountRepository.save(account);

        StripePaymentProvider.AccountLinkInfo link = stripePaymentProvider.createAccountLink(
                account.getStripeAccountId(), request.returnUrl(), request.refreshUrl());

        return new OnboardingLinkResponse(
                account.getId(),
                account.getStripeAccountId(),
                account.getOnboardingStatus(),
                link.url(),
                link.expiresAt());
    }

    /**
     * Connect 状態を取得する（設計書 02 §2.2）。
     *
     * <p>IDOR: scope 所有権を照合し、無関係 scope / 不在は {@code PAYMENT_C002}（404 秘匿）。</p>
     *
     * @param scopeKind 受領主体種別
     * @param scopeId   受領主体 ID（USER 時は本人固定で無視）
     * @return Connect 状態レスポンス
     */
    @Transactional(readOnly = true)
    public ConnectStatusResponse getStatus(ScopeKind scopeKind, Long scopeId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ResolvedScope scope = authorizeAndResolveScope(scopeKind, scopeId, currentUserId);

        ConnectAccountEntity account = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scope.scopeKind(), scope.scopeId())
                // 認可は通過したが口座未作成 → 存在しないものとして 404 秘匿（IDOR・03 §4）
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        return new ConnectStatusResponse(
                account.getId(),
                account.getScopeKind(),
                account.getScopeId(),
                account.getOnboardingStatus(),
                Boolean.TRUE.equals(account.getChargesEnabled()),
                Boolean.TRUE.equals(account.getPayoutsEnabled()),
                deserializeRequirements(account.getRequirementsDue()));
    }

    /**
     * {@code account.updated} Webhook を反映する（鏡像更新・02 §4.2）。
     *
     * <p>Connect Webhook ハンドラから呼ばれる。対象 {@code acct_xxx} が未知の場合は何もしない
     * （自社が作成していないアカウントの更新は無視・症状は情報ログに残す）。</p>
     *
     * @param stripeAccountId  対象 Connect アカウント ID（{@code acct_xxx}）
     * @param chargesEnabled   課金可否
     * @param payoutsEnabled   払出可否
     * @param requirementsDue  KYC 要件不足項目
     */
    public void applyAccountUpdated(String stripeAccountId, boolean chargesEnabled,
                                    boolean payoutsEnabled, List<String> requirementsDue) {
        Optional<ConnectAccountEntity> found = connectAccountRepository.findByStripeAccountId(stripeAccountId);
        if (found.isEmpty()) {
            log.info("account.updated 対象の Connect アカウントが未登録。スキップします: stripeAccountId={}",
                    stripeAccountId);
            return;
        }
        ConnectAccountEntity account = found.get();
        // 昇格判定: payouts_enabled が false→true へ遷移したか（鏡像更新の前に旧値を退避）。
        boolean wasPayoutsEnabled = Boolean.TRUE.equals(account.getPayoutsEnabled());
        account.setChargesEnabled(chargesEnabled);
        account.setPayoutsEnabled(payoutsEnabled);
        account.setRequirementsDue(serializeRequirements(requirementsDue));
        account.setOnboardingStatus(resolveStatus(payoutsEnabled, requirementsDue));
        connectAccountRepository.save(account);
        log.info("Connect 鏡像更新: stripeAccountId={}, payoutsEnabled={}, status={}",
                stripeAccountId, payoutsEnabled, account.getOnboardingStatus());

        // 第三陣: payouts_enabled が false→true に遷移したら、この口座を payee とする HELD escrow を昇格する
        // （設計書 02 §5.2）。鏡像更新（既存処理）は壊さず、その後段に昇格を足す。
        //
        // Issue #2990 L3: 昇格は業務TX内で直接呼ばず、AFTER_COMMIT へ移した。
        // 昇格先の EscrowLifecycleService#promoteHeldEscrow は REQUIRES_NEW で payee 口座を読み直し
        // payouts_enabled を検証するため、鏡像更新が未 commit の状態で呼ぶと必ず旧値（false）を読み、
        // 昇格が毎回空振りしていた（詳細は ConnectHeldEscrowPromotionListener の javadoc）。
        if (!wasPayoutsEnabled && payoutsEnabled) {
            eventPublisher.publishEvent(new ConnectPayoutsEnabledEvent(account.getId()));
        }
    }

    /**
     * {@code account.application.deauthorized} を反映する（02 §4.2）。
     */
    public void applyDeauthorized(String stripeAccountId) {
        connectAccountRepository.findByStripeAccountId(stripeAccountId).ifPresent(account -> {
            account.setOnboardingStatus(OnboardingStatus.DISABLED);
            account.setPayoutsEnabled(false);
            account.setChargesEnabled(false);
            connectAccountRepository.save(account);
            log.info("Connect deauthorized: stripeAccountId={}", stripeAccountId);
        });
    }

    /**
     * payouts_enabled / requirements から onboarding 状態を導出する。
     */
    private OnboardingStatus resolveStatus(boolean payoutsEnabled, List<String> requirementsDue) {
        if (payoutsEnabled) {
            return OnboardingStatus.READY;
        }
        if (requirementsDue != null && !requirementsDue.isEmpty()) {
            return OnboardingStatus.RESTRICTED;
        }
        return OnboardingStatus.ONBOARDING;
    }

    /**
     * 認可を行い、実際に扱う scope（USER は本人固定）を解決する。
     *
     * <p>設計書 03 §3 マトリクス: USER=本人固定 / TEAM=checkPermission / ORG=checkAdminOrHasPermission。
     * TEAM/ORG で認可 API を取り違えない（PayeeScopeResolver の scopeType を用いる）。</p>
     */
    private ResolvedScope authorizeAndResolveScope(ScopeKind scopeKind, Long scopeId, Long currentUserId) {
        return switch (scopeKind) {
            case USER ->
                // 本人固定: リクエストの scopeId は無視し、認証ユーザ本人へ強制（他人の onboarding 不可・03 §3）
                    new ResolvedScope(ScopeKind.USER, currentUserId, null);
            case TEAM -> {
                requireScopeId(scopeId);
                accessControlService.checkPermission(currentUserId, scopeId,
                        PayeeScopeResolver.SCOPE_TYPE_TEAM, PERMISSION_MANAGE_PAYMENT);
                yield new ResolvedScope(ScopeKind.TEAM, scopeId, null);
            }
            case ORG -> {
                requireScopeId(scopeId);
                accessControlService.checkAdminOrHasPermission(currentUserId, scopeId,
                        PayeeScopeResolver.SCOPE_TYPE_ORGANIZATION, PERMISSION_MANAGE_PAYMENT);
                yield new ResolvedScope(ScopeKind.ORG, scopeId, scopeId);
            }
        };
    }

    private void requireScopeId(Long scopeId) {
        if (scopeId == null) {
            throw new BusinessException(ConnectPaymentErrorCode.PAYEE_REQUIRED);
        }
    }

    private String serializeRequirements(List<String> requirementsDue) {
        if (requirementsDue == null || requirementsDue.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(requirementsDue);
        } catch (JsonProcessingException e) {
            // 監査情報の鏡像は握り潰さず記録するが、本体処理は止めない（null 化して記録）
            log.warn("requirements_due の JSON 直列化に失敗しました", e);
            return null;
        }
    }

    private List<String> deserializeRequirements(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("requirements_due の JSON 復元に失敗しました: value={}", json, e);
            return Collections.emptyList();
        }
    }

    /**
     * 認可後に確定した実 scope。
     *
     * @param scopeKind      実 scope 種別
     * @param scopeId        実 scope ID（USER は本人 userId）
     * @param organizationId テナント列（ORG のみ非 null）
     */
    private record ResolvedScope(ScopeKind scopeKind, Long scopeId, Long organizationId) {}
}
