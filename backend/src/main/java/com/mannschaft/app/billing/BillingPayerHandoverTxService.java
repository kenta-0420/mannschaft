package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）: 引継フローの<b>トランザクション単位</b>（設計書 §3.1〜§3.7）。
 *
 * <p>{@link BillingPayerHandoverService} は「DB tx → commit → Stripe → DB tx」という多段構成を取る
 * （外部 API 呼び出しを長い {@code @Transactional} の内側に抱えない・既存
 * {@link BillingCheckoutService} と同流儀）。Spring の自己呼び出しではプロキシを経ず
 * {@code @Transactional} が効かないため、<b>個々のトランザクション単位を本クラスへ切り出す</b>。</p>
 *
 * <p><b>通知</b>: 業務 tx 内では {@link ApplicationEventPublisher#publishEvent} だけを行い、
 * 実配送は {@code AFTER_COMMIT} リスナー（{@link BillingPayerHandoverNotificationListener}）へ委ねる。</p>
 *
 * <p><b>時刻</b>: handover 側は {@link Instant}、{@code billing_contracts} 側は {@link LocalDateTime}。
 * 変換は既存 {@code BillingContractService#cancelPaidAtPeriodEnd} と対称に {@code clock.getZone()} を用いる
 * （{@code ZoneId.of("...")} のリテラル直書きは番人 {@code DateTimeAndZoneGuardTest} が拒否する）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPayerHandoverTxService {

    /** 終端状態（{@code open_old_contract_id} 生成列で NULL になる 3 値・設計書 §4.2）。 */
    static final List<PayerHandoverStatus> TERMINAL_STATUSES = List.of(
            PayerHandoverStatus.COMPLETED, PayerHandoverStatus.FAILED, PayerHandoverStatus.EXPIRED);

    private final BillingPayerHandoverRequestRepository handoverRequestRepository;
    private final BillingContractRepository billingContractRepository;
    private final ActiveContractPointerRepository activeContractPointerRepository;
    private final BillingOperationAuthorizer billingOperationAuthorizer;
    private final BillingPayerHandoverCandidateResolver candidateResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    // ============================================================
    // 承諾（AC-11 / AC-12 / AC-16）
    // ============================================================

    /**
     * 承諾可否を<b>行ロック下で</b>検証する（設計書 §5.6・AC-11/AC-12/AC-25 の前段）。
     *
     * <p>状態を変えない読み取り専用の検証だが、{@code SELECT ... FOR UPDATE} を取るため
     * 書き込みトランザクションで実行する（{@code requireCanManage} も {@code MANDATORY}）。
     * 期限切れだけは {@code EXPIRED} への確定を伴う（設計書 §5.3）。</p>
     *
     * @return 検証済みスナップショット（支払い手段検証は tx の外で行うため本メソッドでは行わない）
     */
    @Transactional
    public AcceptValidation validateAcceptable(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);

        // ★IDOR: URL 由来の scope と行の scope が一致しなければ、存在自体を明かさず 404 で畳む
        //   （別スコープの ADMIN が他スコープの引継要求 ID を指定して承諾できてしまうのを防ぐ）。
        requireSameScope(handover, scopeKind, scopeId);

        // 認可は「行の scope と一致確認済みの引数 scope」に対して行う（AC-11）。
        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);

        // ★承諾者の適格性（設計書 §5.6・§5.2・Codex検分1巡目 P1-2）。
        //   requireCanManage は「スコープの課金を管理できるか」しか見ない。設計書が承諾者として定めるのは
        //   一貫して「対象スコープの<b>他</b> ADMIN」（＝申請時に通知を送った引継先候補と同一集合）であり、
        //   これを検証しないと (1) 旧 payer 本人が自己承諾して支払担当が変わらないまま SWITCHING まで進む、
        //   (2) 候補として通知もされていない権限保持者（TEAM の DEPUTY_ADMIN 等）が承諾できる、
        //   という2つの穴が開く。候補集合は申請時と同じ resolver に一本化して判定する。
        requireEligibleAcceptor(handover, operatorUserId);

        Instant now = clock.instant();
        if (!handover.getExpiresAt().isAfter(now)) {
            handover.setStatus(PayerHandoverStatus.EXPIRED);
            handoverRequestRepository.save(handover);
            throw new BusinessException(EntitlementErrorCode.HANDOVER_EXPIRED);
        }
        requireAcceptableStatus(handover, operatorUserId);

        return new AcceptValidation(handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                handover.getOldContractId(), handover.getStatus(), handover.getPspNewSubscriptionRef(),
                handover.getNewContractId());
    }

    /**
     * 引継要求の発行を対象スコープの他 ADMIN 全員へ通知する（設計書 §5.2・AC-9）。
     *
     * <p>{@code REQUIRED}（既定）で呼び出し元の業務トランザクションに参加する。イベントは
     * <b>業務 tx 内で publish し、配送は {@code AFTER_COMMIT} リスナーが行う</b>ため、
     * 要求行の作成がロールバックされれば通知も送られない（逆向きの不整合も生じない）。</p>
     */
    @Transactional
    public void publishHandoverRequested(UUID handoverRequestId, EntitlementScopeKind scopeKind,
            Long scopeId, List<Long> recipientUserIds, Long actorUserId) {
        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.HANDOVER_REQUESTED,
                handoverRequestId, scopeKind, scopeId, recipientUserIds, actorUserId));
    }

    /**
     * 支払い手段未登録による差し戻し（設計書 §3.6 二段検証の1段目・AC-16/AC-19）。
     *
     * <p><b>旧契約には一切触れない</b>。この時点ではまだ旧サブスクへ {@code cancel_at_period_end} を
     * 設定していないため、旧契約は完全に無傷のまま維持される。</p>
     */
    @Transactional
    public void transitionToRequiresPaymentMethod(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        requireSameScope(handover, scopeKind, scopeId);
        requireEligibleAcceptor(handover, operatorUserId);
        requireAcceptableStatus(handover, operatorUserId);
        handover.setStatus(PayerHandoverStatus.REQUIRES_PAYMENT_METHOD);
        handoverRequestRepository.save(handover);

        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.PAYMENT_METHOD_REQUIRED,
                handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                List.of(operatorUserId), operatorUserId));
    }

    /**
     * 承諾を {@code REQUESTED} へ巻き戻し、先行作成した {@code PENDING_HANDOVER} 契約を破棄する
     * （設計書 §3.6 遷移表・Codex検分1巡目 P1-3）。
     *
     * <p><b>解決する詰み</b>: 承諾は「先に {@code ACCEPTED}＋{@code PENDING_HANDOVER} を DB 確定 →
     * 後から Stripe の Checkout Session を作成」という順序で進む。Stripe 呼び出しが失敗すると
     * 状態は {@code ACCEPTED} のまま残るが、<b>再承諾の入口は {@code REQUESTED} と
     * {@code REQUIRES_PAYMENT_METHOD} しかない</b>ため、誰も再試行できず猶予期限まで放置され
     * {@code EXPIRED} になるまで詰む。同じ詰みは利用者が Checkout を放棄した場合
     * （{@code checkout.session.expired}）にも起きる。</p>
     *
     * <p><b>なぜ巻き戻して安全か</b>: この時点では<b>旧契約に一切触れていない</b>。旧サブスクへの
     * {@code cancel_at_period_end=true} は (a)引継確定条件（{@code checkout.session.completed}）と
     * 同時にしか設定されないため（§3.6 表(a)・R3-P1-3）、承諾確定前の巻き戻しは旧契約を無傷のまま残す。
     * 設計書が支払い手段なしの場合に「状態は {@code ACCEPTED} に留めず差し戻す」と定めているのと同じ原理である。</p>
     *
     * <p><b>pointer は触らない</b>: {@code PENDING_HANDOVER} 契約はそもそも pointer を持たず、同スロットの
     * pointer は<b>旧契約</b>のものである。{@code BillingContractService#abandonPendingContract} は
     * スロット単位で pointer を物理 DELETE するため、これを引継契約に適用すると
     * <b>旧契約の entitlement を巻き添えで剥がす</b>。本メソッドが別経路として存在する理由がこれである。</p>
     *
     * <p><b>冪等・webhook 逆順に安全</b>: {@code ACCEPTED} 以外は no-op。{@code checkout.session.completed} が
     * 先に届いて {@code SWITCHING} へ進んだ後に {@code expired} が遅れて届いても、確定済みの引継を
     * 巻き戻さない。</p>
     *
     * <p><b>過去の試行に属する巻き戻し要求は無視する</b>（Codex検分2巡目 P1-3）: 承諾 A が失敗して
     * 巻き戻り、別 ADMIN の承諾 B が成立した後に、<b>A の Checkout</b> の
     * {@code checkout.session.expired} が遅れて届くことがある。イベント元の契約 ID を照合せずに
     * 巻き戻すと、この遅着イベントが<b>進行中の承諾 B を壊す</b>。{@code expectedNewContractId} が
     * 与えられた場合は要求行の現在の {@code new_contract_id} と一致するときだけ巻き戻す。</p>
     *
     * @param handoverRequestId     対象の引継要求
     * @param expectedNewContractId 巻き戻しを許す新契約 ID。{@code null} なら照合しない
     *                              （呼び出し元自身がその承諾試行の実行者である場合に限り使う）
     * @param reason                ログに残す巻き戻し理由
     */
    @Transactional
    public void rollbackAcceptanceToRequested(
            UUID handoverRequestId, UUID expectedNewContractId, String reason) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findByIdForUpdate(handoverRequestId).orElse(null);
        if (handover == null || handover.getStatus() != PayerHandoverStatus.ACCEPTED) {
            return;
        }
        if (expectedNewContractId != null
                && !expectedNewContractId.equals(handover.getNewContractId())) {
            // 過去の承諾試行に属する遅着イベント。現在進行中の承諾を巻き戻してはならない。
            log.info("柱③-B: 過去の承諾試行に属する巻き戻し要求を無視しました"
                    + " handoverRequestId={}, eventContractId={}, currentNewContractId={}, reason={}",
                    handoverRequestId, expectedNewContractId, handover.getNewContractId(), reason);
            return;
        }

        UUID newContractId = handover.getNewContractId();
        handover.setStatus(PayerHandoverStatus.REQUESTED);
        handover.setAcceptedAt(null);
        // 次の承諾者が別 ADMIN でも正しい payer で契約を作り直せるよう、承諾者と新契約の紐付けを外す。
        handover.setNewPayerUserId(null);
        handover.setNewContractId(null);
        handoverRequestRepository.save(handover);

        if (newContractId != null) {
            billingContractRepository.findByIdAndDeletedAtIsNull(newContractId).ifPresent(newContract -> {
                if (newContract.getStatus() == ContractStatus.PENDING_HANDOVER) {
                    newContract.setStatus(ContractStatus.CANCELLED);
                    newContract.setCancelledAt(LocalDateTime.now(clock));
                    billingContractRepository.save(newContract);
                }
            });
        }

        log.warn("柱③-B: 承諾を REQUESTED へ巻き戻しました（旧契約は無傷・再承諾可能）"
                + " handoverRequestId={}, discardedNewContractId={}, reason={}",
                handoverRequestId, newContractId, reason);
    }

    /**
     * 期限超過の未解決承諾の照合に必要な最小情報。
     *
     * @param handoverRequestId 引継要求 ID
     * @param newPayerUserId    記録済みの承諾者（この Customer だけが §3.2 の照合対象になり得る）
     * @param newContractId     先行作成済みの引継先契約
     */
    public record ReconcileTarget(UUID handoverRequestId, Long newPayerUserId, UUID newContractId) {
    }

    /**
     * 期限超過かつ未解決の {@code ACCEPTED} 行を読み出す（該当しなければ {@code null}）。
     *
     * <p>Stripe 照会は tx の外で行うため、ここでは照合に要る値だけを取り出して返す。</p>
     */
    @Transactional(readOnly = true)
    public ReconcileTarget loadExpiredUnresolvedAcceptance(UUID handoverRequestId, Instant now) {
        return handoverRequestRepository.findById(handoverRequestId)
                .filter(h -> h.getStatus() == PayerHandoverStatus.ACCEPTED)
                .filter(h -> h.getPspNewSubscriptionRef() == null)
                .filter(h -> !h.getExpiresAt().isAfter(now))
                .filter(h -> h.getNewPayerUserId() != null)
                .map(h -> new ReconcileTarget(h.getId(), h.getNewPayerUserId(), h.getNewContractId()))
                .orElse(null);
    }

    /**
     * 期限超過のまま未解決だった承諾を {@code EXPIRED} で終端化する（設計書 §5.3・Codex検分4巡目 P1）。
     *
     * <p>Stripe 照会で<b>新サブスクが存在しないことを確定させてから</b>呼ぶこと。存在するのに終端化すると
     * 課金される新サブスクを孤児として残してしまう。呼び出し側
     * （{@link BillingPayerHandoverService#reconcileExpiredAcceptance}）がその確認を担う。</p>
     *
     * <p>終端化により生成列 {@code open_old_contract_id} が NULL になり、同一旧契約への再要求
     * ブロックが解け、§5.4 により止められていた purge の期末解約フォールバックにも処理が渡る
     * （＝旧 payer の課金を止められる状態に戻る）。</p>
     *
     * <p><b>冪等</b>: {@code ACCEPTED} 以外、または期限未到来なら no-op。</p>
     *
     * @return 実際に終端化したなら {@code true}
     */
    @Transactional
    public boolean expireUnresolvedAcceptance(UUID handoverRequestId, Instant now) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findByIdForUpdate(handoverRequestId).orElse(null);
        if (handover == null
                || handover.getStatus() != PayerHandoverStatus.ACCEPTED
                || handover.getPspNewSubscriptionRef() != null
                || handover.getExpiresAt().isAfter(now)) {
            return false;
        }

        UUID newContractId = handover.getNewContractId();
        handover.setStatus(PayerHandoverStatus.EXPIRED);
        handoverRequestRepository.save(handover);

        // 先行作成した PENDING_HANDOVER 契約は課金に至っていないので破棄する。
        // pointer は触らない（同スロットの pointer は旧契約のものであり、消すと旧の entitlement が飛ぶ）。
        if (newContractId != null) {
            billingContractRepository.findByIdAndDeletedAtIsNull(newContractId).ifPresent(newContract -> {
                if (newContract.getStatus() == ContractStatus.PENDING_HANDOVER) {
                    newContract.setStatus(ContractStatus.CANCELLED);
                    newContract.setCancelledAt(LocalDateTime.now(clock));
                    billingContractRepository.save(newContract);
                }
            });
        }

        log.warn("柱③-B: 期限超過のまま未解決だった承諾を EXPIRED で終端化しました"
                + "（Stripe 上に新サブスク不在を確認済み・purge の期末解約フォールバックへ渡る）"
                + " handoverRequestId={}, discardedNewContractId={}", handoverRequestId, newContractId);
        return true;
    }

    /**
     * {@code ACCEPTED} へ遷移し、引継先の {@code billing_contracts} 行を
     * <b>{@code PENDING_HANDOVER}</b> で先行作成する（設計書 §3.1・P0-4）。
     *
     * <p><b>pointer は作らない</b>。{@code active_contract_pointers.uk_acp_slot} はスロット単位 UNIQUE のため、
     * 新旧2契約が同時に pointer を持とうとすると衝突する。pointer の付け替えは切替TX
     * （{@link #executeSwitchTx}）でのみ、旧削除と新作成を同一トランザクションで行う。</p>
     *
     * <p>行ロックを取り直したうえで状態を再検証するため、{@link #validateAcceptable} との間に
     * 別 ADMIN の承諾が割り込んでも遷移が二重に成立することはない（AC-12）。</p>
     */
    @Transactional
    public AcceptTransition transitionToAccepted(
            EntitlementScopeKind scopeKind, Long scopeId, UUID handoverRequestId, Long operatorUserId) {

        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        requireSameScope(handover, scopeKind, scopeId);
        // 承諾者の適格性は「実際に書き込む」本メソッドでも独立に検証する（public 入口ごとの二重防御）。
        // validateAcceptable を経ずに本メソッドだけを呼ばれても穴が開かないようにするため。
        requireEligibleAcceptor(handover, operatorUserId);
        requireAcceptableStatus(handover, operatorUserId);

        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

        Instant now = clock.instant();
        handover.setStatus(PayerHandoverStatus.ACCEPTED);
        handover.setAcceptedAt(now);
        handover.setNewPayerUserId(operatorUserId);

        // 既に新契約が作られていれば作り直さない（承諾の再試行・冪等）。
        UUID newContractId = handover.getNewContractId();
        if (newContractId == null) {
            BillingContractEntity newContract = BillingContractEntity.builder()
                    .scopeKind(oldContract.getScopeKind())
                    .scopeId(oldContract.getScopeId())
                    .organizationId(oldContract.getOrganizationId())
                    .contractKind(oldContract.getContractKind())
                    .planKey(oldContract.getPlanKey())
                    .featureKey(oldContract.getFeatureKey())
                    .status(ContractStatus.PENDING_HANDOVER)
                    .memberCountSnapshot(oldContract.getMemberCountSnapshot())
                    .bandNoSnapshot(oldContract.getBandNoSnapshot())
                    // 価格スナップショットは旧契約から引き継ぐ（引継は値上げではない）。
                    .priceJpySnapshot(oldContract.getPriceJpySnapshot())
                    .priceBandVersionId(oldContract.getPriceBandVersionId())
                    .contractedAt(LocalDateTime.ofInstant(now, clock.getZone()))
                    .createdBy(operatorUserId)
                    .payerUserId(operatorUserId)
                    .handoverRequestId(handover.getId())
                    .build();
            newContractId = billingContractRepository.save(newContract).getId();
            handover.setNewContractId(newContractId);
        }
        handoverRequestRepository.save(handover);

        return new AcceptTransition(handover.getId(), newContractId, operatorUserId,
                oldContract.getId(), oldContract.getPriceJpySnapshot(),
                buildDisplayName(oldContract), toInstant(oldContract.getCurrentPeriodEnd()),
                handover.getPspNewSubscriptionRef());
    }

    /**
     * 新サブスク ID を永続化する（設計書 §3.2 一次防衛の要・AC-7/AC-25）。
     *
     * <p>Stripe API 成功後に別操作として書き込むため<b>原子性は成立しない</b>。
     * この間に落ちた場合は、次回リトライの回復経路（DB → List Subscriptions の順で照会）が回収する。</p>
     */
    @Transactional
    public void persistNewSubscriptionRef(UUID handoverRequestId, String newSubscriptionRef) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        if (handover.getPspNewSubscriptionRef() == null) {
            handover.setPspNewSubscriptionRef(newSubscriptionRef);
            handoverRequestRepository.save(handover);
        }
    }

    // ============================================================
    // (a) 引継確定（checkout.session.completed）— AC-6 / AC-31 / AC-30 1段目
    // ============================================================

    /**
     * {@code SWITCHING} へ遷移し、新サブスク ID を確定する（設計書 §3.6 (a)）。
     *
     * <p><b>冪等</b>: 既に {@code SWITCHING} 以降（または終端）なら {@code null} を返し、呼び出し側は
     * 旧サブスクへの再設定を行わない。</p>
     *
     * @return 後続の Stripe 操作に必要な参照（no-op のときは {@code null}）
     */
    @Transactional
    public CheckoutCompletion markSwitching(UUID handoverRequestId, String newSubscriptionRef) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findByIdForUpdate(handoverRequestId).orElse(null);
        if (handover == null) {
            log.warn("柱③-B: 引継確定 webhook の対象が見つかりません handoverRequestId={}", handoverRequestId);
            return null;
        }
        if (handover.getStatus() != PayerHandoverStatus.ACCEPTED) {
            log.info("柱③-B: 引継確定 webhook は状態遷移させない（既に {} ）handoverRequestId={}",
                    handover.getStatus(), handoverRequestId);
            return null;
        }
        handover.setStatus(PayerHandoverStatus.SWITCHING);
        if (newSubscriptionRef != null) {
            handover.setPspNewSubscriptionRef(newSubscriptionRef);
        }
        handoverRequestRepository.save(handover);

        String oldSubscriptionRef = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .map(BillingContractEntity::getPspSubscriptionRef)
                .orElse(null);
        return new CheckoutCompletion(handover.getId(), oldSubscriptionRef,
                handover.getPspNewSubscriptionRef());
    }

    /**
     * 終端化済みの引継に対して遅着した {@code checkout.session.completed} が連れてきた
     * <b>孤児サブスク</b>を検出する（設計書 §3.6 遷移表・Codex検分5巡目 P1）。
     *
     * <p><b>塞ぐ穴</b>: 期限超過の照合（{@code reconcileExpiredAcceptance}）は「Stripe に不在」を
     * 確認してから {@code EXPIRED} へ終端化するが、その<b>照会と終端化の間に利用者が Checkout を
     * 完了させる</b>ことがある。この場合 Stripe には新サブスクが生成済みなのに要求は
     * {@code EXPIRED} になっており、遅れて届く {@code completed} webhook を単に no-op にすると、
     * <b>DB に記録されず解約もされないサブスクが課金だけ続ける</b>（孤児）。</p>
     *
     * <p><b>DB 側の直列化では原理的に防げない</b>: 行ロックで DB 操作を直列化しても、Stripe 側で
     * サブスクが生成された事実と webhook 到達の間の時間差は消せない。よって「起きてしまった後に
     * 金銭を守る補償」を最終防衛線として置く必要がある。設計書も同種の状況
     * （§3.6 2段目の {@code pending_setup_intent} 未解決・{@code customer.subscription.deleted}）で
     * <b>新 trial サブスクを {@code cancelImmediately} して無課金のまま取り消す</b>ことを定めており、
     * 本補償はその扱いと整合する（trial 中のため取り消せば課金は発生しない）。</p>
     *
     * <p><b>{@code EXPIRED} のみを対象にする理由</b>: {@code SWITCHING}/{@code PARTIALLY_COMPLETED}/
     * {@code COMPLETED} は新サブスクを正当に保有している状態であり取り消してはならない。
     * {@code FAILED} は §3.6 の失敗経路が既に新サブスクの取消を担っているため二重に取り消さない。
     * 孤児が生じ得るのは「不在を確認して終端化した」本経路＝{@code EXPIRED} だけである。</p>
     *
     * @return 取り消すべきサブスク参照。該当しなければ {@code null}
     */
    @Transactional(readOnly = true)
    public String detectOrphanNewSubscription(UUID handoverRequestId, String newSubscriptionRef) {
        if (newSubscriptionRef == null) {
            return null;
        }
        return handoverRequestRepository.findById(handoverRequestId)
                .filter(h -> h.getStatus() == PayerHandoverStatus.EXPIRED)
                .map(h -> newSubscriptionRef)
                .orElse(null);
    }

    /**
     * 旧サブスクへの {@code cancel_at_period_end=true} 設定が成功した時刻を永続化する
     * （設計書 §3.6.1(a)・R4-P1-2）。
     *
     * <p><b>Stripe API 呼び出しと本 DB 書き込みは原子的ではない</b>（外部システムは DB tx に巻き込めない）。
     * 「Stripe では成功したが本書き込み前にクラッシュ」した不整合は、夜次照合バッチ（PR-4）が
     * Stripe 実物と突合して補完する前提で設計している。</p>
     */
    @Transactional
    public void persistOldCancelScheduledAt(UUID handoverRequestId, Instant scheduledAt) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setOldCancelScheduledAt(scheduledAt);
        handoverRequestRepository.save(handover);
    }

    /**
     * 追加認証（SCA/3DS）要求の通知を発行する（設計書 §3.6・AC-30 の1段目）。
     *
     * <p><b>状態遷移は行わない</b>。旧サブスクの {@code cancel_at_period_end=true} は既に設定済みであり、
     * 引継自体は進行中扱いのままとする。</p>
     */
    @Transactional
    public void publishAdditionalAuthRequired(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findById(handoverRequestId).orElse(null);
        if (handover == null || handover.getNewPayerUserId() == null) {
            return;
        }
        eventPublisher.publishEvent(new BillingPayerHandoverNotificationEvent(
                BillingPayerHandoverNotificationKind.ADDITIONAL_AUTH_REQUIRED,
                handover.getId(), handover.getScopeKind(), handover.getScopeId(),
                List.of(handover.getNewPayerUserId()), handover.getNewPayerUserId()));
    }

    // ============================================================
    // (b) pointer 切替（旧期末到達）— AC-27 / AC-30 2段目 / AC-32 / AC-35
    // ============================================================

    /**
     * 切替の前提情報を読み出す（設計書 §3.6 (b)）。{@code SWITCHING} 以外は {@code null}。
     *
     * <p>{@code PARTIALLY_COMPLETED}（非終端・リトライ対象）も切替の再試行対象に含める。</p>
     */
    @Transactional(readOnly = true)
    public SwitchContext loadSwitchContext(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover =
                handoverRequestRepository.findById(handoverRequestId).orElse(null);
        if (handover == null) {
            return null;
        }
        if (handover.getStatus() != PayerHandoverStatus.SWITCHING
                && handover.getStatus() != PayerHandoverStatus.PARTIALLY_COMPLETED) {
            return null;
        }
        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId()).orElse(null);
        if (oldContract == null) {
            return null;
        }
        return new SwitchContext(handover.getId(), oldContract.getPspSubscriptionRef(),
                handover.getPspNewSubscriptionRef(), toInstant(oldContract.getCurrentPeriodEnd()));
    }

    /**
     * 引継を {@code FAILED} で確定する（設計書 §3.6.1・AC-32・R5-P2）。
     *
     * <p>Stripe 側の差し戻し（新 trial サブスクの無課金取消・旧サブスクの
     * {@code cancel_at_period_end=false}）は呼び出し側が tx の外で済ませている。本メソッドは
     * その<b>対</b>として {@code old_cancel_scheduled_at} を NULL クリアする——クリアし忘れると、
     * 同一契約への再要求時に「予約済み」と誤認され夜次照合バッチの検出対象から外れる。</p>
     *
     * <p>あわせて新契約（{@code PENDING_HANDOVER}）を {@code CANCELLED} で無効化する。
     * 旧契約の pointer は無傷のため利用者影響は無い。</p>
     */
    @Transactional
    public void markFailedAndClearCancelSchedule(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.FAILED);
        handover.setOldCancelScheduledAt(null);
        handoverRequestRepository.save(handover);

        if (handover.getNewContractId() != null) {
            billingContractRepository.findByIdAndDeletedAtIsNull(handover.getNewContractId())
                    .filter(c -> c.getStatus() == ContractStatus.PENDING_HANDOVER)
                    .ifPresent(c -> {
                        c.setStatus(ContractStatus.CANCELLED);
                        c.setCancelledAt(LocalDateTime.ofInstant(clock.instant(), clock.getZone()));
                        billingContractRepository.save(c);
                    });
        }
    }

    /**
     * {@code MANUAL_INTERVENTION} へ倒す（設計書 §3.6.2・AC-35・R5-P1-1/2）。
     *
     * <p><b>非終端</b>のため {@code open_old_contract_id} は値を保持し続け、同一契約への新規引継要求は
     * 運用者の {@code RESUME} まで物理的にブロックされる（人手対応中の二重進行を防ぐ意図的な設計）。</p>
     */
    @Transactional
    public void markManualIntervention(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.MANUAL_INTERVENTION);
        handoverRequestRepository.save(handover);
    }

    /**
     * ローカル切替TX（設計書 §3.1・§3.6 (b)・AC-6/AC-27）。
     *
     * <p><b>Stripe API 呼び出しを一切含まない</b>。Stripe 側は承諾確定（{@code checkout.session.completed}）
     * の時点で {@code cancel_at_period_end=true} が設定済みであり確定しているため、ここで行うのは
     * ローカル DB 操作だけである。</p>
     *
     * <p><b>旧 pointer の物理 DELETE と新 pointer の INSERT を同一トランザクションで行う</b>ことが、
     * entitlement 空白ゼロ・二重付与ゼロの根拠である（DB トランザクションの原子性のみに依存する）。
     * <b>分割してはならない。</b></p>
     */
    @Transactional
    public void executeSwitchTx(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        BillingContractEntity oldContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getOldContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));
        BillingContractEntity newContract = billingContractRepository
                .findByIdAndDeletedAtIsNull(handover.getNewContractId())
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

        String slotAddonKey = oldContract.getContractKind() == ContractKind.ADDON
                ? oldContract.getFeatureKey() : "";

        // ① 旧 pointer を物理 DELETE（contract_id 一致条件つき。切替後に届く旧 webhook が
        //    新 pointer を消してしまう P0-3 の穴を、削除側でも同じ条件に揃える）。
        activeContractPointerRepository.hardDeleteBySlotAndContractId(
                oldContract.getScopeKind(), oldContract.getScopeId(),
                oldContract.getContractKind(), slotAddonKey, oldContract.getId());

        // ② 新 pointer を INSERT（①と同一トランザクション。ここを分けると entitlement に空白が生じる）。
        activeContractPointerRepository.saveAndFlush(ActiveContractPointerEntity.builder()
                .scopeKind(newContract.getScopeKind())
                .scopeId(newContract.getScopeId())
                .contractKind(newContract.getContractKind())
                .addonFeatureKey(slotAddonKey)
                .contractId(newContract.getId())
                .organizationId(newContract.getOrganizationId())
                .build());

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        oldContract.setStatus(ContractStatus.CANCELLED);
        oldContract.setCancelledAt(now);
        billingContractRepository.save(oldContract);

        newContract.setStatus(ContractStatus.ACTIVE);
        newContract.setPspSubscriptionRef(handover.getPspNewSubscriptionRef());
        billingContractRepository.save(newContract);

        handover.setStatus(PayerHandoverStatus.COMPLETED);
        handover.setCompletedAt(clock.instant());
        handoverRequestRepository.save(handover);
    }

    /**
     * ローカル切替TX の DB 書き込みだけが失敗した状態（設計書 §3.5 再定義）。
     *
     * <p><b>非終端</b>であり夜次バッチのリトライ対象。Stripe 側は既に確定済み（旧は期末で終わる）なので、
     * リトライは「pointer 付替え＋状態遷移」という冪等な操作の再実行で足りる。
     * <b>終端扱いにしてはならない</b>（リトライ経路から外れて宙ぶらりんになる）。</p>
     */
    @Transactional
    public void markPartiallyCompleted(UUID handoverRequestId) {
        BillingPayerHandoverRequestEntity handover = lockOrThrow(handoverRequestId);
        handover.setStatus(PayerHandoverStatus.PARTIALLY_COMPLETED);
        handoverRequestRepository.save(handover);
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    private BillingPayerHandoverRequestEntity lockOrThrow(UUID handoverRequestId) {
        return handoverRequestRepository.findByIdForUpdate(handoverRequestId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));
    }

    /** スコープ越境は存在自体を明かさず 404 で畳む（既存 {@code loadContractInScope} と同流儀）。 */
    private void requireSameScope(
            BillingPayerHandoverRequestEntity handover, EntitlementScopeKind scopeKind, Long scopeId) {
        if (handover.getScopeKind() != scopeKind || !handover.getScopeId().equals(scopeId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND);
        }
    }

    /**
     * 承諾者が「対象スコープの<b>他</b> ADMIN」であることを要求する（設計書 §5.6・Codex検分1巡目 P1-2）。
     *
     * <p>判定は申請時に通知先を決めたのと同一の {@link BillingPayerHandoverCandidateResolver} に委ね、
     * 「通知を受け取る者」と「承諾できる者」が常に同じ集合であることを保証する。
     * スコープの一致と管理権限は呼び出し側で検証済みであり、契約の存在は既に相手に見えているため、
     * ここは 404 で畳まず 403（{@code HANDOVER_NOT_ELIGIBLE_ACCEPTOR}）を返す。</p>
     */
    /**
     * 承諾を受け付けてよい状態かを検証する（設計書 §3.6・Codex検分3巡目 P1）。
     *
     * <p>通常の入口は {@code REQUESTED}（新規・巻き戻し後）と {@code REQUIRES_PAYMENT_METHOD}（差し戻し後）。
     * これに加えて<b>{@code ACCEPTED} は「記録済みの承諾者本人」だけが再試行できる</b>。</p>
     *
     * <p><b>なぜ本人限定の再試行が必要か</b>: 承諾後の Stripe List Subscriptions 照会（§3.2 の回復照合）が
     * 失敗すると、「その承諾者の Customer に新サブスクが既に在るか」が<b>不明</b>なまま残る。この曖昧な状態で
     * {@code REQUESTED} へ巻き戻して別 ADMIN B に承諾させると、B の承諾で走る回復照合は
     * §3.2（R3-P0）の定めどおり {@code customer={B の Customer}} に絞って走査するため
     * <b>A の Customer にあるサブスクを原理的に発見できず</b>、二重サブスク＝二重課金になる
     * （Idempotency-Key も customer が変わればパラメータ不一致で効かない）。
     * よって曖昧な間は承諾者を A に固定し、A 本人の再試行でのみ同じ Customer を照会させて回収する。</p>
     *
     * <p><b>状態機械は増やしていない</b>: 新しい状態も新しい遷移も追加せず、既存 {@code ACCEPTED} への
     * 再入を承諾者本人に限って許すだけである。{@code transitionToAccepted} は元から
     * 「既に新契約が作られていれば作り直さない」冪等実装であり、この再入を前提にしている。
     * A が戻らなければ猶予期限で {@code EXPIRED} となり §5.3 の purge fallback に渡る
     * （設計書が「誰も承諾しなかった場合」に定める既存の挙動と同じ）。</p>
     */
    private void requireAcceptableStatus(
            BillingPayerHandoverRequestEntity handover, Long operatorUserId) {
        PayerHandoverStatus status = handover.getStatus();
        if (status == PayerHandoverStatus.REQUESTED
                || status == PayerHandoverStatus.REQUIRES_PAYMENT_METHOD) {
            return;
        }
        if (status == PayerHandoverStatus.ACCEPTED
                && operatorUserId != null
                && operatorUserId.equals(handover.getNewPayerUserId())) {
            // Stripe 上の作成有無が曖昧なまま残った承諾の、本人による再試行。
            return;
        }
        throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_ACCEPTABLE);
    }

    private void requireEligibleAcceptor(
            BillingPayerHandoverRequestEntity handover, Long operatorUserId) {
        if (!candidateResolver.isEligibleAcceptor(
                handover.getScopeKind(), handover.getScopeId(),
                handover.getOldPayerUserId(), operatorUserId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_ELIGIBLE_ACCEPTOR);
        }
    }

    /**
     * {@code billing_contracts} の壁時計（{@link LocalDateTime}）を {@link Instant} へ変換する。
     *
     * <p>既存 {@code BillingContractService#cancelPaidAtPeriodEnd} の
     * {@code LocalDateTime.ofInstant(instant, clock.getZone())} と<b>対称</b>な逆変換であり、
     * 同じ {@code Clock} の zone を用いるため往復で値が変わらない（AC-5 の unix 秒一致の根拠）。</p>
     */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }

    private String buildDisplayName(BillingContractEntity contract) {
        return contract.getContractKind() == ContractKind.PLAN
                ? "Mannschaft プラン: " + contract.getPlanKey()
                : "Mannschaft 機能: " + contract.getFeatureKey();
    }

    // ============================================================
    // 戻り値レコード（Entity を外へ漏らさない）
    // ============================================================

    /** 承諾可否の検証結果（{@link #validateAcceptable}）。 */
    public record AcceptValidation(
            UUID handoverRequestId, EntitlementScopeKind scopeKind, Long scopeId,
            UUID oldContractId, PayerHandoverStatus status,
            String existingNewSubscriptionRef, UUID existingNewContractId) {
    }

    /** {@code ACCEPTED} 遷移の結果（{@link #transitionToAccepted}）。 */
    public record AcceptTransition(
            UUID handoverRequestId, UUID newContractId, Long newPayerUserId, UUID oldContractId,
            Integer priceJpy, String displayName, Instant oldPeriodEnd, String existingNewSubscriptionRef) {
    }

    /** 引継確定（{@code checkout.session.completed}）の結果（{@link #markSwitching}）。 */
    public record CheckoutCompletion(
            UUID handoverRequestId, String oldSubscriptionRef, String newSubscriptionRef) {
    }

    /** 切替の前提情報（{@link #loadSwitchContext}）。 */
    public record SwitchContext(
            UUID handoverRequestId, String oldSubscriptionRef, String newSubscriptionRef, Instant oldPeriodEnd) {
    }
}
