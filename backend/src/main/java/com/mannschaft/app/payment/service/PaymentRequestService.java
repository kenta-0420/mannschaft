package com.mannschaft.app.payment.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.entity.StripeCustomerEntity;
import com.mannschaft.app.payment.entity.TeamPaymentAdvanceEntity;
import com.mannschaft.app.payment.escrow.ConnectChargeService;
import com.mannschaft.app.payment.escrow.MembershipChargeCommand;
import com.mannschaft.app.payment.escrow.MembershipChargeResult;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F08.9 P7 第一波: 協会→加盟チーム請求サービス（payment_requests・基盤＋支払い）。
 *
 * <p>協会(ORG)が加盟チーム(TEAM)へ請求を発行（{@link #create}・DRAFT）し、取消（{@link #cancel}）でき、
 * チーム ADMIN が「チーム ADMIN 個人の Stripe Customer で立替課金」（案3・README §6.3）して支払う（{@link #pay}）。
 * 配信（SENT 化＋確認必須通知）・OVERDUE バッチ・Controller・一覧 API は第二波。</p>
 *
 * <p><b>money rail（案3 の Stripe 表現・README §6.3 / 01 §3.1）:</b> 支払いは
 * {@link ConnectChargeService#charge(MembershipChargeCommand)}（即時モード・consume のみ・無改変）へ橋渡しする。
 * 既存 {@code charge()} は escrow を {@code source_kind=MEMBERSHIP}・{@code payer_scope=USER}（操作 ADMIN 個人＝
 * 実際の Stripe Customer）で記録する。設計書 §3.1 は協会請求の escrow を {@code payer=TEAM} と記すが、
 * <b>consume 専用の {@code charge()} は payer=USER を焼くため、ここでは payer=USER（ADMIN）で記録し、業務上の
 * 「請求主体＝チーム」は {@code payment_requests.payer_scope}＋{@code team_payment_advances.team_id} が担う</b>
 * （案3 の「課金主体＝個人 Customer／請求主体＝チーム」の乖離を立替記録で埋める設計と整合）。差異は設計書側へ同期記載した。</p>
 *
 * <p>ドメイン境界: payment ドメイン内に閉じる（org/team/user は論理参照・ID のみ）。ADMIN 認可は
 * {@link AccessControlService} 経由でロール判定する（クロスドメイン Repository 参照をしない）。</p>
 *
 * <p>設計書: docs/features/F08.9_membership_billing_paywall/01_data_model.md §2.2 / 02_api_design.md §7 /
 * 03_security.md §1 / README §6。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentRequestService {

    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private static final String SCOPE_TYPE_ORGANIZATION = "ORGANIZATION";

    /** 支払い可能な状態（SENT/VIEWED/OVERDUE）。OVERDUE でも支払える（実運用・02_api §7 / 本第一波で確定）。 */
    private static final Set<PaymentRequestStatus> PAYABLE_STATUSES =
            Set.of(PaymentRequestStatus.SENT, PaymentRequestStatus.VIEWED, PaymentRequestStatus.OVERDUE);

    /** 取消可能な状態（DRAFT/SENT のみ・PAID 後不可）。 */
    private static final Set<PaymentRequestStatus> CANCELLABLE_STATUSES =
            Set.of(PaymentRequestStatus.DRAFT, PaymentRequestStatus.SENT);

    private final PaymentRequestRepository paymentRequestRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final StripePaymentProvider stripePaymentProvider;
    private final ConnectChargeService connectChargeService;
    private final TeamPaymentAdvanceService teamPaymentAdvanceService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * 協会(ORG)が加盟チームへの請求を発行する（DRAFT 起票・02_api §7）。
     *
     * <p>認可: 発行者（協会 ADMIN/DEPUTY_ADMIN）。着金先 Connect 口座は発行者（協会）の scope から解決して
     * 焼き付ける（{@code ORG} scope の {@code connect_accounts}）。口座未登録は
     * {@link MembershipBillingErrorCode#PAYMENT_REQUEST_CONNECT_NOT_READY}（発行時は存在のみ要求し READY は
     * 支払い時に検証するが、口座そのものが無ければ着金先を焼けないため発行時に拒否する）。</p>
     *
     * <p><b>再請求（supersede）:</b> {@code cmd.supersededRequestId()} 指定時は、対象旧行が同テナント・
     * {@code CANCELLED} であることを確認し（循環防止: CANCELLED の行のみ supersede 可）、新行起票後に旧行の
     * {@code supersededById} へ新行を指す。</p>
     *
     * @param orgId       テナント（協会）＝発行者組織 ID
     * @param actorUserId 操作者（協会 ADMIN）
     * @param cmd         発行コマンド
     * @return DRAFT 起票した請求
     */
    public PaymentRequestEntity create(Long orgId, Long actorUserId, CreatePaymentRequestCommand cmd) {
        if (cmd.faceAmount() <= 0L || cmd.faceAmount() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("faceAmount は正の円整数（int 範囲内）であること: " + cmd.faceAmount());
        }
        if (cmd.payerTeamId() == null || cmd.title() == null || cmd.title().isBlank() || cmd.dueDate() == null) {
            throw new IllegalArgumentException("payerTeamId / title / dueDate は必須です");
        }

        // 認可: 協会(ORG) ADMIN/DEPUTY_ADMIN のみ発行できる（03_security §1）。
        requireOrgAdmin(actorUserId, orgId);

        // 着金先（協会の Connect 口座）を ORG scope から解決し焼き付ける。口座が無ければ着金先不在で拒否。
        ConnectAccountEntity payee = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(ScopeKind.ORG, orgId)
                .orElseThrow(() -> new BusinessException(
                        MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY));

        // 再請求（supersede）対象の検証: 同テナント・CANCELLED のみ supersede 可（循環防止）。
        PaymentRequestEntity superseded = null;
        if (cmd.supersededRequestId() != null) {
            superseded = paymentRequestRepository.findByIdAndDeletedAtIsNull(cmd.supersededRequestId())
                    .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
            if (!orgId.equals(superseded.getOrganizationId())) {
                // 他テナントの請求を supersede しようとする IDOR は 404 秘匿。
                throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
            }
            if (superseded.getStatus() != PaymentRequestStatus.CANCELLED) {
                // CANCELLED 以外を supersede するのは不正（再請求は誤キャンセル後のみ・循環防止）。
                throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
            }
        }

        PaymentRequestEntity request = PaymentRequestEntity.builder()
                .organizationId(orgId)
                .issuerScopeKind(ScopeKind.ORG)
                .issuerScopeId(orgId)
                .payerScopeKind(ScopeKind.TEAM)
                .payerScopeId(cmd.payerTeamId())
                .payeeConnectAccountId(payee.getId())
                .title(cmd.title())
                .description(cmd.description())
                .faceAmount((int) cmd.faceAmount())
                .currency(cmd.currency() != null ? cmd.currency() : "JPY")
                .taxCategory(cmd.taxCategory())
                .dueDate(cmd.dueDate())
                .status(PaymentRequestStatus.DRAFT)
                .createdBy(actorUserId)
                .build();
        request = paymentRequestRepository.save(request);

        if (superseded != null) {
            superseded.supersedeBy(request.getId());
            paymentRequestRepository.save(superseded);
            log.info("再請求として発行: newId={}, supersededId={}", request.getId(), superseded.getId());
        }

        recordAudit(AuditEventType.PAYMENT_REQUEST_CREATED, actorUserId, null, orgId,
                String.format("{\"paymentRequestId\":\"%s\",\"payerTeamId\":%d,\"faceAmount\":%d}",
                        request.getId(), cmd.payerTeamId(), cmd.faceAmount()));
        log.info("協会請求を DRAFT 起票: id={}, org={}, payerTeam={}, faceAmount={}",
                request.getId(), orgId, cmd.payerTeamId(), cmd.faceAmount());
        return request;
    }

    /**
     * 協会が請求を取消する（DRAFT/SENT → CANCELLED・PAID 後不可・02_api §7）。
     *
     * @param orgId       テナント（協会）
     * @param requestId   取消対象の請求 ID
     * @param actorUserId 操作者（協会 ADMIN）
     * @return CANCELLED 化した請求
     */
    public PaymentRequestEntity cancel(Long orgId, UUID requestId, Long actorUserId) {
        PaymentRequestEntity request = loadForOrg(orgId, requestId);
        requireOrgAdmin(actorUserId, orgId);

        if (request.getStatus() == PaymentRequestStatus.PAID) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
        }
        if (!CANCELLABLE_STATUSES.contains(request.getStatus())) {
            // VIEWED/OVERDUE/CANCELLED からの取消は不可（症状を隠さず 409）。
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        request.cancel();
        paymentRequestRepository.save(request);

        recordAudit(AuditEventType.PAYMENT_REQUEST_CANCELLED, actorUserId, null, orgId,
                String.format("{\"paymentRequestId\":\"%s\"}", request.getId()));
        log.info("協会請求を CANCELLED: id={}, org={}", request.getId(), orgId);
        return request;
    }

    /**
     * チーム ADMIN が協会請求を支払う（案3 立替課金・SENT/VIEWED/OVERDUE → PAID・02_api §7）。
     *
     * <ol>
     *   <li>請求を ID で引き、{@code payer_scope_kind==TEAM} かつ {@code payer_scope_id==teamId} を検証（IDOR・403）。</li>
     *   <li>当該チーム ADMIN を {@link AccessControlService} で検証（403）。</li>
     *   <li>状態ゲート: SENT/VIEWED/OVERDUE のみ。PAID は 409（二重支払い防止）。他は 409。</li>
     *   <li>着金先（協会の Connect 口座）の READY（payouts_enabled）を<b>支払い時に</b>検証（即時モードゆえ HELD にしない）。</li>
     *   <li>操作 ADMIN 個人の Stripe Customer を get-or-create（案3・課金主体＝個人）。</li>
     *   <li>{@link ConnectChargeService#charge} で Destination PI 作成（consume・無改変）。</li>
     *   <li>請求を PAID 化＋escrow 連結。{@code team_payment_advances} を PENDING で起票（同一 Tx・冪等）。</li>
     * </ol>
     *
     * <p><b>PAID 化のタイミング（設計判断）:</b> escrow は {@code charge()} 直後 AUTHORIZED（succeeded webhook で
     * CAPTURED）だが、設計書 02 §7 は支払い操作で {@code status=PAID} と定める。本第一波は設計書に従い、charge 成功
     * （PI 作成成立＝払い手が支払い操作を完了）をもって請求を PAID とする（会費 member_payments の PENDING→webhook PAID
     * とは状態語彙が異なる＝payment_requests に PENDING が無いため）。webhook 連動の厳密化は第二波の検討事項。</p>
     *
     * @param teamId         請求先チーム ID（URL スコープ）
     * @param requestId      支払い対象の請求 ID
     * @param actorUserId    操作者（チーム ADMIN・立替える個人）
     * @param idempotencyKey 冪等性キー（Idempotency-Key ヘッダ起源・Stripe へ橋渡し）
     * @return 支払い結果（escrow ID / 立替 ID / clientSecret）
     */
    public PaymentRequestPayResult pay(Long teamId, UUID requestId, Long actorUserId, String idempotencyKey) {
        PaymentRequestEntity request = paymentRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));

        // 1. IDOR: 請求先チーム一致を検証（payer_scope_kind=TEAM かつ payer_scope_id==teamId）。
        if (request.getPayerScopeKind() != ScopeKind.TEAM || !request.getPayerScopeId().equals(teamId)) {
            log.warn("協会請求支払い: 請求先チーム不一致（403）: requestId={}, urlTeam={}, payerScope={}/{}",
                    requestId, teamId, request.getPayerScopeKind(), request.getPayerScopeId());
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM);
        }

        // 2. 認可: 当該チーム ADMIN/DEPUTY_ADMIN のみ支払える（03_security §1）。
        requireTeamAdmin(actorUserId, teamId);

        // 3. 状態ゲート: 支払い可能は SENT/VIEWED/OVERDUE のみ。PAID は二重支払い防止で 409。
        if (request.getStatus() == PaymentRequestStatus.PAID) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_ALREADY_PAID);
        }
        if (!PAYABLE_STATUSES.contains(request.getStatus())) {
            // DRAFT（未配信）/CANCELLED からの支払いは不可（症状を隠さず 409）。
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_INVALID_STATUS);
        }

        // 4. 着金先（協会の Connect 口座）の READY を支払い時に検証（発行時ではない・即時モードゆえ HELD にしない）。
        ConnectAccountEntity payee = connectAccountRepository.findById(request.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(
                        MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY));
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            log.warn("協会請求支払い拒否（着金口座が未 READY）: requestId={}, payeeAccountId={}",
                    requestId, payee.getId());
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_CONNECT_NOT_READY);
        }

        // 5. 操作 ADMIN 個人の Stripe Customer を get-or-create（案3・課金主体＝個人）。
        StripeCustomerEntity payerCustomer = getOrCreateStripeCustomer(actorUserId);

        // 6. ConnectChargeService.charge（consume・無改変）。escrow source_id には請求先 teamId を渡す
        //    （payment_request は UUID で escrow source_id=BIGINT に載らないため。実質の二重防止は本メソッドの
        //    status ゲート＋ team_payment_advances の UNIQUE＋ Stripe Idempotency-Key が担う）。
        long faceAmount = request.getFaceAmount().longValue();
        MembershipChargeResult chargeResult = connectChargeService.charge(new MembershipChargeCommand(
                faceAmount,
                payee.getId(),
                payerCustomer.getStripeCustomerId(),
                actorUserId,
                teamId,
                request.getOrganizationId(),
                idempotencyKey));

        // 7. 請求を PAID 化＋escrow 連結。team_payment_advances を PENDING で起票（同一 Tx・冪等）。
        request.markAsPaid(chargeResult.escrowTransactionId());
        paymentRequestRepository.save(request);

        TeamPaymentAdvanceEntity advance = teamPaymentAdvanceService.createAdvance(
                request.getOrganizationId(), teamId, actorUserId,
                chargeResult.escrowTransactionId(), request.getId(),
                request.getFaceAmount(), request.getCurrency());

        recordAudit(AuditEventType.PAYMENT_REQUEST_PAID, actorUserId, teamId, request.getOrganizationId(),
                String.format("{\"paymentRequestId\":\"%s\",\"escrowId\":\"%s\",\"advanceId\":\"%s\"}",
                        request.getId(), chargeResult.escrowTransactionId(), advance.getId()));
        log.info("協会請求を支払い PAID（案3 立替課金）: requestId={}, teamId={}, payer={}, escrowId={}, advanceId={}",
                request.getId(), teamId, actorUserId, chargeResult.escrowTransactionId(), advance.getId());
        return new PaymentRequestPayResult(
                request.getId(), chargeResult.escrowTransactionId(), advance.getId(), chargeResult.clientSecret());
    }

    /**
     * チーム（請求先）が受信した請求一覧を取得する（チーム視点一覧の本体・Controller は第二波）。
     *
     * @param teamId      チーム ID
     * @param actorUserId 操作者（チーム ADMIN）
     * @return 受信請求（新しい順）
     */
    @Transactional(readOnly = true)
    public List<PaymentRequestEntity> findForTeam(Long teamId, Long actorUserId) {
        requireTeamAdmin(actorUserId, teamId);
        return paymentRequestRepository
                .findByPayerScopeKindAndPayerScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(ScopeKind.TEAM, teamId);
    }

    /**
     * 協会（請求元）が発行した請求一覧を取得する（協会視点一覧の本体・Controller は第二波）。
     *
     * @param orgId       協会組織 ID
     * @param actorUserId 操作者（協会 ADMIN）
     * @return 発行請求（新しい順）
     */
    @Transactional(readOnly = true)
    public List<PaymentRequestEntity> findForOrg(Long orgId, Long actorUserId) {
        requireOrgAdmin(actorUserId, orgId);
        return paymentRequestRepository
                .findByIssuerScopeKindAndIssuerScopeIdAndDeletedAtIsNullOrderByCreatedAtDesc(ScopeKind.ORG, orgId);
    }

    // ─── 内部ヘルパー ─────────────

    /** 請求を ID で引き、テナント（org）一致を検証する（不一致・不在は 404 秘匿・IDOR）。 */
    private PaymentRequestEntity loadForOrg(Long orgId, UUID requestId) {
        PaymentRequestEntity request = paymentRequestRepository.findByIdAndDeletedAtIsNull(requestId)
                .orElseThrow(() -> new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND));
        if (!orgId.equals(request.getOrganizationId())) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOUND);
        }
        return request;
    }

    /** 操作者が協会(ORG) ADMIN/DEPUTY_ADMIN であることを要求する。違反は権限なし（403）へ正規化。 */
    private void requireOrgAdmin(Long actorUserId, Long orgId) {
        try {
            accessControlService.checkAdminOrAbove(actorUserId, orgId, SCOPE_TYPE_ORGANIZATION);
        } catch (BusinessException e) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM, e);
        }
    }

    /** 操作者が当該チーム ADMIN/DEPUTY_ADMIN であることを要求する。違反は権限なし（403）へ正規化。 */
    private void requireTeamAdmin(Long actorUserId, Long teamId) {
        try {
            accessControlService.checkAdminOrAbove(actorUserId, teamId, SCOPE_TYPE_TEAM);
        } catch (BusinessException e) {
            throw new BusinessException(MembershipBillingErrorCode.PAYMENT_REQUEST_NOT_FOR_THIS_TEAM, e);
        }
    }

    /** 操作 ADMIN 個人の Stripe Customer を get-or-create（案3・課金主体＝個人・P1 と同パターン）。 */
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

    private void recordAudit(AuditEventType eventType, Long actorUserId, Long teamId, Long orgId, String metadata) {
        auditLogService.record(eventType.name(), actorUserId, null, teamId, orgId, null, null, null, metadata);
    }
}
