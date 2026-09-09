package com.mannschaft.app.billing;

import com.mannschaft.app.billing.BillingPaymentGateway.CheckoutSessionInfo;
import com.mannschaft.app.billing.BillingPaymentGateway.SubscriptionSnapshot;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.AcceptTransition;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.CheckoutCompletion;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.ReconcileTarget;
import com.mannschaft.app.billing.BillingPayerHandoverTxService.SwitchContext;
import com.mannschaft.app.auth.service.WithdrawalStateQueryService;
import com.mannschaft.app.common.BusinessException;
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

    /**
     * 切替バッチ・夜次照合バッチの抽出対象状態（PR-4）。
     *
     * <p>{@code MANUAL_INTERVENTION} は<b>含めない</b>。運用者の {@code RESUME} 待ちであり、
     * 機械的に切替や Stripe 設定を再試行してはならない状態だからである（設計書 §3.6.2）。</p>
     */
    static final List<PayerHandoverStatus> SWITCH_TARGET_STATUSES =
            List.of(PayerHandoverStatus.SWITCHING, PayerHandoverStatus.PARTIALLY_COMPLETED);

    /**
     * 夜次照合が同じ失敗を繰り返しても解消しないとき、恒久失敗と判断して
     * {@code MANUAL_INTERVENTION} へ倒すまでの猶予（設計書 §3.6.2 入口3・PR-4 Codex検分1巡目 P1-3）。
     *
     * <p><b>なぜ時間で測るか</b>: 試行回数を持つには列の追加（＝マイグレーション）が要るが、
     * 恒久失敗の判定に必要なのは「何回叩いたか」ではなく<b>「いつまで解消しないか」</b>である。
     * 承諾確定（{@code accepted_at}）から本猶予を過ぎてなお旧サブスクの期末解約予約を
     * 確認できない行は、毎晩の再試行では解消しないものとして人手へ渡す。
     * これは<b>単一の引継要求行の経過時間</b>であり、退会世代の推測ではない。</p>
     */
    static final Duration CANCEL_SCHEDULE_ESCALATION = Duration.ofDays(3);

    /**
     * {@code SWITCHING} のまま {@code pending_setup_intent} が解決しない場合に
     * {@code FAILED} を確定させるまでの猶予（設計書 §5.5 ④・AC-20・PR-4 Codex検分1巡目 P1-6）。
     *
     * <p>設計書は「一定時間（既定24時間）認証未完了なら {@code FAILED} とし、他の ADMIN へ
     * 再度通知を送る」と定める。旧期末到達まで待つと、そのとき旧サブスクは既に終了しており
     * <b>差し戻しても継続を復旧できない</b>ため、期末より前に決着させる必要がある。</p>
     */
    static final Duration PENDING_SETUP_INTENT_DEADLINE = Duration.ofHours(24);

    /**
     * 旧期末の手前でこれ以上待たずに滞留を検査する余裕（PR-4 Codex検分2巡目 P1-6）。
     *
     * <p>旧期末を過ぎると {@code cancel_at_period_end=false} を送っても<b>終了済みサブスクは
     * 復旧できない</b>ため、差し戻しが有効なうちに決着させる必要がある。引継要求の作成時にも
     * 同じ余裕を要件化している（{@link #requestHandover}）。</p>
     */
    static final Duration PERIOD_END_SAFETY_MARGIN = Duration.ofHours(6);

    /** 複合カーソルの初回値（全ての UUID より小さい）。 */
    private static final UUID MIN_UUID = new UUID(0L, 0L);

    /**
     * 猶予期限到来で {@code EXPIRED} へ倒せる状態（設計書 §4.2 遷移表の「{@code EXPIRED} の遷移元」）。
     *
     * <p>いずれも Stripe には一切触れていない段階のため、照合なしで安全に終端化できる。</p>
     */
    static final List<PayerHandoverStatus> EXPIRABLE_STATUSES =
            List.of(PayerHandoverStatus.REQUESTED, PayerHandoverStatus.REQUIRES_PAYMENT_METHOD);

    private final BillingPayerHandoverRequestRepository handoverRequestRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingOperationAuthorizer billingOperationAuthorizer;
    private final BillingPaymentGateway billingPaymentGateway;
    private final BillingPayerHandoverTxService handoverTxService;
    private final BillingPayerHandoverCandidateResolver candidateResolver;
    /** 退会申請の現在状態（auth ドメインへは Service 経由でのみ触れる・検分2巡目 P1-1）。 */
    private final WithdrawalStateQueryService withdrawalStateQueryService;
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

        return createHandoverRequest(contract, scopeKind, scopeId, operatorUserId);
    }

    /**
     * 引継要求作成の共通本体（対話 API・退会イベントの両経路が通る）。
     *
     * <p>認可だけが2経路で異なる（対話 API は {@code requireCanManage} を先に通す／退会経路は
     * 「契約の payer が退会者本人であること」だけを見る）。それ以外の前提検証・候補解決・
     * 進行中要求の排他・通知 publish はここに一元化する。</p>
     *
     * @param scopeKind 呼び出し側が期待するスコープ種別（対話 API では URL 由来・退会経路では契約行由来）
     * @param scopeId   同上
     * @param operatorUserId 旧 payer 本人であるべきユーザー ID
     */
    private HandoverRequestResult createHandoverRequest(BillingContractEntity contract,
            EntitlementScopeKind scopeKind, Long scopeId, Long operatorUserId) {

        UUID oldContractId = contract.getId();

        // USER スコープは契約者本人以外に payer が存在し得ず、引継の概念自体が無い（設計書 §4.2）。
        if (scopeKind == null || scopeKind == EntitlementScopeKind.USER) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_SCOPE_NOT_SUPPORTED);
        }
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

        // ★旧期末までの猶予を要件化する（PR-4 Codex検分2巡目 P1-6）。
        //   期末の直前（例えば1時間前）でも要求・承諾できてしまうと、追加認証の期限処理
        //   （承諾+24時間）より先に旧サブスクが期末終了する。終了後に cancel_at_period_end=false を
        //   送っても【終了済みサブスクは復旧できない】ため、「期末より前に FAILED として差し戻す」
        //   という安全条件が原理的に成立しない。安全に決着させられる時間が残っている契約だけ受け付ける。
        if (oldPeriodEnd.isBefore(now.plus(PENDING_SETUP_INTENT_DEADLINE).plus(PERIOD_END_SAFETY_MARGIN))) {
            log.info("柱③-B: 旧期末までの猶予が不足しているため引継要求を受け付けません"
                            + " contractId={}, oldPeriodEnd={}, 必要猶予={}+{}",
                    oldContractId, oldPeriodEnd, PENDING_SETUP_INTENT_DEADLINE, PERIOD_END_SAFETY_MARGIN);
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // 旧 payer は payer_user_id を正とし、legacy 行（V203 バックフィル前）のみ created_by へ倒す。
        Long oldPayerUserId = contract.getPayerUserId() != null
                ? contract.getPayerUserId() : contract.getCreatedBy();
        if (oldPayerUserId == null) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // ⑤-2 申請者は旧 payer 本人に限る（設計書 §3「1段目: 旧 payer による引継申請」・Codex検分1巡目 P1-1）。
        //     requireCanManage はスコープの管理権限しか見ないため、これだけでは同一スコープの別 ADMIN が
        //     他人の支払契約について勝手に引継を申請できてしまう（申請は他 ADMIN 全員への通知を発火させ、
        //     uk_bphr_open_old_contract により当該契約への以後の申請を猶予期間中ブロックもする）。
        //     契約の存在自体は認可済みスコープ内で既に見えているため、404 で畳まず 403 を返す。
        if (!oldPayerUserId.equals(operatorUserId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_OLD_PAYER);
        }

        // ⑥ AC-10/17/18: 引継先候補が居なければ要求を作らない。
        List<Long> candidates = candidateResolver.candidateAdminUserIds(scopeKind, scopeId, oldPayerUserId);
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
    // 1段目（内部経路）: 退会受付イベントによる引継申請
    // ============================================================

    /**
     * 退会受付（{@code WithdrawalRequestedEvent}）から引継要求を作成する内部入口
     * （設計書 §5.1・Codex 検分1巡目 P0 の是正）。
     *
     * <h2>なぜ {@link #requestHandover} を使ってはいけないのか</h2>
     * <p>{@link #requestHandover} は対話 API 用であり {@code billingOperationAuthorizer.requireCanManage}
     * を通る。その認可 SQL は {@code users.deleted_at IS NULL AND status = 'ACTIVE'} を必須条件にしている。
     * ところが {@code UserService#requestWithdrawal} は<b>先に {@code deleted_at} を立てて commit し</b>、
     * その後 {@code AFTER_COMMIT} で本処理を呼ぶ。したがって退会者は<b>必ず全件で認可に失敗</b>し、
     * 引継要求は1件も作られない。現行 purge は USER スコープ契約しか処理しないため、
     * TEAM/ORG の旧 payer への課金がそのまま継続していた。</p>
     *
     * <h2>ではこの経路の安全性は何が担保するのか</h2>
     * <p>「現在もそのスコープの管理者か」ではなく、<b>「その契約の払い手が、いま退会したその人自身か」</b>
     * を同一トランザクション内で行ロック後に検証する。呼び出し側は契約 ID しか渡せず、
     * <b>スコープは契約行から読み出す</b>ため、任意ユーザー・任意スコープを渡して越境することはできない。
     * 退会者は自分が払い手である契約の引継しか起こせない。</p>
     *
     * @param oldContractId            引継元契約 ID
     * @param withdrawingPayerUserId   退会を申請した払い手のユーザー ID
     * @return 作成した引継要求
     * @throws BusinessException {@code HANDOVER_NOT_FOUND}（不存在）/
     *                           {@code HANDOVER_SCOPE_NOT_SUPPORTED}（USER スコープ）/
     *                           {@code HANDOVER_NOT_OLD_PAYER}（契約の payer が退会者ではない）/
     *                           {@code HANDOVER_CONTRACT_NOT_ELIGIBLE} /
     *                           {@code HANDOVER_NO_CANDIDATE} / {@code HANDOVER_ALREADY_IN_PROGRESS}
     */
    @Transactional
    public HandoverRequestResult requestHandoverForWithdrawal(UUID oldContractId, Long withdrawingPayerUserId) {
        if (withdrawingPayerUserId == null) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND);
        }
        // 行ロックを取ってから読む。承諾（acceptHandover）や別経路の更新と競合しても、
        // payer の判定と要求作成が同じスナップショットの上で行われることを保証する。
        BillingContractEntity contract = billingContractRepository.findByIdForUpdate(oldContractId)
                .orElseThrow(() -> new BusinessException(EntitlementErrorCode.HANDOVER_NOT_FOUND));

        // ① 認可を最初に通す（Codex 検分4巡目 B）。
        //    「この契約の払い手が、渡された本人か」は本経路における唯一の認可根拠であり、
        //    契約の適格性（PSP 紐付け・期末・PAST_DUE）や退会状態より【先に】判定しなければならない。
        //    後回しにすると、越境入力が payer 検証に到達する前に別の前提検証で弾かれ、
        //    「越境を拒否できている」ことを誰も確認できなくなる（fail closed の順序）。
        Long contractPayerUserId = contract.getPayerUserId() != null
                ? contract.getPayerUserId() : contract.getCreatedBy();
        if (contractPayerUserId == null || !contractPayerUserId.equals(withdrawingPayerUserId)) {
            throw new BusinessException(EntitlementErrorCode.HANDOVER_NOT_OLD_PAYER);
        }

        // ② 「いま本当に退会申請中か」を処理時点の DB 真値で確かめる（Codex 検分2巡目 P1-1）。
        //    退会と退会取消はどちらも共用 event-pool 上の非同期処理で到達順が保証されない。退会受付の
        //    直後に取り消すと、取消処理が先に走って対象ゼロで終わり、そのあとに届いた古い退会イベントが
        //    REQUESTED の引継要求を作ってしまう。この防御は本メソッドが唯一の入口であるためここに置く。
        // 【ロック版必須】非ロック読み取りだと「退会中を読む → 取消が commit → 取消イベントが対象ゼロで
        //   終了 → 古い処理が REQUESTED を作成」が成立する（Codex 検分5巡目 P1-1）。
        if (withdrawalStateQueryService.lockAndFindPendingWithdrawalAttempt(contractPayerUserId)
                .isEmpty()) {
            log.info("柱③-B: 処理時点で退会申請中ではないため引継要求を作成しません userId={}, contractId={}",
                    withdrawingPayerUserId, oldContractId);
            throw new BusinessException(EntitlementErrorCode.HANDOVER_CONTRACT_NOT_ELIGIBLE);
        }

        // スコープは呼び出し側からではなく【契約行から】読む（越境入力の余地を構造的に無くす）。
        return createHandoverRequest(contract, contract.getScopeKind(), contract.getScopeId(),
                withdrawingPayerUserId);
    }

    /**
     * 退会取消（{@code WithdrawalCancelledEvent}）に伴い、退会者が旧 payer である {@code REQUESTED} の
     * 引継要求を {@code FAILED} へ終端化する（設計書 §4.2 遷移表・Codex 検分1巡目 P1-3）。
     *
     * <p>本メソッド自身はトランザクションを開始しない。1件の失敗が他の要求の終端化を巻き添えに
     * しないよう、要求ごとに {@link BillingPayerHandoverTxService#failRequestedOnWithdrawalCancelled}
     * の独立トランザクションで確定させる。</p>
     *
     * @return 終端化できた要求の件数
     */
    public int failRequestedHandoversOnWithdrawalCancelled(Long oldPayerUserId) {
        if (oldPayerUserId == null) {
            return 0;
        }
        List<UUID> targetIds = handoverRequestRepository
                .findByOldPayerUserIdAndStatus(oldPayerUserId, PayerHandoverStatus.REQUESTED)
                .stream()
                .map(BillingPayerHandoverRequestEntity::getId)
                .toList();
        int failed = 0;
        for (UUID handoverRequestId : targetIds) {
            try {
                if (handoverTxService.failRequestedOnWithdrawalCancelled(handoverRequestId, oldPayerUserId)) {
                    failed++;
                }
            } catch (Exception e) {
                log.error("柱③-B: 退会取消に伴う引継要求の終端化に失敗しました handoverRequestId={}, oldPayerUserId={}",
                        handoverRequestId, oldPayerUserId, e);
            }
        }
        log.info("柱③-B: 退会取消に伴い引継要求を終端化しました oldPayerUserId={}, 対象={}, 終端化={}",
                oldPayerUserId, targetIds.size(), failed);
        return failed;
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
            //
            // ★この照会が失敗したときに REQUESTED へ巻き戻してはならない（Codex検分3巡目 P1）。
            //   照会は §3.2（R3-P0）の定めどおり customer={新 Customer} に絞って走査する。つまり
            //   「A の Customer にサブスクが在るか」が不明なまま巻き戻すと、次に別 ADMIN B が承諾した際の
            //   回復照会は B の Customer しか見ないため A のサブスクを発見できず、二重サブスク＝二重課金の
            //   窓が開く（冪等キーもパラメータが変わるため効かない）。
            //   よって Stripe 上の作成有無が曖昧な間は承諾者を A に固定したまま ACCEPTED に留め、
            //   A 本人の再試行でのみ同じ Customer を照会させて回収する。
            //   （A が戻らなければ猶予期限で EXPIRED となり §5.3 の purge fallback に渡る。
            //     これは設計書が「誰も承諾しなかった場合」に定める既存の挙動そのものであり、
            //     新しい状態も新しい遷移も増やさない。）
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

        // ここまで来た時点で「DB にも Stripe にも新サブスクは存在しない」ことを<b>照会成功をもって確認済み</b>。
        // したがって Checkout 作成に失敗した場合に限り、Stripe 上に何も残っていないことが確定しているので
        // 安全に REQUESTED へ巻き戻せる（別 ADMIN が承諾しても回収漏れが起きない）。
        // 旧契約にも一切触れていない（cancel_at_period_end は checkout.session.completed と同時のみ・R3-P1-3）。
        // 例外は握りつぶさず必ず再送出する（呼び出し元・監視に失敗を正しく伝える）。
        CheckoutSessionInfo info;
        try {
            info = billingPaymentGateway.createHandoverSubscriptionCheckout(
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
        } catch (RuntimeException ex) {
            // 自分自身が実行した承諾試行の巻き戻しであり、対象は今作った新契約に限る。
            handoverTxService.rollbackAcceptanceToRequested(
                    handoverRequestId, accepted.newContractId(),
                    "Checkout Session の作成に失敗: " + ex.getClass().getSimpleName());
            throw ex;
        }

        return new HandoverAcceptResult(handoverRequestId, PayerHandoverStatus.ACCEPTED,
                accepted.newContractId(), info.url());
    }

    /**
     * Checkout の放棄（{@code checkout.session.expired}）を受けて承諾を巻き戻す
     * （設計書 §3.6 遷移表・Codex検分1巡目 P1-3）。
     *
     * <p>通常契約の {@code checkout.session.expired} は
     * {@code BillingContractService#abandonPendingContract} が {@code PENDING} 契約を破棄して再挑戦可能にするが、
     * 同メソッドは <b>{@code PENDING} 以外を no-op</b> とするため引継の {@code PENDING_HANDOVER} 契約には効かず、
     * 引継だけが {@code ACCEPTED} のまま取り残されて再承諾できなくなっていた。さらに同メソッドは
     * スロット単位で pointer を物理 DELETE するため、仮に適用すると<b>旧契約の entitlement を巻き添えで剥がす</b>。
     * よって引継専用のこの経路で巻き戻す。</p>
     *
     * <p><b>冪等・webhook 逆順に安全</b>: {@code ACCEPTED} 以外は no-op のため、{@code completed} が先に
     * 処理されて {@code SWITCHING} へ進んだ後に {@code expired} が遅れて届いても引継を壊さない。</p>
     *
     * <p><b>過去の試行の遅着にも安全</b>: 承諾 A が失敗して巻き戻り、別 ADMIN の承諾 B が成立した後に
     * A の Checkout の {@code expired} が届くことがある。イベント元の契約 ID を渡して要求行の現在の
     * {@code new_contract_id} と照合させ、不一致なら巻き戻さない（Codex検分2巡目 P1-3）。</p>
     *
     * @param newContractId webhook の metadata が指す引継先契約 ID（＝どの承諾試行のイベントか）
     */
    public void onHandoverCheckoutExpired(UUID handoverRequestId, UUID newContractId) {
        handoverTxService.rollbackAcceptanceToRequested(
                handoverRequestId, newContractId, "checkout.session.expired（利用者が Checkout を放棄）");
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
            // ★孤児サブスクの補償（設計書 §3.6 遷移表・Codex検分5巡目 P1）。
            //   期限超過の照合は「Stripe に不在」を確認してから EXPIRED へ終端化するが、その照会と
            //   終端化の間に利用者が Checkout を完了させると、Stripe 上に新サブスクが在るのに要求は
            //   EXPIRED という状態になる。ここで単に no-op を返すと、DB に記録されず解約もされない
            //   サブスクが課金だけ続ける。DB 側の直列化では Stripe 側イベントの遅着を原理的に防げないため、
            //   「起きてしまった後に金銭を守る」補償を最終防衛線として置く。
            //   trial 中のため即時取消で課金は発生しない（§3.6 2段目の失敗経路と同じ扱い）。
            String orphanRef =
                    handoverTxService.detectOrphanNewSubscription(handoverRequestId, newSubscriptionRef);
            if (orphanRef != null) {
                log.error("柱③-B: 終端化(EXPIRED)後に遅着した引継確定 webhook が新サブスクを連れてきました。"
                        + "孤児として課金され続けるのを防ぐため即時取消します"
                        + " handoverRequestId={}, subscriptionRef={}", handoverRequestId, orphanRef);
                billingPaymentGateway.cancelHandoverNewSubscription(orphanRef, handoverRequestId);
            }
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
            // ★【CAS で権利を取ってから Stripe を変更する】（PR-4 Codex検分3巡目 P1-1）
            //   是正前は Stripe を先に叩いてから CAS を呼んでいた。CAS 自体の期待元状態検証は
            //   正しくても、外部副作用より後に呼ぶ以上 fencing になっていない——Stripe 照会中に
            //   別 worker が COMPLETED へ進めていても、新サブスクは既に取り消されている。
            if (!handoverTxService.markFailedPendingCleanup(
                    handoverRequestId, SWITCH_TARGET_STATUSES)) {
                log.warn("柱③-B: 他の処理が先に状態を進めたため Stripe には触れません handoverRequestId={}",
                        handoverRequestId);
                return;
            }
            cleanupAfterFailure(handoverRequestId, ctx.oldSubscriptionRef(), ctx.newSubscriptionRef());
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
     * 切替対象（{@code SWITCHING}／{@code PARTIALLY_COMPLETED} かつ旧契約の期末に到達済み）の
     * 引継要求 ID を返す。
     *
     * <p>駆動は {@link BillingPayerHandoverBatchService}（PR-4 で結線）。
     * {@code PARTIALLY_COMPLETED}（ローカル切替TXのみ未了・非終端）も同じ抽出に含めるのは、
     * この状態がリトライされないと pointer が旧のまま宙ぶらりんで残るためである（§3.5）。</p>
     *
     * @param now 判定基準時刻
     */
    @Transactional(readOnly = true)
    public List<UUID> findSwitchDueHandoverIds(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        // billing_contracts.current_period_end は LocalDateTime のため、同じ Clock の zone で壁時計へ変換して比較する。
        return handoverRequestRepository.findSwitchDueIds(
                SWITCH_TARGET_STATUSES, LocalDateTime.ofInstant(now, clock.getZone()));
    }

    /**
     * 旧サブスクへの {@code cancel_at_period_end=true} 設定が確認できていない引継要求 ID を返す
     * （設計書 §3.6.1(a)・AC-34）。
     */
    @Transactional(readOnly = true)
    public List<UUID> findOldCancelScheduleUnconfirmedIds() {
        return handoverRequestRepository.findOldCancelScheduleUnconfirmedIds(SWITCH_TARGET_STATUSES);
    }

    /**
     * 猶予期限を過ぎたまま誰にも承諾されていない引継要求 ID を返す（設計書 §5.3・AC-21）。
     *
     * <p>従来、期限切れの判定は<b>承諾操作が来たときにしか行われなかった</b>。AC-21 が定めるのは
     * まさに「全 ADMIN が承諾を無視した」ケースであり、承諾操作は永久に来ない。放置すると
     * 非終端のまま purge のフォールバックを止め続け、旧 payer への課金が止まらない。</p>
     */
    @Transactional(readOnly = true)
    public List<UUID> findOverdueUnacceptedIds(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return handoverRequestRepository.findOverdueUnacceptedIds(EXPIRABLE_STATUSES, now);
    }

    /**
     * 猶予期限を過ぎた未承諾の引継要求を {@code EXPIRED} で終端化する（設計書 §5.3・AC-21）。
     *
     * <p>Stripe に一切触れていない行のみが対象のため照合は不要（{@link #reconcileExpiredAcceptance}
     * との分界。あちらは新サブスクが実在しうるため必ず Stripe 照合を伴う）。</p>
     *
     * @return 実際に終端化したなら {@code true}
     */
    public boolean expireOverdueUnaccepted(UUID handoverRequestId) {
        return handoverTxService.expireOverdueUnaccepted(handoverRequestId, clock.instant());
    }

    /**
     * 夜次照合: 旧サブスクの {@code cancel_at_period_end} を<b>Stripe 実物と突合して</b>整合を回復する
     * （設計書 §3.6.1(a) 第一防衛・AC-34）。
     *
     * <p><b>DB の NULL を「未設定」と決めつけてはならない。</b> 設定 API の成功と
     * {@code old_cancel_scheduled_at} の永続化は別操作であり原子性が無いため、NULL には
     * 「本当に未設定」と「設定済みだが DB 書き込みだけ落ちた」の2通りがある。前者だけを想定して
     * 無条件に再設定する実装は、後者で無駄な Stripe 呼び出しを毎晩繰り返すことになる。
     * よって必ず Stripe 実物を引いてから分岐する:</p>
     * <ul>
     *   <li>実物が {@code true} → 再設定は不要。{@code old_cancel_scheduled_at} だけ埋めて整合を回復する</li>
     *   <li>実物が {@code false} → 設定 API を再実行する（{@code cancelAtPeriodEnd} は冪等）</li>
     *   <li>実物を引けなかった → <b>DB を書き換えない</b>。曖昧なまま「整合済み」と記録すると、
     *       以後この行は抽出対象から外れて二度と照合されなくなる</li>
     * </ul>
     */
    public void reconcileOldCancelSchedule(UUID handoverRequestId) {
        BillingPayerHandoverTxService.CancelScheduleTarget target =
                handoverTxService.loadCancelScheduleTarget(handoverRequestId);
        if (target == null) {
            // 既に確認済み・終端化済み・旧サブスク参照なし。Stripe を叩かない。
            return;
        }

        SubscriptionSnapshot snapshot = billingPaymentGateway.retrieveSubscription(target.oldSubscriptionRef());
        if (snapshot == null) {
            log.error("柱③-B 夜次照合: 旧サブスクを Stripe から取得できず cancel_at_period_end を確認できません"
                            + "（次回の照合で再試行）handoverRequestId={}, oldSubscriptionRef={}",
                    handoverRequestId, target.oldSubscriptionRef());
            return;
        }

        if (!snapshot.cancelAtPeriodEnd()) {
            // 本当に未設定だった。ここを埋めないと旧サブスクが通常課金を続ける（二重課金の穴）。
            log.warn("柱③-B 夜次照合: 旧サブスクの cancel_at_period_end が未設定でした。再設定します"
                    + " handoverRequestId={}, oldSubscriptionRef={}",
                    handoverRequestId, target.oldSubscriptionRef());
            try {
                billingPaymentGateway.scheduleCancelAtPeriodEndForHandover(
                        target.oldSubscriptionRef(), handoverRequestId);
            } catch (RuntimeException e) {
                // ★恒久失敗の受け皿（PR-4 Codex検分1巡目 P1-3・設計書 §3.6.2 入口3）。
                //   是正前はここで例外がバッチ外周へ抜け、ログだけ残して状態も試行管理も
                //   変わらなかった。恒久的な Stripe 4xx や参照不整合は毎晩同じ失敗を繰り返し、
                //   行は SWITCHING のまま永久に残る。RESUME は MANUAL_INTERVENTION 専用なので
                //   運用者にも決着させる手段が無い（＝旧 payer への課金が止まらない）。
                if (isPermanentlyFailing(target, e)) {
                    log.error("柱③-B 夜次照合: cancel_at_period_end の設定が恒久的に解消しないため"
                                    + " MANUAL_INTERVENTION へ倒します handoverRequestId={}, 猶予={}",
                            handoverRequestId, CANCEL_SCHEDULE_ESCALATION, e);
                    handoverTxService.markManualIntervention(handoverRequestId);
                    return;
                }
                // 一時的失敗と判断した場合のみ、次回の照合へ委ねる（DB は書き換えない）。
                throw e;
            }
        } else {
            log.warn("柱③-B 夜次照合: Stripe 側は予約済みで DB 記録だけが欠けていました。DB を回復します"
                    + " handoverRequestId={}, oldSubscriptionRef={}",
                    handoverRequestId, target.oldSubscriptionRef());
        }
        handoverTxService.persistOldCancelScheduledAt(handoverRequestId, clock.instant());
    }

    /**
     * 失敗が<b>恒久的か一時的か</b>を Stripe の応答から分類する
     * （設計書 §3.6.2 入口3・PR-4 Codex検分2巡目 P1-3）。
     *
     * <h2>なぜ経過時間だけでは誤るのか</h2>
     * <p>是正前は {@code accepted_at + 3日} という経過時間だけで恒久失敗を決めていた。これでは
     * <b>承諾から3日後に初めて起きた timeout や Stripe 5xx も即座に人手へ送られ</b>（自動再試行から
     * 外れる）、逆に<b>恒久的な 4xx でも3日間は毎晩同じ失敗を繰り返す</b>。時間は分類の根拠ではなく、
     * 分類が付かない場合の<b>上限</b>にすぎない。</p>
     *
     * <h2>分類</h2>
     * <ul>
     *   <li><b>恒久</b>: Stripe が 4xx を返した（{@code 429} を除く）。リクエスト内容そのものが
     *       受理されない状態であり、同じ内容を再送しても結果は変わらない（対象サブスクが存在しない・
     *       権限が無い・パラメータ不正等）。人手が要る。</li>
     *   <li><b>一時</b>: {@code 429}（レート制限）・5xx・接続断・分類材料が無い場合。再試行で回復しうる。</li>
     * </ul>
     *
     * <p>分類できない一時失敗が延々と続く可能性は残るため、{@link #CANCEL_SCHEDULE_ESCALATION} を
     * <b>上限</b>として併用する（「毎晩繰り返しても解消しない」も恒久性の証拠ではあるため）。</p>
     */
    private boolean isPermanentlyFailing(
            BillingPayerHandoverTxService.CancelScheduleTarget target, RuntimeException cause) {

        Integer statusCode = stripeStatusCodeOf(cause);
        if (statusCode != null) {
            boolean permanent = statusCode >= 400 && statusCode < 500 && statusCode != 429;
            log.warn("柱③-B 夜次照合: Stripe 応答から失敗を分類しました handoverRequestId={},"
                            + " statusCode={}, 判定={}",
                    target.handoverRequestId(), statusCode, permanent ? "恒久" : "一時");
            if (permanent) {
                return true;
            }
            // 一時失敗でも、上限を過ぎていれば人手へ渡す（下の時間上限へ落ちる）。
        }

        Instant acceptedAt = target.acceptedAt();
        if (acceptedAt == null) {
            log.warn("柱③-B 夜次照合: 分類材料も accepted_at も無いため恒久失敗の判定を保留します"
                    + " handoverRequestId={}", target.handoverRequestId(), cause);
            return false;
        }
        boolean overDeadline = !clock.instant().isBefore(acceptedAt.plus(CANCEL_SCHEDULE_ESCALATION));
        if (overDeadline) {
            log.error("柱③-B 夜次照合: 一時失敗が上限（{}）を超えて解消しないため恒久として扱います"
                    + " handoverRequestId={}", CANCEL_SCHEDULE_ESCALATION, target.handoverRequestId());
        }
        return overDeadline;
    }

    /**
     * 例外の cause 連鎖から Stripe の HTTP ステータスを取り出す（見つからなければ {@code null}）。
     *
     * <p>{@code StripePaymentProviderImpl} は {@code StripeException} を
     * {@code BusinessException(STRIPE_API_ERROR, e)} で包んで投げる（PR-4 で cause を保持するよう是正した）。
     * ここで cause を辿るのは、<b>握り潰された例外からは分類ができない</b>ためである。</p>
     */
    private Integer stripeStatusCodeOf(Throwable cause) {
        for (Throwable t = cause; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof com.stripe.exception.StripeException stripeException) {
                return stripeException.getStatusCode();
            }
        }
        return null;
    }

    // ============================================================
    // SWITCHING 滞留の監視と追加認証の期限処理（設計書 §5.5 ④・AC-20・P1-6）
    // ============================================================

    /**
     * 滞留抽出の1ページ（キーセット送り用に「最後に見た {@code (acceptedAt, id)}」を伴う）。
     *
     * <p><b>複合カーソルである理由</b>（3巡目 P1-3）: {@code acceptedAt} 単独では、
     * 同一 {@code acceptedAt} の行がページサイズを超えたときに、1ページ目に入らなかった
     * 同時刻行が次ページの {@code >} 条件から<b>全て脱落する</b>。承諾は同時操作で
     * 同一秒に固まりうるため、{@code id} まで含めて厳密に前へ進む。</p>
     */
    public record StalledSwitchingPage(
            List<UUID> handoverRequestIds, Instant lastAcceptedAt, UUID lastId) {
    }

    /**
     * 滞留している {@code SWITCHING} を<b>キーセット送りで</b>1ページぶん返す
     * （PR-4 Codex検分2巡目 P1-5/P1-6）。
     *
     * <h2>抽出条件が2本ある理由（P1-6）</h2>
     * <ul>
     *   <li>承諾から {@link #PENDING_SETUP_INTENT_DEADLINE}（24時間）経過</li>
     *   <li><b>または</b>旧期末が {@link #PERIOD_END_SAFETY_MARGIN} 以内に迫っている</li>
     * </ul>
     * <p>後者が無いと、旧期末まで24時間未満の契約では<b>監視より先に旧サブスクが期末終了し</b>、
     * そのあと {@code cancel_at_period_end=false} を送っても終了済みサブスクは復旧できない。
     * 「期末より前に FAILED として差し戻す」という本処理の安全条件が成立しなくなる。</p>
     *
     * <h2>キーセットで進む理由（P1-5）</h2>
     * <p>この抽出は<b>処理しても状態が変わらない行</b>（認証完了済みで旧期末を待つ正常な行）を返しうる。
     * 固定の先頭N件で切ると、その種の古い行がN件あるだけで後続の認証未解決行が永久に検査されない。
     * よって {@code acceptedAt} を carry して次ページへ進む。</p>
     *
     * @param afterAcceptedAt 直前ページの最後の {@code acceptedAt}（初回は {@link Instant#EPOCH}）
     */
    @Transactional(readOnly = true)
    public StalledSwitchingPage findStalledSwitchingPage(
            Instant afterAcceptedAt, UUID afterId, int pageSize) {
        Instant now = clock.instant();
        List<Object[]> rows = handoverRequestRepository.findStalledSwitchingPage(
                PayerHandoverStatus.SWITCHING,
                now.minus(PENDING_SETUP_INTENT_DEADLINE),
                LocalDateTime.ofInstant(now.plus(PERIOD_END_SAFETY_MARGIN), clock.getZone()),
                afterAcceptedAt == null ? Instant.EPOCH : afterAcceptedAt,
                afterId == null ? MIN_UUID : afterId,
                org.springframework.data.domain.PageRequest.of(0, pageSize));
        List<UUID> ids = rows.stream().map(r -> (UUID) r[0]).toList();
        if (rows.isEmpty()) {
            return new StalledSwitchingPage(ids, null, null);
        }
        Object[] last = rows.get(rows.size() - 1);
        return new StalledSwitchingPage(ids, (Instant) last[1], (UUID) last[0]);
    }

    /**
     * {@code SWITCHING} 滞留を Stripe 実物と突合し、追加認証が期限内に完了していなければ
     * {@code FAILED} を確定して他 ADMIN へ再通知する（設計書 §5.5 ④・AC-20）。
     *
     * <p><b>DB の滞留だけで終端化してはならない</b>。滞留は「認証が終わっていない」とは限らず、
     * 単に旧期末が遠いだけのこともある。よって必ず Stripe の {@code pending_setup_intent} を
     * 再検証し、<b>未解決のときだけ</b>終端化する。解決済みなら滞留として記録し、
     * 通常どおり旧期末到達時の切替バッチに委ねる。</p>
     *
     * <p>終端化するときは §3.6 の FAILED 経路と同じ後始末を伴う: 新 trial サブスクを無課金取消し、
     * 旧サブスクの {@code cancel_at_period_end} を差し戻し、{@code old_cancel_scheduled_at} を
     * NULL クリアする。この時点なら<b>旧期末はまだ来ていないため差し戻しが有効</b>である。</p>
     *
     * @return 終端化したなら {@code true}
     */
    public boolean reconcileStalledSwitching(UUID handoverRequestId) {
        BillingPayerHandoverTxService.StalledSwitchingTarget target =
                handoverTxService.loadStalledSwitchingTarget(handoverRequestId);
        if (target == null) {
            return false;
        }

        SubscriptionSnapshot newSnapshot =
                billingPaymentGateway.retrieveSubscription(target.newSubscriptionRef());
        if (newSnapshot == null) {
            // 実物を引けない間は判断材料が無い。状態は変えず次回の照合に委ねる。
            log.error("柱③-B 滞留監視: 新サブスクを Stripe から取得できません handoverRequestId={}",
                    handoverRequestId);
            return false;
        }
        if (!newSnapshot.hasPendingSetupIntent()) {
            // ★認証は完了している＝この行は【正常な旧期末待ち】であり、以後この照合で見る必要が無い。
            //   記録して抽出対象から外す（3巡目 P1-3）。外さないと、状態が変わらないこの種の行が
            //   毎晩の抽出に残り続け、1回の実行件数の上限を埋めて
            //   【後続の認証未解決行を永久に飢餓させる】（カーソルは実行のたびに初期化されるため、
            //   上限に達する限りその先へは決して到達しない）。
            handoverTxService.markSetupIntentVerified(handoverRequestId);
            log.info("柱③-B 滞留監視: 追加認証は完了済みのため以後の抽出から外します（旧期末待ち）"
                    + " handoverRequestId={}", handoverRequestId);
            return false;
        }

        log.error("柱③-B 滞留監視: pending_setup_intent が未解決のため FAILED を確定します"
                + " handoverRequestId={}", handoverRequestId);

        // ★【CAS で権利を取ってから Stripe を変更する】（PR-4 Codex検分2巡目 P1-1）
        //   是正前は Stripe を先に変更してから CAS を呼んでいた。Stripe 照会中に別 worker が
        //   COMPLETED まで進めていると、CAS は正しく拒否するのに<b>Stripe 側は新サブスク取消済み・
        //   旧サブスク継続</b>という状態が残る——pointer が指す新契約と Stripe 実物が矛盾し、
        //   利用者は entitlement を持ったまま課金対象のサブスクを失う。
        //   終端化の権利を先に取り、取れなければ Stripe には一切触れない。
        if (!handoverTxService.failStalledSwitchingAndRenotify(handoverRequestId)) {
            log.warn("柱③-B 滞留監視: 他の処理が先に状態を進めたため Stripe には触れません"
                    + " handoverRequestId={}", handoverRequestId);
            return false;
        }

        // 権利を取った後にだけ Stripe を変更する。後始末が落ちても
        // old_cancel_scheduled_at が残るため、夜次バッチが必ず回収する（3巡目 P1-2）。
        cleanupAfterFailure(handoverRequestId, target.oldSubscriptionRef(), target.newSubscriptionRef());

        // ★再要求の作成と再通知は【後始末が終わってから】（3巡目 P1-2）。
        //   先に作ると、後始末が落ちた場合に「旧試行のサブスクが Stripe に残ったまま、
        //   別 ADMIN が新しい承諾を進められる」＝二重サブスクの窓が開く。
        handoverTxService.renotifyWithFreshRequest(handoverRequestId);
        return true;
    }

    /**
     * 失敗確定後の Stripe 後始末（新 trial サブスクの無課金取消・旧サブスクの差し戻し）を行い、
     * 完了を記録する（PR-4 Codex検分3巡目 P1-1/P1-2）。
     *
     * <p><b>必ず CAS で失敗確定の権利を取った後に呼ぶこと。</b> 完了の記録
     * （{@code old_cancel_scheduled_at} の NULL クリア）は全ての Stripe 呼び出しが成功した後にだけ行う。
     * 途中で落ちれば列は残り、「{@code FAILED} なのに残っている」という証跡から
     * 夜次バッチが回収して再試行する（Stripe 側の操作はいずれも冪等）。</p>
     *
     * @param oldSubscriptionRef 差し戻す旧サブスク（差し戻さない判断のときは {@code null}）
     */
    private void cleanupAfterFailure(UUID handoverRequestId, String oldSubscriptionRef,
            String newSubscriptionRef) {
        try {
            if (newSubscriptionRef != null) {
                billingPaymentGateway.cancelHandoverNewSubscription(newSubscriptionRef, handoverRequestId);
            }
            if (oldSubscriptionRef != null) {
                billingPaymentGateway.revertCancelAtPeriodEndForHandover(
                        oldSubscriptionRef, handoverRequestId);
            }
        } catch (RuntimeException e) {
            log.error("柱③-B: 失敗確定後の Stripe 後始末に失敗しました。"
                            + "old_cancel_scheduled_at を残して夜次バッチの回収対象にします"
                            + " handoverRequestId={}, oldSubscriptionRef={}, newSubscriptionRef={}",
                    handoverRequestId, oldSubscriptionRef, newSubscriptionRef, e);
            throw e;
        }
        handoverTxService.finishFailureCleanup(handoverRequestId);
    }

    /**
     * {@code FAILED} で終端したが Stripe の後始末が未了の引継要求 ID を返す
     * （PR-4 Codex検分3巡目 P1-2・夜次バッチの回収経路）。
     */
    @Transactional(readOnly = true)
    public List<UUID> findFailureCleanupBacklogIds() {
        return handoverRequestRepository.findFailedWithPendingCleanupIds(PayerHandoverStatus.FAILED);
    }

    /**
     * 失敗確定後に取り残された Stripe 後始末を再試行し、完了したら AC-20 の再要求まで進める。
     *
     * <p>Stripe 側の操作（新サブスクの即時解約・旧サブスクの差し戻し）はいずれも冪等であり、
     * 既に済んでいる場合も安全に再送できる。</p>
     *
     * @return 後始末を完了できたなら {@code true}
     */
    public boolean retryFailureCleanup(UUID handoverRequestId) {
        BillingPayerHandoverTxService.FailureCleanupTarget target =
                handoverTxService.loadFailureCleanupTarget(handoverRequestId);
        if (target == null) {
            return false;
        }
        cleanupAfterFailure(handoverRequestId, target.oldSubscriptionRef(), target.newSubscriptionRef());
        // 後始末が終わって初めて、承諾可能な新しい要求を作れる（作成要件は Tx 層が再検証する）。
        handoverTxService.renotifyWithFreshRequest(handoverRequestId);
        return true;
    }

    // ============================================================
    // MANUAL_INTERVENTION からの RESUME（設計書 §3.6.2・AC-37）
    // ============================================================

    /** {@code RESUME} で運用者が明示的に選ぶ遷移先（設計書 §3.6.2 出口）。 */
    public enum ResumeTarget {
        /** (i) 切替再試行が可能と判断した: {@code SWITCHING} へ戻し切替バッチの次回実行で再評価させる。 */
        SWITCHING,
        /** (ii) 引継自体を諦める: {@code FAILED} で終端化する。 */
        FAILED
    }

    /**
     * {@code MANUAL_INTERVENTION} から運用者の判断で再開・終端化する（設計書 §3.6.2・AC-37）。
     *
     * <p><b>差し戻し（{@code revertOldCancelSchedule}）を必須にしていない理由</b>: {@code MANUAL_INTERVENTION}
     * へ倒れる主因は「期末境界越え＝旧サブスクが既に次の期間へ更新済み」であり、その状況で
     * {@code cancel_at_period_end=false} を機械的に戻すのは<b>誤り</b>のことがある（旧をさらに継続させてしまう）。
     * よって差し戻すかどうかは運用者が明示的に選ぶ。設計書が「運用者の判断に委ねる」と定めるとおりである。</p>
     *
     * @param revertOldCancelSchedule {@code FAILED} 確定時に旧サブスクを継続へ差し戻すか（§3.6.1 の対の操作）
     */
    public void resumeManualIntervention(EntitlementScopeKind scopeKind, Long scopeId,
            UUID handoverRequestId, Long operatorUserId,
            ResumeTarget target, boolean revertOldCancelSchedule) {

        Objects.requireNonNull(target, "target must not be null");
        // tx1: 行ロック下でスコープ一致・認可・状態（MANUAL_INTERVENTION 限定）を検証する。
        BillingPayerHandoverTxService.ResumeContext ctx = handoverTxService.loadResumeContext(
                scopeKind, scopeId, handoverRequestId, operatorUserId);

        if (target == ResumeTarget.SWITCHING) {
            // Stripe には触れない。次回の切替バッチが実行前チェック（§3.6.1(b)）を通して再評価する。
            handoverTxService.resumeToSwitching(handoverRequestId);
            log.warn("柱③-B: 運用者の RESUME により切替を再試行させます handoverRequestId={}, operatorUserId={}",
                    handoverRequestId, operatorUserId);
            return;
        }

        // ★【CAS で権利を取ってから Stripe を変更する】（PR-4 Codex検分3巡目 P1-1）。
        //   期待元状態は MANUAL_INTERVENTION のみ（同2巡目 P1-2）——同時 RESUME の一方が
        //   SWITCHING へ戻した直後に、他方の FAILED 確定が成立してしまうのを防ぐ。
        if (!handoverTxService.markFailedPendingCleanup(
                handoverRequestId, List.of(PayerHandoverStatus.MANUAL_INTERVENTION))) {
            log.warn("柱③-B: 他の処理が先に状態を進めたため RESUME→FAILED を中止します"
                    + " handoverRequestId={}", handoverRequestId);
            return;
        }
        // 新 trial サブスクは無課金のため取り消してよい（放置すると孤児として課金され得る）。
        cleanupAfterFailure(handoverRequestId, revertOldCancelSchedule ? ctx.oldSubscriptionRef() : null,
                ctx.newSubscriptionRef());
        log.warn("柱③-B: 運用者の RESUME により引継を FAILED で確定しました"
                        + " handoverRequestId={}, operatorUserId={}, 旧サブスク差し戻し={}",
                handoverRequestId, operatorUserId, revertOldCancelSchedule);
    }

    // ============================================================
    // 期限超過の未解決承諾の照合・終端化（設計書 §5.3・§3.6.1(a)・Codex検分4巡目 P1）
    // ============================================================

    /**
     * 猶予期限を過ぎたまま {@code ACCEPTED} で未解決の引継要求 ID を返す。
     *
     * @param now 判定基準時刻
     */
    @Transactional(readOnly = true)
    public List<UUID> findExpiredUnresolvedAcceptanceIds(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        return handoverRequestRepository.findExpiredUnresolvedAcceptedIds(
                PayerHandoverStatus.ACCEPTED, now);
    }

    /**
     * 期限超過のまま未解決だった承諾を、<b>Stripe と照合してから</b>安全に解決する。
     *
     * <p><b>塞ぐ穴</b>: 承諾後の List 照会が失敗すると「承諾者 A の Customer に新サブスクが在るか」が
     * 不明なまま {@code ACCEPTED} が残る（§3.2 の照合は customer 指定のため、A を固定して本人の
     * 再試行に委ねるのが唯一安全な扱い）。しかし A が戻らないとこの行は誰にも触られず、
     * <b>非終端ゆえ §5.4 で purge の期末解約フォールバックはスキップされ続け</b>、生成列 + UNIQUE で
     * 同一旧契約への再要求もブロックされ続ける。旧 payer の課金が止まらないまま永久に残るため、
     * 期限超過後は機械的に決着させる経路が必須である。</p>
     *
     * <p><b>単純な期限切れにはできない</b>: Stripe 上に新サブスクが実在するのに {@code EXPIRED} に
     * すると、課金される新サブスクを孤児として残す。よって必ず<b>記録済み承諾者の Customer を
     * List 照合してから</b>分岐する:</p>
     * <ul>
     *   <li>(a) 実在した → {@code psp_new_subscription_ref} へ回収し、引継を続行させる
     *       （以後は通常どおり (a)引継確定条件・切替バッチの経路に乗る）</li>
     *   <li>(b) 不在が確定した → {@code EXPIRED} で終端化し、先行作成した {@code PENDING_HANDOVER}
     *       契約を破棄する。これで再要求ブロックが解け、purge の期末解約フォールバックへ処理が渡る</li>
     * </ul>
     *
     * <p><b>照会自体が失敗した場合は状態を変えない</b>（曖昧なまま終端化しない）。次回の照合で再試行する。
     * 例外は握りつぶさず呼び出し元へ伝える。</p>
     *
     * <p><b>PR-4 の夜次照合バッチとの分界</b>: 設計書 §3.6.1(a) の夜次照合バッチは
     * 「非終端かつ {@code old_cancel_scheduled_at IS NULL} の行を Stripe と突合して整合を回復する」
     * 処理であり、本メソッドはその対象集合の<b>部分集合（承諾直後・期限超過）に対する同種の照合</b>である。
     * {@code @Scheduled} 本体は PR-4 のスコープであるため本 PR では駆動を結線せず、
     * {@link #findExpiredUnresolvedAcceptanceIds} と本メソッドの組で提供する
     * （{@code findSwitchDueHandoverIds} / {@link #executeSwitch} と同じ分界）。PR-4 の夜次照合バッチは
     * この2メソッドを呼ぶだけでよく、照合ロジックが二重実装にならない。</p>
     *
     * @return 引継を続行させたなら {@code true}（サブスク回収）、終端化したなら {@code false}
     */
    public boolean reconcileExpiredAcceptance(UUID handoverRequestId) {
        ReconcileTarget target = handoverTxService.loadExpiredUnresolvedAcceptance(
                handoverRequestId, clock.instant());
        if (target == null) {
            // 既に誰かが解決済み（本人の再試行で回収された・別経路で終端化された等）。
            return false;
        }

        // ★終端化の前に必ず「承諾者本人の Customer」を照合する。ここを飛ばすと課金される
        //   新サブスクを孤児として残す危険がある（§3.2 の照合は customer 指定のため、
        //   照合できる Customer は要求行に記録された承諾者のもの<b>だけ</b>である）。
        String subscriptionRef = billingPaymentGateway
                .findHandoverSubscriptionRef(target.newPayerUserId(), handoverRequestId)
                .orElse(null);

        if (subscriptionRef != null) {
            // (a) 実在した: 回収して引継を続行させる（終端化してはならない）。
            handoverTxService.persistNewSubscriptionRef(handoverRequestId, subscriptionRef);
            log.warn("柱③-B: 期限超過の承諾で新サブスクの実在を確認し回収しました（引継は続行）"
                    + " handoverRequestId={}, subscriptionRef={}", handoverRequestId, subscriptionRef);
            return true;
        }

        // (b) 不在が確定した: 安全に終端化できる。
        handoverTxService.expireUnresolvedAcceptance(handoverRequestId, clock.instant());
        return false;
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

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
