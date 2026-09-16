package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PayerRelationship;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentMapper;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.dto.BulkPaymentRequest;
import com.mannschaft.app.payment.dto.BulkPaymentResponse;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.payment.dto.ConnectCheckoutResponse;
import com.mannschaft.app.payment.dto.CreateManualPaymentRequest;
import com.mannschaft.app.payment.dto.MemberPaymentResponse;
import com.mannschaft.app.payment.dto.ReconcileResponse;
import com.mannschaft.app.payment.dto.RemindResponse;
import com.mannschaft.app.payment.dto.UpdatePaymentRequest;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.event.PaymentRemindNotificationEvent;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 支払い記録サービス。手動記録・Stripe 決済・返金・CSV エクスポート等を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberPaymentService {

    private final MemberPaymentRepository memberPaymentRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final PaymentItemService paymentItemService;
    private final StripePaymentProvider stripePaymentProvider;
    private final PaymentMapper paymentMapper;
    private final NameResolverService nameResolverService;
    // Issue #2990 L7: 未払いリマインドの通知は業務TXの外（AFTER_COMMIT）へ移したため、
    // 本サービスは通知コラボレータ（NotificationHelper / UserLocaleCache / MessageSource）を持たない。
    // 受信者 locale の解決と文面の組み立ては PaymentRemindNotificationListener が行う。
    private final ApplicationEventPublisher eventPublisher;

    // === F08.9 P1 Wave4: 払い手分離・Connect 即時 charge 連携 ===
    private final PaymentAuthorizationService paymentAuthorizationService;
    private final ConnectChargeService connectChargeService;
    private final ConnectAccountRepository connectAccountRepository;

    // === F08.9 認可 AC-6: 受益者のスコープ所属検証（F00 正準の AccessControlService 経由）===
    private final AccessControlService accessControlService;

    // === F08.9 受益者制限: チーム/組織別「受益者は会員のみ」設定（既定 ON）と組織配下 MEMBER 判定 ===
    private final PaymentBeneficiarySettingService paymentBeneficiarySettingService;
    private final com.mannschaft.app.organization.service.OrganizationMembershipService organizationMembershipService;

    @Value("${app.base-url}")
    private String baseUrl;

    /**
     * 支払い項目ごとの支払い記録をページング取得する。
     */
    public Page<MemberPaymentResponse> listPayments(Long paymentItemId, String statusFilter, Pageable pageable) {
        Page<MemberPaymentEntity> page;
        if (statusFilter != null) {
            PaymentStatus status = PaymentStatus.valueOf(statusFilter);
            page = memberPaymentRepository.findByPaymentItemIdAndStatus(paymentItemId, status, pageable);
        } else {
            page = memberPaymentRepository.findByPaymentItemId(paymentItemId, pageable);
        }
        Map<Long, String> nameMap = nameResolverService.resolveUserFullNames(
                page.getContent().stream().map(MemberPaymentEntity::getUserId).collect(Collectors.toSet()));
        return page.map(entity -> enrichUserName(paymentMapper.toMemberPaymentResponse(entity), nameMap));
    }

    /**
     * 手動支払い記録を作成する。
     *
     * <p><b>F08.9 P1 Wave4: 払い手分離・ADMIN_MANUAL 認可。</b> {@code recordedBy}（記録した管理者）が
     * 当該 payment_item スコープ（team/org）の ADMIN であることを {@link PaymentAuthorizationService} で検証し
     * （{@code manualRecordByAdmin=true}）、成立した関係（通常 {@link PayerRelationship#ADMIN_MANUAL}、
     * 受益者本人が記録した場合は {@link PayerRelationship#SELF}）を払い手列に埋める。Connect は通さず
     * 手動記録（即 PAID）のままとする（02 §1.1）。権原なき記録は {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）。</p>
     */
    @Transactional
    public MemberPaymentResponse createManualPayment(Long paymentItemId, Long recordedBy,
                                                      CreateManualPaymentRequest request) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        // DONATION 以外は重複チェック
        if (paymentItem.getType() != PaymentItemType.DONATION) {
            if (memberPaymentRepository.existsValidPaidPayment(request.getUserId(), paymentItemId)) {
                throw new BusinessException(PaymentErrorCode.ALREADY_PAID);
            }
        }

        // 払い手分離の認可（02 §1.1 / 03 §2）: 記録者が受益者本人（SELF）か、scope ADMIN（ADMIN_MANUAL）かを検証。
        // 権原が成立しなければ MEMBERSHIP_PAYER_NOT_AUTHORIZED（403）。recorded_by は従来通り記録者を保持する。
        PayerRelationship relationship = paymentAuthorizationService.authorizePayment(
                recordedBy, request.getUserId(), paymentItemId, true);

        // AC-6: 受益者が当該スコープのメンバー（MEMBER 以上・純 SUPPORTER 除外・組織配下チーム所属者は許容・
        // 退会/inactive は除外）であることを検証する。非所属は USER_NOT_MEMBER（PAYMENT_027）。
        verifyBeneficiaryMembership(request.getUserId(), paymentItem);

        LocalDate validFrom = request.getValidFrom() != null
                ? request.getValidFrom()
                : request.getPaidAt().toLocalDate();
        LocalDate validUntil = request.getValidUntil() != null
                ? request.getValidUntil()
                : calculateValidUntilWithItem(paymentItem, validFrom);

        MemberPaymentEntity entity = MemberPaymentEntity.builder()
                .userId(request.getUserId())
                .paymentItemId(paymentItemId)
                .amountPaid(request.getAmountPaid())
                .currency(paymentItem.getCurrency())
                .paymentMethod(resolveManualPaymentMethod(request.getPaymentMethod()))
                .status(PaymentStatus.PAID)
                .validFrom(validFrom)
                .validUntil(validUntil)
                .paidAt(request.getPaidAt())
                .recordedBy(recordedBy)
                .payerUserId(recordedBy)
                .payerRelationship(relationship)
                .note(request.getNote())
                .build();

        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("手動支払い記録: id={}, userId={}, paymentItemId={}, payer={}, relationship={}",
                saved.getId(), request.getUserId(), paymentItemId, recordedBy, relationship);
        return enrichUserName(paymentMapper.toMemberPaymentResponse(saved));
    }

    /**
     * 手動記録の決済手段を解決する。未指定（null）時は {@link PaymentMethod#MANUAL}（その他／不明）にフォールバックする。
     *
     * <p>{@link PaymentMethod#STRIPE} は手動記録では DTO の BeanValidation
     * （{@link CreateManualPaymentRequest#isPaymentMethodAllowedForManual()}）で 400 に弾かれるが、
     * 多層防御として Service 層でも明示的に拒否する（{@code @Valid} を経由しない内部呼び出し・
     * 将来の別経路でも不変条件を Service 自身が保証するため）。STRIPE 指定時は
     * {@link PaymentErrorCode#STRIPE_NOT_ALLOWED_FOR_MANUAL}（400）を投げる。</p>
     */
    private PaymentMethod resolveManualPaymentMethod(PaymentMethod requested) {
        if (requested == PaymentMethod.STRIPE) {
            throw new BusinessException(PaymentErrorCode.STRIPE_NOT_ALLOWED_FOR_MANUAL);
        }
        return requested != null ? requested : PaymentMethod.MANUAL;
    }

    /**
     * 支払い記録を修正する。
     */
    @Transactional
    public MemberPaymentResponse updatePayment(Long paymentItemId, Long paymentId, UpdatePaymentRequest request) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        entity.updateManualPayment(request.getAmountPaid(), request.getValidFrom(),
                request.getValidUntil(), request.getNote());
        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("支払い記録修正: id={}", paymentId);
        return enrichUserName(paymentMapper.toMemberPaymentResponse(saved));
    }

    /**
     * 支払い記録を取り消す（CANCELLED）。
     */
    @Transactional
    public void cancelPayment(Long paymentItemId, Long paymentId) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        if (entity.getStatus() == PaymentStatus.REFUNDED || entity.getStatus() == PaymentStatus.CANCELLED) {
            throw new BusinessException(PaymentErrorCode.ALREADY_REFUNDED);
        }

        entity.markAsCancelled();
        memberPaymentRepository.save(entity);
        log.info("支払い記録取り消し: id={}", paymentId);
    }

    /**
     * 一括手動支払い記録を作成する。
     */
    @Transactional
    public BulkPaymentResponse createBulkPayments(Long paymentItemId, Long recordedBy,
                                                   BulkPaymentRequest request) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        // 欠落① 根治: 一括入金は ADMIN によるバッチ操作。ループに入る前に1度だけ払い手（記録者）の
        // スコープ ADMIN 権原を検証する。非 ADMIN は MEMBERSHIP_PAYER_NOT_AUTHORIZED（403）を投げ、
        // @Transactional により一括処理全体をロールバックする（1件も保存しない・部分保存しない）。
        paymentAuthorizationService.authorizeBulkPaymentByAdmin(recordedBy, paymentItemId);

        int createdCount = 0;
        List<BulkPaymentResponse.SkippedEntry> skipped = new ArrayList<>();

        for (CreateManualPaymentRequest payment : request.getPayments()) {
            try {
                // DONATION 以外は重複チェック
                if (paymentItem.getType() != PaymentItemType.DONATION
                        && memberPaymentRepository.existsValidPaidPayment(payment.getUserId(), paymentItemId)) {
                    skipped.add(new BulkPaymentResponse.SkippedEntry(payment.getUserId(), "ALREADY_PAID"));
                    continue;
                }

                // AC-6: 受益者が当該スコープのメンバー（MEMBER 以上・純 SUPPORTER 除外・配下許容・退会除外）で
                // なければ当該要素を USER_NOT_MEMBER 理由でスキップする（所属分は created）。
                if (!isBeneficiaryMember(payment.getUserId(), paymentItem)) {
                    skipped.add(new BulkPaymentResponse.SkippedEntry(
                            payment.getUserId(), PaymentErrorCode.USER_NOT_MEMBER.getCode()));
                    continue;
                }

                LocalDate validFrom = payment.getValidFrom() != null
                        ? payment.getValidFrom()
                        : payment.getPaidAt().toLocalDate();
                LocalDate validUntil = payment.getValidUntil() != null
                        ? payment.getValidUntil()
                        : calculateValidUntilWithItem(paymentItem, validFrom);

                MemberPaymentEntity entity = MemberPaymentEntity.builder()
                        .userId(payment.getUserId())
                        .paymentItemId(paymentItemId)
                        .amountPaid(payment.getAmountPaid())
                        .currency(paymentItem.getCurrency())
                        .paymentMethod(resolveManualPaymentMethod(payment.getPaymentMethod()))
                        .status(PaymentStatus.PAID)
                        .validFrom(validFrom)
                        .validUntil(validUntil)
                        .paidAt(payment.getPaidAt())
                        .recordedBy(recordedBy)
                        .note(payment.getNote())
                        .build();

                memberPaymentRepository.save(entity);
                createdCount++;
            } catch (Exception e) {
                skipped.add(new BulkPaymentResponse.SkippedEntry(payment.getUserId(), e.getMessage()));
            }
        }

        log.info("一括支払い記録: paymentItemId={}, created={}, skipped={}", paymentItemId, createdCount, skipped.size());
        return new BulkPaymentResponse(createdCount, skipped.size(), skipped);
    }

    /**
     * 全額返金を実行する。
     */
    @Transactional
    public MemberPaymentResponse refundPayment(Long paymentItemId, Long paymentId, Long refundedBy) {
        MemberPaymentEntity entity = memberPaymentRepository.findByIdAndPaymentItemId(paymentId, paymentItemId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // STRIPE（オンライン決済）以外は返金不可（CASH/BANK_TRANSFER/MANUAL のオフライン記録は取り消しで運用）。
        if (entity.getPaymentMethod() != PaymentMethod.STRIPE) {
            throw new BusinessException(PaymentErrorCode.MANUAL_PAYMENT_NOT_REFUNDABLE);
        }
        if (entity.getStatus() == PaymentStatus.REFUNDED || entity.getStatus() == PaymentStatus.CANCELLED) {
            throw new BusinessException(PaymentErrorCode.ALREADY_REFUNDED);
        }
        if (entity.getStatus() == PaymentStatus.PENDING) {
            throw new BusinessException(PaymentErrorCode.PENDING_PAYMENT_NOT_REFUNDABLE);
        }

        // Stripe 先、DB 後
        String refundId = stripePaymentProvider.createRefund(
                entity.getStripePaymentIntentId(), paymentId, refundedBy);

        entity.markAsRefunded(refundId);
        MemberPaymentEntity saved = memberPaymentRepository.save(entity);
        log.info("返金実行: id={}, refundId={}", paymentId, refundId);
        return enrichUserName(paymentMapper.toMemberPaymentResponse(saved));
    }

    /**
     * Stripe Checkout セッションを作成する。
     */
    @Transactional
    public CheckoutResponse createCheckout(Long paymentItemId, Long userId) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        if (paymentItem.getStripePriceId() == null) {
            throw new BusinessException(PaymentErrorCode.STRIPE_PRICE_NOT_SET);
        }

        // DONATION 以外は重複チェック
        if (paymentItem.getType() != PaymentItemType.DONATION) {
            if (memberPaymentRepository.existsValidPaidPayment(userId, paymentItemId)) {
                throw new BusinessException(PaymentErrorCode.ALREADY_PAID);
            }
        }

        // Stripe Customer の取得または作成
        StripeCustomerEntity stripeCustomer = getOrCreateStripeCustomer(userId);

        // PENDING レコードを作成
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(userId)
                .paymentItemId(paymentItemId)
                .amountPaid(paymentItem.getAmount())
                .currency(paymentItem.getCurrency())
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PENDING)
                .build();
        payment = memberPaymentRepository.save(payment);

        // Checkout Session を作成
        String successUrl = baseUrl + "/payment/complete?session_id={CHECKOUT_SESSION_ID}";
        String cancelUrl = baseUrl + "/payment/cancelled";

        StripePaymentProvider.CheckoutSessionInfo sessionInfo =
                stripePaymentProvider.createCheckoutSession(
                        paymentItem.getStripePriceId(),
                        stripeCustomer.getStripeCustomerId(),
                        payment.getId(),
                        successUrl,
                        cancelUrl
                );

        payment.setStripeCheckoutSessionId(sessionInfo.sessionId());
        memberPaymentRepository.save(payment);

        log.info("Checkout セッション作成: paymentId={}, sessionId={}", payment.getId(), sessionInfo.sessionId());
        return new CheckoutResponse(sessionInfo.checkoutUrl(), sessionInfo.sessionId(), sessionInfo.expiresAt());
    }

    /**
     * F08.9 P1 Wave4 (T7): 会費を払い手分離＋Connect 即時 charge でチェックアウトする（設計書 02 §1.1）。
     *
     * <p><b>既存の素 Checkout（自社集金・{@link #createCheckout}）は壊さず、会費の新規決済を Connect 即時へ
     * 切り替える専用経路。</b> 受領者（チーム/組織）の Connect 口座へ destination charge し、手数料を
     * application_fee で控除する。払い手は受益者と別人でもよい（IDOR は権原検証で防ぐ）。</p>
     *
     * <p>フロー（02 §1.1）:</p>
     * <ol>
     *   <li><b>払い手の確定</b>: {@code payerUserId}（呼出側が {@code SecurityUtils.getCurrentUserId()} で解決）。
     *       後見切替（X-Proxy-For-User-Id）でも払い手はログインユーザーのまま（P1 では考慮不要）。</li>
     *   <li><b>権原検証</b>: {@link PaymentAuthorizationService#authorizePayment}（{@code manualRecordByAdmin=false}）。
     *       P1 では SELF のみ通過し、他は {@code MEMBERSHIP_PAYER_NOT_AUTHORIZED}（403）。</li>
     *   <li><b>重複チェック</b>: {@code existsValidPaidPayment(beneficiary, item)} 真なら
     *       {@code MEMBERSHIP_ALREADY_PAID}（409）。</li>
     *   <li><b>受領者 Connect 口座解決</b>: payment_item のスコープ（team/org）→ {@link ScopeKind} で口座を引き、
     *       READY（{@code payouts_enabled}）でなければ {@code ONBOARDING_NOT_READY}（409）。即時モードゆえ HELD にしない。</li>
     *   <li><b>払い手 Stripe Customer の get-or-create</b>。</li>
     *   <li><b>charge</b>: {@link ConnectChargeService#charge} で Destination PaymentIntent を作成し
     *       {@code clientSecret} を払い手本人へ返す。</li>
     *   <li><b>PENDING 起票</b>: {@code member_payments} を PENDING で起票し、払い手列・{@code escrow_transaction_id}
     *       を埋める（受益者＝{@code userId}）。CAPTURED→PAID 反映は escrow の {@code payment_intent.succeeded}
     *       webhook → {@code EscrowCapturedEvent} → {@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener}
     *       が行う。</li>
     * </ol>
     *
     * @param paymentItemId     支払い対象の会費項目
     * @param beneficiaryUserId 受益者（会費の対象者）
     * @param payerUserId       払い手（実際に支払うログインユーザー・呼出側で SecurityUtils 解決）
     * @param idempotencyKey    冪等性キー（Idempotency-Key ヘッダ起源・Stripe へ橋渡し）
     * @return clientSecret / memberPaymentId / escrowTransactionId
     */
    @Transactional
    public ConnectCheckoutResponse createConnectCheckout(Long paymentItemId, Long beneficiaryUserId,
                                                         Long payerUserId, String idempotencyKey) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        // 1-2. 払い手分離の権原検証（P1 では SELF のみ通過・他は 403）。manualRecordByAdmin=false（Connect 即時決済）。
        PayerRelationship relationship = paymentAuthorizationService.authorizePayment(
                payerUserId, beneficiaryUserId, paymentItemId, false);

        // 3. 重複チェック（受益者×項目に有効な PAID があれば 409）。DONATION は重複を許す。
        if (paymentItem.getType() != PaymentItemType.DONATION
                && memberPaymentRepository.existsValidPaidPayment(beneficiaryUserId, paymentItemId)) {
            throw new BusinessException(MembershipBillingErrorCode.MEMBERSHIP_ALREADY_PAID);
        }

        // 4. 受領者 Connect 口座をスコープから解決し READY を判定（即時モードゆえ非 READY は HELD にせず 409）。
        ConnectAccountEntity payee = resolvePayeeConnectAccount(paymentItem);
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            log.warn("会費 Connect checkout 拒否（受領口座が未 READY）: itemId={}, payeeAccount={}, payoutsEnabled={}",
                    paymentItemId, payee.getStripeAccountId(), payee.getPayoutsEnabled());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }

        // 5. 払い手の Stripe Customer を get-or-create（払い手＝決済者の Customer）。
        StripeCustomerEntity payerCustomer = getOrCreateStripeCustomer(payerUserId);

        // 6. ConnectChargeService.charge（Destination PI 作成・即時 AUTOMATIC・冪等キーを Stripe へ橋渡し）。
        long faceAmount = paymentItem.getAmount().longValueExact();
        MembershipChargeResult chargeResult = connectChargeService.charge(new MembershipChargeCommand(
                faceAmount,
                payee.getId(),
                payerCustomer.getStripeCustomerId(),
                payerUserId,
                paymentItemId,
                paymentItem.getOrganizationId(),
                idempotencyKey));

        // 7. member_payments を PENDING で起票（払い手列・escrow_transaction_id を埋める。受益者＝userId）。
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(beneficiaryUserId)
                .paymentItemId(paymentItemId)
                .amountPaid(paymentItem.getAmount())
                .currency(paymentItem.getCurrency())
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PENDING)
                .payerUserId(payerUserId)
                .payerRelationship(relationship)
                .escrowTransactionId(chargeResult.escrowTransactionId())
                .build();
        payment = memberPaymentRepository.save(payment);

        log.info("会費 Connect checkout 起票（PENDING・PAID は webhook で反映）: paymentId={}, beneficiary={}, payer={}, "
                        + "relationship={}, escrowId={}",
                payment.getId(), beneficiaryUserId, payerUserId, relationship, chargeResult.escrowTransactionId());
        return new ConnectCheckoutResponse(
                chargeResult.clientSecret(), payment.getId(), chargeResult.escrowTransactionId());
    }

    /**
     * F08.9 P5 第二波: 継続課金（subscribe）の初回単発 charge 由来の支払いを {@code member_payments} に
     * PENDING で起票する（設計書 02 §4.1・初回は P1 同型の単発 destination charge）。
     *
     * <p>{@link MembershipSubscriptionService#subscribe} が初回 charge（{@link ConnectChargeService#charge}）の後に呼ぶ。
     * P1 の {@link #createConnectCheckout} と同じ列（払い手分離・{@code escrow_transaction_id}）を埋めつつ、
     * 継続課金由来であることを示す {@code membership_subscription_id} を連結する。PENDING→PAID 反映は
     * 既存の escrow CAPTURED 連動（{@link #applyMembershipPaidByEscrow}）に相乗りする。</p>
     *
     * @param beneficiaryUserId      受益者（会費の対象者・{@code member_payments.user_id}）
     * @param paymentItemId          会費項目 ID
     * @param amount                 額面（起票額・P1 同型で payment_item の amount）
     * @param currency               通貨
     * @param payerUserId            払い手ユーザー ID
     * @param relationship           払い手・受益者の関係（権原評価結果）
     * @param escrowTransactionId    初回 charge で作成した escrow の ID
     * @param membershipSubscriptionId 親サブスクリプション ID（継続課金連結キー）
     * @return 起票した PENDING の {@code member_payments.id}
     */
    @Transactional
    public Long recordSubscriptionInitialChargePending(Long beneficiaryUserId, Long paymentItemId,
                                                        java.math.BigDecimal amount, String currency,
                                                        Long payerUserId, PayerRelationship relationship,
                                                        UUID escrowTransactionId, UUID membershipSubscriptionId) {
        MemberPaymentEntity payment = MemberPaymentEntity.builder()
                .userId(beneficiaryUserId)
                .paymentItemId(paymentItemId)
                .amountPaid(amount)
                .currency(currency)
                .paymentMethod(PaymentMethod.STRIPE)
                .status(PaymentStatus.PENDING)
                .payerUserId(payerUserId)
                .payerRelationship(relationship)
                .escrowTransactionId(escrowTransactionId)
                .membershipSubscriptionId(membershipSubscriptionId)
                .build();
        payment = memberPaymentRepository.save(payment);
        log.info("継続課金 初回 charge 起票（PENDING・PAID は escrow webhook 連動）: paymentId={}, beneficiary={}, "
                        + "payer={}, escrowId={}, subscriptionId={}",
                payment.getId(), beneficiaryUserId, payerUserId, escrowTransactionId, membershipSubscriptionId);
        return payment.getId();
    }

    /**
     * F08.9 P1 Wave4 (T8): escrow が MEMBERSHIP を CAPTURED にしたとき、{@code member_payments} を
     * PENDING→PAID に反映する（設計書 02 §1.1 / §4.2）。
     *
     * <p>{@link com.mannschaft.app.payment.escrow.MembershipPaymentCaptureListener} が
     * {@code EscrowCapturedEvent}（AFTER_COMMIT）を受けて呼ぶ。{@code escrow_transaction_id} で member_payment を
     * 突合し、PENDING のときのみ PAID 化して有効期間（type 別: ANNUAL_FEE+365 / MONTHLY_FEE+31 / ITEM・DONATION=null）
     * を設定する。</p>
     *
     * <p><b>冪等（二重反映防止）:</b> 対象が見つからない／既に PENDING でない（PAID 等）場合は no-op で正常終了する
     * （webhook 再送・同期確定の二経路で二度呼ばれても二重 PAID にしない）。escrow 側の CAPTURED 冪等
     * （行ロック＋status 再判定）と相まって二重課金・二重反映を防ぐ。</p>
     *
     * @param escrowTransactionId CAPTURED になった escrow の ID（member_payments との突合キー）
     * @return 本呼び出しで新規に PENDING→PAID にした member_payment が継続課金由来（{@code membership_subscription_id}
     *         連結）の場合はその継続課金 ID（呼び出し側＝リスナが PENDING→ACTIVE 化の起点に用いる・F08.9 P5 第三波）。
     *         会費外/未連結/既に PAID/後段状態/単発（subscription 連結なし）の場合は {@code null}。
     */
    @Transactional
    public UUID applyMembershipPaidByEscrow(UUID escrowTransactionId) {
        MemberPaymentEntity payment = memberPaymentRepository
                .findByEscrowTransactionId(escrowTransactionId)
                .orElse(null);
        if (payment == null) {
            // 会費以外の escrow（RECRUITMENT 等）や、Connect checkout を経由しない記録には member_payment が無い。no-op。
            log.info("会費 PAID 反映: escrow に対応する member_payment なし（会費外/未連結）。no-op: escrowId={}",
                    escrowTransactionId);
            return null;
        }
        if (payment.getStatus() != PaymentStatus.PAID
                && payment.getStatus() != PaymentStatus.PENDING) {
            // CANCELLED/REFUNDED 等の後段状態は触らない（症状を隠さず情報ログ）。
            log.info("会費 PAID 反映: member_payment が後段状態のため反映しない: paymentId={}, status={}",
                    payment.getId(), payment.getStatus());
            return null;
        }
        if (payment.getStatus() == PaymentStatus.PAID) {
            // 既に PAID（webhook 再送・同期確定の二経路）。冪等 no-op。PENDING→ACTIVE 化は subscription 側の冪等で防ぐため
            // ここで subscription ID を返さない（二重 ACTIVE トリガを避ける・既に活性化済みのはず）。
            log.info("会費 PAID 反映: 既に PAID（冪等 no-op）: paymentId={}, escrowId={}",
                    payment.getId(), escrowTransactionId);
            return null;
        }

        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(payment.getPaymentItemId());
        LocalDate validFrom = LocalDate.now();
        LocalDate validUntil = calculateValidUntilWithItem(paymentItem, validFrom);
        payment.markAsPaidByEscrowCapture(validFrom, validUntil);
        memberPaymentRepository.save(payment);
        log.info("会費 PAID 反映完了（escrow CAPTURED 連動）: paymentId={}, escrowId={}, validUntil={}, subscriptionId={}",
                payment.getId(), escrowTransactionId, validUntil, payment.getMembershipSubscriptionId());
        // 継続課金の初回単発 charge 由来（membership_subscription_id 連結）なら、その ID を返して
        // 呼び出し側（MembershipPaymentCaptureListener）が PENDING→ACTIVE 化を起こす（案b の活性化点・F08.9 P5 第三波）。
        return payment.getMembershipSubscriptionId();
    }

    /**
     * 受益者が payment_item のスコープのメンバー（AC-6）であることを検証する。非所属は
     * {@link PaymentErrorCode#USER_NOT_MEMBER}（PAYMENT_027・403/404 系の WARN）を投げる（単一記録用・死にコード解消）。
     *
     * <p>判定は {@link #isBeneficiaryMember} に委譲し、一括（skip）と検証ロジックを共通化する。</p>
     */
    private void verifyBeneficiaryMembership(Long beneficiaryUserId, PaymentItemEntity paymentItem) {
        if (!isBeneficiaryMember(beneficiaryUserId, paymentItem)) {
            log.info("会費受益者がスコープ非所属（手動入金拒否）: userId={}, itemId={}",
                    beneficiaryUserId, paymentItem.getId());
            throw new BusinessException(PaymentErrorCode.USER_NOT_MEMBER);
        }
    }

    /**
     * 受益者が payment_item のスコープの受益者要件を満たすかを返す（AC-6・単一/一括共通の所属判定）。
     *
     * <p>判定はチーム/組織別の<b>「受益者は会員のみ」設定（{@link PaymentBeneficiarySettingService}・既定 ON）</b>で分岐する:</p>
     *
     * <p><b>memberOnly = false（応援者も受益者可）</b> — 従来挙動を維持し
     * {@link AccessControlService#isMemberOrDescendant}（{@code includeSupporters=false}）に委譲する。
     * ただし TEAM スコープでは {@code isMember} が role_kind を問わないため、純 SUPPORTER も所属していれば許容される
     * （= 応援者も受益者可・設定 OFF の意図通り）。ORGANIZATION スコープは配下チームの SUPPORTER を除外する。</p>
     *
     * <p><b>memberOnly = true（既定・会員のみ）</b> — 全スコープで純 SUPPORTER を除外しつつ組織配下 MEMBER を許容する
     * （実 {@link AccessControlService} の挙動を確認済・下記根拠）:</p>
     * <ul>
     *   <li><b>TEAM</b>: {@code hasRoleOrAbove(userId, scopeId, "TEAM", "MEMBER")}。
     *       {@code resolveEffectiveRole} が user_roles＋memberships.role_kind を統合し priority 最小（最強）を採り、
     *       {@code effective.priority() <= MEMBER.priority()} で比較する。priority は MEMBER(4) < SUPPORTER(5)
     *       （V2.014__seed_roles.sql）なので純 SUPPORTER は false（確実に除外）。</li>
     *   <li><b>ORGANIZATION</b>: 組織に直接 MEMBER 権限ロール/所属を持つ場合は {@code hasRoleOrAbove(.., "ORGANIZATION", "MEMBER")}
     *       で許容。配下チームの MEMBER は直接 ORG ロールを持たないため {@code hasRoleOrAbove} では拾えないので、
     *       {@code OrganizationMembershipService.isInOrgDistributionAudience(orgId, userId, false)}（純 SUPPORTER 除外・
     *       配下 MEMBER 許容）との OR で合成する。これにより「配下チームの会員は受益者可・純 SUPPORTER は不可」を満たす。</li>
     * </ul>
     *
     * <p><b>退会/inactive 除外</b>はいずれの経路でも担保される（{@code isMember}/{@code resolveEffectiveRole} は
     * {@code memberships.leftAt IS NULL}／アクティブな user_roles を見るため）。
     * スコープ解決（team_id / organization_id → scopeId / scopeType）は {@link #resolveScopeType} /
     * {@link #resolveScopeId} に共通化する。スコープ未設定の不整合データは判定不能のため非所属（false）に倒す
     * （fail-safe・症状を隠さない）。</p>
     */
    private boolean isBeneficiaryMember(Long beneficiaryUserId, PaymentItemEntity paymentItem) {
        String scopeType = resolveScopeType(paymentItem);
        Long scopeId = resolveScopeId(paymentItem);
        if (scopeType == null || scopeId == null) {
            log.warn("payment_item にスコープ（team/org）が無く受益者の所属を判定できません: itemId={}", paymentItem.getId());
            return false;
        }

        boolean memberOnly = paymentBeneficiarySettingService.isMemberOnly(
                paymentItem.getTeamId(), paymentItem.getOrganizationId());
        if (!memberOnly) {
            // 設定 OFF: 従来挙動（応援者も受益者可。TEAM は SUPPORTER 許容・ORG は配下 SUPPORTER 除外）。
            return accessControlService.isMemberOrDescendant(beneficiaryUserId, scopeId, scopeType, false);
        }

        // 設定 ON（既定・会員のみ）: 全スコープで純 SUPPORTER を除外し、組織配下 MEMBER は許容する。
        if ("ORGANIZATION".equals(scopeType)) {
            return accessControlService.hasRoleOrAbove(beneficiaryUserId, scopeId, "ORGANIZATION", "MEMBER")
                    || organizationMembershipService.isInOrgDistributionAudience(scopeId, beneficiaryUserId, false);
        }
        return accessControlService.hasRoleOrAbove(beneficiaryUserId, scopeId, "TEAM", "MEMBER");
    }

    /**
     * payment_item のスコープ種別を解決する（{@code "TEAM"} / {@code "ORGANIZATION"}）。
     * team_id 優先・いずれも未設定なら {@code null}（AC-6 / 払い手認可で共通使用）。
     */
    private String resolveScopeType(PaymentItemEntity paymentItem) {
        if (paymentItem.getTeamId() != null) {
            return "TEAM";
        }
        if (paymentItem.getOrganizationId() != null) {
            return "ORGANIZATION";
        }
        return null;
    }

    /**
     * payment_item のスコープ ID を解決する（team_id 優先・いずれも未設定なら {@code null}）。
     */
    private Long resolveScopeId(PaymentItemEntity paymentItem) {
        if (paymentItem.getTeamId() != null) {
            return paymentItem.getTeamId();
        }
        return paymentItem.getOrganizationId();
    }

    /**
     * payment_item のスコープ（team/org）から受領者の Connect 口座を解決する。
     *
     * <p>team_id 設定時は {@link ScopeKind#TEAM}、organization_id 設定時は {@link ScopeKind#ORG} で
     * {@link ConnectAccountRepository#findByScopeKindAndScopeIdAndDeletedAtIsNull} を引く。口座が無ければ
     * {@code ONBOARDING_NOT_READY}（受領者が口座未登録＝READY でない・409）。スコープ未設定の不整合データは
     * 解決不能のため同コードで拒否する（症状を隠さない）。</p>
     */
    private ConnectAccountEntity resolvePayeeConnectAccount(PaymentItemEntity paymentItem) {
        ScopeKind scopeKind;
        Long scopeId;
        if (paymentItem.getTeamId() != null) {
            scopeKind = ScopeKind.TEAM;
            scopeId = paymentItem.getTeamId();
        } else if (paymentItem.getOrganizationId() != null) {
            scopeKind = ScopeKind.ORG;
            scopeId = paymentItem.getOrganizationId();
        } else {
            log.warn("payment_item にスコープ（team/org）が無く Connect 口座を解決できません: itemId={}", paymentItem.getId());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }
        return connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scopeKind, scopeId)
                .orElseThrow(() -> {
                    log.warn("受領者の Connect 口座が未登録（READY でない）: itemId={}, scope={}/{}",
                            paymentItem.getId(), scopeKind, scopeId);
                    return new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
                });
    }

    /**
     * ユーザーの Stripe Customer を取得、無ければ作成する（F08.9 P1 Wave4・払い手の Customer）。
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

    /**
     * 未払いリマインドを送信する。
     */
    @Transactional
    public RemindResponse sendRemind(Long paymentItemId) {
        PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(paymentItemId);

        if (paymentItem.getType() == PaymentItemType.DONATION) {
            throw new BusinessException(PaymentErrorCode.DONATION_REMIND_NOT_ALLOWED);
        }

        // 未払いメンバーの取得。通知は業務TXに参加させず、commit 後に
        // PaymentRemindNotificationListener が受信者ごと独立トランザクションで配送する（#2990 L7）。
        List<Long> unpaidUserIds = memberPaymentRepository.findUnpaidUserIdsByPaymentItemId(paymentItemId);
        Long scopeId = paymentItem.getTeamId() != null
                ? paymentItem.getTeamId() : paymentItem.getOrganizationId();
        eventPublisher.publishEvent(new PaymentRemindNotificationEvent(
                paymentItemId, paymentItem.getTeamId(), scopeId, unpaidUserIds));
        log.info("リマインド送信: paymentItemId={}, notifiedCount={}", paymentItemId, unpaidUserIds.size());
        return new RemindResponse(unpaidUserIds.size(), paymentItem.getName());
    }

    /**
     * 支払い状況を CSV バイト列で取得する。
     */
    public byte[] exportPaymentsCsv(Long paymentItemId) {
        paymentItemService.findByIdOrThrow(paymentItemId);
        List<MemberPaymentEntity> payments = memberPaymentRepository.findByPaymentItemId(paymentItemId);

        // メンバー名をバッチ解決
        Set<Long> userIds = payments.stream()
                .map(MemberPaymentEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(userIds);

        StringBuilder sb = new StringBuilder();
        // BOM 付き UTF-8
        sb.append('\uFEFF');
        sb.append("メンバーID,メンバー名,ステータス,支払い金額,通貨,支払い方法,支払い日時,有効期間開始,有効期間終了,備考\n");

        for (MemberPaymentEntity payment : payments) {
            sb.append(payment.getUserId()).append(',');
            sb.append(escapeCsv(nameMap.getOrDefault(payment.getUserId(), "不明"))).append(',');
            sb.append(payment.getStatus().name()).append(',');
            sb.append(payment.getAmountPaid()).append(',');
            sb.append(payment.getCurrency()).append(',');
            sb.append(payment.getPaymentMethod().name()).append(',');
            sb.append(payment.getPaidAt() != null ? payment.getPaidAt() : "").append(',');
            sb.append(payment.getValidFrom() != null ? payment.getValidFrom() : "").append(',');
            sb.append(payment.getValidUntil() != null ? payment.getValidUntil() : "").append(',');
            sb.append(payment.getNote() != null ? payment.getNote().replace(",", "，") : "");
            sb.append('\n');
        }

        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 自分の支払い記録をページング取得する。
     */
    public Page<MemberPaymentResponse> listMyPayments(Long userId, Pageable pageable) {
        Page<MemberPaymentEntity> page =
                memberPaymentRepository.findByUserIdOrderByPaidAtDescCreatedAtDesc(userId, pageable);
        Map<Long, String> nameMap = nameResolverService.resolveUserFullNames(
                page.getContent().stream().map(MemberPaymentEntity::getUserId).collect(Collectors.toSet()));
        return page.map(entity -> enrichUserName(paymentMapper.toMemberPaymentResponse(entity), nameMap));
    }

    /**
     * Stripe 手動再同期を実行する。
     */
    @Transactional
    public ReconcileResponse reconcile(Long paymentId) {
        MemberPaymentEntity entity = memberPaymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.PAYMENT_NOT_FOUND));

        // STRIPE（オンライン決済）以外は再同期不可（CASH/BANK_TRANSFER/MANUAL のオフライン記録は対象外）。
        if (entity.getPaymentMethod() != PaymentMethod.STRIPE) {
            throw new BusinessException(PaymentErrorCode.STRIPE_PAYMENT_ONLY);
        }

        String previousStatus = entity.getStatus().name();
        StripePaymentProvider.SessionStatusInfo statusInfo =
                stripePaymentProvider.retrieveSessionStatus(entity.getStripeCheckoutSessionId());

        boolean reconciled = false;
        if ("succeeded".equals(statusInfo.paymentIntentStatus())
                && entity.getStatus() == PaymentStatus.PENDING) {
            PaymentItemEntity paymentItem = paymentItemService.findByIdOrThrow(entity.getPaymentItemId());
            LocalDate validFrom = LocalDate.now();
            LocalDate validUntil = calculateValidUntilWithItem(paymentItem, validFrom);
            entity.markAsPaid(statusInfo.paymentIntentId(), entity.getAmountPaid(),
                    validFrom, validUntil, null);
            reconciled = true;
        } else if ("expired".equals(statusInfo.paymentIntentStatus())
                && entity.getStatus() == PaymentStatus.PENDING) {
            entity.markAsCancelled();
            reconciled = true;
        }

        if (reconciled) {
            memberPaymentRepository.save(entity);
        }

        log.info("Stripe 再同期: paymentId={}, reconciled={}", paymentId, reconciled);
        return new ReconcileResponse(paymentId, previousStatus, entity.getStatus().name(),
                statusInfo.paymentIntentStatus(), reconciled);
    }

    /**
     * 単一レスポンスに会員実名（userName）を充填する。退会者は「不明なユーザー」。
     */
    private MemberPaymentResponse enrichUserName(MemberPaymentResponse response) {
        if (response == null) {
            return null;
        }
        return response.toBuilder()
                .userName(nameResolverService.resolveUserFullName(response.getUserId()))
                .build();
    }

    /**
     * バッチ解決済みの名前マップを使ってレスポンスに会員実名（userName）を充填する。
     */
    private MemberPaymentResponse enrichUserName(MemberPaymentResponse response, Map<Long, String> nameMap) {
        if (response == null) {
            return null;
        }
        return response.toBuilder()
                .userName(nameMap.getOrDefault(response.getUserId(), "不明なユーザー"))
                .build();
    }

    /**
     * CSV 出力用のエスケープ処理。カンマを全角カンマに置換する。
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return value.replace(",", "，");
    }

    /**
     * 有効期限を計算する。
     *
     * <p>TERM 型の場合は {@code termEndsOn} を返す（単発 destination charge の有効期間は期別設定値）。
     * TERM 型で {@code termEndsOn} が設定されていない場合は null（通常はバリデーションで防ぐ）。</p>
     */
    private LocalDate calculateValidUntil(PaymentItemType type, LocalDate validFrom) {
        return switch (type) {
            case ANNUAL_FEE -> validFrom.plusDays(365);
            case MONTHLY_FEE -> validFrom.plusDays(31);
            case ITEM, DONATION -> null;
            case TERM -> null; // TERM は paymentItem.termEndsOn を使う（calculateValidUntilWithItem で解決）
        };
    }

    /**
     * 有効期限を PaymentItemEntity の情報を含めて計算する。TERM 型は {@code termEndsOn} を返す。
     */
    private LocalDate calculateValidUntilWithItem(PaymentItemEntity paymentItem, LocalDate validFrom) {
        if (paymentItem.getType() == PaymentItemType.TERM) {
            return paymentItem.getTermEndsOn(); // null の場合はバリデーションで防止済み
        }
        return calculateValidUntil(paymentItem.getType(), validFrom);
    }
}
