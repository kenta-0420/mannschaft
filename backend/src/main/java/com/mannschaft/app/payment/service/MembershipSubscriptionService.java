package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.WithdrawalStateQueryService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.payment.BillingInterval;
import com.mannschaft.app.payment.FeeBreakdown;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.MembershipSubscriptionStatus;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.MembershipSubscriptionListItemResponse;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipPayerWithdrawalCancellationStatus;
import com.mannschaft.app.payment.entity.MembershipSubscriptionEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.MembershipPayerWithdrawalCancellationRepository;
import com.mannschaft.app.payment.repository.MembershipSubscriptionRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * 加入時の安全側既定 {@code application_fee_percent}（invoice 上書きが正・webhook が fee_policy_key で固定額へ）。
     *
     * <p>通常は {@link #safeApplicationFeePercent(FeeBreakdown)} が「総手数料 ÷ 実請求額」で率を算出する。
     * 本定数は実請求額が算出不能な異常時のみのフォールバック。</p>
     */
    public static final BigDecimal SAFE_DEFAULT_APPLICATION_FEE_PERCENT = new BigDecimal("5");

    /** subscribe の二重加入防止で「有効」とみなす状態（終端 CANCELLED/EXPIRED 以外）。 */
    private static final List<MembershipSubscriptionStatus> ACTIVE_LIKE_STATUSES = List.of(
            MembershipSubscriptionStatus.PENDING,
            MembershipSubscriptionStatus.ACTIVE,
            MembershipSubscriptionStatus.PAST_DUE);

    private final MembershipSubscriptionRepository membershipSubscriptionRepository;
    private final PaymentItemService paymentItemService;
    private final PaymentItemRepository paymentItemRepository;
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final ConnectAccountRepository connectAccountRepository;
    private final ConnectChargeService connectChargeService;
    private final FeePolicyResolver feePolicyResolver;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final MemberPaymentService memberPaymentService;
    private final UserRepository userRepository;
    /** 手数料折半の正典（純粋関数）。数式を再実装せず必ず本計算器を通す（初回サイクルと同一の値を得るため）。 */
    private final PaymentFeeCalculator paymentFeeCalculator;
    /** 柱③-B PR-3: 退会/退会取消の1契約ぶんを独立トランザクションで処理するオーケストレータ。 */
    private final MembershipPayerWithdrawalRunner payerWithdrawalRunner;
    /** 柱③-B PR-3: 「退会処理由来で予約したか」の正本（退会取消時の復旧対象判定・再試行の拾い直し）。 */
    private final MembershipPayerWithdrawalCancellationRepository payerWithdrawalCancellationRepository;
    /** 柱③-B PR-3: 退会申請の現在状態（auth ドメインへは Service 経由でのみ触れる）。 */
    private final WithdrawalStateQueryService withdrawalStateQueryService;

    /**
     * 柱③-B PR-3: 退会取消で「取り消すべき予約が残っているかもしれない」作業行の状態
     * （Codex 検分3巡目 P1-2）。
     *
     * <p>{@code PENDING} を含めるのが要点。tx① を終えて Stripe を呼ぶ間に退会が取り消されると
     * 行は {@code PENDING} のまま残るが、Stripe 側には予約が入っている可能性がある。
     * {@code SUCCEEDED}/{@code RESTORING} だけを対象にすると、この窓で生まれた予約を誰も取り消せない。</p>
     */
    private static final List<MembershipPayerWithdrawalCancellationStatus> RESTORE_TARGET_STATUSES =
            List.of(MembershipPayerWithdrawalCancellationStatus.PENDING,
                    MembershipPayerWithdrawalCancellationStatus.FAILED,
                    MembershipPayerWithdrawalCancellationStatus.SUCCEEDED,
                    MembershipPayerWithdrawalCancellationStatus.RESTORING);

    /**
     * 【残債2 payment ドメイン公開 API】ユーザーの Stripe Customer 用メールアドレスを解決する。
     *
     * <p>{@link PaymentMethodService#getOrCreateStripeCustomer} が Stripe Customer 新規作成時に渡す
     * 実メールを取得するために呼び出す。本メソッドは payment ドメイン内で唯一 {@link UserEntity}/
     * {@link UserRepository} を直接参照する既存の凍結済みクラス（凍結 ArchUnit
     * {@code CrossDomainEntityImportArchTest}・D-1 の既存違反）に集約する。他クラス（例:
     * {@code PaymentMethodService}）が新規に {@code UserEntity} を直接参照すると
     * {@code BillingPurgeEventListener → gdpr.entity.AccountPurgeCompletionStatusEntity} と同様に
     * <b>新規</b>のクロスドメイン Entity 依存となり番人テストが fail するため、既存の凍結済み参照範囲を再利用する。</p>
     *
     * <p><b>退会済みユーザーは空を返す（{@code deletedAt} 非 null）。</b>
     * ユーザーが存在しない場合も空を返す。呼び出し側（{@code PaymentMethodService}）はこの場合
     * Stripe Customer の新規作成自体を拒否する方針とする（判断理由は呼び出し側の Javadoc 参照）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return メールアドレス（退会済み/不在なら空）
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveEmailForStripeCustomer(Long userId) {
        return userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .map(UserEntity::getEmail);
    }

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

        // 7. 初回: 保存済み既定 PM で server-side off-session 即時確定する単発 destination charge（R2-1 根治）。
        //    P1（FE on-session confirm 前提）と異なり P5 継続課金は払い手不在で確定するため confirmImmediately=true。
        //    AUTHORIZED 起票→CAPTURED は succeeded webhook（EscrowWebhookService）に委ねる（escrow 流儀は P1 と整合）。
        //    off-session confirm がカード認証要求/拒否で失敗した場合は症状を隠さず専用 402（MEMBERSHIP_BILLING_023）へ
        //    変換する（Stripe 例外を握り潰さない・PI はプロバイダ側で cancel 済みで孤児を残さない）。
        MembershipChargeResult chargeResult;
        try {
            chargeResult = connectChargeService.charge(new MembershipChargeCommand(
                    faceAmount,
                    payee.getId(),
                    payerCustomer.getStripeCustomerId(),
                    payerUserId,
                    itemId,
                    item.getOrganizationId(),
                    idempotencyKey,
                    null,
                    defaultPaymentMethod,
                    true));
        } catch (StripePaymentProvider.OffSessionConfirmationException e) {
            log.warn("継続課金 加入の初回 off-session 確定がカード認証要求/拒否で失敗（402 で拒否）: itemId={}, payer={}, "
                            + "stripeErrorCode={}", itemId, payerUserId, e.getStripeErrorCode(), e);
            throw new BusinessException(
                    MembershipBillingErrorCode.SUBSCRIPTION_OFF_SESSION_AUTHENTICATION_REQUIRED, e);
        }

        // charge 成功後の DB 処理は P7 §11.1 同型で try-catch し、失敗は ERROR ログ＋再 throw（症状を隠さない）。
        try {
            // 8. 継続課金 Price を作成（案C・2明細）。手数料内訳は初回 charge と同一の policy・同一の計算器で求めるため、
            //    2 サイクル目以降の invoice 合計は初回 PaymentIntent の金額（chargeAmount）と必ず一致する。
            //    安全ガード（total_fee > face）は既に上の charge で評価済み（ここに到達した時点で通過している）。
            FeeBreakdown fee = paymentFeeCalculator.calculate(faceAmount, policy);
            RecurringPrices prices = createRecurringPrices(item, interval, fee.payerFee());

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

            // 10. Stripe Subscription を案b（次サイクル開始・初回 invoice なし）＋案C（2明細）で作成し ID を焼付。
            long billingCycleAnchorEpochSec = computeNextCycleAnchorEpochSec(interval);
            StripePaymentProvider.SubscriptionInfo subInfo = stripePaymentProvider.createSubscription(
                    payerCustomer.getStripeCustomerId(),
                    prices.toPriceIds(),
                    defaultPaymentMethod,
                    payee.getStripeAccountId(),
                    safeApplicationFeePercent(fee),
                    billingCycleAnchorEpochSec,
                    "sub-create-" + subscription.getId());
            subscription.linkStripeIds(subInfo.subscriptionId(), payerCustomer.getStripeCustomerId());
            subscription.linkRecurringPrices(prices.feePriceId(), prices.surchargePriceId());
            subscription = membershipSubscriptionRepository.save(subscription);

            log.info("継続課金 加入 起票（PENDING・ACTIVE は初回 charge CAPTURED）: subscriptionId={}, stripeSub={}, "
                            + "beneficiary={}, payer={}, relationship={}, feePolicyKey={}, escrowId={}, "
                            + "face={}, payerFee={}, charge={}, appFee={}, surchargePrice={}",
                    subscription.getId(), subInfo.subscriptionId(), beneficiaryUserId, payerUserId, relationship,
                    feePolicyKey, chargeResult.escrowTransactionId(),
                    fee.faceAmount(), fee.payerFee(), fee.chargeAmount(), fee.applicationFeeAmount(),
                    prices.surchargePriceId());
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

        // 柱③-B PR-3（検分2巡目 P1-1）: 本人（または後見保護者）が明示的に解約を決めた瞬間、
        // 「この期末解約は退会処理由来である」という記録を無効化する。無効化しないと、遅れて届いた
        // 退会取消イベントの復旧処理が cancel_at_period_end=true しか見ないため、
        // この新しい意思まで解除してしまう。
        payerWithdrawalRunner.supersedeByUserDecision(subscriptionId);

        log.info("継続課金 期末解約予約: subscriptionId={}, stripeSub={}, periodEnd={}",
                subscriptionId, subscription.getStripeSubscriptionId(), subscription.getCurrentPeriodEnd());
        return subscription;
    }

    /**
     * 柱③-B（CMP-260901-1538）PR-3: 退会する<b>払い手</b>の継続課金を一括で期末解約する
     * （設計書 {@code billing_payer_handover_design.md} §6・AC-13）。
     *
     * <h2>なぜ必要か（§1.4-2）</h2>
     * <p>{@code membership_subscriptions} は payer / beneficiary / payee の3分離が済んでいるにもかかわらず、
     * 退会フロー（{@code WithdrawalStripeHandler}）は Stripe 連携が未実装のスタブであった。結果として
     * <b>払い手が退会しても Stripe 側の継続課金は止まらず、退会済み個人の Customer への課金が続く</b>。
     * 本メソッドはその穴を {@code payer_user_id} 起点で塞ぐ。</p>
     *
     * <h2>対象と挙動</h2>
     * <ul>
     *   <li>対象: {@code payer_user_id = payerUserId} かつ status が {@code ACTIVE}/{@code PAST_DUE}
     *       （論理削除を除く）。{@code PENDING}（初回課金前）と終端（{@code CANCELLED}/{@code EXPIRED}）は対象外。</li>
     *   <li>動作: Stripe に {@code cancel_at_period_end=true} を発行し、DB へ反映する（<b>即時解約はしない</b>。
     *       受益者は支払い済みの期間を最後まで使えるべきであり、日割り返金も発生させない）。</li>
     *   <li>通知: 受益者へ「payer の退会に伴い期末で終了する」旨を予告する
     *       （{@link com.mannschaft.app.payment.event.MembershipPayerWithdrawalNotificationEvent}・publish のみ）。</li>
     * </ul>
     *
     * <h2>冪等性</h2>
     * <p>既に {@code cancel_at_period_end=true} の行は Stripe 呼び出しごとスキップする（再実行しても無害）。
     * {@code stripe_subscription_id} 未連結の行は Stripe 操作できないため DB のみ予約して WARN を残す。</p>
     *
     * <h2>トランザクション設計（Codex 検分1巡目 P1-2 の是正）</h2>
     * <p><b>本メソッド自身はトランザクションを開始しない。</b>ID だけを抽出し、1契約ずつ
     * {@link MembershipPayerWithdrawalRunner}（別 Bean）へ渡して<b>契約ごとに独立したトランザクション</b>
     * で処理する。是正前は全件を1つの {@code REQUIRES_NEW} トランザクションで処理しており、
     * {@code save} の SQL 発行は commit まで遅延しうるため、<b>最後の flush/commit で1件でも DB 更新が
     * 失敗すると、成功した全契約の DB 変更と通知イベントがまとめてロールバックする</b>一方で、先に成功した
     * Stripe の {@code cancel_at_period_end=true} は戻らなかった。</p>
     *
     * <p>各トランザクションは対象行を {@code SELECT ... FOR UPDATE} でロックしてから payer / status /
     * {@code cancel_at_period_end} を取り直して検証する。抽出クエリにロックが無いままだと
     * {@code customer.subscription.deleted} webhook（同じ行のロックを取る）と競合し、古い ACTIVE
     * エンティティを保持したままの UPDATE が webhook の CANCELLED を上書きしうる。</p>
     *
     * <h2>失敗の永続化（同 P1-1）</h2>
     * <p>失敗はログだけでなく {@code membership_payer_withdrawal_cancellations} に
     * {@code PENDING}/{@code FAILED} として残る。退会イベント自体は永続化されない Spring の
     * インメモリイベントであり、Stripe 失敗・投入拒否・プロセス停止で処理が失われうるため、
     * <b>再試行対象を機械的に拾える形</b>を DB 側に用意しておく必要がある。
     * 再試行の駆動（夜次バッチ）は PR-4 に委ねる。</p>
     *
     * @param payerUserId 退会する払い手のユーザー ID
     * @return 期末解約を予約できた継続課金の ID 一覧（該当なし・全件対象外なら空）
     */
    public List<UUID> cancelAllForPayerOnWithdrawal(Long payerUserId) {
        if (payerUserId == null) {
            return List.of();
        }
        // ロックは取らず ID だけを抽出する（ロックは契約ごとの独立 tx の中で取り直す）。
        //
        // 対象は次の【和集合】である（Codex 検分5巡目 P1-2 の是正）:
        //   (a) まだ期末解約が予約されていない継続課金
        //   (b) 自分たちの作業行が非終端（PENDING/FAILED/RESTORING）で残っている継続課金
        //
        // (a) だけに絞る理由: 既に cancel_at_period_end=true の契約を無条件に対象へ入れると、
        // 【本人が自分の意思で解約した契約】にまで作業行（PENDING）を作ってしまい、その後の
        // 退会取消でそれを「退会処理由来」と誤認して解除してしまう。
        // (b) を足す理由: 「Stripe 側は解除済みなのに DB だけ true」という乖離が残った契約は
        // (a) から漏れるため、非終端の作業行を手がかりに拾い直して Stripe と揃え直す必要がある。
        List<UUID> unscheduled = membershipSubscriptionRepository.findUnscheduledIdsByPayerUserIdIn(
                List.of(payerUserId),
                List.of(MembershipSubscriptionStatus.ACTIVE, MembershipSubscriptionStatus.PAST_DUE));
        java.util.LinkedHashSet<UUID> targetSet = new java.util.LinkedHashSet<>(unscheduled);
        payerWithdrawalCancellationRepository
                .findByPayerUserIdAndStatusIn(payerUserId,
                        MembershipPayerWithdrawalCancellationStatus.NON_TERMINAL)
                .forEach(record -> targetSet.add(record.getSubscriptionId()));
        List<UUID> targetIds = List.copyOf(targetSet);
        if (targetIds.isEmpty()) {
            log.info("払い手退会に伴う継続課金の期末解約: 対象なし payerUserId={}", payerUserId);
            return List.of();
        }

        // Stripe に触れる前に対象【全件】の作業行を1トランザクションで確定させる（検分2巡目 P1-2）。
        // ここを契約ごとの prepare に任せると、途中で停止した契約には行が残らず PR-4 が拾えない。
        // 戻り値が空＝処理時点で退会申請中ではない（退会取消が先に確定した）ので、何もしない。
        List<UUID> reserved = payerWithdrawalRunner.reserveAll(targetIds, payerUserId);
        if (reserved.isEmpty()) {
            log.info("払い手退会に伴う継続課金の期末解約: 予約対象なし（退会申請中でない/全件確定済み） payerUserId={}",
                    payerUserId);
            return List.of();
        }

        List<UUID> scheduled = new ArrayList<>();
        int skipped = 0;
        int failed = 0;
        for (UUID subscriptionId : reserved) {
            switch (payerWithdrawalRunner.cancelOne(subscriptionId, payerUserId)) {
                case SCHEDULED -> scheduled.add(subscriptionId);
                case SKIPPED, ABORTED -> skipped++;
                case FAILED -> failed++;
            }
        }

        log.info("払い手退会に伴う継続課金の期末解約: payerUserId={}, 対象={}, 予約={}, 対象外={}, 失敗={}",
                payerUserId, reserved.size(), scheduled.size(), skipped, failed);
        return List.copyOf(scheduled);
    }

    /**
     * 柱③-B PR-3: 退会申請中の払い手のうち<b>まだ期末解約が予約されていない</b>継続課金 ID を返す
     * （PR-4 の照合バッチの起点・Codex 検分2巡目 P1-2）。
     *
     * <p>作業行（{@code membership_payer_withdrawal_cancellations}）の走査だけでは、行が
     * <b>そもそも作られなかった</b>ケースを永久に拾えない——退会本体の commit 後・非同期タスク開始前の
     * プロセス停止、{@code event-pool} の投入拒否がそれにあたる。本メソッドは作業行ではなく
     * <b>退会状態そのもの</b>を起点にするため、行の有無に関係なく「やり残した解約」を再構築できる。</p>
     *
     * <p>本 PR では駆動（夜次バッチ）は実装せず PR-4 に委ねるが、<b>検索経路は本 PR で用意する</b>。
     * 経路が無ければ PR-4 でも書きようがないためである。</p>
     *
     * <h2>重複排除の責務は本メソッドが持つ（Codex 検分3巡目 P2）</h2>
     * <p>PR-4 の再試行バッチは「非終端の作業行」と「本 backlog」の2経路を走査するが、両者の集合が
     * 重なっていると同じ契約へ二重に投入されうる。<b>その dedup は PR-4 ではなく本メソッドが行う</b>——
     * 非終端の作業行を持つ契約は既に作業行経路が拾うため、ここからは除外して返す。
     * これにより2つの集合は定義上互いに素になり、PR-4 側は単純に union できる。</p>
     *
     * @return 期末解約を予約すべきなのに未予約で、かつ作業行も持たない継続課金 ID（該当なしなら空）
     */
    public List<UUID> findWithdrawalCancelBacklog() {
        List<Long> pendingWithdrawalUserIds = withdrawalStateQueryService.findUserIdsWithPendingWithdrawal();
        if (pendingWithdrawalUserIds.isEmpty()) {
            return List.of();
        }
        List<UUID> unscheduled = membershipSubscriptionRepository.findUnscheduledIdsByPayerUserIdIn(
                pendingWithdrawalUserIds,
                List.of(MembershipSubscriptionStatus.ACTIVE, MembershipSubscriptionStatus.PAST_DUE));
        if (unscheduled.isEmpty()) {
            return List.of();
        }
        // 非終端の作業行を持つ契約は作業行経路の担当。ここで落として集合を互いに素にする。
        Set<UUID> handledByWorkRow = payerWithdrawalCancellationRepository
                .findByStatusInOrderByUpdatedAtAsc(MembershipPayerWithdrawalCancellationStatus.NON_TERMINAL)
                .stream()
                .map(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .collect(java.util.stream.Collectors.toSet());
        return unscheduled.stream().filter(id -> !handledByWorkRow.contains(id)).toList();
    }

    /**
     * 柱③-B PR-4: 期末解約を<b>再試行すべき払い手</b>の ID を返す（夜次再試行バッチの抽出）。
     *
     * <h2>2経路の union と、なぜ dedup が要るのか</h2>
     * <p>拾うべき対象は次の和集合である。</p>
     * <ol>
     *   <li>非終端の作業行（{@code PENDING}／{@code FAILED}）を持つ契約の払い手
     *       ——着手したが Stripe・DB の確定に至らなかったもの</li>
     *   <li>{@link #findWithdrawalCancelBacklog()} が返す契約の払い手
     *       ——作業行が<b>そもそも作られなかった</b>もの（退会本体の commit 後・非同期タスク開始前の停止、
     *       {@code event-pool} の投入拒否）</li>
     * </ol>
     * <p>{@code findWithdrawalCancelBacklog()} は非終端の作業行を持つ契約を除外するため、
     * <b>同一時点のスナップショットとしては</b>①と②は互いに素になる。しかし同メソッドは複数の独立クエリの
     * 組み合わせでトランザクションを張っておらず、<b>照会の間に作業行が作られれば重なる</b>——
     * 時間軸を含めると「常に互いに素」ではない。よってここで払い手単位に dedup してから返す。</p>
     *
     * <p>なお重複が仮に残っても二重発行にはならない。駆動先の
     * {@link #cancelAllForPayerOnWithdrawal(Long)} は行ロック下で払い手・状態・
     * {@code cancel_at_period_end} を取り直して再検証し、Stripe の冪等キーも退会試行ごとに不変だからである
     * （抽出側の dedup と処理側の再検証という二段の防御）。</p>
     *
     * @return 解約を再試行すべき払い手のユーザー ID（重複なし）
     */
    public List<Long> findWithdrawalCancelRetryPayerUserIds() {
        Set<Long> payerUserIds = new LinkedHashSet<>();

        // ① 非終端の作業行（解約側の2状態のみ。RESTORING は解除側の担当であり混ぜてはならない）。
        payerWithdrawalCancellationRepository
                .findByStatusInOrderByUpdatedAtAsc(List.of(
                        MembershipPayerWithdrawalCancellationStatus.PENDING,
                        MembershipPayerWithdrawalCancellationStatus.FAILED))
                .forEach(record -> payerUserIds.add(record.getPayerUserId()));

        // ② 作業行が作られなかった取りこぼし（退会状態そのものを起点に再構築する）。
        List<UUID> backlog = findWithdrawalCancelBacklog();
        if (!backlog.isEmpty()) {
            membershipSubscriptionRepository.findAllById(backlog)
                    .forEach(subscription -> payerUserIds.add(subscription.getPayerUserId()));
        }

        payerUserIds.remove(null);
        return List.copyOf(payerUserIds);
    }

    /**
     * 柱③-B PR-4: 期末解約の<b>解除</b>を再試行すべき払い手の ID を返す（夜次再試行バッチの抽出）。
     *
     * <p>{@code RESTORING} は「Stripe の予約解除に着手したが確定に至っていない」状態であり、
     * 放置すると「退会を取り消したのに期末でメンバーシップが終了する」という利用者の意思に反する結末が残る。
     * 解約側（{@code PENDING}／{@code FAILED}）とは<b>処理の向きが逆</b>のため、必ず別集合として扱う。</p>
     *
     * <p>払い手が再び退会申請中であれば、駆動先の
     * {@link #restoreAllForPayerOnWithdrawalCancelled(Long)} は {@code prepareRestore} の再検証で
     * 空を返す（「退会申請中ではない」ことが解除の前提であるため）。抽出側で退会状態を見ないのは、
     * 見た直後に状態が変わりうる以上、判断は行ロック下の再検証に一本化するのが正しいからである。</p>
     *
     * @return 解除を再試行すべき払い手のユーザー ID（重複なし）
     */
    public List<Long> findWithdrawalRestoreRetryPayerUserIds() {
        Set<Long> payerUserIds = new LinkedHashSet<>();
        payerWithdrawalCancellationRepository
                .findByStatusInOrderByUpdatedAtAsc(List.of(
                        MembershipPayerWithdrawalCancellationStatus.RESTORING))
                .forEach(record -> payerUserIds.add(record.getPayerUserId()));
        payerUserIds.remove(null);
        return List.copyOf(payerUserIds);
    }

    /**
     * 柱③-B PR-3: 退会取消に伴い、<b>退会処理由来で予約した</b>期末解約だけを解除する
     * （設計書 §6.1・Codex 検分1巡目 P1-3）。
     *
     * <p>退会は30日以内に取り消せる（{@code WithdrawalCancelledEvent}）。取り消したのに期末で
     * メンバーシップが終了しては筋が通らないため、予約を解除して元へ戻す。</p>
     *
     * <p><b>単純に payer の {@code cancel_at_period_end=true} を全解除してはならない。</b>
     * それでは<b>本人が退会前に明示解約した契約まで復活させてしまう</b>。boolean 1つでは由来を
     * 区別できないため、{@code membership_payer_withdrawal_cancellations}（退会処理が予約した行だけが
     * 存在する）を由来の正本として引く。</p>
     *
     * @param payerUserId 退会を取り消した払い手のユーザー ID
     * @return 予約を解除できた継続課金の ID 一覧
     */
    public List<UUID> restoreAllForPayerOnWithdrawalCancelled(Long payerUserId) {
        if (payerUserId == null) {
            return List.of();
        }
        List<UUID> targetIds = payerWithdrawalCancellationRepository
                .findByPayerUserIdAndStatusIn(payerUserId, RESTORE_TARGET_STATUSES)
                .stream()
                .map(MembershipPayerWithdrawalCancellationEntity::getSubscriptionId)
                .toList();
        if (targetIds.isEmpty()) {
            log.info("払い手退会取消: 復旧対象なし payerUserId={}", payerUserId);
            return List.of();
        }

        List<UUID> restored = new ArrayList<>();
        for (UUID subscriptionId : targetIds) {
            if (payerWithdrawalRunner.restoreOne(subscriptionId, payerUserId)) {
                restored.add(subscriptionId);
            }
        }
        log.info("払い手退会取消: 期末解約の予約を解除しました payerUserId={}, 対象={}, 解除={}",
                payerUserId, targetIds.size(), restored.size());
        return List.copyOf(restored);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 第四波: skip（今月スキップ）/ resume（再開）/ 一覧 API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 継続課金を今月スキップする（{@code pause_collection・behavior=void}・設計書 02 §4.3）。
     *
     * <p>フロー:</p>
     * <ol>
     *   <li>404 確認: 見つからなければ {@code SUBSCRIPTION_NOT_FOUND}（404）。</li>
     *   <li>認可: 払い手本人 or 後見保護者（{@link #isOwnerOrGuardian}）。無権原は 403。</li>
     *   <li>状態検証: ACTIVE のみ（他→{@code SUBSCRIPTION_NOT_ACTIVE} 409）。</li>
     *   <li>二重スキップ防止: skip_until 設定済→{@code SUBSCRIPTION_ALREADY_SKIPPED} 409。</li>
     *   <li>resumes_at 算出: {@code current_period_end + 1 billing_interval}（period_end 起点・Clock 不要）。</li>
     *   <li>Stripe 先（{@code pause_collection={behavior:void, resumes_at}}）・DB 後。</li>
     *   <li>{@link MembershipSubscriptionEntity#applySkipUntil} で skip_until を反映し save。</li>
     * </ol>
     *
     * <p><b>toBuilder() 禁止:</b> toBuilder() で再構築すると UuidV7Entity の id を失い UPDATE が INSERT 化する（第三波根治済み）。
     * 必ず既存ミューテータで原子的に更新する。</p>
     *
     * @param subscriptionId 継続課金 ID
     * @param actorUserId    操作者（払い手本人 or 後見保護者）
     * @return skip 後の継続課金（skipUntil に再開予定日）
     */
    @Transactional
    public MembershipSubscriptionEntity skip(UUID subscriptionId, Long actorUserId) {
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdAndDeletedAtIsNull(subscriptionId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 認可: 払い手本人 or 受益者の後見保護者（サブスク所有権・03 §1）。
        if (!isOwnerOrGuardian(subscription, actorUserId)) {
            log.info("継続課金 スキップ 拒否（所有者/後見でない）: subscriptionId={}, actor={}, payer={}",
                    subscriptionId, actorUserId, subscription.getPayerUserId());
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_AUTHORIZED);
        }

        // 状態検証: ACTIVE のみスキップ可（02_api §4.3）。
        if (subscription.getStatus() != MembershipSubscriptionStatus.ACTIVE) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }

        // 二重スキップ防止（Entity 側でも検証済みだが Service 層で先に 409 を返す）。
        if (subscription.getSkipUntil() != null) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_ALREADY_SKIPPED);
        }

        // Stripe Subscription ID 未連結は整合性異常として 409。
        if (subscription.getStripeSubscriptionId() == null) {
            log.warn("継続課金 スキップ 不能（stripe_subscription_id 未連結・異常）: subscriptionId={}", subscriptionId);
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }

        // resumes_at = current_period_end + 1 billing_interval（period_end 起点・Clock 不要・02 §4.3）。
        // スキップ月の invoice は void 化され valid_until は延びない（ペイウォール無改修で整合・README §4.5）。
        // period_end が未確定（PENDING）の場合はここまで到達しない（ACTIVE 判定で弾かれる）。
        LocalDate currentPeriodEnd = subscription.getCurrentPeriodEnd();
        if (currentPeriodEnd == null) {
            log.warn("継続課金 スキップ 不能（current_period_end 未設定・整合性異常）: subscriptionId={}", subscriptionId);
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }
        LocalDate resumesAt = addOneCycle(currentPeriodEnd, subscription.getBillingInterval());
        long resumesAtEpochSec = resumesAt.atStartOfDay(UserZoneLocalDateTimeParser.SERVER_ZONE).toEpochSecond();

        // Stripe 先（pause_collection）・DB 後（症状を隠さない・Stripe 失敗は例外を再 throw）。
        stripePaymentProvider.pauseSubscriptionCollection(
                subscription.getStripeSubscriptionId(),
                resumesAtEpochSec,
                "sub-skip-" + subscriptionId);

        subscription.applySkipUntil(resumesAt);
        subscription = membershipSubscriptionRepository.save(subscription);

        log.info("継続課金 今月スキップ: subscriptionId={}, stripeSub={}, resumesAt={}",
                subscriptionId, subscription.getStripeSubscriptionId(), resumesAt);
        return subscription;
    }

    /**
     * 継続課金のスキップ（{@code pause_collection}）を解除して再開する（設計書 02 §4.3）。
     *
     * <p>フロー:</p>
     * <ol>
     *   <li>404 確認・認可（{@link #isOwnerOrGuardian}）。</li>
     *   <li>スキップ未適用の場合→{@code SUBSCRIPTION_NOT_SKIPPED}（409・MEMBERSHIP_BILLING_022）。</li>
     *   <li>Stripe 先（pause_collection 解除）・DB 後（{@link MembershipSubscriptionEntity#clearSkip}）。</li>
     * </ol>
     *
     * @param subscriptionId 継続課金 ID
     * @param actorUserId    操作者（払い手本人 or 後見保護者）
     * @return resume 後の継続課金（skipUntil=null）
     */
    @Transactional
    public MembershipSubscriptionEntity resume(UUID subscriptionId, Long actorUserId) {
        MembershipSubscriptionEntity subscription = membershipSubscriptionRepository
                .findByIdAndDeletedAtIsNull(subscriptionId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_FOUND));

        // 認可: 払い手本人 or 受益者の後見保護者（サブスク所有権・03 §1）。
        if (!isOwnerOrGuardian(subscription, actorUserId)) {
            log.info("継続課金 再開 拒否（所有者/後見でない）: subscriptionId={}, actor={}, payer={}",
                    subscriptionId, actorUserId, subscription.getPayerUserId());
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_AUTHORIZED);
        }

        // スキップ未適用の場合は 409（MEMBERSHIP_BILLING_022）。
        if (subscription.getSkipUntil() == null) {
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_SKIPPED);
        }

        // Stripe Subscription ID 未連結は整合性異常として 409。
        if (subscription.getStripeSubscriptionId() == null) {
            log.warn("継続課金 再開 不能（stripe_subscription_id 未連結・異常）: subscriptionId={}", subscriptionId);
            throw new BusinessException(MembershipBillingErrorCode.SUBSCRIPTION_NOT_ACTIVE);
        }

        // Stripe 先（pause_collection 解除）・DB 後。
        stripePaymentProvider.resumeSubscriptionCollection(
                subscription.getStripeSubscriptionId(),
                "sub-resume-" + subscriptionId);

        subscription.clearSkip();
        subscription = membershipSubscriptionRepository.save(subscription);

        log.info("継続課金 再開（スキップ解除）: subscriptionId={}, stripeSub={}",
                subscriptionId, subscription.getStripeSubscriptionId());
        return subscription;
    }

    /**
     * 払い手向け継続課金一覧を名前解決込みで取得する（{@code GET /api/v1/me/membership-subscriptions}）。
     *
     * <p>払い手本人 ({@code payerUserId}) のサブスクを全件取得し、
     * 会費項目名・受益者表示名を N+1 を防いでバッチ解決する。</p>
     *
     * @param payerUserId 払い手ユーザーID（呼出側で SecurityUtils 解決）
     * @return 作成日時降順の継続課金一覧（名前解決済み）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionListItemResponse> findForPayerWithNames(Long payerUserId) {
        List<MembershipSubscriptionEntity> subs =
                membershipSubscriptionRepository.findByPayerUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(payerUserId);
        return buildListResponse(subs);
    }

    /**
     * チーム向け継続課金一覧を名前解決込みで取得する（{@code GET /api/v1/teams/{id}/membership-subscriptions}）。
     *
     * <p>チーム ID（{@code scopeKind=TEAM, scopeId=teamId}）の全サブスクを取得し、名前解決する。
     * status フィルタが指定された場合はその状態のみ返す。</p>
     *
     * @param teamId 受領主体チーム ID
     * @param status 状態フィルタ（null=全件・02_api §4.1「status フィルタ任意」）
     * @return 作成日時降順の継続課金一覧（名前解決済み）
     */
    @Transactional(readOnly = true)
    public List<MembershipSubscriptionListItemResponse> findForTeamWithNames(Long teamId,
                                                                              MembershipSubscriptionStatus status) {
        List<MembershipSubscriptionEntity> subs;
        if (status != null) {
            subs = membershipSubscriptionRepository
                    .findByScopeKindAndScopeIdAndStatusInAndDeletedAtIsNullOrderByCreatedAtDesc(
                            ScopeKind.TEAM, teamId, Collections.singletonList(status));
        } else {
            subs = membershipSubscriptionRepository
                    .findByScopeKindAndScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(ScopeKind.TEAM, teamId);
        }
        return buildListResponse(subs);
    }

    /**
     * サブスクリストを一覧 DTO へ変換する（N+1 を防ぐためバッチ名前解決）。
     *
     * <p>会費項目名: {@code payment_items} をまとめて取得。
     * 受益者表示名: {@code users} をまとめて取得（論理削除済みも含む・UserEntity.anonymize 済みの表示名を使う）。
     * ドメイン越境は Service 層メソッド経由（CLAUDE.md 原則5）。</p>
     *
     * <p>TODO: 将来的には UserUpdatedEvent で分離予定。現在は読取専用クロスドメイン参照。</p>
     */
    private List<MembershipSubscriptionListItemResponse> buildListResponse(
            List<MembershipSubscriptionEntity> subs) {
        if (subs.isEmpty()) {
            return Collections.emptyList();
        }

        // 会費項目名: payment_item ID を一括取得（N+1 防止）。
        Set<Long> itemIds = subs.stream().map(MembershipSubscriptionEntity::getPaymentItemId)
                .collect(Collectors.toSet());
        Map<Long, String> itemNameMap = paymentItemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(item -> item.getId(), item -> item.getName() != null ? item.getName() : ""));

        // 受益者表示名: users を一括取得（N+1 防止）。
        Set<Long> beneficiaryIds = subs.stream().map(MembershipSubscriptionEntity::getBeneficiaryUserId)
                .collect(Collectors.toSet());
        Map<Long, String> displayNameMap = userRepository.findByIdIn(new java.util.ArrayList<>(beneficiaryIds))
                .stream().collect(Collectors.toMap(
                        UserEntity::getId,
                        u -> u.getDisplayName() != null ? u.getDisplayName() : ""));

        return subs.stream()
                .map(s -> MembershipSubscriptionListItemResponse.from(
                        s,
                        itemNameMap.getOrDefault(s.getPaymentItemId(), ""),
                        displayNameMap.getOrDefault(s.getBeneficiaryUserId(), "")))
                .collect(Collectors.toList());
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
                .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toLocalDate();
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
        return next.atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toEpochSecond();
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
     * 継続課金用の Stripe Price（recurring）を作成する（案C・2明細サブスク・手数料折半の根治）。
     *
     * <p><b>なぜ 2 本作るのか:</b> 手数料の正典（{@link PaymentFeeCalculator}）は「総手数料を支払側と受取側で 50/50 に
     * 折半する」であり、支払側の実請求は {@code 額面＋payerFee}（例 10,000＋250＝10,250）である。初回サイクルは
     * {@code ConnectChargeService.charge} が {@code chargeAmount} で PaymentIntent を作るため正しいが、
     * recurring Price を額面のままにすると 2 サイクル目以降の invoice が 10,000 となり、
     * {@code application_fee_amount} を 500 に上書きしても<b>受取側の着金が 9,500</b>（正: 9,750）となって
     * 受取側が毎月「額面の折半分」を余分に負担してしまう。よって Subscription を
     * 「会費 Price（額面）」＋「支払側手数料 Price（payerFee）」の 2 明細で構成し invoice 合計を 10,250 に揃える。</p>
     *
     * <p><b>{@code payerFee == 0} のときは手数料 Price を作らない</b>（単価 0 の明細を作らない・NULL 保持）。</p>
     *
     * <p><b>{@code payment_items.stripePriceId} は汚染しない:</b> 同カラムは一回払い（額面のみ）の Price を指すため、
     * 金額の異なる recurring Price を書き込むと誤課金の源になる。Product のみ項目へ焼き付けて再利用し、
     * recurring Price は契約側（{@code membership_subscriptions}）に保持する。</p>
     *
     * @param item      会費項目
     * @param interval  課金周期
     * @param payerFee  支払側の折半手数料（0 なら手数料 Price を作らない）
     * @return 生成した recurring Price 群（会費／手数料）
     */
    private RecurringPrices createRecurringPrices(PaymentItemEntity item, BillingInterval interval, long payerFee) {
        String productId = item.getStripeProductId();
        if (productId == null) {
            productId = stripePaymentProvider.createProduct(item.getName(), item.getId());
        }

        // 会費分（額面）。
        String feePriceId = stripePaymentProvider.createRecurringPrice(
                productId, item.getAmount(), item.getCurrency(), interval);

        // 支払側手数料分（payerFee）。0 円の明細は作らない。
        String surchargePriceId = null;
        if (payerFee > 0L) {
            surchargePriceId = stripePaymentProvider.createRecurringPrice(
                    productId, BigDecimal.valueOf(payerFee), item.getCurrency(), interval);
        }

        // Product のみ項目へ焼き付ける（Price 欄は一回払い用のまま触らない）。
        if (!productId.equals(item.getStripeProductId())) {
            item.updateStripeProductId(productId);
            paymentItemService.saveStripeIds(item);
        }
        return new RecurringPrices(feePriceId, surchargePriceId);
    }

    /**
     * 継続課金の recurring Price 群（会費／支払側手数料）。{@code surchargePriceId} は payerFee=0 のとき null。
     */
    private record RecurringPrices(String feePriceId, String surchargePriceId) {

        /** Stripe Subscription へ渡す明細の Price ID 列（会費＋任意で手数料）。 */
        List<String> toPriceIds() {
            return (surchargePriceId == null)
                    ? List.of(feePriceId)
                    : List.of(feePriceId, surchargePriceId);
        }
    }

    /**
     * 安全側の {@code application_fee_percent} を「総手数料 ÷ 実請求額」で算出する（invoice 上書き失敗時の保険）。
     *
     * <p>{@code application_fee_percent} は<b>invoice 総額</b>に対する率なので、額面基準の 5% をそのまま渡すと
     * 総額 10,250 の 5%＝512 となり総手数料 500 を超えて<b>取り過ぎる</b>。実請求額基準
     * （500 ÷ 10,250 ≒ 4.88%）で渡すことで、万一 {@code invoice.created} の固定額上書きが失敗しても
     * 手数料が正しい値の近傍に収まる。</p>
     *
     * <p>Stripe の {@code application_fee_percent} は<b>小数第 2 位まで</b>のため scale=2（HALF_UP）に丸める。</p>
     */
    private static BigDecimal safeApplicationFeePercent(FeeBreakdown fee) {
        if (fee.chargeAmount() <= 0L) {
            return SAFE_DEFAULT_APPLICATION_FEE_PERCENT;
        }
        return BigDecimal.valueOf(fee.applicationFeeAmount())
                .multiply(BigDecimal.valueOf(100L))
                .divide(BigDecimal.valueOf(fee.chargeAmount()), 2, java.math.RoundingMode.HALF_UP);
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
