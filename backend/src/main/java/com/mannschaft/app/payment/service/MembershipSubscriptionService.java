package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * F08.9 P5 会員継続課金サービス（membership_subscriptions）。
 *
 * <p><b>第一波（P5-1）の責務は取得系のみ。</b> subscribe（加入）/ cancel（期末解約）/ skip（今月スキップ）/
 * resume（再開）/ Webhook 連携（invoice.* / subscription.deleted）の本体は後続波で実装する。</p>
 *
 * <p><b>方針（症状を隠すガワは作らない）:</b> 未実装メソッドのプレースホルダ（{@code UnsupportedOperationException} を
 * 投げるガワ）は<b>あえて置かない</b>。公開メソッドは波ごとに本物を追加する方針とし、本波では
 * {@link MembershipSubscriptionRepository} を包む素朴な取得系（払い手視点 / 受領主体視点）のみを公開する。
 * これにより「存在するが動かない API」を残さず、未実装は API 自体が未存在であることで正直に表現する。</p>
 *
 * <h2>後続波の実装計画（本波では未実装）</h2>
 * <ul>
 *   <li><b>第二波（subscribe）:</b> SetupIntent で保存した PM・受領者 Connect 口座・billing_anchor_day で
 *       Stripe Subscription を作成し PENDING で起票。加入時に {@code FeePolicyResolver(MEMBERSHIP)} で
 *       {@code fee_policy_key} を焼き付け（遡及防止）。初回は単発 destination charge、Subscription は次サイクル開始
 *       （案 b・PoC 実証 2026-06-05）。<b>前提として P1 の SetupIntent/PM 保存導線が必要（現状未整備・報告参照）。</b></li>
 *   <li><b>第三波（cancel/skip/resume）:</b> {@link MembershipSubscriptionEntity} の状態遷移メソッド
 *       （{@code scheduleCancelAtPeriodEnd}/{@code applySkipUntil}/{@code clearSkip}）＋ Stripe
 *       {@code pause_collection} 連携。所有権認可（payer_user_id / 後見保護者）。</li>
 *   <li><b>第四波（Webhook）:</b> {@code invoice.created} の固定手数料上書き（焼付 fee_policy_key で算出）・
 *       {@code invoice.paid} の escrow CAPTURED 起票＋valid_until 延長・{@code invoice.payment_failed} の
 *       PAST_DUE 遷移（{@code markPastDue}/{@code markRecovered}/{@code markCancelled} を使用）。</li>
 * </ul>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/02_api_design.md §4 / 01_data_model.md §2.1</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MembershipSubscriptionService {

    /** 加入時の安全側既定 application_fee_percent（invoice 上書きが正・第四波 webhook が fee_policy_key で固定額へ）。 */
    public static final BigDecimal SAFE_DEFAULT_APPLICATION_FEE_PERCENT = new BigDecimal("5");

    /** subscribe の二重加入防止で「有効」とみなす状態（終端 CANCELLED/EXPIRED 以外）。 */
    private static final List<MembershipSubscriptionStatus> ACTIVE_LIKE_STATUSES = List.of(
            MembershipSubscriptionStatus.PENDING,
            MembershipSubscriptionStatus.ACTIVE,
            MembershipSubscriptionStatus.PAST_DUE);

    private final MembershipSubscriptionRepository membershipSubscriptionRepository;
    private final PaymentItemService paymentItemService;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final ConnectAccountRepository connectAccountRepository;
    private final ConnectChargeService connectChargeService;
    private final FeePolicyResolver feePolicyResolver;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final MemberPaymentService memberPaymentService;

    /**
     * 払い手視点の継続課金一覧を取得する（「自分が払い手の継続課金一覧」API の本体・02_api §4.1）。
     *
     * @param payerUserId 払い手ユーザーID（呼出側で SecurityUtils 解決）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForPayer(Long payerUserId) {
        return membershipSubscriptionRepository
                .findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(payerUserId);
    }

    /**
     * 払い手視点の継続課金一覧を状態で絞って取得する。
     *
     * @param payerUserId 払い手ユーザーID
     * @param statuses    抽出する状態（例: ACTIVE/PAST_DUE）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForPayer(
            Long payerUserId, List<MembershipSubscriptionStatus> statuses) {
        return membershipSubscriptionRepository
                .findByPayerUserIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(payerUserId, statuses);
    }

    /**
     * 受領主体（チーム/組織）視点の継続課金一覧を取得する（管理者向け一覧 API の本体・02_api §4.1）。
     *
     * @param scopeKind 受領主体の種別（TEAM/ORG）
     * @param scopeId   受領主体 ID（team_id/org_id）
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForScope(ScopeKind scopeKind, Long scopeId) {
        return membershipSubscriptionRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(scopeKind, scopeId);
    }

    /**
     * チーム視点の継続課金一覧を取得する（{@link #findForScope} の TEAM 限定の薄いラッパ・02_api §4.1
     * {@code GET /api/v1/teams/{id}/membership-subscriptions}）。
     *
     * @param teamId チーム ID
     * @return 作成日時降順の継続課金一覧（論理削除を除外）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionEntity> findForTeam(Long teamId) {
        return findForScope(ScopeKind.TEAM, teamId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 第二波: subscribe（加入・案b）/ cancel（期末解約）
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 継続課金に加入する（案b・初回単発 charge＋次サイクル Subscription・設計書 02 §4.1）。
     *
     * <p>フロー:</p>
     * <ol>
     *   <li>項目検証: {@code is_recurring=true} でなければ {@code SUBSCRIPTION_ITEM_NOT_RECURRING}（409）。</li>
     *   <li>権原検証: {@link PaymentAuthorizationService#authorizePayment}（{@code manualRecordByAdmin=false}）で
     *       払い手→受益者の代理払い権原を実評価（SELF/GUARDIAN/GUARDIAN_PROXY/PROXY_GRANT）。無権原は 403。</li>
     *   <li>二重加入防止: 受益者×項目に終端でないサブスクがあれば {@code SUBSCRIPTION_ALREADY_EXISTS}（409）。</li>
     *   <li>受領 Connect 口座解決＋READY 検証（非 READY は {@code ONBOARDING_NOT_READY} 409・即時モードゆえ HELD にしない）。</li>
     *   <li>払い手 default PM 検証: 未保存なら {@code SUBSCRIPTION_PAYMENT_METHOD_NOT_SAVED}（409・SetupIntent 導線へ）。</li>
     *   <li>{@code fee_policy_key} を {@link FeePolicyResolver#resolve}(MEMBERSHIP) で解決し焼付（遡及防止）。</li>
     *   <li>初回: {@link ConnectChargeService#charge}（P1 同型・単発 destination charge・AUTHORIZED 起票→CAPTURED は webhook）。
     *       {@code member_payments} を {@link MemberPaymentService#recordSubscriptionInitialChargePending} で
     *       PENDING 起票し subscription を連結。</li>
     *   <li>Subscription 作成: 継続課金 Price を get-or-create し、{@code billing_cycle_anchor=次サイクル開始}・
     *       {@code proration_behavior=none} で「初回 invoice なし」の Subscription を作成（PoC 実証）。</li>
     *   <li>{@code membership_subscriptions} を PENDING で INSERT（face/currency price-lock 焼付・Stripe ID 連結）。
     *       PENDING→ACTIVE 化は第三波 webhook（初回 invoice.paid）。</li>
     * </ol>
     *
     * <p><b>charge 後 DB 失敗の補償（P7 §11.1 同型）:</b> 初回 charge 成功後の DB 処理（起票・Subscription 作成・INSERT）が
     * 失敗した場合、PaymentIntent / escrow は既に作られているため、ERROR ログ（PI・idempotencyKey）を残して
     * 再 throw する（症状を隠さない）。webhook（escrow succeeded）は escrow を CAPTURED にするが、member_payment は
     * トランザクションロールバックで未起票のため {@link MemberPaymentService#applyMembershipPaidByEscrow} は no-op に倒れ、
     * 突合キー（escrow_transaction_id）で後追い調査できる。冪等キーで再実行時の二重 charge は Stripe 側で防がれる。</p>
     *
     * @param itemId            会費項目 ID（{@code is_recurring=true}）
     * @param payerUserId       払い手（呼出側で SecurityUtils 解決）
     * @param beneficiaryUserId 受益者
     * @param billingAnchorDay  ユーザ指定決済日（1-28・任意・記録のみ。本波の anchor 算出は次サイクル開始）
     * @param idempotencyKey    冪等性キー（Idempotency-Key ヘッダ起源・Stripe へ橋渡し）
     * @return 起票した継続課金（PENDING）
     */
    @Transactional
    public MembershipSubscriptionEntity subscribe(Long itemId, Long payerUserId, Long beneficiaryUserId,
                                                  Short billingAnchorDay, String idempotencyKey) {
        PaymentItemEntity item = paymentItemService.findByIdOrThrow(itemId);

        // 1. is_recurring 検証（単発項目への subscribe は 409）。
        if (!Boolean.TRUE.equals(item.getIsRecurring())) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_ITEM_NOT_RECURRING);
        }

        // 2. 権原検証（P2 実装を consume・無権原は 403）。
        PayerRelationship relationship = paymentAuthorizationService.authorizePayment(
                payerUserId, beneficiaryUserId, itemId, false);

        // 3. 二重加入防止（受益者×項目に終端でないサブスクがあれば 409）。
        if (membershipSubscriptionRepository
                .existsByBeneficiaryUserIdAndPaymentItemIdAndStatusInAndDeletedAtIsNull(
                        beneficiaryUserId, itemId, ACTIVE_LIKE_STATUSES)) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_ALREADY_EXISTS);
        }

        // 4. 受領 Connect 口座を scope から解決し READY を判定（即時モードゆえ非 READY は HELD にせず 409）。
        ScopeAndAccount scopeAndAccount = resolvePayeeConnectAccount(item);
        ConnectAccountEntity payee = scopeAndAccount.account();
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            log.warn("継続課金 加入拒否（受領口座が未 READY）: itemId={}, payeeAccount={}, payoutsEnabled={}",
                    itemId, payee.getStripeAccountId(), payee.getPayoutsEnabled());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }

        // 5. 払い手の Stripe Customer を get-or-create し、default PM 保存済みを検証（未保存は 409・SetupIntent 導線へ）。
        StripeCustomerEntity payerCustomer = getOrCreateStripeCustomer(payerUserId);
        String defaultPaymentMethod = payerCustomer.getDefaultPaymentMethod();
        if (defaultPaymentMethod == null || defaultPaymentMethod.isBlank()) {
            log.info("継続課金 加入拒否（default PM 未保存）: payer={}, itemId={}", payerUserId, itemId);
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_PAYMENT_METHOD_NOT_SAVED);
        }

        // 6. fee_policy_key を解決し焼付（consume のみ・遡及防止）。
        FeePolicy policy = feePolicyResolver.resolve(EscrowSourceKind.MEMBERSHIP, null);
        String feePolicyKey = policy.policyKey();

        long faceAmount = item.getAmount().longValueExact();
        BillingInterval interval = resolveBillingInterval(item);

        // 7. 初回: P1 同型の単発 destination charge（AUTHORIZED 起票→CAPTURED は escrow webhook）。
        MembershipChargeResult chargeResult = connectChargeService.charge(new MembershipChargeCommand(
                faceAmount,
                payee.getId(),
                payerCustomer.getStripeCustomerId(),
                payerUserId,
                itemId,
                item.getOrganizationId(),
                idempotencyKey));

        // charge 成功後の DB 処理は P7 §11.1 同型で try-catch し、失敗は ERROR ログ＋再 throw（症状を隠さない）。
        try {
            // 8. 継続課金 Price を get-or-create（recurring Price が無ければ Product/Price を作って項目に焼付）。
            String recurringPriceId = getOrCreateRecurringPrice(item, interval);

            // 9. membership_subscriptions を PENDING で INSERT（face/currency price-lock 焼付・初回 charge を連結）。
            //    Stripe ID は Subscription 作成後に linkStripeIds で焼き付ける。
            MembershipSubscriptionEntity subscription = MembershipSubscriptionEntity.builder()
                    .organizationId(item.getOrganizationId())
                    .paymentItemId(itemId)
                    .beneficiaryUserId(beneficiaryUserId)
                    .payerUserId(payerUserId)
                    .paymentProxyGrantId(null)
                    .scopeKind(scopeAndAccount.scopeKind())
                    .scopeId(scopeAndAccount.scopeId())
                    .payeeConnectAccountId(payee.getId())
                    .stripeCustomerId(payerCustomer.getStripeCustomerId())
                    .billingInterval(interval)
                    .billingAnchorDay(billingAnchorDay)
                    .status(MembershipSubscriptionStatus.PENDING)
                    .feePolicyKey(feePolicyKey)
                    .faceAmount((int) faceAmount)
                    .currency(item.getCurrency())
                    .cancelAtPeriodEnd(false)
                    .build();
            subscription = membershipSubscriptionRepository.save(subscription);

            // member_payments を PENDING で起票し subscription を連結（PAID 反映は escrow CAPTURED 連動に相乗り）。
            memberPaymentService.recordSubscriptionInitialChargePending(
                    beneficiaryUserId, itemId, item.getAmount(), item.getCurrency(),
                    payerUserId, relationship, chargeResult.escrowTransactionId(), subscription.getId());

            // 10. Stripe Subscription を案b（次サイクル開始・初回 invoice なし）で作成し ID を焼付。
            long billingCycleAnchorEpochSec = computeNextCycleAnchorEpochSec(interval);
            StripePaymentProvider.SubscriptionInfo subInfo = stripePaymentProvider.createSubscription(
                    payerCustomer.getStripeCustomerId(),
                    recurringPriceId,
                    defaultPaymentMethod,
                    payee.getStripeAccountId(),
                    SAFE_DEFAULT_APPLICATION_FEE_PERCENT,
                    billingCycleAnchorEpochSec,
                    "sub-create-" + subscription.getId());
            subscription.linkStripeIds(subInfo.subscriptionId(), payerCustomer.getStripeCustomerId());
            subscription = membershipSubscriptionRepository.save(subscription);

            log.info("継続課金 加入 起票（PENDING・ACTIVE は初回 invoice.paid webhook）: subscriptionId={}, stripeSub={}, "
                            + "beneficiary={}, payer={}, relationship={}, feePolicyKey={}, escrowId={}",
                    subscription.getId(), subInfo.subscriptionId(), beneficiaryUserId, payerUserId, relationship,
                    feePolicyKey, chargeResult.escrowTransactionId());
            return subscription;
        } catch (RuntimeException e) {
            // charge は成功済み（PI・escrow 作成済）だが DB 処理が失敗。症状を隠さず ERROR ログ＋再 throw（02 §11.1 同型）。
            log.error("継続課金 加入の charge 後 DB 処理が失敗（PI/escrow は作成済・要調査）: itemId={}, payer={}, "
                            + "escrowId={}, paymentIntentId={}, idempotencyKey={}",
                    itemId, payerUserId, chargeResult.escrowTransactionId(), chargeResult.paymentIntentId(),
                    idempotencyKey, e);
            throw e;
        }
    }

    /**
     * 継続課金の初回単発 charge の CAPTURED を受けて PENDING→ACTIVE に活性化する（案b の<b>唯一の活性化点</b>・
     * F08.9 P5 第三波・設計書 02 §4.1 / §4.2）。
     *
     * <p><b>PENDING→ACTIVE の発火点を1箇所に確定する（二重発火しない）。</b> 案b では初回会費を Subscription の
     * invoice ではなく P1 同型の単発 destination charge で徴収するため、初回 {@code invoice.paid} は発生しない
     * （Stripe Subscription は {@code billing_cycle_anchor}=次サイクルで起動）。よって PENDING→ACTIVE の起点は
     * 「初回単発 charge の CAPTURED」であり、escrow の {@code payment_intent.succeeded} → {@link EscrowCapturedEvent}
     * → {@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener} が
     * {@link MemberPaymentService#applyMembershipPaidByEscrow}（PAID 反映）で連結 subscription ID を取得し、本メソッドを呼ぶ。
     * Webhook 側（{@link MembershipSubscriptionWebhookService}）は PENDING→ACTIVE を<b>行わず</b>、ACTIVE/PAST_DUE への
     * サイクル反映のみ担う（活性化の二重発火を防ぐ）。</p>
     *
     * <p>現サイクルは「活性化日（{@code validFrom}）〜課金周期で算出した期末」とする（受益者 valid_until と同期・
     * MONTHLY=+1ヶ月 / YEARLY=+1年）。</p>
     *
     * <p><b>冪等:</b> 行ロック取得後に PENDING でなければ no-op（既に ACTIVE 化済み／再送／二経路重複）。
     * これにより初回 charge CAPTURED の再送・並行と、稀に先着しうる次サイクル invoice.paid とで二重 ACTIVE 化しない。</p>
     *
     * @param subscriptionId 活性化対象の継続課金 ID（連結 member_payment 由来）
     */
    @Transactional
    public void activateOnInitialChargeIfPending(UUID subscriptionId) {
        if (subscriptionId == null) {
            return;
        }
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdForUpdate(subscriptionId)
                .orElse(null);
        if (subscription == null) {
            log.info("継続課金 活性化: 対象 subscription なし（論理削除済/不在）。no-op: subscriptionId={}", subscriptionId);
            return;
        }
        if (subscription.getStatus() != MembershipSubscriptionStatus.PENDING) {
            // 既に ACTIVE 等（再送・二経路重複）。冪等 no-op。
            log.info("継続課金 活性化: PENDING でないため no-op（冪等）: subscriptionId={}, status={}",
                    subscriptionId, subscription.getStatus());
            return;
        }
        LocalDate periodStart = LocalDate.now();
        LocalDate periodEnd = addOneCycle(periodStart, subscription.getBillingInterval());
        subscription.markActive(periodStart, periodEnd);
        membershipSubscriptionRepository.save(subscription);
        log.info("継続課金 活性化 PENDING→ACTIVE（初回 charge CAPTURED 連動）: subscriptionId={}, periodStart={}, periodEnd={}",
                subscriptionId, periodStart, periodEnd);
    }

    /**
     * 課金周期 1 期分を加算する（受益者 valid_until 同期の current_period_end 算出・MONTHLY=+1ヶ月 / YEARLY=+1年）。
     */
    private LocalDate addOneCycle(LocalDate from, BillingInterval interval) {
        return (interval == BillingInterval.YEARLY) ? from.plusYears(1) : from.plusMonths(1);
    }

    /**
     * 継続課金を期末解約予約する（{@code cancel_at_period_end=true}・設計書 02 §4.1）。
     *
     * <p>期末まで利用可・日割り返金なし・期末前は再有効化可。Stripe {@code cancel_at_period_end=true} を更新し、
     * Entity の {@link MembershipSubscriptionEntity#scheduleCancelAtPeriodEnd}（ACTIVE/PAST_DUE のみ可）を反映する。
     * 応答に期末日（{@code current_period_end}）を含め「○月○日まで利用可」を明示する（04 §2）。</p>
     *
     * <p>認可: 払い手本人 or 後見保護者（{@code payer_user_id}・03 §1）。IDOR は 404 でなく
     * 所有者一致＋後見判定で 403（SUBSCRIPTION_NOT_AUTHORIZED）。存在しないサブスクは 404
     * （SUBSCRIPTION_NOT_FOUND）。</p>
     *
     * @param subscriptionId 継続課金 ID
     * @param actorUserId    操作者（呼出側で SecurityUtils 解決・払い手本人 or 後見保護者）
     * @return 期末解約予約後の継続課金（current_period_end に期末日）
     */
    @Transactional
    public MembershipSubscriptionEntity cancel(UUID subscriptionId, Long actorUserId) {
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdAndDeletedAtIsNull(subscriptionId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 認可: 払い手本人 or 受益者の後見保護者（サブスク所有権・03 §1）。無権原は 403。
        if (!isOwnerOrGuardian(subscription, actorUserId)) {
            log.info("継続課金 解約 拒否（所有者/後見でない）: subscriptionId={}, actor={}, payer={}",
                    subscriptionId, actorUserId, subscription.getPayerUserId());
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_AUTHORIZED);
        }

        // Entity の不変条件（ACTIVE/PAST_DUE のみ予約可）。それ以外は 409（SUBSCRIPTION_NOT_ACTIVE）。
        if (subscription.getStatus() != MembershipSubscriptionStatus.ACTIVE
                && subscription.getStatus() != MembershipSubscriptionStatus.PAST_DUE) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }

        // Stripe 先（cancel_at_period_end=true）・DB 後。stripe_subscription_id 未連結は整合性異常として 409。
        if (subscription.getStripeSubscriptionId() == null) {
            log.warn("継続課金 解約 不能（stripe_subscription_id 未連結・異常）: subscriptionId={}", subscriptionId);
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }
        StripePaymentProvider.SubscriptionInfo subInfo = stripePaymentProvider.cancelSubscriptionAtPeriodEnd(
                subscription.getStripeSubscriptionId(), "sub-cancel-" + subscriptionId);

        subscription.scheduleCancelAtPeriodEnd();
        // 期末日（Stripe の current_period_end）を応答用に反映（DATE で保持）。
        if (subInfo.currentPeriodEnd() != null) {
            subscription = applyCurrentPeriodEnd(subscription, subInfo.currentPeriodEnd());
        }
        subscription = membershipSubscriptionRepository.save(subscription);

        log.info("継続課金 期末解約予約: subscriptionId={}, stripeSub={}, periodEnd={}",
                subscriptionId, subscription.getStripeSubscriptionId(), subscription.getCurrentPeriodEnd());
        return subscription;
    }

    /**
     * Stripe の {@code current_period_end}（unix 秒）を Entity の {@code current_period_end}（DATE）へ反映する。
     * status 遷移はせず DATE のみ更新する。
     *
     * <p><b>根治（第三波で修正）:</b> 旧実装は {@code toBuilder().build()} で再構築していたが、Lombok の {@code @Builder} は
     * 親クラス {@link MembershipSubscriptionEntity} の {@code id}（{@code UuidV7Entity}）を引き継がないため id を失い、
     * 解約 save が UPDATE でなく新規 INSERT（重複行）になる潜在バグがあった。{@link MembershipSubscriptionEntity#applyCurrentPeriod}
     * ミューテータで原子的に更新し id を保つ。</p>
     */
    private MembershipSubscriptionEntity applyCurrentPeriodEnd(MembershipSubscriptionEntity subscription,
                                                               long currentPeriodEndEpochSec) {
        LocalDate periodEnd = java.time.Instant.ofEpochSecond(currentPeriodEndEpochSec)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        subscription.applyCurrentPeriod(null, periodEnd);
        return subscription;
    }

    /**
     * 操作者がサブスクの払い手本人、または受益者の後見保護者かを判定する（03 §1）。
     */
    private boolean isOwnerOrGuardian(MembershipSubscriptionEntity subscription, Long actorUserId) {
        if (actorUserId == null) {
            return false;
        }
        if (actorUserId.equals(subscription.getPayerUserId())) {
            return true;
        }
        // 後見保護者は受益者に対する権原評価（PaymentAuthorizationService 経由・無権原は例外→false）。
        try {
            paymentAuthorizationService.authorizePayment(
                    actorUserId, subscription.getBeneficiaryUserId(), subscription.getPaymentItemId(), false);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /**
     * 次サイクル開始（billing_cycle_anchor）の unix 秒を算出する（案b・初回単発 charge 済みゆえ次サイクルから）。
     *
     * <p>MONTHLY は 1 ヶ月後、YEARLY は 1 年後の同時刻を anchor とする。proration_behavior=none と組み合わせ
     * 「初回 invoice を発生させない」（PoC 実証）。</p>
     */
    private long computeNextCycleAnchorEpochSec(BillingInterval interval) {
        java.time.LocalDateTime next = (interval == BillingInterval.YEARLY)
                ? java.time.LocalDateTime.now().plusYears(1)
                : java.time.LocalDateTime.now().plusMonths(1);
        return next.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

    /**
     * 項目の課金周期を解決する。{@code payment_items.billing_interval} を優先し、未設定なら type から導く。
     */
    private BillingInterval resolveBillingInterval(PaymentItemEntity item) {
        if (item.getBillingInterval() != null) {
            return item.getBillingInterval();
        }
        return switch (item.getType()) {
            case ANNUAL_FEE -> BillingInterval.YEARLY;
            default -> BillingInterval.MONTHLY;
        };
    }

    /**
     * 継続課金用の Stripe Price（recurring）を get-or-create する。
     *
     * <p>項目に Product が無ければ作成し、recurring Price を作成して項目へ焼き付ける（{@code stripeProductId}/
     * {@code stripePriceId}）。{@code payment_items.stripePriceId} は一回払いと recurring を区別しないため、本波では
     * 簡明に「既存 stripePriceId があればそれを recurring として用いる」のではなく、recurring 専用の Price を
     * 都度 get-or-create する設計とし、Product を再利用する（Price は recurring であることを保証）。</p>
     */
    private String getOrCreateRecurringPrice(PaymentItemEntity item, BillingInterval interval) {
        String productId = item.getStripeProductId();
        if (productId == null) {
            productId = stripePaymentProvider.createProduct(item.getName(), item.getId());
        }
        String recurringPriceId = stripePaymentProvider.createRecurringPrice(
                productId, item.getAmount(), item.getCurrency(), interval);
        // 項目に Product/Price を焼き付け（次回以降の get-or-create で Product を再利用）。
        item.updateStripeIds(productId, recurringPriceId);
        paymentItemService.saveStripeIds(item);
        return recurringPriceId;
    }

    /**
     * payment_item のスコープ（team/org）から受領 Connect 口座を解決する（P1 {@code MemberPaymentService} 同型）。
     */
    private ScopeAndAccount resolvePayeeConnectAccount(PaymentItemEntity item) {
        ScopeKind scopeKind;
        Long scopeId;
        if (item.getTeamId() != null) {
            scopeKind = ScopeKind.TEAM;
            scopeId = item.getTeamId();
        } else if (item.getOrganizationId() != null) {
            scopeKind = ScopeKind.ORG;
            scopeId = item.getOrganizationId();
        } else {
            log.warn("payment_item にスコープ（team/org）が無く Connect 口座を解決できません: itemId={}", item.getId());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }
        ConnectAccountEntity account = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scopeKind, scopeId)
                .orElseThrow(() -> {
                    log.warn("受領者の Connect 口座が未登録（READY でない）: itemId={}, scope={}/{}",
                            item.getId(), scopeKind, scopeId);
                    return new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
                });
        return new ScopeAndAccount(scopeKind, scopeId, account);
    }

    /**
     * 払い手の Stripe Customer を取得、無ければ作成する（P1 {@code MemberPaymentService.getOrCreateStripeCustomer} と
     * 同一挙動・email プレースホルダは P1 既知負債を踏襲して直さない）。
     */
    private StripeCustomerEntity getOrCreateStripeCustomer(Long userId) {
        return stripeCustomerRepository.findByUserId(userId)
                .orElseGet(() -> {
                    String customerId = stripePaymentProvider.createCustomer("user@example.com", userId);
                    return stripeCustomerRepository.save(StripeCustomerEntity.builder()
                            .userId(userId)
                            .stripeCustomerId(customerId)
                            .build());
                });
    }

    /** 受領 Connect 口座の解決結果（scope と口座をまとめて返す）。 */
    private record ScopeAndAccount(ScopeKind scopeKind, Long scopeId, ConnectAccountEntity account) {}
}
