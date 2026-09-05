package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingPaymentGateway.CheckoutSessionInfo;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.AcceptTransition;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.CheckoutCompletion;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.SwitchContext;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 柱③-B 組織契約の請求担当引継（CMP-260901-1538）: 引継フローのオーケストレーション。
 *
 * <p>設計書: {@code docs/architecture/billing_payer_handover_design.md}（§2.3・§3.1〜§3.7・§5）。</p>
 *
 * <h2>方式の要点</h2>
 * <ul>
 *   <li><b>置換方式＋{@code trial_end}</b>: Stripe は Subscription の customer 差し替えに非対応のため、
 *       新 Customer で新サブスクを作り旧を解約する。新サブスクの {@code trial_end} に
 *       <b>旧契約の {@code current_period_end} と同一 unix 秒</b>を指定することで、併存期間の課金をゼロにし、
 *       旧期末と新開始の間に隙間も重複も作らない（§2.3・AC-4/AC-5）。</li>
 *   <li><b>二重課金の構造的排除</b>: 承諾確定（{@code checkout.session.completed}）と<b>同時に</b>旧サブスクへ
 *       {@code cancel_at_period_end=true} を設定する。以後どの後続手順が失敗しても、旧は Stripe 側の保証で
 *       必ず期末に終了する（R3-P1-3・AC-31）。</li>
 *   <li><b>二重サブスク作成の一次防衛</b>: 新サブスク作成の前に必ず「DB の {@code psp_new_subscription_ref}」→
 *       「Stripe List Subscriptions の全ページ走査」の順で確認し、<b>両方が空のときだけ</b>作成する
 *       （Idempotency-Key は 24h で失効するため補助にすぎない・§3.2・AC-7/AC-25/AC-33）。</li>
 *   <li><b>切替TX はローカル DB のみ</b>: pointer の付け替えは旧期末到達を唯一の条件に実行し、
 *       旧 pointer 削除と新 pointer 作成を同一トランザクションで行う（entitlement 空白ゼロ・二重付与ゼロ・AC-27）。</li>
 * </ul>
 *
 * <h2>トランザクション境界</h2>
 * <p>Stripe 呼び出しを長い {@code @Transactional} の内側に抱えない（既存 {@link BillingCheckoutService} と同流儀）。
 * 個々のトランザクション単位は {@link BillingPayerHandoverTxService} に切り出してあり、本クラスは
 * 「DB tx → commit → Stripe → DB tx」の順序だけを組み立てる。</p>
 *
 * <h2>時刻</h2>
 * <p>handover 側は {@link Instant}、{@code billing_contracts} 側は {@link LocalDateTime}。変換は既存
 * {@code BillingContractService#cancelPaidAtPeriodEnd} と<b>対称</b>に同じ {@link Clock} の zone で行う
 * （{@code ZoneId.of("...")} の直書きは番人 {@code DateTimeAndZoneGuardTest} が拒否する）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPayerHandoverService {

    /** 承諾の猶予期限（設計書 §5.3・暫定 14 日）。 */
    static final Duration ACCEPTANCE_GRACE = Duration.ofDays(14);

    /** 引継先候補として数えるロール名（設計書 §5.5 ①②）。 */
    private static final String ADMIN_ROLE = "ADMIN";

    private final BillingPayerHandoverRequestRepository handoverRequestRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingOperationAuthorizer billingOperationAuthorizer;
    private final BillingPaymentGateway billingPaymentGateway;
    private final BillingPayerHandoverTxService handoverTxService;
    private final RoleService roleService;
    private final Clock clock;

    @Value("${app.base-url}")
    private String appBaseUrl;

    /**
     * 引継要求の作成結果。
     *
     * @param handoverRequestId {@code billing_payer_handover_requests.id}
     * @param oldContractId     引継元契約
     * @param scopeKind         TEAM / ORG
     * @param scopeId           teams.id / organizations.id
     * @param status            作成直後は常に {@link PayerHandoverStatus#REQUESTED}
     * @param requestedAt       要求時刻
     * @param expiresAt         猶予期限（{@code requestedAt} + 14 日）
     */
    public record HandoverRequestResult(UUID handoverRequestId, UUID oldContractId,
            EntitlementScopeKind scopeKind, Long scopeId, PayerHandoverStatus status,
            Instant requestedAt, Instant expiresAt) {
    }

    /**
     * 引継承諾の結果。
     *
     * @param handoverRequestId 引継要求 ID
     * @param status            {@link PayerHandoverStatus#ACCEPTED} または
     *                          {@link PayerHandoverStatus#REQUIRES_PAYMENT_METHOD}（差し戻し・例外ではない）
     * @param newContractId     引継先契約（差し戻し時は {@code null}）
     * @param checkoutUrl       新サブスクの Checkout URL（回復経路で既存サブスクを再利用した場合は {@code null}）
     */
    public record HandoverAcceptResult(UUID handoverRequestId, PayerHandoverStatus status,
            UUID newContractId, String checkoutUrl) {
    }

    // ============================================================
    // 1段目: 旧 payer による引継申請
    // ============================================================

    /**
     * 引継要求を作成する（承諾型2段の1段目・設計書 §5.1・§5.5・AC-10/17/18/29）。
     *
     * <p>Stripe 呼び出しを一切含まないため単一トランザクションで完結する。
     * 前提を1つでも満たさない場合は<b>要求行を作らない</b>（作ってから失敗させると
     * {@code uk_bphr_open_old_contract} が同一契約への再申請をブロックし続けてしまう）。</p>
     *
     * @throws BusinessException {@code HANDOVER_SCOPE_NOT_SUPPORTED}（USER スコープ）/
     *                           {@code HANDOVER_NOT_FOUND}（不存在・スコープ越境を 404 で畳む）/
     *                           {@code HANDOVER_CONTRACT_NOT_ELIGIBLE}（PSP 未紐付・期末 NULL・PAST_DUE・期末が過去）/
     *                           {@code HANDOVER_NO_CANDIDATE}（引継先 ADMIN 不在）/
     *                           {@code HANDOVER_ALREADY_IN_PROGRESS}（進行中の要求あり）
     */
    @Transactional
    public HandoverRequestResult requestHandover(EntitlementScopeKind scopeKind, Long scopeId,
            UUID oldContractId, Long operatorUserId) {

        // ① USER スコープは契約者本人以外に payer が存在し得ず、引継の概念自体が無い（設計書 §4.2）。
        if (scopeKind == null || scopeKind == EntitlementScopeKind.USER) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_SCOPE_NOT_SUPPORTED);
        }

        // ② 認可（Propagation.MANDATORY のため書き込み tx の内側から呼ぶ）。
        billingOperationAuthorizer.requireCanManage(operatorUserId, scopeKind, scopeId);

        // ③ 契約解決。スコープ越境は存在自体を明かさず 404 で畳む（IDOR 二重防御）。
        BillingContractEntity contract = billingContractRepository.findByIdAndDeletedAtIsNull(oldContractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));
        if (contract.getScopeKind() != scopeKind || !contract.getScopeId().equals(scopeId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND);
        }

        // ④ §5.1（R2-P1-6）: trial_end 方式・pointer 切替はいずれも「Stripe 実在サブスクの期末」を前提にする。
        //    無償契約／PSP 未作成の PENDING 契約はこの経路の対象外。
        if (contract.getPspSubscriptionRef() == null || contract.getCurrentPeriodEnd() == null) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // ⑤ AC-29: PAST_DUE または期末が過去なら拒否。trial_end には未来時刻しか指定できず、
        //    ここを素通りさせると Stripe が 400 を返す（先に支払回収または解約を促す）。
        Instant now = clock.instant();
        Instant oldPeriodEnd = toInstant(contract.getCurrentPeriodEnd());
        if (contract.getStatus() == ContractStatus.PAST_DUE || !oldPeriodEnd.isAfter(now)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // 旧 payer は payer_user_id を正とし、legacy 行（V203 バックフィル前）のみ created_by へ倒す。
        Long oldPayerUserId = contract.getPayerUserId() != null
                ? contract.getPayerUserId() : contract.getCreatedBy();
        if (oldPayerUserId == null) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // ⑥ AC-10/17/18: 引継先候補が居なければ要求を作らない。
        List<Long> candidates = candidateAdminUserIds(scopeKind, scopeId, oldPayerUserId);
        if (candidates.isEmpty()) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NO_CANDIDATE);
        }

        // ⑦ 進行中（非終端）の要求は同一契約に1件まで。DB 側は生成列 + UNIQUE が最終防衛。
        if (!handoverRequestRepository.findByOldContractIdAndStatusNotIn(
                oldContractId, BillingPayerHandoverTxService.TERMINAL_STATUSES).isEmpty()) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_ALREADY_IN_PROGRESS);
        }

        Instant expiresAt = now.plus(ACCEPTANCE_GRACE);
        BillingPayerHandoverRequestEntity request = BillingPayerHandoverRequestEntity.builder()
                .oldContractId(oldContractId)
                .scopeKind(scopeKind)
                .scopeId(scopeId)
                .oldPayerUserId(oldPayerUserId)
                .status(PayerHandoverStatus.REQUESTED)
                .requestedAt(now)
                .expiresAt(expiresAt)
                .build();

        BillingPayerHandoverRequestEntity saved;
        try {
            saved = handoverRequestRepository.save(request);
        } catch (DataIntegrityViolationException ex) {
            // uk_bphr_open_old_contract（生成列 + UNIQUE）による物理拒否。⑦のアプリ層チェックの
            // TOCTOU レースをここで閉じる。
            throw new BusinessException(EntitlementErrorCode.HANDOVER_ALREADY_IN_PROGRESS, ex);
        }

        // 通知は業務 tx 内では publish のみ（実配送は AFTER_COMMIT リスナー）。
        handoverTxService.publishHandoverRequested(
                saved.getId(), scopeKind, scopeId, candidates, operatorUserId);

        log.info("柱③-B: 引継要求を発行しました handoverRequestId={}, oldContractId={}, scope={}/{}, 候補数={}",
                saved.getId(), oldContractId, scopeKind, scopeId, candidates.size());

        return new HandoverRequestResult(saved.getId(), oldContractId, scopeKind, scopeId,
                PayerHandoverStatus.REQUESTED, now, expiresAt);
    }

    // ============================================================
    // 2段目: 新 payer（他 ADMIN）による承諾
    // ============================================================

    /**
     * 引継要求を承諾する（承諾型2段の2段目・設計書 §3.2・§3.6・§5.6）。
     *
     * <h2>トランザクションの分割（3段）</h2>
     * <ol>
     *   <li><b>tx1</b>: 行を {@code SELECT ... FOR UPDATE} でロックし、スコープ一致・認可・期限・状態を検証（AC-11/12）</li>
     *   <li><b>tx 外</b>: 新 payer の支払い手段検証（二段検証の1段目・AC-16）と、新サブスクの照会／作成（Stripe）</li>
     *   <li><b>tx2</b>: 行ロックを取り直して再検証し、{@code ACCEPTED} 遷移＋新契約を {@code PENDING_HANDOVER} で作成</li>
     * </ol>
     * <p>ロックを2度取るのは、<b>外部 API 呼び出しを行ロック保持中の tx に抱えない</b>ためである。
     * tx2 が状態を取り直して再検証するので、割り込んだ別 ADMIN の承諾と二重に成立することはない（AC-12）。</p>
     *
     * <h2>支払い手段が無い場合（AC-16）</h2>
     * <p>例外にせず {@link PayerHandoverStatus#REQUIRES_PAYMENT_METHOD} を結果の status で返す。
     * この時点では<b>旧契約に一切触れていない</b>（旧サブスクへの {@code cancel_at_period_end} も未設定）ため、
     * 旧契約は完全に無傷のまま維持される。</p>
     *
     * @param scopeKind URL 由来のスコープ種別（行の scope と不一致なら 404 で畳む・IDOR）
     * @param scopeId   URL 由来のスコープ ID
     */
    public HandoverAcceptResult acceptHandover(EntitlementScopeKind scopeKind, Long scopeId,
            UUID handoverRequestId, Long operatorUserId) {

        // tx1: 行ロック下でスコープ一致・認可・期限・状態を検証する。
        handoverTxService.validateAcceptable(scopeKind, scopeId, handoverRequestId, operatorUserId);

        // 二段検証の1段目（AC-16）。Stripe 参照は tx の外で行う。
        if (!billingPaymentGateway.hasUsablePaymentMethod(operatorUserId)) {
            handoverTxService.transitionToRequiresPaymentMethod(
                    scopeKind, scopeId, handoverRequestId, operatorUserId);
            log.info("柱③-B: 支払い手段が未登録のため承諾を差し戻しました（旧契約は無傷）"
                    + " handoverRequestId={}, operatorUserId={}", handoverRequestId, operatorUserId);
            return new HandoverAcceptResult(handoverRequestId,
                    PayerHandoverStatus.REQUIRES_PAYMENT_METHOD, null, null);
        }

        // tx2: ACCEPTED へ遷移し、新契約を PENDING_HANDOVER で先行作成（pointer は作らない）。
        AcceptTransition accepted = handoverTxService.transitionToAccepted(
                scopeKind, scopeId, handoverRequestId, operatorUserId);

        // ★§3.2 回復順序（この順序が二重サブスク＝二重課金を防ぐ一次防衛。入れ替えてはならない）。
        // (i) DB に psp_new_subscription_ref が既にあれば作成をスキップ。
        String subscriptionRef = accepted.existingNewSubscriptionRef();
        if (subscriptionRef == null) {
            // (ii) Stripe List Subscriptions を全ページ走査し metadata.handoverRequestId で突合。
            //      「Stripe には作成済みだが DB 反映前に落ちた」ケースを回収する（read-after-write 整合のため待機不要）。
            subscriptionRef = billingPaymentGateway
                    .findHandoverSubscriptionRef(accepted.newPayerUserId(), handoverRequestId)
                    .orElse(null);
            if (subscriptionRef != null) {
                handoverTxService.persistNewSubscriptionRef(handoverRequestId, subscriptionRef);
                log.warn("柱③-B: Stripe には作成済みだが DB 未反映の新サブスクを回収しました"
                        + " handoverRequestId={}, subscriptionRef={}", handoverRequestId, subscriptionRef);
            }
        }
        if (subscriptionRef != null) {
            // 既存サブスクを再利用するため Checkout は生成しない（二重作成の回避）。
            return new HandoverAcceptResult(handoverRequestId, PayerHandoverStatus.ACCEPTED,
                    accepted.newContractId(), null);
        }

        // (iii) DB も List も空のときだけ新規作成する。
        if (accepted.priceJpy() == null) {
            // §5.1 の絞り込みを通っていれば有償契約のはず。ここに来るのは整合性の破れなので隠さず上申する。
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }
        CheckoutSessionInfo info = billingPaymentGateway.createHandoverSubscriptionCheckout(
                accepted.newPayerUserId(),
                accepted.priceJpy(),
                accepted.displayName(),
                accepted.newContractId(),
                accepted.oldContractId(),
                handoverRequestId,
                // ★AC-5: trial_end は旧契約の current_period_end と同一 unix 秒（隙間も重複も生じない）。
                accepted.oldPeriodEnd(),
                appBaseUrl + "/billing/plans?handover=success",
                appBaseUrl + "/billing/plans?handover=cancelled");

        return new HandoverAcceptResult(handoverRequestId, PayerHandoverStatus.ACCEPTED,
                accepted.newContractId(), info.url());
    }

    // ============================================================
    // (a) 引継確定（checkout.session.completed）
    // ============================================================

    /**
     * 引継確定条件の成立を受けて {@code SWITCHING} へ進め、<b>同時に旧サブスクを期末解約予約する</b>
     * （設計書 §3.6 (a)・AC-6/AC-31/AC-30 の1段目）。
     *
     * <p>この「承諾確定と同時の {@code cancel_at_period_end=true}」が二重課金を構造的に消す要である。
     * 以後どの後続手順（切替TX・trial 終了時の請求等）が失敗しても、旧サブスクは Stripe 側の保証で
     * 必ず期末に終了する。</p>
     *
     * <p><b>冪等</b>: 既に {@code SWITCHING} 以降なら no-op（webhook 再送でも旧へ再設定しない）。</p>
     */
    public void onHandoverCheckoutCompleted(UUID handoverRequestId, String newSubscriptionRef) {

        CheckoutCompletion completion = handoverTxService.markSwitching(handoverRequestId, newSubscriptionRef);
        if (completion == null) {
            return; // 冪等 no-op（対象なし／既に SWITCHING 以降）。
        }

        if (completion.oldSubscriptionRef() == null) {
            // 旧サブスクが引けないと期末解約予約ができない＝二重課金の穴が開く。握りつぶさず ERROR で上申する。
            log.error("柱③-B: 旧サブスク参照が解決できず cancel_at_period_end を予約できません"
                    + "（二重課金の恐れ・夜次照合バッチでの検出対象）handoverRequestId={}", handoverRequestId);
        } else {
            billingPaymentGateway.scheduleCancelAtPeriodEndForHandover(
                    completion.oldSubscriptionRef(), handoverRequestId);
            // ★Stripe API 呼び出しと本 DB 書き込みは原子的ではない（外部システムは DB tx に巻き込めない）。
            //   「Stripe では成功したが DB 書き込み前にクラッシュ」した不整合は、
            //   夜次照合バッチ（PR-4）が Stripe 実物と突合して補完する前提で設計している（§3.6.1(a)）。
            handoverTxService.persistOldCancelScheduledAt(handoverRequestId, clock.instant());
        }

        // AC-30 の1段目: pending_setup_intent が残っていれば通知のみ（状態遷移はさせない）。
        String ref = completion.newSubscriptionRef() != null
                ? completion.newSubscriptionRef() : newSubscriptionRef;
        if (ref == null) {
            return;
        }
        SubscriptionSnapshot snapshot = billingPaymentGateway.retrieveSubscription(ref);
        if (snapshot != null && snapshot.hasPendingSetupIntent()) {
            log.warn("柱③-B: 新サブスクに pending_setup_intent が残っています（追加認証を通知・状態遷移はしない）"
                    + " handoverRequestId={}, subscriptionRef={}", handoverRequestId, ref);
            handoverTxService.publishAdditionalAuthRequired(handoverRequestId);
        }
    }

    // ============================================================
    // (b) pointer 切替（旧期末到達）
    // ============================================================

    /**
     * 旧期末到達時のローカル切替を実行する（設計書 §3.6 (b)・§3.6.1(b)・AC-27/AC-30/AC-32/AC-35）。
     *
     * <p><b>本メソッドが唯一の切替TX実行者</b>である（webhook 等の他経路はこの判定・実行を代行しない）。
     * 切替の前に Stripe 実物で2点を確認する:</p>
     * <ol>
     *   <li><b>二段検証の2段目</b>: 新サブスクの {@code pending_setup_intent} が未解決なら切替せず
     *       {@code FAILED} 確定（新 trial サブスクを無課金取消し、旧を継続へ差し戻し、
     *       {@code old_cancel_scheduled_at} を NULL クリア）。</li>
     *   <li><b>旧の {@code cancel_at_period_end}</b>: DB を信用せず Stripe 実物で確認する。
     *       {@code false} かつ<b>期末境界越えなし</b>ならその場で設定してから切替、
     *       <b>期末境界越えあり</b>なら {@code MANUAL_INTERVENTION} へ倒す
     *       （自動での {@code true} 設定・void・refund は行わない・R5-P1-1 裁定）。</li>
     * </ol>
     */
    public void executeSwitch(UUID handoverRequestId) {

        SwitchContext ctx = handoverTxService.loadSwitchContext(handoverRequestId);
        if (ctx == null) {
            log.debug("柱③-B: 切替対象外のためスキップします handoverRequestId={}", handoverRequestId);
            return;
        }

        // ① 二段検証の2段目（AC-30・最終確定）。
        SubscriptionSnapshot newSnapshot = billingPaymentGateway.retrieveSubscription(ctx.newSubscriptionRef());
        if (newSnapshot != null && newSnapshot.hasPendingSetupIntent()) {
            log.error("柱③-B: 旧期末到達時点でも pending_setup_intent が未解決のため引継を FAILED 確定します"
                            + " handoverRequestId={}, newSubscriptionRef={}",
                    handoverRequestId, ctx.newSubscriptionRef());
            // (a) 新 trial サブスクを無課金取消（trial 中のため即時解約で害はない）。
            billingPaymentGateway.cancelHandoverNewSubscription(ctx.newSubscriptionRef(), handoverRequestId);
            // (b) 旧サブスクを継続へ差し戻し。
            billingPaymentGateway.revertCancelAtPeriodEndForHandover(ctx.oldSubscriptionRef(), handoverRequestId);
            // (c) ★AC-32/R5-P2: 差し戻しと対で old_cancel_scheduled_at を NULL クリアする
            //     （クリア忘れは「予約済み」の誤認となり夜次照合の検出対象から外れる）。
            handoverTxService.markFailedAndClearCancelSchedule(handoverRequestId);
            return;
        }

        // ② §3.6.1(b): DB ではなく Stripe 実物で cancel_at_period_end を確認する。
        SubscriptionSnapshot oldSnapshot = billingPaymentGateway.retrieveSubscription(ctx.oldSubscriptionRef());
        if (oldSnapshot != null && !oldSnapshot.cancelAtPeriodEnd()) {
            Instant currentPeriodStart = oldSnapshot.currentPeriodStart();
            // 期末境界越え判定: 旧サブスクの current_period_start が本来の旧期末以降なら、
            // 予約未設定のまま請求サイクルが更新済み（＝既に次の期間ぶんが走っている）。
            boolean rolledOver = ctx.oldPeriodEnd() != null && currentPeriodStart != null
                    && !currentPeriodStart.isBefore(ctx.oldPeriodEnd());
            if (rolledOver) {
                log.error("柱③-B: 期末境界越えを検知しました（cancel_at_period_end 未設定のまま旧サブスクが更新済み）。"
                                + "自動での true 設定・void・refund は行わず MANUAL_INTERVENTION へ倒します"
                                + " handoverRequestId={}, oldSubscriptionRef={},"
                                + " oldCurrentPeriodStart={}, expectedOldPeriodEnd={}",
                        handoverRequestId, ctx.oldSubscriptionRef(), currentPeriodStart, ctx.oldPeriodEnd());
                handoverTxService.markManualIntervention(handoverRequestId);
                return;
            }
            // 境界越えなし＝まだ旧期間内。直ちに true を設定すれば当初想定どおり期末で終了する。
            log.warn("柱③-B: 旧サブスクの cancel_at_period_end が未設定でした。その場で設定してから切替します"
                    + " handoverRequestId={}, oldSubscriptionRef={}", handoverRequestId, ctx.oldSubscriptionRef());
            billingPaymentGateway.scheduleCancelAtPeriodEndForHandover(ctx.oldSubscriptionRef(), handoverRequestId);
            handoverTxService.persistOldCancelScheduledAt(handoverRequestId, clock.instant());
        }

        // ③ 切替TX（ローカル DB 操作のみ・Stripe 呼び出しを含まない）。
        try {
            handoverTxService.executeSwitchTx(handoverRequestId);
        } catch (RuntimeException e) {
            // Stripe 側は既に確定済み（旧は期末で終わる）。未了なのはローカル切替だけなので
            // PARTIALLY_COMPLETED（非終端）として記録し夜次バッチのリトライへ委ねる。
            // ここで FAILED（終端）にするとリトライ経路から外れ、pointer が旧のまま宙ぶらりんになる。
            log.error("柱③-B: ローカル切替TX が失敗しました。PARTIALLY_COMPLETED として記録しリトライに委ねます"
                    + " handoverRequestId={}", handoverRequestId, e);
            handoverTxService.markPartiallyCompleted(handoverRequestId);
        }
    }

    /**
     * 切替対象（{@code SWITCHING} かつ旧契約の期末に到達済み）の引継要求 ID を返す。
     *
     * <p>{@code @Scheduled} バッチ本体は PR-4 のスコープであり、本メソッドは抽出のみを提供する。</p>
     *
     * @param now 判定基準時刻
     */
    @Transactional(readOnly = true)
    public List<UUID> findSwitchDueHandoverIds(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        // billing_contracts.current_period_end は LocalDateTime のため、同じ Clock の zone で壁時計へ変換して比較する。
        return handoverRequestRepository.findSwitchDueIds(
                PayerHandoverStatus.SWITCHING, LocalDateTime.ofInstant(now, clock.getZone()));
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    /**
     * 引継先候補の ADMIN を列挙する（設計書 §5.5 ①②・AC-10/17/18）。
     *
     * <p><b>越境は Service 経由</b>（{@link RoleService}）で行う。{@code role} ドメインの Repository を
     * 直接 DI するのは {@code CrossDomainRepositoryDependencyArchTest}（D-5）違反である。</p>
     *
     * <p><b>「退会予定」の除外はクエリ側で成立している</b>: {@code RoleService} の候補クエリは
     * いずれも {@code users.deleted_at IS NULL AND users.status = 'ACTIVE'} で絞る。退会申請
     * （{@code UserService#requestWithdrawal} → {@code UserEntity#requestDeletion}）は撤回ウィンドウ中でも
     * {@code deleted_at} を立てるため、退会予定の ADMIN はそもそもこの一覧に現れない。よって
     * 「ADMIN が0人」（分岐①）と「他 ADMIN 全員が退会予定」（分岐②）は同じ空リストとして現れ、
     * どちらも {@code HANDOVER_NO_CANDIDATE} になる。</p>
     *
     * <p>ORG 側の {@code getAdminUserIdsByOrganizationId} は ADMIN に加えて DEPUTY_ADMIN も返すが、
     * {@link BillingOperationAuthorizer#requireCanManage} も課金権限を持つ DEPUTY_ADMIN を承諾者として
     * 許可するため、候補集合として整合している。</p>
     *
     * @param oldPayerUserId 旧 payer 自身は候補から除く（自分へは引き継げない）
     */
    private List<Long> candidateAdminUserIds(
            EntitlementScopeKind scopeKind, Long scopeId, Long oldPayerUserId) {

        List<Long> admins = switch (scopeKind) {
            case TEAM -> roleService.getUserIdsByTeamIdAndRoleName(scopeId, ADMIN_ROLE);
            case ORG -> roleService.getAdminUserIdsByOrganizationId(scopeId);
            case USER -> List.of(); // 呼び出し前に弾いているが switch の網羅のため。
        };
        if (admins == null) {
            return List.of();
        }
        return admins.stream()
                .filter(java.util.Objects::nonNull)
                .filter(userId -> !userId.equals(oldPayerUserId))
                .distinct()
                .toList();
    }

    /**
     * {@code billing_contracts} の壁時計（{@link LocalDateTime}）を {@link Instant} へ変換する。
     *
     * <p>既存 {@code BillingContractService#cancelPaidAtPeriodEnd} の
     * {@code LocalDateTime.ofInstant(instant, clock.getZone())} と<b>対称</b>な逆変換であり、
     * 同じ {@link Clock} の zone を用いるため往復で unix 秒が変わらない（AC-5 の一致の根拠）。</p>
     */
    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(clock.getZone()).toInstant();
    }
}
