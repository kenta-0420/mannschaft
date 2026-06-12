package com.mannschaft.app.payment.escrow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.FeeBreakdown;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyResolver;
import com.mannschaft.app.payment.PaymentFeeCalculator;
import com.mannschaft.app.payment.connect.ConnectAccountEntity;
import com.mannschaft.app.payment.connect.ConnectAccountRepository;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.payment.connect.PayeeScopeResolver;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.recovery.FeeRecoveryBalanceEntity;
import com.mannschaft.app.payment.recovery.FeeRecoveryBalanceRepository;
import com.mannschaft.app.payment.recovery.FeeRecoveryCalculator;
import com.mannschaft.app.payment.stripe.CaptureMethod;
import com.mannschaft.app.payment.stripe.StripePaymentProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * F22.1 統一決済 P2-b: 共通送金サービス（与信＝authorize の中核・設計書 02 §0 / §5.1）。
 *
 * <p>謝礼（{@link EscrowSourceKind#RECRUITMENT}・エスクローモード）の与信を一元化する。
 * 手数料折半（5%＝支払者2.5%+受取側2.5%）は {@link PaymentFeeCalculator} に集約し、本サービスでは
 * 数式を再実装しない（設計書 02 §3.5・散在禁止）。</p>
 *
 * <p><b>P2-c 第一波で {@link #capture(UUID)}（払出＝capture+transfer）を追加した。</b>
 * 返金（reverse_transfer）は次波（P2-c-2）であり本サービスには実装しない（設計書 02 §6）。</p>
 *
 * <p><b>F08.9 P1 Wave0 で {@link #charge(MembershipChargeCommand)}（会費の即時 charge）を追加した。</b>
 * 会費（{@link EscrowSourceKind#MEMBERSHIP}）は<b>即時モード</b>（{@link EscrowCaptureMode#AUTOMATIC}）で、
 * 与信フェーズを経ず {@link CaptureMethod#AUTOMATIC} の Destination PaymentIntent を作成する。CAPTURED 確定と
 * 複式記帳は既存 {@code payment_intent.succeeded} Webhook（{@link EscrowWebhookService}）に委ね、charge() では
 * ledger を起票しない（二重記帳防止・既存の event_id UNIQUE＋行ロック冪等に相乗り）。</p>
 *
 * <p>分岐（設計書 02 §5.1 / §5.2）:</p>
 * <ul>
 *   <li>受取側 {@code payouts_enabled=false}（onboarding 未完了）→ {@link EscrowStatus#HELD}。
 *       PaymentIntent は<b>作成しない</b>（口座登録完了後に capture 再開）。{@code hold_expires_at=now+72h}。</li>
 *   <li>受取側 {@code payouts_enabled=true} → manual-capture の Destination PaymentIntent を作成し
 *       {@link EscrowStatus#AUTHORIZED}。{@code clientSecret} を支払者本人へ返す（カード confirm 用）。</li>
 * </ul>
 *
 * <p>認可/IDOR: {@code actorUserId} が非 null（明示 API 経路）の場合のみ札主 scope の ADMIN を
 * {@link AccessControlService} で検証する（TEAM=checkPermission / ORG=checkAdminOrHasPermission・
 * 設計書 03 §3）。無関係者は {@link ConnectPaymentErrorCode#PAYMENT_FORBIDDEN}（403）。
 * {@code actorUserId=null}（応募成立イベント駆動の system 経路・設計書 02 §1 行#4「外部API無し」）は
 * 認可済みフロー前提でスキップする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class ConnectChargeService {

    /** HELD（受取側 onboarding 未完了）の与信保持期限（72h・設計書 02 §5.2）。 */
    static final long HELD_GRACE_HOURS = 72L;

    // 第一陣 status 意味論の根治: AUTHORIZED の hold 失効基準（最大7日）は、与信が真に立つ webhook
    // （amount_capturable_updated）昇格時に刻むため EscrowWebhookService 側へ移設した（authorize 時には立てない）。

    /** TEAM/ORG scope ADMIN 判定に用いる権限名（Connect onboarding と同等の管理権限）。 */
    static final String PERMISSION_MANAGE_PAYMENT = "MANAGE_RECRUITMENTS";

    private final EscrowTransactionRepository escrowTransactionRepository;
    private final ConnectAccountRepository connectAccountRepository;
    private final PaymentFeeCalculator paymentFeeCalculator;
    private final StripePaymentProvider stripePaymentProvider;
    private final AccessControlService accessControlService;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final RefundRepository refundRepository;
    private final PayeeScopeResolver payeeScopeResolver;
    private final FeePolicyResolver feePolicyResolver;
    private final FeeRecoveryBalanceRepository feeRecoveryBalanceRepository;

    /**
     * 謝礼の与信を開始する（設計書 02 §5.1）。
     *
     * @param cmd 与信コマンド
     * @return 与信結果（AUTHORIZED 時は clientSecret 同梱・HELD 時は null）
     */
    public AuthorizeChargeResult authorize(AuthorizeChargeCommand cmd) {
        // 認可（明示 API 経路のみ）: 受領主体（=札主 scope）の ADMIN/権限保有者のみ与信開始可（IDOR・03 §3/§4）。
        authorizeActorIfPresent(cmd);

        // 冪等: 同一応募（source_kind × source_id × source_participant_id）の二重与信を 1 件に収束（02 §9）。
        // 🟠3 決定（P2-c 第一波）: DB レベルの全行 UNIQUE backstop は張らない。アプリ層の冪等チェックを正とする。
        //   理由1: cancel(CANCELLED)/refund(REFUNDED) 後の再与信は同三つ組で別行を要するため、全行 UNIQUE は不可。
        //   理由2: MySQL は部分 UNIQUE（filtered index）非対応。アクティブ状態のみの一意化には生成列 active_key
        //          （CANCELLED/REFUNDED 時 NULL・それ以外は三つ組ハッシュ）＋ UNIQUE が必要だが、二重 capture は
        //          (a) status no-op + (b) Stripe idempotency key "capture-{escrowId}" + (c) 札行 PESSIMISTIC_WRITE
        //          の三重で、二重 authorize は本 findBy 事前チェック＋上流 recruitment_participants の一意制約で
        //          既に防げており、生成列 UNIQUE は冗長（過剰実装）。最小で二重を防げる現状維持＋本注記とする。
        //   将来再考の引き金: 並行 authorize の競合が実機で観測された場合は active_key 生成列 UNIQUE を Flyway 追加する。
        var existing = escrowTransactionRepository.findBySourceKindAndSourceIdAndSourceParticipantId(
                cmd.sourceKind(), cmd.sourceId(), cmd.sourceParticipantId());
        if (existing.isPresent()) {
            EscrowTransactionEntity e = existing.get();
            log.info("与信は既に存在します（冪等・再作成しない）: escrowId={}, status={}", e.getId(), e.getStatus());
            return toResult(e, null);
        }

        // 手数料パターン（率%＋固定額¥）を解決し（R1・02 §3.5.1）、PaymentFeeCalculator で折半計算する
        // （数式を再実装しない・一元化・02 §3.5）。安全ガード違反は PAYMENT_C060(422) で拒否する（§3.5.2）。
        FeePolicy policy = feePolicyResolver.resolve(cmd.sourceKind(), cmd.subKey());
        FeeBreakdown fee = calculateWithPolicyGuard(cmd.faceAmount(), policy);
        String currency = cmd.currency() != null ? cmd.currency() : "JPY";

        // 受取側 Connect 口座を解決し payouts_enabled を判定（02 §5.1 step2 / §5.2）。
        ConnectAccountEntity payee = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(cmd.payeeKind(), cmd.payeeScopeId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        boolean payoutsEnabled = Boolean.TRUE.equals(payee.getPayoutsEnabled());
        LocalDateTime now = LocalDateTime.now();

        // 第三陣-b「7日超 fallback」（マスター裁可・02 §5.1）: 成立〜役務日が7日超の謝礼は、カード与信が
        // 役務完了前に失効するため成立時に与信（manual-capture PI）を立てない。即時モード（AUTOMATIC）の DEFERRED
        // 行を PaymentIntent 未作成で起票し、最終認証時に即時払い（chargeDeferred）へフォールバックする。
        // captureMode=AUTOMATIC（会費の即時 charge と一貫）。clientSecret は返さない（PI 未作成）。
        if (cmd.deferred()) {
            EscrowTransactionEntity deferred = EscrowTransactionEntity.builder()
                    .sourceKind(cmd.sourceKind())
                    .captureMode(EscrowCaptureMode.AUTOMATIC)
                    .sourceId(cmd.sourceId())
                    .sourceParticipantId(cmd.sourceParticipantId())
                    .payerScopeKind(cmd.payerScopeKind())
                    .payerScopeId(cmd.payerScopeId())
                    .payerStripeCustomerId(cmd.payerStripeCustomerId())
                    .payeeKind(cmd.payeeKind())
                    .payeeConnectAccountId(payee.getId())
                    .organizationId(cmd.organizationId())
                    .faceAmount(fee.faceAmount())
                    .amount(fee.chargeAmount())
                    .currency(currency)
                    .applicationFeeAmount(fee.applicationFeeAmount())
                    .feePolicyKey(policy.policyKey()) // 適用パターンを焼き付け（遡及防止・R1・01 §3.2）。
                    .status(EscrowStatus.DEFERRED)
                    .build();
            deferred = escrowTransactionRepository.save(deferred);
            log.info("与信を DEFERRED で記録（7日超 fallback・成立時は与信せず完了時即時払い予定・PI 未作成）: "
                            + "escrowId={}, payeeAccount={}, charge={}, appFee={}",
                    deferred.getId(), payee.getStripeAccountId(), fee.chargeAmount(), fee.applicationFeeAmount());
            return toResult(deferred, null);
        }

        // §6.3 第四陣 A（次回入金相殺）: 当該 payee の未回収残高を、本 charge の application_fee に上乗せして実回収する。
        // 隔離原則（最重要）: 上乗せは「他者債務の回収」という別概念であり、本 escrow 自身の
        // face/totalFee/transferAmount には一切混ぜない。具体的には
        //   ・escrow.application_fee_amount は self の totalFee のまま据え置く（返金の transferAmount = amount − totalFee を温存）。
        //   ・回収分は PI の application_fee_amount にのみ上乗せ（Stripe 上で payee の送金から余分に控除＝payee が負担）。
        //   ・回収の事実は独立した RECOVERY 仕訳（D PAYEE = C PLATFORM_FEE）＋ outstanding 減算で別管理する。
        // これにより capture/返金の既存会計は self の totalFee 基準のまま不変で、recovery は self-balancing な別バッチに閉じる。
        // chk_et_fee 不可侵: recovery ≤ amount − totalFee より PI の application_fee = totalFee + recovery ≤ amount。
        // 回収の outstanding 減算＋RECOVERY 仕訳は「charge 成立（実際に課金できた段）」で行う:
        //   ・manual-capture（謝礼）は capture() で送金が動くため、本メソッドでは PI に上乗せのみ行い記帳は capture() に委ねる。
        long selfFee = fee.applicationFeeAmount();
        long recovery = (payoutsEnabled)
                ? computeRecoveryUplift(payee.getId(), currency, fee.chargeAmount(), selfFee)
                : 0L; // HELD（PI 未作成）は上乗せしない。onboarding 完了→PI 作成時の経路で改めて回収する。
        long piApplicationFee = selfFee + recovery;

        EscrowTransactionEntity.EscrowTransactionEntityBuilder builder = EscrowTransactionEntity.builder()
                .sourceKind(cmd.sourceKind())
                .captureMode(EscrowCaptureMode.MANUAL)
                .sourceId(cmd.sourceId())
                .sourceParticipantId(cmd.sourceParticipantId())
                .payerScopeKind(cmd.payerScopeKind())
                .payerScopeId(cmd.payerScopeId())
                .payerStripeCustomerId(cmd.payerStripeCustomerId())
                .payeeKind(cmd.payeeKind())
                .payeeConnectAccountId(payee.getId())
                .organizationId(cmd.organizationId())
                .faceAmount(fee.faceAmount())
                .amount(fee.chargeAmount())
                .currency(currency)
                .applicationFeeAmount(selfFee) // 隔離: escrow 列は self の totalFee のまま（recovery は混ぜない）。
                .feePolicyKey(policy.policyKey()); // 適用パターンを焼き付け（遡及防止・R1・01 §3.2）。

        String clientSecret;
        if (!payoutsEnabled) {
            // 受取側 onboarding 未完了 → HELD。PaymentIntent は作らない（口座登録完了後に再開・02 §5.2）。
            EscrowTransactionEntity held = builder
                    .status(EscrowStatus.HELD)
                    .holdExpiresAt(now.plusHours(HELD_GRACE_HOURS))
                    .build();
            held = escrowTransactionRepository.save(held);
            log.info("与信を HELD で記録（受取側 onboarding 未完了・PI 未作成）: escrowId={}, payeeAccount={}",
                    held.getId(), payee.getStripeAccountId());
            return toResult(held, null);
        }

        // 受取側 onboarding 完了 → manual-capture の Destination PaymentIntent を作成（02 §5.1 step5）。
        // PI の application_fee_amount のみ recovery を上乗せ（payee 送金から回収）。escrow 列・返金計算は self 基準のまま不変。
        String idempotencyKey = "escrow-" + cmd.sourceId() + "-" + cmd.sourceParticipantId();
        StripePaymentProvider.PaymentIntentInfo pi = stripePaymentProvider.createDestinationPaymentIntent(
                fee.chargeAmount(), currency, cmd.payerStripeCustomerId(), piApplicationFee,
                payee.getStripeAccountId(), CaptureMethod.MANUAL, idempotencyKey);

        // 第一陣 status 意味論の根治: manual-capture PI は札主が Stripe.js で confirm するまで真の与信
        // （amount_capturable）が立たない。よってこの時点で AUTHORIZED にするのは誤りで、capture 失敗の温床だった。
        // PI 作成済・札主未 confirm の中間状態 PENDING_CONFIRMATION で起票し、真の与信確定（AUTHORIZED 昇格）は
        // payment_intent.amount_capturable_updated webhook 受信時のみ行う（EscrowWebhookService）。
        // authorized_at/hold_expires_at（hold 失効基準）も与信が立つまで未確定のため、ここでは設定しない
        // （webhook 昇格時に authorized_at を刻む）。
        EscrowTransactionEntity pendingConfirmation = builder
                .status(EscrowStatus.PENDING_CONFIRMATION)
                .stripePaymentIntentId(pi.paymentIntentId())
                .build();
        pendingConfirmation = escrowTransactionRepository.save(pendingConfirmation);
        // §6.3 第四陣 A: PI に上乗せした回収を outstanding 減算＋RECOVERY 仕訳で記帳（冪等・self-balancing 別バッチ）。
        // 与信が後で取消（CANCELLED）/ModeB 返金で巻き戻る場合は再計上（recapitalizeRecovery）で残高へ戻す。
        recordRecoveryExecution(pendingConfirmation, recovery, pi.paymentIntentId());
        clientSecret = pi.clientSecret();
        log.info("与信を PENDING_CONFIRMATION で記録（PI 作成済・札主 confirm 待ち）: escrowId={}, paymentIntentId={}, "
                        + "charge={}, selfFee={}, recovery={}",
                pendingConfirmation.getId(), pi.paymentIntentId(), fee.chargeAmount(), selfFee, recovery);
        return toResult(pendingConfirmation, clientSecret);
    }

    /**
     * 札主（支払者本人）の決済確認ビューを取得する（謝礼・第二陣・設計書 02 §1 行#8 / 03 §1）。
     *
     * <p>成立リスナ（{@link RecruitmentChargeAuthorizationListener}）が成立時に escrow＋manual-capture PaymentIntent を
     * <b>事前起票</b>（{@link EscrowStatus#PENDING_CONFIRMATION}）するため、本メソッドは<b>新規 authorize を呼ばず</b>
     * 既存 escrow を引き当てて札主へ {@code clientSecret}＋手数料内訳を返す（二重与信回避）。GET 由来の照会であり
     * <b>副作用を起こさない</b>（authorize/PI 作成をここでは行わない）。{@code clientSecret} は PI に保存していないため
     * {@link StripePaymentProvider#retrievePaymentIntentClientSecret} で Stripe から retrieve する（PCI・03 §1）。</p>
     *
     * <p><b>リスナ競合（@Async 遅延）の扱い:</b> 成立直後は本リスナが {@code @Async} で escrow を起票する前に札主が
     * 確認画面を開きうる。その場合 escrow が未存在のため {@link ConnectPaymentErrorCode#PAYMENT_RESOURCE_NOT_FOUND}
     * （404・「準備中」）を返す。GET で副作用（新規 authorize）を起こさない方針ゆえ、FE はリトライ（ポーリング）で
     * 起票完了を待つ（症状を隠さず「準備中」として 404 を返し、握りつぶさない）。</p>
     *
     * <p><b>認可/IDOR（PCI）:</b> {@code clientSecret} は<b>支払者本人</b>（{@code payer_scope_kind=USER} かつ
     * {@code payer_scope_id == actorUserId}）にのみ返す。受取側（payee）scope の ADMIN は状態・金額のみ（clientSecret は
     * 含めない）。いずれにも該当しない無関係者は存在を漏らさず 404 秘匿（03 §3/§4）。</p>
     *
     * @param sourceKind    出所種別（通常 {@link EscrowSourceKind#RECRUITMENT}）
     * @param sourceId      札 ID（escrow の source_id）
     * @param participantId 応募 ID（escrow の source_participant_id）
     * @param actorUserId   照会者ユーザー ID（札主本人 or 受取側 ADMIN・認可/IDOR）
     * @return 決済確認ビュー（札主本人 × PENDING_CONFIRMATION 時のみ clientSecret 同梱）
     */
    @Transactional(readOnly = true)
    public PaymentView getRecruitmentPaymentView(EscrowSourceKind sourceKind, Long sourceId,
                                                 Long participantId, Long actorUserId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository
                .findBySourceKindAndSourceIdAndSourceParticipantId(sourceKind, sourceId, participantId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        return buildPaymentView(escrow, actorUserId);
    }

    /**
     * エスクロー取引の状態を照会する（汎用・設計書 02 §1 行#8 / §8）。
     *
     * <p>認可で出し分ける: 支払者本人なら {@code clientSecret} を含む（PENDING_CONFIRMATION 時）、受取側 scope の
     * ADMIN は状態・金額のみ（{@code clientSecret} 除外）、無関係者は 404 秘匿。{@link #getRecruitmentPaymentView} と
     * 認可・出し分けロジックを共有する（{@link #buildPaymentView}）。GET 由来で副作用を起こさない。</p>
     *
     * @param escrowId    エスクロー取引 ID
     * @param actorUserId 照会者ユーザー ID（支払者本人 or 受取側 ADMIN・認可/IDOR）
     * @return 照会ビュー（支払者本人 × PENDING_CONFIRMATION 時のみ clientSecret 同梱）
     */
    @Transactional(readOnly = true)
    public PaymentView getEscrowView(UUID escrowId, Long actorUserId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findById(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        return buildPaymentView(escrow, actorUserId);
    }

    /**
     * 受取側（payee）が受け取ったエスクロー取引を一覧する（フォロー Wave A・設計書 02 §1 / 03 §1）。
     *
     * <p>返金は受取側（応じ手＝payee 本人 or そのチーム/組織 ADMIN）が操作する設計だが、対象 escrow を引き当てる
     * 一覧 EP が無かった（従来は単一照会のみ）。本メソッドは「自分（USER）が受取」または「自分が ADMIN の TEAM/ORG が
     * 受取」のエスクローを一覧し、本格的な返金管理画面を支える。</p>
     *
     * <p><b>認可/IDOR（03 §3/§4）:</b> 指定 scope（{@code scopeKind}×{@code scopeId}）に対し、
     * USER は<b>本人のみ</b>（{@code scopeId == actorUserId}）、TEAM は {@link AccessControlService#checkPermission}、
     * ORG は {@link AccessControlService#checkAdminOrHasPermission}（権限 {@link #PERMISSION_MANAGE_PAYMENT}）で
     * 検証する。無関係者は {@link ConnectPaymentErrorCode#PAYMENT_FORBIDDEN}（403）。これにより他人の受取エスクローを
     * 覗けない。</p>
     *
     * <p><b>scope→escrow の解決:</b> 受取主体は {@code escrow.payee_connect_account_id}（{@code connect_accounts} 論理
     * 参照）で表現される。指定 scope の Connect 口座を {@code findByScopeKindAndScopeIdAndDeletedAtIsNull} で解決し、
     * その口座 ID に紐づく escrow をページングで引く。Connect 口座が未登録（onboarding 未着手で受取実績ゼロ）の場合は
     * 空ページを返す（症状を隠さず「まだ何も受け取っていない」を 200＋空で表現）。</p>
     *
     * <p><b>PCI（03 §10）:</b> 本一覧は受取側向けであり {@code clientSecret} を一切載せない（{@link ReceivedEscrow} に
     * フィールド自体が無い）。{@code pi_xxx}/{@code acct_xxx} 等の Stripe 生 ID も返さない。</p>
     *
     * @param scopeKind     受取 scope 種別（USER/TEAM/ORG）
     * @param scopeId       受取 scope ID（USER は users.id・TEAM は teams.id・ORG は organizations.id）
     * @param statusFilter  状態フィルタ（任意・null は全状態）
     * @param actorUserId   照会者ユーザー ID（本人 or scope ADMIN・認可/IDOR）
     * @param pageable      ページング（既存作法・created_at 降順）
     * @return 受取エスクローの 1 ページ
     */
    @Transactional(readOnly = true)
    public Page<ReceivedEscrow> listReceivedEscrows(ScopeKind scopeKind, Long scopeId,
                                                    EscrowStatus statusFilter, Long actorUserId,
                                                    Pageable pageable) {
        authorizeScopeForReceivedList(scopeKind, scopeId, actorUserId);

        // 受取 scope の Connect 口座を解決する。未登録（受取実績ゼロ）なら空ページ（症状を隠さず 200＋空）。
        ConnectAccountEntity payee = connectAccountRepository
                .findByScopeKindAndScopeIdAndDeletedAtIsNull(scopeKind, scopeId)
                .orElse(null);
        if (payee == null) {
            return Page.empty(pageable);
        }

        Page<EscrowTransactionEntity> page = (statusFilter == null)
                ? escrowTransactionRepository
                        .findByPayeeConnectAccountIdOrderByCreatedAtDesc(payee.getId(), pageable)
                : escrowTransactionRepository
                        .findByPayeeConnectAccountIdAndStatusOrderByCreatedAtDesc(
                                payee.getId(), statusFilter, pageable);
        return page.map(this::toReceivedEscrow);
    }

    /**
     * 受取エスクロー一覧の scope 認可を検証する（USER=本人のみ / TEAM=checkPermission / ORG=checkAdminOrHasPermission）。
     *
     * <p>USER は scope 認可の対象外（本人固定）のため {@code scopeId == actorUserId} を直接照合し、不一致は 403。
     * TEAM/ORG は {@link AccessControlService} の認可エラーを Connect 系 403 へ正規化する。返金 EP の
     * {@link #authorizePayeeAdmin} と同じ認可基準（受取側 ADMIN・本人）だが、一覧は scope を引数で受け取るため
     * scope→escrow ではなく scope を直接検証する点が異なる。</p>
     */
    private void authorizeScopeForReceivedList(ScopeKind scopeKind, Long scopeId, Long actorUserId) {
        if (scopeKind == ScopeKind.USER) {
            if (actorUserId == null || !actorUserId.equals(scopeId)) {
                throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
            }
            return;
        }
        try {
            switch (scopeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, scopeId,
                        payeeScopeResolver.toAccessControlScopeType(scopeKind), PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, scopeId,
                        payeeScopeResolver.toAccessControlScopeType(scopeKind), PERMISSION_MANAGE_PAYMENT);
                default -> throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN);
            }
        } catch (BusinessException e) {
            if (e.getErrorCode() instanceof ConnectPaymentErrorCode) {
                throw e;
            }
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN, e);
        }
    }

    /** escrow を受取側一覧の行ビューへ写す（clientSecret は含めない・返金累計を集計）。 */
    private ReceivedEscrow toReceivedEscrow(EscrowTransactionEntity e) {
        long refundedAmount = sumRefundedTransferAmount(e.getId());
        return new ReceivedEscrow(
                e.getId(), e.getSourceKind(), e.getSourceId(), e.getSourceParticipantId(),
                e.getCaptureMode(), e.getStatus(), e.getFaceAmount(), e.getAmount(),
                e.getApplicationFeeAmount(), refundedAmount, e.getCreatedAt());
    }

    /**
     * 受取側（payee）が受け取ったエスクロー 1 件分の内部ビュー（フォロー Wave A・設計書 02 §1 / 03 §1）。
     *
     * <p><b>clientSecret を持たない</b>（受取側向け・PCI）。Controller がこの record を DTO
     * （{@code ReceivedEscrowResponse}）へ写す。金額は最小通貨単位（円整数）。{@code refundedAmount} は
     * transferAmount ベースの返金累計（FAILED 除く）。</p>
     *
     * @param escrowId             エスクロー取引 ID
     * @param sourceKind           出所種別
     * @param sourceId             出所 ID
     * @param sourceParticipantId  応募 ID（謝礼のみ・会費は null）
     * @param captureMode          capture モード
     * @param status               エスクロー状態
     * @param faceAmount           額面（円整数）
     * @param chargeAmount         課金額（円整数）
     * @param applicationFeeAmount Mannschaft 徴収手数料（円整数）
     * @param refundedAmount       返金累計（transferAmount ベース・円整数）
     * @param createdAt            起票日時
     */
    public record ReceivedEscrow(UUID escrowId, EscrowSourceKind sourceKind, Long sourceId,
                                 Long sourceParticipantId, EscrowCaptureMode captureMode, EscrowStatus status,
                                 long faceAmount, long chargeAmount, long applicationFeeAmount,
                                 long refundedAmount, LocalDateTime createdAt) {}

    /**
     * escrow の照会ビューを認可出し分けで組み立てる（{@link #getRecruitmentPaymentView}/{@link #getEscrowView} 共通）。
     *
     * <p>(1) 支払者本人（{@code payer_scope_kind=USER} かつ {@code payer_scope_id == actorUserId}）→ 全情報＋
     * {@code clientSecret}（PENDING_CONFIRMATION のときのみ Stripe から retrieve）。
     * (2) 受取側 scope の ADMIN → 状態・金額のみ（{@code clientSecret=null}）。
     * (3) いずれでもない → 404 秘匿（IDOR）。</p>
     */
    private PaymentView buildPaymentView(EscrowTransactionEntity escrow, Long actorUserId) {
        boolean isPayer = escrow.getPayerScopeKind() == ScopeKind.USER
                && actorUserId != null
                && actorUserId.equals(escrow.getPayerScopeId());

        if (isPayer) {
            // 支払者本人へ clientSecret を返す条件は「PI 作成済・札主の confirm 待ち」:
            //   (1) 従来 escrow（MANUAL）: PENDING_CONFIRMATION（amount_capturable_updated 前）。
            //   (2) 完了時即時払い（第三陣-b・AUTOMATIC）: AUTHORIZED かつ未 capture（succeeded webhook 前）。
            //       DEFERRED→chargeDeferred で AUTOMATIC PI を作成し AUTHORIZED へ置いた直後の confirm 待ち状態
            //       （第二陣 EP 同型再利用）。capture_method=automatic ゆえ amount_capturable 段はなく、confirm で
            //       直接 succeeded→CAPTURED。CAPTURED 以降/HELD（PI 未作成）/DEFERRED（PI 未作成）は clientSecret 不要。
            boolean awaitingManualConfirm = escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION;
            boolean awaitingImmediateConfirm = escrow.getStatus() == EscrowStatus.AUTHORIZED
                    && escrow.getCaptureMode() == EscrowCaptureMode.AUTOMATIC;
            String clientSecret = null;
            if ((awaitingManualConfirm || awaitingImmediateConfirm)
                    && escrow.getStripePaymentIntentId() != null) {
                clientSecret = stripePaymentProvider
                        .retrievePaymentIntentClientSecret(escrow.getStripePaymentIntentId())
                        .clientSecret();
            }
            return PaymentView.forPayer(escrow, clientSecret);
        }

        // 支払者本人でなければ受取側 scope ADMIN を検証（無関係者は 404 秘匿）。clientSecret は含めない（PCI）。
        authorizePayeeAdminForView(escrow, actorUserId);
        return PaymentView.forPayee(escrow);
    }

    /**
     * 照会者が受取側 scope（payee の TEAM/ORG）の ADMIN であることを検証する（出し分け用）。
     *
     * <p>{@link #authorizePayeeAdmin} と同じ認可基準だが、照会（read）の IDOR 秘匿では認可失敗も<b>404 へ統一</b>する
     * （支払者本人でない無関係者と受取側でない他人の挙動を区別させない・存在秘匿）。USER 受領（個人）は scope 認可の
     * 対象外であり、本照会では受取者本人の clientSecret 経路（payer=USER と別枠）を本波で提供しないため 404 秘匿で拒否する。</p>
     */
    private void authorizePayeeAdminForView(EscrowTransactionEntity escrow, Long actorUserId) {
        ConnectAccountEntity payee = connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        ScopeKind payeeKind = payee.getScopeKind();
        if (payeeKind == ScopeKind.USER) {
            // 個人受領の照会は本波未提供。存在を漏らさず 404 秘匿で拒否する（IDOR）。
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        }
        try {
            switch (payeeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                default -> throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
            }
        } catch (BusinessException e) {
            // 既に Connect 系（404/秘匿）ならそのまま。それ以外（認可失敗）も照会では存在秘匿のため 404 へ統一する。
            if (e.getErrorCode() instanceof ConnectPaymentErrorCode) {
                throw e;
            }
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND, e);
        }
    }

    /**
     * 札主の決済確認 / エスクロー照会の内部ビュー（設計書 02 §1 行#8 / 03 §1）。
     *
     * <p>{@code clientSecret} は支払者本人 × PENDING_CONFIRMATION のときのみ非 null。受取側 ADMIN の照会では null。
     * 金額は最小通貨単位（円整数）。Controller がこの record を DTO（{@code RecruitmentPaymentResponse}）へ写す。</p>
     *
     * @param escrowId             エスクロー取引 ID
     * @param status               エスクロー状態
     * @param clientSecret         PaymentIntent の client_secret（支払者本人 × PENDING_CONFIRMATION 時のみ非 null）
     * @param faceAmount           額面（円整数）
     * @param chargeAmount         課金額（額面 + 2.5% 上乗せ・円整数）
     * @param applicationFeeAmount Mannschaft 徴収手数料（円整数）
     */
    public record PaymentView(UUID escrowId, EscrowStatus status, String clientSecret,
                              long faceAmount, long chargeAmount, long applicationFeeAmount) {

        /** 支払者本人向け（clientSecret 同梱可）。 */
        static PaymentView forPayer(EscrowTransactionEntity e, String clientSecret) {
            return new PaymentView(e.getId(), e.getStatus(), clientSecret,
                    e.getFaceAmount(), e.getAmount(), e.getApplicationFeeAmount());
        }

        /** 受取側 ADMIN 向け（clientSecret 除外・状態/金額のみ）。 */
        static PaymentView forPayee(EscrowTransactionEntity e) {
            return new PaymentView(e.getId(), e.getStatus(), null,
                    e.getFaceAmount(), e.getAmount(), e.getApplicationFeeAmount());
        }
    }

    /**
     * 会費の即時 charge を行う（設計書 F08.9 02 §1.1 / README §3.4）。
     *
     * <p>会費（{@link EscrowSourceKind#MEMBERSHIP}）は<b>即時モード</b>（{@link EscrowCaptureMode#AUTOMATIC}）で、
     * 謝礼の与信→後 capture とは異なり<b>与信フェーズを経ず</b> {@link CaptureMethod#AUTOMATIC} の Destination
     * PaymentIntent を作成する。{@code transfer_data.destination}＝受領者 Connect 口座・{@code application_fee_amount}＝
     * 折半分・Customer＝払い手。手数料は {@link PaymentFeeCalculator} に一元化する（数式を再実装しない・02 §3.5）。</p>
     *
     * <p><b>口座 READY 必須（即時モードゆえ HELD にしない・02 §1.1 注記）:</b> 受領者 Connect 口座が
     * {@code payouts_enabled=false}（onboarding 未完了）なら、保留（HELD）せず
     * {@link ConnectPaymentErrorCode#ONBOARDING_NOT_READY}（409・「受領口座の登録が完了していません」）で拒否する。
     * 払い手 API（呼び出し側）が払い手向け文言へ変換し、受領者へ onboarding 督促を出す。</p>
     *
     * <p><b>ledger 二重記帳の回避（根治・README §3.4 / 02 §5.3 と整合）:</b> escrow 行は
     * {@link EscrowStatus#AUTHORIZED}（PaymentIntent 作成済み・succeeded webhook 待ち）で INSERT し、
     * <b>本メソッドでは複式記帳（CAPTURE/TRANSFER_OUT/FEE）を起票しない</b>。CAPTURED 確定と ledger 起票は
     * Stripe の {@code payment_intent.succeeded} platform Webhook（{@link EscrowWebhookService#handleWebhook}・
     * {@code applySucceeded} は AUTHORIZED/HELD を受理して CAPTURED 化＋記帳する）に一元化する。これにより
     * 「即時 charge と webhook の二経路が同じ ledger を二重に書く」事故を、既存の {@code event_id} UNIQUE 冪等ゲートと
     * escrow 行 {@code PESSIMISTIC_WRITE} ロックに相乗りして物理的に防ぐ。
     * （設計書 README §141 は「INSERT 時点で status=CAPTURED」と記すが、その場合 succeeded webhook の冪等 no-op で
     * ledger が一度も書かれず複式記帳が欠落する。README §128/§162 が webhook で ledger を起票すると定める意図に従い、
     * 本実装は AUTHORIZED で INSERT し記帳を webhook へ委ねる方を正とする。差異は設計書追従時に解消する。）</p>
     *
     * <p><b>冪等:</b> 同一 {@code sourceId}（会費項目）の既存 escrow があれば再作成しない（Stripe にも
     * {@code idempotencyKey} を橋渡しし二重 PaymentIntent 作成を Stripe 側でも拒否・02 §9）。</p>
     *
     * <p>{@code clientSecret} は払い手本人のみへ返す前提（PCI SAQ-A・03 §1）。</p>
     *
     * @param cmd 会費 charge コマンド
     * @return charge 結果（escrow ID / clientSecret / paymentIntentId / status＝通常 AUTHORIZED）
     */
    public MembershipChargeResult charge(MembershipChargeCommand cmd) {
        if (cmd.faceAmount() <= 0L) {
            throw new IllegalArgumentException("faceAmount must be positive (会費・円整数): " + cmd.faceAmount());
        }
        // 🟡1 null ガード（F08.9 R2-2 検分 2026-06-08）: idempotencyKey が null/blank の場合、下記 dedup が効かず
        // 二重 escrow 起票が生じうる。呼び出し側（P1/P7）は常に Idempotency-Key ヘッダ起源の一意値を渡すため
        // 現状実害はないが、誤った呼び出しを明示拒否し契約違反を早期発見する（症状を隠さない）。
        if (cmd.idempotencyKey() == null || cmd.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException(
                    "idempotencyKey は business 冪等の必須キー。null/blank では escrow 二重起票を防止できない"
                    + "（呼び出し側で必ず一意値を渡すこと）");
        }

        // 冪等（R2-2 根治）: 即時 charge の二重起票防止は呼び出し側が渡す idempotencyKey（Idempotency-Key ヘッダ起源・
        // P5/P7 で別値・Stripe へも橋渡し）で行う。
        // 旧実装は findBySourceKindAndSourceId(MEMBERSHIP, source_id) のみで判定していたが、source_id の意味が
        // 呼び出し側で異なる（P5=payment_item_id / P7=team_id）ため、両者の値が一致すると名前空間が衝突し、P7 が P5 の
        // escrow を「冪等ヒット」と誤判定して実 charge なしに流用していた。idempotencyKey は業務リクエスト単位で一意
        // （同一リクエストの二重送信のみが同一キーを再利用）なので、値が一致しても別取引は必ず別 escrow になる。
        if (cmd.idempotencyKey() != null && !cmd.idempotencyKey().isBlank()) {
            var existing = escrowTransactionRepository.findByStripeIdempotencyKey(cmd.idempotencyKey());
            if (existing.isPresent()) {
                EscrowTransactionEntity e = existing.get();
                log.info("会費 charge は既に存在します（冪等・再作成しない・idempotencyKey 一致）: escrowId={}, status={}",
                        e.getId(), e.getStatus());
                return new MembershipChargeResult(e.getId(), null, e.getStripePaymentIntentId(), e.getStatus());
            }
        }

        // 手数料パターン（率%＋固定額¥）を解決し（R1・02 §3.5.1）、PaymentFeeCalculator で折半計算する
        // （数式を再実装しない・一元化・02 §3.5）。安全ガード違反は PAYMENT_C060(422) で拒否する（§3.5.2）。
        FeePolicy policy = feePolicyResolver.resolve(EscrowSourceKind.MEMBERSHIP, cmd.subKey());
        FeeBreakdown fee = calculateWithPolicyGuard(cmd.faceAmount(), policy);
        String currency = "JPY";

        // 受領者 Connect 口座を ID で解決（会費 API は受益者→scope→口座を解決済みで口座 ID を渡す）。
        ConnectAccountEntity payee = connectAccountRepository.findById(cmd.payeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 即時モードゆえ口座未 READY は HELD にせずエラー（02 §1.1 注記）。
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            log.warn("会費 charge 拒否（受領口座が未 READY・即時モードゆえ HELD にしない）: payeeAccount={}, payoutsEnabled={}",
                    payee.getStripeAccountId(), payee.getPayoutsEnabled());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }

        // §6.3 第四陣 A（次回入金相殺）: 当該 payee の未回収残高を本 charge の application_fee に上乗せして実回収する。
        // 隔離: escrow 列は self の totalFee のまま据え置き、PI の application_fee_amount にのみ recovery を上乗せする
        // （payee 送金から回収）。記帳・残高減算は self-balancing な RECOVERY 別バッチ。chk_et_fee 不可侵。
        long selfFee = fee.applicationFeeAmount();
        long recovery = computeRecoveryUplift(payee.getId(), currency, fee.chargeAmount(), selfFee);
        long piApplicationFee = selfFee + recovery;

        // AUTOMATIC（即時 capture）の Destination PaymentIntent を作成（idempotencyKey を Stripe へ橋渡し）。
        // R2-1: confirmImmediately=true（P5 継続課金の初回会費・払い手不在）なら、保存済み既定 PM で
        // server-side off-session 即時確定する（succeeded webhook が CAPTURED 化＋記帳）。false（P1/P7）は従来どおり
        // 未 confirm の PI を作成し FE の on-session confirm に委ねる（後方互換・無破壊）。
        StripePaymentProvider.PaymentIntentInfo pi;
        if (cmd.confirmImmediately()) {
            if (cmd.paymentMethodId() == null || cmd.paymentMethodId().isBlank()) {
                // 即時確定要求なのに PM が無いのは呼び出し側の契約違反（症状を隠さず拒否）。
                throw new IllegalArgumentException(
                        "confirmImmediately=true requires a non-blank paymentMethodId (off-session confirm)");
            }
            pi = stripePaymentProvider.createAndConfirmDestinationPaymentIntent(
                    fee.chargeAmount(), currency, cmd.payerStripeCustomerId(), piApplicationFee,
                    payee.getStripeAccountId(), CaptureMethod.AUTOMATIC, cmd.paymentMethodId(), cmd.idempotencyKey());
        } else {
            pi = stripePaymentProvider.createDestinationPaymentIntent(
                    fee.chargeAmount(), currency, cmd.payerStripeCustomerId(), piApplicationFee,
                    payee.getStripeAccountId(), CaptureMethod.AUTOMATIC, cmd.idempotencyKey());
        }

        // escrow を MEMBERSHIP/AUTOMATIC で INSERT。hold_expires_at=NULL（即時・与信フェーズなし）。
        // status=AUTHORIZED（succeeded webhook 待ち）。CAPTURED 確定＋ledger 起票は webhook に委ねる（二重記帳しない）。
        //
        // 🟡2 TODO: source_id は source_kind 内で名前空間が重複し得る（P5=payment_item_id / P7=team_id）。
        // business 冪等は idempotencyKey で担保しているため現状実害はないが、findBySourceKindAndSourceId を
        // 新規経路で使う際は誤再利用（P7 が P5 の escrow を流用するなど）に注意。
        // 将来は source_ref（ドメインプレフィックス付き文字列キー）等での厳密化を検討する
        // （F08.9 R2-2 検分 2026-06-08）。
        EscrowTransactionEntity charged = EscrowTransactionEntity.builder()
                .sourceKind(EscrowSourceKind.MEMBERSHIP)
                .captureMode(EscrowCaptureMode.AUTOMATIC)
                .sourceId(cmd.sourceId())
                .sourceParticipantId(null)
                .payerScopeKind(ScopeKind.USER)
                .payerScopeId(cmd.payerUserId())
                .payerStripeCustomerId(cmd.payerStripeCustomerId())
                .payeeKind(payee.getScopeKind())
                .payeeConnectAccountId(payee.getId())
                .organizationId(cmd.organizationId())
                .faceAmount(fee.faceAmount())
                .amount(fee.chargeAmount())
                .currency(currency)
                .applicationFeeAmount(fee.applicationFeeAmount())
                .feePolicyKey(policy.policyKey()) // 適用パターンを焼き付け（遡及防止・R1・01 §3.2）。
                .status(EscrowStatus.AUTHORIZED)
                .stripePaymentIntentId(pi.paymentIntentId())
                .stripeIdempotencyKey(cmd.idempotencyKey()) // R2-2: 業務冪等キーを焼き付け（次回二重送信を dedup）。
                .authorizedAt(LocalDateTime.now())
                .holdExpiresAt(null)
                .build();
        charged = escrowTransactionRepository.save(charged);
        // §6.3 第四陣 A: PI に上乗せした回収を outstanding 減算＋RECOVERY 仕訳で記帳（冪等・self-balancing 別バッチ）。
        recordRecoveryExecution(charged, recovery, pi.paymentIntentId());

        log.info("会費 charge を作成（即時 AUTOMATIC・succeeded webhook で CAPTURED+記帳）: escrowId={}, piId={}, "
                        + "charge={}, selfFee={}, recovery={}",
                charged.getId(), pi.paymentIntentId(), fee.chargeAmount(), selfFee, recovery);
        return new MembershipChargeResult(charged.getId(), pi.clientSecret(), pi.paymentIntentId(), charged.getStatus());
    }

    /**
     * 謝礼の払出（capture+transfer）を確定する（設計書 02 §5.3）。
     *
     * <p>manual-capture の与信（{@link EscrowStatus#AUTHORIZED}）を Stripe で capture し、同時に
     * {@code transfer_data.destination} へ送金（{@code application_fee_amount} 控除後）する。escrow を
     * {@link EscrowStatus#CAPTURED} にし {@code captured_at} を記録、複式記帳（CAPTURE/TRANSFER_OUT/FEE）を
     * {@code ledger_entries} に追記する（借方合計＝貸方合計・01 §3.3）。</p>
     *
     * <p><b>冪等:</b> 既に {@link EscrowStatus#CAPTURED} なら no-op（Stripe capture を 2 回呼ばない）。さらに
     * Stripe へ {@code idempotency_key="capture-{escrowId}"} を渡し、ネットワーク再送も Stripe 側で拒否する
     * （二重防御・02 §5.3）。</p>
     *
     * <p><b>二重払出・二重記帳防止（根治）:</b> escrow 行を {@code PESSIMISTIC_WRITE} でロックして読む
     * （{@link EscrowTransactionRepository#findByIdForUpdate}）。同期フック（AFTER_COMMIT 後）の capture と
     * {@code payment_intent.succeeded} webhook（{@link EscrowWebhookService#handleWebhook}）が並行しても、
     * 行ロックで read-then-write を直列化し、ロック取得後に status を再判定（CAPTURED なら no-op）することで
     * ledger 二重記帳を物理的に防ぐ（02 §5.3）。</p>
     *
     * <p><b>トランザクション境界:</b> {@link Propagation#REQUIRES_NEW} の独立トランザクションで実行する。
     * MarketFinalize フック（{@link MarketChargeCaptureListener}）は finalize の確定トランザクションが
     * <b>コミットされた後</b>（{@code AFTER_COMMIT}）に本メソッドを呼ぶため、確定（COMPLETED）は既に durable で
     * ある。本メソッドの新規トランザクションが失敗（ロールバック）しても確定は巻き戻らず、webhook の安全網で
     * 後追い確定できる結果整合とする。</p>
     *
     * <p><b>不正状態:</b> {@link EscrowStatus#HELD}（onboarding 未完で payout 不能）/{@link EscrowStatus#CANCELLED}/
     * {@link EscrowStatus#REFUNDED} 等の後段状態、または PI 未設定からの capture は
     * {@link ConnectPaymentErrorCode#INVALID_ESCROW_STATE}（409）で拒否する（症状を隠さない）。</p>
     *
     * @param escrowId 払出対象のエスクロー取引 ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void capture(UUID escrowId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findByIdForUpdate(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 冪等: CAPTURED 済みは再 capture しない（Stripe capture を 2 回呼ばない・02 §5.3）。
        if (escrow.getStatus() == EscrowStatus.CAPTURED) {
            log.info("払出は既に確定済み（冪等・no-op）: escrowId={}", escrowId);
            return;
        }

        // 第一陣 status 意味論の根治: PENDING_CONFIRMATION（札主未 confirm）は真の与信が立っておらず、
        // capture を呼んでも Stripe で必ず失敗する。Stripe へ到達させず専用コード 409 で拒否する（症状を隠さない）。
        // 札主が confirm し payment_intent.amount_capturable_updated で AUTHORIZED へ昇格してから capture すること。
        if (escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION) {
            log.warn("札主の confirm 前（PENDING_CONFIRMATION）からの capture 要求を拒否: escrowId={}", escrowId);
            throw new BusinessException(ConnectPaymentErrorCode.AUTHORIZATION_NOT_CONFIRMED);
        }

        // AUTHORIZED（真に与信確定済）以外（HELD/CANCELLED/REFUNDED/DISPUTED 等）は払出不能。HELD は payout 不能の
        // ため capture せず、onboarding 完了→AUTHORIZED 化（§5.2 webhook）を待つ。症状を隠さず 409 で拒否する。
        if (escrow.getStatus() != EscrowStatus.AUTHORIZED || escrow.getStripePaymentIntentId() == null) {
            log.warn("払出不能な状態からの capture 要求を拒否: escrowId={}, status={}, hasPi={}",
                    escrowId, escrow.getStatus(), escrow.getStripePaymentIntentId() != null);
            throw new BusinessException(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);
        }

        // capture と同時に transfer_data.destination へ送金（application_fee_amount 控除）。
        String idempotencyKey = "capture-" + escrowId;
        StripePaymentProvider.PaymentIntentInfo pi =
                stripePaymentProvider.captureManualPaymentIntent(escrow.getStripePaymentIntentId(), idempotencyKey);

        escrow.setStatus(EscrowStatus.CAPTURED);
        escrow.setCapturedAt(LocalDateTime.now());
        escrowTransactionRepository.save(escrow);

        // 複式記帳（02 §5.3・01 §3.3）: capture 総額（charge=amount）を ESCROW に借方計上し、
        // 受取側送金（transfer=amount−application_fee）を PAYEE に、Mannschaft 手数料を PLATFORM_FEE に貸方計上する。
        // FEE は本波では設定値（application_fee_amount）。Stripe 実手数料での純益確定は P2-c-2 のリコンシリエーション。
        long captureAmount = escrow.getAmount();
        long feeAmount = escrow.getApplicationFeeAmount();
        long transferOut = captureAmount - feeAmount;
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                .debit(LedgerEntryType.CAPTURE, LedgerAccount.ESCROW, captureAmount, pi.paymentIntentId())
                .credit(LedgerEntryType.TRANSFER_OUT, LedgerAccount.PAYEE, transferOut, pi.paymentIntentId())
                .credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, feeAmount, pi.paymentIntentId())
                .build();
        ledgerEntryRepository.saveAll(entries);

        log.info("謝礼の払出を確定 CAPTURED: escrowId={}, piId={}, capture={}, transfer={}, fee={}",
                escrowId, pi.paymentIntentId(), captureAmount, transferOut, feeAmount);
    }

    /**
     * 完了時即時払い（7日超 fallback）の謝礼を最終認証時に即時 charge する（第三陣-b・マスター裁可・02 §5.3 / §5.1）。
     *
     * <p>成立〜役務日が7日超で成立時に与信を立てず {@link EscrowStatus#DEFERRED} で起票した escrow を、最終認証
     * （役務完了）時にここで即時払い（{@link CaptureMethod#AUTOMATIC} の Destination PaymentIntent）へフォールバック
     * する。会費（F08.9）の即時 charge 作法を流用し、{@code transfer_data.destination}＝受領側 Connect 口座・
     * {@code application_fee_amount}＝折半分・Customer＝札主。PaymentIntent を作成して escrow を
     * {@link EscrowStatus#AUTHORIZED}（PI 作成済・succeeded webhook 待ち＝会費 charge() と一貫）にし、
     * {@code clientSecret} を返す。札主は<b>第二陣の決済確認 EP</b>（{@link #getRecruitmentPaymentView}/
     * {@link #getEscrowView}）で clientSecret を受け取り Stripe.js で confirm する（同型再利用）。confirm すると
     * AUTOMATIC PI は {@code payment_intent.succeeded} を発火し、{@link EscrowWebhookService} が AUTHORIZED→
     * {@link EscrowStatus#CAPTURED} 化＋複式記帳する（charge() と同じく本メソッドでは ledger を起票しない・二重記帳防止）。</p>
     *
     * <p><b>なぜ PENDING_CONFIRMATION でなく AUTHORIZED か（第三陣バッチ非干渉・根治）:</b> DEFERRED の最終認証は
     * 成立から7日超後（しばしば72h超）に起きる。{@code captureMode=AUTOMATIC} の即時 charge を会費と同様
     * {@code status=AUTHORIZED}＋{@code hold_expires_at=NULL} で起票すれば、第三陣の自動取消バッチの
     * PENDING_CONFIRMATION 抽出（{@code created_at}＜now−72h）にも HELD/AUTHORIZED 抽出
     * （{@code hold_expires_at≦now+2h}・NULL は不一致）にも掛からず、誤って自動取消されない。manual 与信の
     * PENDING_CONFIRMATION（{@code created_at} 基準で 72h 猶予）とは性質が異なるため、即時モードは AUTHORIZED に
     * 倣う（既存バッチ・webhook の意味論を変えずに共存）。</p>
     *
     * <p><b>冪等:</b> 既に AUTHORIZED（PI 作成済・再フック）/CAPTURED/CANCELLED 等の後段状態なら no-op で既存の
     * clientSecret（AUTHORIZED かつ PI 有時のみ retrieve）を返す。Stripe へも {@code idempotencyKey} を渡し
     * 二重 PI 作成を Stripe 側でも拒否する（02 §9）。行ロック（{@code PESSIMISTIC_WRITE}）で並行フックを直列化する。</p>
     *
     * <p><b>口座 READY 必須（即時モードゆえ HELD にしない・会費 charge と一貫）:</b> 受領側 Connect 口座が
     * {@code payouts_enabled=false}（onboarding 未完）なら {@link ConnectPaymentErrorCode#ONBOARDING_NOT_READY}
     * （409）で拒否する（症状を隠さない）。</p>
     *
     * <p><b>不正状態:</b> {@link EscrowStatus#DEFERRED} 以外（AUTHORIZED/HELD 等の従来 escrow 経路）からの本メソッド
     * 呼び出しは {@link ConnectPaymentErrorCode#INVALID_ESCROW_STATE}（409）で拒否する（従来 escrow は capture を使う）。</p>
     *
     * @param escrowId 完了時即時払い対象（DEFERRED）のエスクロー取引 ID
     * @return 即時 charge 結果（AUTHORIZED＋clientSecret・hold_expires_at=NULL・札主の confirm 用）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthorizeChargeResult chargeDeferred(UUID escrowId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findByIdForUpdate(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 冪等: 既に即時 charge 起票済み（AUTHORIZED・PI 有）/確定/取消なら再作成しない。
        if (escrow.getStatus() == EscrowStatus.AUTHORIZED
                || escrow.getStatus() == EscrowStatus.CAPTURED
                || escrow.getStatus() == EscrowStatus.CANCELLED
                || escrow.getStatus() == EscrowStatus.REFUNDED
                || escrow.getStatus() == EscrowStatus.PARTIALLY_REFUNDED) {
            log.info("完了時即時払いは既に起票/確定済み（冪等・no-op）: escrowId={}, status={}", escrowId, escrow.getStatus());
            String clientSecret = (escrow.getStatus() == EscrowStatus.AUTHORIZED
                    && escrow.getStripePaymentIntentId() != null)
                    ? stripePaymentProvider.retrievePaymentIntentClientSecret(escrow.getStripePaymentIntentId())
                            .clientSecret()
                    : null;
            return toResult(escrow, clientSecret);
        }

        // DEFERRED 以外（従来 escrow の AUTHORIZED/HELD 等）からの即時 charge 要求は誤り。従来 escrow は capture を使う。
        if (escrow.getStatus() != EscrowStatus.DEFERRED) {
            log.warn("完了時即時払い不能な状態からの chargeDeferred 要求を拒否: escrowId={}, status={}",
                    escrowId, escrow.getStatus());
            throw new BusinessException(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);
        }

        ConnectAccountEntity payee = connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 即時モードゆえ口座未 READY は HELD にせずエラー（会費 charge と一貫・02 §1.1 注記）。
        if (!Boolean.TRUE.equals(payee.getPayoutsEnabled())) {
            log.warn("完了時即時払い拒否（受領口座が未 READY・即時モードゆえ HELD にしない）: escrowId={}, payeeAccount={}",
                    escrowId, payee.getStripeAccountId());
            throw new BusinessException(ConnectPaymentErrorCode.ONBOARDING_NOT_READY);
        }

        // §6.3 第四陣 A（次回入金相殺）: DEFERRED の即時 charge も payee の未回収残高を application_fee に上乗せして回収する。
        // 隔離: escrow.application_fee_amount（self の totalFee）は据え置き、PI の application_fee_amount にのみ recovery を上乗せ。
        long selfFee = escrow.getApplicationFeeAmount();
        long recovery = computeRecoveryUplift(payee.getId(), escrow.getCurrency(), escrow.getAmount(), selfFee);
        long piApplicationFee = selfFee + recovery;

        // AUTOMATIC（即時 capture）の Destination PaymentIntent を作成（会費 charge と同型・idempotencyKey を Stripe へ橋渡し）。
        String idempotencyKey = "deferred-" + escrow.getSourceId() + "-" + escrow.getSourceParticipantId();
        StripePaymentProvider.PaymentIntentInfo pi = stripePaymentProvider.createDestinationPaymentIntent(
                escrow.getAmount(), escrow.getCurrency(), escrow.getPayerStripeCustomerId(),
                piApplicationFee, payee.getStripeAccountId(), CaptureMethod.AUTOMATIC, idempotencyKey);

        // AUTHORIZED（PI 作成済・succeeded webhook 待ち＝会費 charge() と一貫）へ遷移し clientSecret を返す。
        // hold_expires_at=NULL（即時モード・与信フェーズなし＝第三陣バッチの自動取消対象外）。confirm→succeeded webhook で
        // 既存 applySucceeded の AUTHORIZED 経路が CAPTURED 化＋複式記帳する（本メソッドでは記帳しない・二重記帳防止）。
        escrow.setStatus(EscrowStatus.AUTHORIZED);
        escrow.setStripePaymentIntentId(pi.paymentIntentId());
        escrow.setAuthorizedAt(LocalDateTime.now());
        escrow.setHoldExpiresAt(null);
        escrowTransactionRepository.save(escrow);
        // §6.3 第四陣 A: PI に上乗せした回収を outstanding 減算＋RECOVERY 仕訳で記帳（冪等・self-balancing 別バッチ）。
        recordRecoveryExecution(escrow, recovery, pi.paymentIntentId());
        log.info("完了時即時払いを起票 DEFERRED→AUTHORIZED（AUTOMATIC・succeeded webhook で CAPTURED+記帳）: "
                        + "escrowId={}, piId={}, charge={}, selfFee={}, recovery={}",
                escrowId, pi.paymentIntentId(), escrow.getAmount(), selfFee, recovery);
        return toResult(escrow, pi.clientSecret());
    }

    /**
     * 謝礼/会費の返金または与信取消を行う（設計書 02 §6.1・設定A・マスター確定）。
     *
     * <p><b>操作主体＝受取側 scope（payee の TEAM/ORG）の ADMIN</b>（Mannschaft 運営は関与しない）。
     * 認可は {@link AccessControlService} で行い、無関係 scope は {@link ConnectPaymentErrorCode#PAYMENT_RESOURCE_NOT_FOUND}
     * で 404 秘匿する（IDOR・03 §3/§4）。</p>
     *
     * <p><b>二重返金・競合防止（根治）:</b> escrow 行を {@code PESSIMISTIC_WRITE} でロックして読む
     * （{@link EscrowTransactionRepository#findByIdForUpdate}・capture / charge.refunded webhook と直列化）。</p>
     *
     * <p><b>状態分岐（設計書 02 §6.1）:</b></p>
     * <ul>
     *   <li>{@link EscrowStatus#CAPTURED}/{@link EscrowStatus#PARTIALLY_REFUNDED}（capture 後）→
     *       <b>支払者負担モデル（decouple 方式・後述）</b>で Stripe 返金。{@code refunds} を {@code PENDING} で
     *       記録し（{@code charge.refunded} webhook で {@code SUCCEEDED} 確定）、累計が transferAmount に達したら
     *       {@link EscrowStatus#REFUNDED}、一部なら {@link EscrowStatus#PARTIALLY_REFUNDED}。
     *       {@code ledger_entries}(REFUND) を監査追記する（借貸一致・金を動かすのは Stripe・自前逆仕訳は作らない）。</li>
     *   <li>{@link EscrowStatus#AUTHORIZED}/{@link EscrowStatus#HELD}（capture 前）→ 返金でなく<b>与信取消</b>
     *       （{@code cancelAuthorization}・支払者課金なし）。{@link EscrowStatus#CANCELLED} にし、課金が起きていない
     *       ため {@code refunds} には記録しない。</li>
     *   <li>既に {@link EscrowStatus#REFUNDED}/{@link EscrowStatus#CANCELLED} → 冪等 no-op。</li>
     * </ul>
     *
     * <p><b>金額モデル＝feeBearer 2モード（マスター確定・2026-06-03）:</b> {@code amountMinor} は両モード共通で
     * <b>受取側が実際に受け取った正味（transferAmount＝{@code amount − application_fee_amount}・額面 10,000 円なら
     * 9,750 円）ベースの精算額 R</b>（{@code 0 < R ≤ transferAmount − 既返金累計}・{@code null} は残額全部）。
     * 残額管理・status 遷移・refunds.amount は両モードとも R（transferAmount ベース）で行い、{@code charge.refunded}
     * webhook 確定ロジックと整合させる（残額超過は {@link ConnectPaymentErrorCode#REFUND_AMOUNT_EXCEEDS}）。</p>
     *
     * <p><b>モードA＝{@link FeeBearer#PAYER}（既定・支払者都合・decouple 方式）:</b> 比例 reverse
     * （{@code Refund.create(reverse_transfer=true)}）は送金を {@code refundAmount/chargeAmount} の比率でしか
     * 巻き戻さず取りこぼしが残り Mannschaft が持ち出しになる。これを避けるため
     * (1) {@link StripePaymentProvider#reverseTransfer} で送金から R を<b>明示的に巻き戻し</b>、
     * (2) {@link StripePaymentProvider#createConnectRefund}（{@code reverse_transfer=false}/
     * {@code refund_application_fee=false}）で支払者へ R を返金する。これで「支払者へ R 返金」「受取側 ±0」
     * 「Mannschaft±0（1.4% keep）」が成立する。巻き戻しを先に行い、失敗時は支払者返金へ進まない。</p>
     *
     * <p><b>モードB＝{@link FeeBearer#PAYEE}（受取側の落ち度/中止）:</b> 支払者へ<b>満額（chargeAmount 相当・
     * grossRefund）</b>を戻し、Mannschaft は application_fee も返金して中立化する
     * （{@code Refund.create(amount=grossRefund, reverse_transfer=true, refund_application_fee=true)}）。
     * grossRefund は精算額 R を支払者上乗せ込みにグロスアップした額（全額時は chargeAmount、部分時は
     * {@code round(chargeAmount × R / transferAmount)}）。
     * <b>⚠️ Stripe 実挙動の制約（正直報告・症状を隠さない）:</b> Stripe の Destination Charge 返金では
     * <b>元取引の決済手数料（≈369）は返金されず、標準フローでは platform（Mannschaft）が負担する</b>
     * （TransferReversal は送金額が上限で受取側から手数料分を追加で巻き戻せない／Account Debits は連結口座の同意・
     * 追加コスト・同一リージョンが要件で返金 1 件ごとの自動操作に適さない）。よって「受取側が Stripe 手数料を負担」を
     * 標準 API のみで自動成立させることは不可能であり、本モードでは<b>Mannschaft が Stripe 手数料を一時負担</b>する。
     * 受取側への最終転嫁はリコンシリエーション（§6.3）／次回入金相殺／運用での Account Debits に委ねる。
     * grossRefund と R の差額（Mannschaft が支払者へ追加で戻す＝放棄する margin）は {@code ledger_entries}
     * （C PLATFORM_FEE）に明示記録して可視化する。</p>
     *
     * @param escrowId     返金対象のエスクロー取引 ID
     * @param amountMinor  精算額 R（transferAmount ベース・最小通貨単位）。{@code null} は全額（残額全部）。
     * @param feeBearer    手数料負担者モード（{@code null}=既定 {@link FeeBearer#PAYER}）
     * @param reason       返金理由（{@code requested_by_customer}/{@code duplicate}/{@code cancellation} 等）
     * @param reasonDetail 補足（PII 非含意・任意・自社台帳のみ保持）
     * @param actorUserId  操作者ユーザー ID（受取側 scope の ADMIN 認可・監査）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RefundResult refund(UUID escrowId, Long amountMinor, FeeBearer feeBearer,
                               String reason, String reasonDetail, Long actorUserId) {
        EscrowTransactionEntity escrow = escrowTransactionRepository.findByIdForUpdate(escrowId)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        // 認可/IDOR: 受取側 scope（payee の TEAM/ORG）の ADMIN のみ。無関係 scope は 404 秘匿（03 §3/§4）。
        authorizePayeeAdmin(escrow, actorUserId);

        // 冪等: 既に終端状態（全額返金/取消済み）なら no-op（Stripe を呼ばない・二重返金しない）。
        if (escrow.getStatus() == EscrowStatus.REFUNDED || escrow.getStatus() == EscrowStatus.CANCELLED) {
            log.info("返金/取消は既に終端状態（冪等・no-op）: escrowId={}, status={}", escrowId, escrow.getStatus());
            return new RefundResult(escrowId, escrow.getStatus(), 0L, 0L);
        }

        // capture 前（DEFERRED/PENDING_CONFIRMATION/AUTHORIZED/HELD）は返金でなく与信取消（支払者に課金なし・02 §6.1）。
        // PENDING_CONFIRMATION（札主未 confirm）も真の与信が立つ前のため、課金は起きておらず取消で足りる。
        // DEFERRED（7日超 fallback・成立時に与信せず PI 未作成）も課金前のため取消で足りる（PI 未作成なら Stripe 呼ばず）。
        if (escrow.getStatus() == EscrowStatus.DEFERRED
                || escrow.getStatus() == EscrowStatus.PENDING_CONFIRMATION
                || escrow.getStatus() == EscrowStatus.AUTHORIZED
                || escrow.getStatus() == EscrowStatus.HELD) {
            cancelAuthorizationForRefund(escrow);
            return new RefundResult(escrowId, EscrowStatus.CANCELLED, 0L, 0L);
        }

        // reason は refunds.reason が NOT NULL のため既定値で補完（Stripe へも正規化して渡す）。
        String effectiveReason = (reason != null && !reason.isBlank()) ? reason : "requested_by_customer";

        // ここから capture 後（CAPTURED / PARTIALLY_REFUNDED）の返金。
        if (escrow.getStripePaymentIntentId() == null) {
            // capture 後で PI が無いのは整合性異常。症状を隠さず 409 で拒否する。
            log.warn("CAPTURED だが PaymentIntent 未設定（異常）。返金不能: escrowId={}", escrowId);
            throw new BusinessException(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);
        }

        // 支払者負担モデル: 返金の上限は「受取側が実際に受け取った正味＝transferAmount（amount − application_fee）」。
        // 支払者上乗せ 2.5% と Stripe 決済手数料は戻らない。残額管理も transferAmount ベースで行う（02 §6.1）。
        long transferAmount = escrow.getAmount() - escrow.getApplicationFeeAmount();
        long alreadyRefunded = sumRefundedTransferAmount(escrowId);
        long residualBefore = transferAmount - alreadyRefunded;
        if (residualBefore <= 0L) {
            // 受取側送金全額が既に巻き戻し済み（理論上 status REFUNDED で弾かれるが二重防御）。
            throw new BusinessException(ConnectPaymentErrorCode.ALREADY_REFUNDED);
        }

        // refundAmount R = 支払者へ戻す額 = 受取側から巻き戻す額（Mannschaft±0・受取側が被る額=支払者戻り）。
        long refundAmount = (amountMinor != null) ? amountMinor : residualBefore;
        if (refundAmount <= 0L || refundAmount > residualBefore) {
            log.warn("返金額が不正/残額超過: escrowId={}, request={}, residual={}（transferベース）",
                    escrowId, refundAmount, residualBefore);
            throw new BusinessException(ConnectPaymentErrorCode.REFUND_AMOUNT_EXCEEDS);
        }

        FeeBearer effectiveBearer = (feeBearer != null) ? feeBearer : FeeBearer.PAYER;
        // 冪等キーは部分返金の連番（両モード共通）。
        int seq = refundRepository.findByEscrowTransactionId(escrowId).size() + 1;

        StripePaymentProvider.ConnectRefundInfo refundInfo;
        List<LedgerEntryEntity> entries;
        // ModeB（受取側負担）でこの返金が支払者へ戻したグロス額（§6.3 第二陣 C1: 実 Stripe 手数料の比例計上に用いる）。
        // ModeA では 0（残高計上しない・不変）。
        long modeBGrossRefund = 0L;
        if (effectiveBearer == FeeBearer.PAYER) {
            // ── モードA＝支払者負担（decouple 方式・比例 reverse の取りこぼし回避・02 §6.1）──
            // (1) 受取側送金から R を明示的に巻き戻す（先に実行。失敗時は支払者返金へ進まず Mannschaft の持ち出しも防ぐ）。
            //     送金 ID は PaymentIntent → latest_charge → charge.transfer で解決する（capture 時に作られた tr_xxx）。
            String transferId = stripePaymentProvider.resolveTransferIdFromPaymentIntent(escrow.getStripePaymentIntentId());
            if (transferId == null) {
                // capture 済みなのに送金が解決できないのは整合性異常。症状を隠さず 409 で拒否する。
                log.warn("CAPTURED だが Transfer 未解決（異常）。返金不能: escrowId={}, piId={}",
                        escrowId, escrow.getStripePaymentIntentId());
                throw new BusinessException(ConnectPaymentErrorCode.INVALID_ESCROW_STATE);
            }
            stripePaymentProvider.reverseTransfer(transferId, refundAmount, "reversal-" + escrowId + "-" + seq);

            // (2) 支払者へ R を返金（reverse_transfer=false: 比例 reverse を使わず明示巻き戻しで Mannschaft±0 を担保。
            //     refund_application_fee=false: 1.4% keep）。
            refundInfo = stripePaymentProvider.createConnectRefund(
                    escrow.getStripePaymentIntentId(), refundAmount, effectiveReason, false, false,
                    "refund-" + escrowId + "-" + seq);

            // ledger(REFUND) 監査追記: 受取側 Connect 残高から戻る（D PAYEE）＝支払者へ返金（C PAYER）。借貸一致（01 §3.3）。
            entries = LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                    .debit(LedgerEntryType.REFUND, LedgerAccount.PAYEE, refundAmount, refundInfo.refundId())
                    .credit(LedgerEntryType.REFUND, LedgerAccount.PAYER, refundAmount, refundInfo.refundId())
                    .build();
            log.info("返金受付（モードA=支払者負担）: escrowId={}, refundId={}, transferId={}, R={}（transferベース）",
                    escrowId, refundInfo.refundId(), transferId, refundAmount);
        } else {
            // ── モードB＝受取側負担（支払者満額返金＋application_fee 返金・02 §6.1）──
            // 支払者へ戻すグロス額: 全額（R==残額全部）なら chargeAmount、部分なら R を支払者上乗せ込みにグロスアップ。
            long grossRefund = grossRefundForPayee(escrow, refundAmount, residualBefore);
            modeBGrossRefund = grossRefund;
            // Refund.create(amount=grossRefund, reverse_transfer=true, refund_application_fee=true)。
            // reverse_transfer=true: 送金を（比例）巻き戻し受取側から回収。refund_application_fee=true: 1.4% を放棄し中立化。
            // ⚠️ Stripe 仕様: 元取引の決済手数料は返金されず標準フローでは Mannschaft 負担。明示 TransferReversal は呼ばない
            //     （reverse_transfer=true が送金巻き戻しを担う・二重巻き戻し防止）。手数料の受取側転嫁はリコンシリ/運用に委ねる。
            refundInfo = stripePaymentProvider.createConnectRefund(
                    escrow.getStripePaymentIntentId(), grossRefund, effectiveReason, true, true,
                    "refund-" + escrowId + "-" + seq);

            // ledger(REFUND) 監査追記: 支払者へ grossRefund（D PAYER）。原資は受取側送金巻き戻し R（C PAYEE）＋
            // Mannschaft が放棄/一時負担する margin（C PLATFORM_FEE = grossRefund − R）。借貸一致（01 §3.3）。
            long platformBorne = grossRefund - refundAmount;
            LedgerEntryBuilder builder = LedgerEntryBuilder.forTransaction(escrowId, escrow.getCurrency())
                    .debit(LedgerEntryType.REFUND, LedgerAccount.PAYER, grossRefund, refundInfo.refundId())
                    .credit(LedgerEntryType.REFUND, LedgerAccount.PAYEE, refundAmount, refundInfo.refundId());
            if (platformBorne > 0L) {
                builder.credit(LedgerEntryType.FEE, LedgerAccount.PLATFORM_FEE, platformBorne, refundInfo.refundId());
            }
            entries = builder.build();
            log.info("返金受付（モードB=受取側負担・支払者満額）: escrowId={}, refundId={}, gross={}, R={}（transferベース）, "
                            + "Mannschaft 放棄/一時負担={}（うち Stripe 手数料は §6.3 でリコンシリ）",
                    escrowId, refundInfo.refundId(), grossRefund, refundAmount, platformBorne);
        }

        // refunds に PENDING で記録（charge.refunded webhook で SUCCEEDED 確定）。amount=精算額 R（transferベース・両モード共通）。
        RefundEntity refundEntity = RefundEntity.builder()
                .escrowTransactionId(escrowId)
                .stripeRefundId(refundInfo.refundId())
                .amount(refundAmount)
                .currency(escrow.getCurrency())
                .reason(effectiveReason)
                .reasonDetail(reasonDetail)
                .refundedByUserId(actorUserId)
                .status(RefundStatus.PENDING)
                .build();
        refundRepository.save(refundEntity);

        // 累計（R ベース）が transferAmount に達したら REFUNDED、それ未満なら PARTIALLY_REFUNDED（両モード共通）。
        long newTotal = alreadyRefunded + refundAmount;
        escrow.setStatus(newTotal >= transferAmount ? EscrowStatus.REFUNDED : EscrowStatus.PARTIALLY_REFUNDED);
        escrowTransactionRepository.save(escrow);

        ledgerEntryRepository.saveAll(entries);

        // §6.3 検分🔴根治: 自己返金（A で回収を上乗せした charge を ModeB 返金）時の回収金消失を、
        //   ① recovery_kind による峻別（sumAppliedRecoveryNetOnEscrow が A 経路のみ集計）と
        //   ② 順序（再計上を C1 発生計上より先に呼ぶ）の二重防御で防ぐ。
        // 順序の意図: recapitalize は当該 escrow の A 回収実行純額を読む。C1 を先に saveAll すると Hibernate AUTO フラッシュで
        // C1 行が同一クエリに混入しうるため、recovery_kind で除外していても、念のため先に recapitalize（A 純額の確定読み取り）を
        // 済ませてから C1（C PAYEE）を積む。両者は同一 payee 残高に正しく加算合成される（A 再計上の +applied と C1 の +recoverable）。
        if (effectiveBearer == FeeBearer.PAYEE) {
            // §6.3 第四陣 A（回収分の再返金エッジ・家老指摘の最小安全策）: この escrow 自身が A 陣で「回収を上乗せされた
            // charge」だった場合、ModeB 返金は refund_application_fee:true で application_fee 全体（self totalFee＋recovery）を
            // 払い戻すため、上乗せした回収が消えてしまう。よって当該 escrow に計上済みの回収実行分（純額）を outstanding へ
            // 先に再計上し、回収が無かったことにして次回再回収できるようにする。ModeA（refund_application_fee:false・recovery 据置）
            // では再計上しない（recovery 維持）。終端 REFUNDED への二重返金は上の冪等 no-op で防がれ、純額判定で二重再計上も防ぐ。
            recapitalizeAppliedRecoveryOnRefund(escrow, refundInfo.refundId());
            // §6.3 第二陣 C1: ModeB のみ、元 charge の実 Stripe 決済手数料を受取側からの未回収残高として計上する。
            // 既存の返金会計（D PAYER / C PAYEE / C PLATFORM_FEE）は上で確定済みで一切触らず、ここでは
            // 自己完結した RECOVERY 仕訳（C1_ACCRUAL・D PLATFORM_FEE = C PAYEE = 実手数料相当）を別バッチで追記し、
            // fee_recovery_balances.outstanding_amount を同額だけ積む（残高計上のみ・実回収は後続 A 陣）。
            recordModeBStripeFeeRecovery(escrow, modeBGrossRefund, refundInfo.refundId());
        }

        log.info("返金を受付 status={}: escrowId={}, refundId={}, feeBearer={}, R={}（transferベース）, 累計={}/{}",
                escrow.getStatus(), escrowId, refundInfo.refundId(), effectiveBearer, refundAmount, newTotal, transferAmount);
        return new RefundResult(escrowId, escrow.getStatus(), refundAmount, transferAmount - newTotal);
    }

    /**
     * モードB（受取側負担）で支払者へ戻すグロス返金額（chargeAmount 相当）を求める。
     *
     * <p>精算額 R（transferAmount ベース）を支払者上乗せ込みにグロスアップする。全額返金
     * （{@code R == residualTransfer}・残額全部）の場合は丸め誤差を排除して残りの chargeAmount を正確に返す
     * （クリーンな CAPTURED からの全額返金なら chargeAmount に一致）。部分の場合は
     * {@code round(chargeAmount × R / transferAmount)} の比例グロスアップとする。</p>
     *
     * @param escrow          対象 escrow（amount=chargeAmount / applicationFee 保持）
     * @param refundAmount    今回の精算額 R（transferAmount ベース）
     * @param residualTransfer 今回返金前の残額（transferAmount − 既返金累計）
     * @return 支払者へ戻すグロス額（最小通貨単位・{@code refundAmount ≤ grossRefund ≤ chargeAmount}）
     */
    private long grossRefundForPayee(EscrowTransactionEntity escrow, long refundAmount, long residualTransfer) {
        long chargeAmount = escrow.getAmount();
        long transferAmount = chargeAmount - escrow.getApplicationFeeAmount();
        if (refundAmount >= residualTransfer) {
            // 残額全部の精算 → 残りの chargeAmount を正確に返す（既返金分のグロスを差し引く）。
            long alreadyRefundedTransfer = transferAmount - residualTransfer;
            long alreadyGross = (transferAmount <= 0L) ? 0L
                    : Math.round((double) chargeAmount * alreadyRefundedTransfer / transferAmount);
            return chargeAmount - alreadyGross;
        }
        // 部分精算 → 比例グロスアップ（最小通貨単位の四捨五入）。
        if (transferAmount <= 0L) {
            return refundAmount;
        }
        return Math.round((double) chargeAmount * refundAmount / transferAmount);
    }

    /**
     * ModeB（受取側負担）返金で Mannschaft が一時負担した <b>元 charge の実 Stripe 決済手数料</b>を、
     * 受取側（payee）から回収すべき未回収残高として計上する（§6.3 第二陣 C1・残高計上のみ）。
     *
     * <p><b>会計の隔離（複式整合を崩さない）:</b> 呼び出し元の既存返金会計
     * （{@code D PAYER=grossRefund / C PAYEE=R / C PLATFORM_FEE=grossRefund−R}）は確定済みで一切触らない。
     * 本メソッドは<b>別バッチ</b>で自己完結した RECOVERY 仕訳のみを追記する:</p>
     * <pre>
     *   D PLATFORM_FEE = stripeFeeRecoverable   (Mannschaft が一時負担＝費用計上)
     *   C PAYEE        = stripeFeeRecoverable   (受取側からの未回収＝receivable)
     * </pre>
     * <p>この仕訳は借方=貸方が常に成立し（{@code LedgerEntryBuilder.build()} で検算）、既存返金バッチの
     * 借貸一致にも影響しない。{@code C PAYEE} の額と同額を {@code fee_recovery_balances.outstanding_amount}
     * に積む。現行 {@code C PLATFORM_FEE}（margin 放棄＝{@code grossRefund−R}）と本仕訳（実手数料）は
     * {@link LedgerEntryType} が {@code FEE} と {@code RECOVERY} で峻別され、意味のズレ（家老指摘）を排する。</p>
     *
     * <p><b>比例計上（部分返金の二重計上回避）:</b> 元 charge の Stripe 手数料は charge 1 件に対する固定費だが、
     * 部分 ModeB 返金が複数回起こりうる。各回でグロス返金の比率
     * {@code round(stripeFee × thisGrossRefund / chargeAmount)} を計上することで、全返金にわたる積み上げ合計が
     * 元手数料を超えない（≤1 円の丸め誤差）。同一返金の二重起票は escrow の終端状態冪等（REFUNDED→no-op）と
     * 1 返金=1 呼び出しで防がれる。</p>
     *
     * <p><b>pending の扱い（症状を隠さない）:</b> balance_transaction 未確定で実手数料が取れない
     * （{@link StripePaymentProvider#PROCESSING_FEE_PENDING}）場合は残高計上を<b>スキップ</b>し、
     * リコンシリエーション（§6.3）での補完に委ねる（0 円で握り潰さない）。</p>
     *
     * @param escrow      返金対象 escrow（payeeConnectAccountId / organizationId / amount=chargeAmount を保持）
     * @param grossRefund 今回 ModeB で支払者へ戻したグロス額（比例計上の分子）
     * @param refundId    対象 Stripe Refund ID（{@code re_xxx}・ledger の stripe_object_id 突合用）
     */
    private void recordModeBStripeFeeRecovery(EscrowTransactionEntity escrow, long grossRefund, String refundId) {
        long stripeFee = stripePaymentProvider.retrieveChargeProcessingFee(escrow.getStripePaymentIntentId());
        if (stripeFee == StripePaymentProvider.PROCESSING_FEE_PENDING) {
            // 実手数料未確定。0 と誤認させず計上をスキップし、§6.3 リコンシリで補完する（症状を隠さない）。
            log.warn("ModeB 実手数料が未確定（pending）のため未回収残高計上をスキップ（§6.3 で補完）: escrowId={}, refundId={}",
                    escrow.getId(), refundId);
            return;
        }
        long chargeAmount = escrow.getAmount();
        // 比例計上: 全額返金なら stripeFee 全部、部分なら gross 比率。chargeAmount<=0 は理論上ないが除算保護。
        long recoverable = (chargeAmount <= 0L) ? stripeFee
                : Math.round((double) stripeFee * grossRefund / chargeAmount);
        if (recoverable <= 0L) {
            log.info("ModeB 実手数料の比例計上額が 0（計上なし）: escrowId={}, stripeFee={}, gross={}, charge={}",
                    escrow.getId(), stripeFee, grossRefund, chargeAmount);
            return;
        }

        // 自己完結 RECOVERY 仕訳（C1 発生計上・D PLATFORM_FEE = C PAYEE = recoverable）を別バッチで追記（既存返金バッチは不変）。
        // recovery_kind=C1_ACCRUAL を焼き付け、A 回収実行/再計上の純額計算（sumAppliedRecoveryNetOnEscrow）から確実に除外する。
        List<LedgerEntryEntity> recoveryEntries = LedgerEntryBuilder.forTransaction(escrow.getId(), escrow.getCurrency())
                .recoveryPair(RecoveryKind.C1_ACCRUAL, LedgerAccount.PLATFORM_FEE, LedgerAccount.PAYEE,
                        recoverable, refundId)
                .build();
        ledgerEntryRepository.saveAll(recoveryEntries);

        // 未回収残高を payee（connect_account）×currency で upsert（無ければ作成・organization_id も埋める）。
        String currency = normalizeRecoveryCurrency(escrow.getCurrency());
        FeeRecoveryBalanceEntity balance = feeRecoveryBalanceRepository
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(escrow.getPayeeConnectAccountId(), currency)
                .orElseGet(() -> FeeRecoveryBalanceEntity.builder()
                        .connectAccountId(escrow.getPayeeConnectAccountId())
                        .organizationId(escrow.getOrganizationId())
                        .currency(currency)
                        .outstandingAmount(0L)
                        .build());
        long current = balance.getOutstandingAmount() != null ? balance.getOutstandingAmount() : 0L;
        balance.setOutstandingAmount(current + recoverable);
        // 既存行で organization_id 未設定なら補完（過去の不完全データの是正・症状を隠さない）。
        if (balance.getOrganizationId() == null && escrow.getOrganizationId() != null) {
            balance.setOrganizationId(escrow.getOrganizationId());
        }
        feeRecoveryBalanceRepository.save(balance);

        log.info("ModeB 実 Stripe 手数料を未回収残高に計上（§6.3 C1）: escrowId={}, payeeAccountId={}, currency={}, "
                        + "stripeFee={}, gross={}, charge={}, 計上額={}, 計上後残高={}",
                escrow.getId(), escrow.getPayeeConnectAccountId(), currency,
                stripeFee, grossRefund, chargeAmount, recoverable, balance.getOutstandingAmount());
    }

    /**
     * 残高表の通貨表現を正規化する。{@code fee_recovery_balances.currency} は minor 母数として小文字
     * （既定 {@code jpy}）で持つため、escrow（{@code JPY} 等大文字）から小文字へ揃える（UNIQUE 突合の安定化）。
     */
    private String normalizeRecoveryCurrency(String currency) {
        return (currency == null || currency.isBlank()) ? "jpy" : currency.toLowerCase(java.util.Locale.ROOT);
    }

    // ============================================================================
    // §6.3 第四陣 A: 次回入金相殺（未回収 Stripe 手数料の自動回収）。
    //   ・computeRecoveryUplift          — charge 起票直前に上乗せ回収額を求める（純粋計算 FeeRecoveryCalculator へ委譲）。
    //   ・recordRecoveryExecution        — PI 作成後に outstanding 減算＋RECOVERY(回収実行) 仕訳を記帳（冪等）。
    //   ・recapitalizeAppliedRecoveryOnRefund — 回収済み charge の取消/ModeB 返金で回収を outstanding へ再計上（逆仕訳）。
    // ============================================================================

    /**
     * 当該 payee×通貨の未回収残高から、本 charge の application_fee に上乗せできる回収額を求める
     * （§6.3 第四陣 A・{@link FeeRecoveryCalculator} 純粋計算へ委譲）。
     *
     * <p>残高行が無ければ 0（通常 charge と完全不変＝後方互換）。{@code chk_et_fee} 不可侵は
     * {@link FeeRecoveryCalculator#recoveryToApply} の headroom クランプで保証される。</p>
     *
     * <p><b>冪等順序（🟠1・PI 二重上乗せ防止）:</b> 本計算は新規 escrow 起票経路でのみ呼ばれる
     * （{@code authorize}/{@code charge} の冒頭で同一冪等キーの既存 escrow は早期 return し、ここへ到達しない）。
     * よって「既に回収適用済みの escrow を再 charge」しても本計算は再実行されず PI に二重上乗せされない。さらに
     * PI 作成は Stripe 冪等キー（{@code idempotencyKey}）で同一 PI を再取得し（上乗せは 1 度のみ）、回収の記帳・
     * {@code outstanding} 減算は {@link #recordRecoveryExecution} の純額ガード（A 経路純額 &gt; 0 なら skip）で二重適用
     * されない。上乗せ・outstanding 減算・RECOVERY 記帳が同一冪等境界（escrow 起票トランザクション）の内側に閉じる。</p>
     *
     * @param connectAccountId 受取側 Connect 口座 ID（残高の主体）
     * @param currency         escrow 通貨（小文字へ正規化して残高と突合）
     * @param amount           今回 charge の請求額（{@code escrow_transactions.amount}）
     * @param selfFee          今回 charge 自身の総手数料（self の {@code application_fee_amount}）
     * @return 上乗せして実回収する額（{@code 0 ≤ recovery ≤ amount − selfFee}）
     */
    private long computeRecoveryUplift(UUID connectAccountId, String currency, long amount, long selfFee) {
        String normalized = normalizeRecoveryCurrency(currency);
        long outstanding = feeRecoveryBalanceRepository
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(connectAccountId, normalized)
                .map(b -> b.getOutstandingAmount() != null ? b.getOutstandingAmount() : 0L)
                .orElse(0L);
        return FeeRecoveryCalculator.recoveryToApply(outstanding, amount, selfFee);
    }

    /**
     * PI に上乗せした回収を「回収実行」として確定する（§6.3 第四陣 A・PI 作成成立後に呼ぶ）。
     *
     * <p><b>会計（C1/C2 と逆向き）:</b> C1/C2 の RECOVERY は「未回収の発生」で {@code D PLATFORM_FEE = C PAYEE}。
     * 本「回収実行」は payee の送金から余分に控除した分を Mannschaft が回収する事実であり向きが逆になる:</p>
     * <pre>
     *   D PAYEE        = recovery   (payee 送金から余分徴収＝payee 負担)
     *   C PLATFORM_FEE = recovery   (Mannschaft が回収＝未回収の解消)
     * </pre>
     * <p>既存の capture/会費の複式記帳（self の totalFee 基準・不変）とは独立した self-balancing 別バッチで、
     * {@code stripe_object_id=piId}。同時に {@code outstanding_amount −= recovery}（payee×通貨）する。</p>
     *
     * <p><b>冪等（二重回収しない）:</b> 当該 escrow に既に回収実行（{@code RECOVERY/PAYEE} 純額 &gt; 0）があれば skip。
     * これにより同一 escrow への二重 charge/二重適用・並行フックでも 1 回しか回収しない。{@code recovery ≤ 0} も no-op。</p>
     *
     * @param escrow   回収を上乗せした charge の escrow（payee/organization/currency を保持）
     * @param recovery 上乗せした回収額（{@code computeRecoveryUplift} の結果・0 なら no-op）
     * @param piId     対象 PaymentIntent ID（{@code pi_xxx}・ledger の stripe_object_id 突合用）
     */
    private void recordRecoveryExecution(EscrowTransactionEntity escrow, long recovery, String piId) {
        if (recovery <= 0L) {
            return;
        }
        // 冪等: 既に回収実行が立っている escrow には二重に回収しない（純額 > 0 なら適用済み）。
        if (ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(escrow.getId()) > 0L) {
            log.info("回収実行は既に計上済み（冪等・skip）: escrowId={}, recovery={}", escrow.getId(), recovery);
            return;
        }

        // 回収実行 RECOVERY 仕訳（A_EXECUTION・D PAYEE = C PLATFORM_FEE = recovery）を self-balancing な別バッチで追記。
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrow.getId(), escrow.getCurrency())
                .recoveryPair(RecoveryKind.A_EXECUTION, LedgerAccount.PAYEE, LedgerAccount.PLATFORM_FEE,
                        recovery, piId)
                .build();
        ledgerEntryRepository.saveAll(entries);

        // outstanding を recovery 分だけ減算（payee×通貨）。残高行は ModeB 返金時に作られている前提だが、防御的に upsert する。
        adjustOutstanding(escrow.getPayeeConnectAccountId(), escrow.getOrganizationId(),
                escrow.getCurrency(), -recovery);

        log.info("未回収 Stripe 手数料を次回入金相殺で回収（§6.3 A・回収実行）: escrowId={}, payeeAccountId={}, piId={}, recovery={}",
                escrow.getId(), escrow.getPayeeConnectAccountId(), piId, recovery);
    }

    /**
     * 回収を上乗せした charge が取消/ModeB 返金で巻き戻る際、当該 escrow の回収実行分（純額）を outstanding へ再計上する
     * （§6.3 第四陣 A・回収分の再返金エッジの最小安全策・家老指摘）。
     *
     * <p>ModeB 返金（{@code refund_application_fee:true}）や与信取消（PI 巻き戻し）では、上乗せした回収が消える/未成立に
     * なるため、回収が無かったことにして次回再回収できるよう残高へ戻す。逆仕訳で打ち消す:</p>
     * <pre>
     *   D PLATFORM_FEE = applied   (回収実行の取消＝Mannschaft の回収を戻す)
     *   C PAYEE        = applied   (payee への戻り＝未回収の再発生)
     * </pre>
     * <p>これは {@link #recordRecoveryExecution} の {@code D PAYEE = C PLATFORM_FEE} の逆であり、
     * {@code sumAppliedRecoveryNetOnEscrow}（{@code RECOVERY×PAYEE} の {@code D − C}）が 0 に戻る。よって二重再計上は
     * 起きない（既に 0 なら no-op）。ModeA 返金では本メソッドを呼ばない（recovery 維持）。</p>
     *
     * @param escrow            巻き戻し対象の escrow（回収を上乗せした charge）
     * @param stripeObjectIdRef 突合用の参照 ID（返金は refundId・取消は cancel キー）
     */
    private void recapitalizeAppliedRecoveryOnRefund(EscrowTransactionEntity escrow, String stripeObjectIdRef) {
        long applied = ledgerEntryRepository.sumAppliedRecoveryNetOnEscrow(escrow.getId());
        if (applied <= 0L) {
            return; // 回収実行なし or 既に再計上済み（純額 0）。二重再計上しない。
        }

        // 逆仕訳（A_RECAPITALIZE・D PLATFORM_FEE = C PAYEE = applied）で回収実行を打ち消す（self-balancing 別バッチ）。
        List<LedgerEntryEntity> entries = LedgerEntryBuilder.forTransaction(escrow.getId(), escrow.getCurrency())
                .recoveryPair(RecoveryKind.A_RECAPITALIZE, LedgerAccount.PLATFORM_FEE, LedgerAccount.PAYEE,
                        applied, stripeObjectIdRef)
                .build();
        ledgerEntryRepository.saveAll(entries);

        // outstanding を再計上（+applied）。次回 charge で再び回収を試みる。
        adjustOutstanding(escrow.getPayeeConnectAccountId(), escrow.getOrganizationId(),
                escrow.getCurrency(), applied);

        log.info("回収を上乗せした charge が巻き戻ったため未回収残高へ再計上（§6.3 A・再返金/取消エッジ）: "
                        + "escrowId={}, payeeAccountId={}, ref={}, 再計上額={}",
                escrow.getId(), escrow.getPayeeConnectAccountId(), stripeObjectIdRef, applied);
    }

    /**
     * payee×通貨の未回収残高を {@code delta} 分だけ加減算（upsert）する（§6.3 第四陣 A 共通）。
     *
     * <p>残高行が無ければ作成（{@code organization_id} も埋める）。既存行で {@code organization_id} 未設定なら補完する
     * （過去データの是正・症状を隠さない）。{@code delta} は減算（回収実行）で負・再計上で正。</p>
     */
    private void adjustOutstanding(UUID connectAccountId, Long organizationId, String currency, long delta) {
        String normalized = normalizeRecoveryCurrency(currency);
        FeeRecoveryBalanceEntity balance = feeRecoveryBalanceRepository
                .findByConnectAccountIdAndCurrencyAndDeletedAtIsNull(connectAccountId, normalized)
                .orElseGet(() -> FeeRecoveryBalanceEntity.builder()
                        .connectAccountId(connectAccountId)
                        .organizationId(organizationId)
                        .currency(normalized)
                        .outstandingAmount(0L)
                        .build());
        long current = balance.getOutstandingAmount() != null ? balance.getOutstandingAmount() : 0L;
        balance.setOutstandingAmount(current + delta);
        if (balance.getOrganizationId() == null && organizationId != null) {
            balance.setOrganizationId(organizationId);
        }
        feeRecoveryBalanceRepository.save(balance);
    }

    /**
     * 返金/与信取消の結果（設計書 02 §6.1）。
     *
     * <p>PCI 禁則（{@code client_secret}/{@code pi_xxx}/{@code acct_xxx}）は含めない（03 §10）。
     * 金額は額面ベース（最小通貨単位）。</p>
     *
     * @param escrowId       エスクロー取引 ID
     * @param status         返金後の escrow 状態
     * @param refundedAmount 今回の返金額（支払者へ戻した額・transferAmount ベース・与信取消時は 0）
     * @param residualAmount 残額（transferAmount − 既返金累計）
     */
    public record RefundResult(UUID escrowId, EscrowStatus status, long refundedAmount, long residualAmount) {}

    /**
     * capture 前（AUTHORIZED/HELD）の与信取消を行う（返金でなく PaymentIntent.cancel・支払者課金なし）。
     *
     * <p>PI 未作成（HELD で onboarding 未完了）の場合は Stripe を呼ばず状態のみ CANCELLED にする。</p>
     */
    private void cancelAuthorizationForRefund(EscrowTransactionEntity escrow) {
        if (escrow.getStripePaymentIntentId() != null) {
            stripePaymentProvider.cancelAuthorization(escrow.getStripePaymentIntentId(), "cancel-" + escrow.getId());
        }
        escrow.setStatus(EscrowStatus.CANCELLED);
        escrow.setCancelledAt(LocalDateTime.now());
        escrowTransactionRepository.save(escrow);
        // §6.3 第四陣 A: 与信取消で PI が巻き戻る（capture されず payee 送金が起きない）ため、A 陣で上乗せ済みの回収は
        // 実際には回収できていない。当該 escrow に計上済みの回収実行分（純額）を outstanding へ再計上し、次回再回収に回す。
        recapitalizeAppliedRecoveryOnRefund(escrow, "cancel-" + escrow.getId());
        log.info("capture 前のため返金でなく与信取消 CANCELLED: escrowId={}, hasPi={}",
                escrow.getId(), escrow.getStripePaymentIntentId() != null);
    }

    /** 既返金累計（支払者へ戻した額＝transferAmount ベース）を集計する。FAILED は除外する（不成立は残額を消費しない）。 */
    private long sumRefundedTransferAmount(UUID escrowId) {
        return refundRepository.findByEscrowTransactionId(escrowId).stream()
                .filter(r -> r.getStatus() != RefundStatus.FAILED)
                .mapToLong(RefundEntity::getAmount)
                .sum();
    }

    /**
     * 返金操作者が受取側 scope（payee の TEAM/ORG）の ADMIN であることを検証する（設計書 03 §3/§4・設定A）。
     *
     * <p>escrow の {@code payeeConnectAccountId} から受取側 Connect 口座を解決し、その {@code scopeKind}/{@code scopeId}
     * に対して {@link AccessControlService} を適用する。口座が解決できない（無関係 escrow）場合は 404 秘匿。
     * USER 受領（個人）は scope 認可の対象外（本人固定）であり、本波の返金 API では USER 受領の明示返金は
     * 提供しないため拒否する（IDOR 秘匿のため 404）。認可エラーは {@link ConnectPaymentErrorCode#PAYMENT_FORBIDDEN}。</p>
     */
    private void authorizePayeeAdmin(EscrowTransactionEntity escrow, Long actorUserId) {
        ConnectAccountEntity payee = connectAccountRepository.findById(escrow.getPayeeConnectAccountId())
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));

        ScopeKind payeeKind = payee.getScopeKind();
        if (payeeKind == ScopeKind.USER) {
            // 個人受領の明示返金は本波未提供。存在を漏らさず 404 秘匿で拒否する（IDOR）。
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        }
        try {
            switch (payeeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, payee.getScopeId(),
                        payeeScopeResolver.toAccessControlScopeType(payeeKind), PERMISSION_MANAGE_PAYMENT);
                default -> throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
            }
        } catch (BusinessException e) {
            // 既に Connect 系コードならそのまま、それ以外（AccessControlService の認可エラー）は 403 へ正規化。
            if (e.getErrorCode() instanceof ConnectPaymentErrorCode) {
                throw e;
            }
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN, e);
        }
    }

    /**
     * {@code actorUserId} が非 null（明示 API 経路）の場合のみ、受領主体（札主 scope）の ADMIN を検証する。
     *
     * <p>USER 受領（個人）の場合は scope 認可の対象外（本人固定・札に紐づく個人受領者）であり、
     * 札スコープ（TEAM/ORG）側の権限で守られるためここでは検証しない。</p>
     */
    private void authorizeActorIfPresent(AuthorizeChargeCommand cmd) {
        Long actorUserId = cmd.actorUserId();
        if (actorUserId == null) {
            return;
        }
        ScopeKind payeeKind = cmd.payeeKind();
        if (payeeKind == ScopeKind.USER) {
            // 個人受領は札スコープ側の権限で守る。本コマンドの payeeScopeId は users.id のため
            // scope 認可の対象にできない。明示経路では organizationId 等の上位 scope で守る前提だが、
            // 本波では USER 受領の明示 API 経路は未提供（イベント駆動のみ）のため拒否しない。
            return;
        }
        try {
            switch (payeeKind) {
                case TEAM -> accessControlService.checkPermission(actorUserId, cmd.payeeScopeId(),
                        PayeeScopeResolver.SCOPE_TYPE_TEAM, PERMISSION_MANAGE_PAYMENT);
                case ORG -> accessControlService.checkAdminOrHasPermission(actorUserId, cmd.payeeScopeId(),
                        PayeeScopeResolver.SCOPE_TYPE_ORGANIZATION, PERMISSION_MANAGE_PAYMENT);
                default -> { /* USER は上で return 済み */ }
            }
        } catch (BusinessException e) {
            // AccessControlService の認可エラーを Connect 系の 403 へ正規化（IDOR は存在秘匿しつつ拒否）。
            throw new BusinessException(ConnectPaymentErrorCode.PAYMENT_FORBIDDEN, e);
        }
    }

    /**
     * 解決済み手数料パターンで折半計算を行い、安全ガード違反（総手数料 > 額面）を 422 へ変換する（R1・02 §3.5.2）。
     *
     * <p>{@link PaymentFeeCalculator#calculate(long, FeePolicy)} は純粋関数として違反を
     * {@link IllegalArgumentException} で拒否する。それをサービス境界で
     * {@link ConnectPaymentErrorCode#FEE_EXCEEDS_FACE_AMOUNT}（422・{@code ERROR_CODE_STATUS_MAP} 登録済み）へ
     * 変換し、握りつぶさず「このパターンはこの額面に適用できない」と返す（症状を隠さない・根治原則）。
     * {@code faceAmount} 自体が非正（決済不能）の場合は呼出側の既存挙動（{@code IllegalArgumentException}）を保つ。</p>
     *
     * @param faceAmount 額面（円整数・最小単位・正値）
     * @param policy     解決済み手数料パターン
     * @return 手数料内訳
     */
    private FeeBreakdown calculateWithPolicyGuard(long faceAmount, FeePolicy policy) {
        try {
            return paymentFeeCalculator.calculate(faceAmount, policy);
        } catch (IllegalArgumentException e) {
            if (faceAmount <= 0L) {
                // 額面非正は決済不能（手数料パターン以前の入力エラー）。既存の charge() 検証と同じ扱いを保つ。
                throw e;
            }
            // 安全ガード違反（総手数料 > 額面）は 422 で拒否する（02 §3.5.2・#1279 の 500 フォールバック回避）。
            log.warn("手数料パターンが額面に適用できません（総手数料 > 額面・拒否）: face={}, policyKey={}, reason={}",
                    faceAmount, policy.policyKey(), e.getMessage());
            throw new BusinessException(ConnectPaymentErrorCode.FEE_EXCEEDS_FACE_AMOUNT, e);
        }
    }

    private AuthorizeChargeResult toResult(EscrowTransactionEntity e, String clientSecret) {
        return new AuthorizeChargeResult(
                e.getId(),
                e.getStatus(),
                clientSecret,
                e.getStripePaymentIntentId(),
                e.getFaceAmount(),
                e.getAmount(),
                e.getApplicationFeeAmount());
    }
}
