package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Billing Center PR6a: <b>プロセス停止窓の回収</b>（D8・AC-77〜AC-84）。
 *
 * <p>期待する振る舞いは {@code BillingContractOperationRecoveryIT}／
 * {@code BillingContractOperationRecoveryReentrancyIT}／{@code BillingOperationStaleThresholdTest}
 * が AC 番号つきで固定している（第4b隊 試練Dの発注書を第10隊が実装した）。</p>
 *
 * <h2>なぜ回収が必要なのか</h2>
 * <p>D1（トランザクション分割）は、既存実装が意図的に選んでいた「Stripe 成功後に tx を
 * ロールバックし、期末の {@code customer.subscription.deleted} で自己修復する」仕組みを<b>外す</b>。
 * その代わりに Saga 側の補償で置き換えるが、Saga には次の3つのプロセス停止窓が残る。
 * 回収しなければ {@code CALLING_STRIPE} と pointer が<b>永久残留</b>し、その契約は
 * 利用者から二度と操作できなくなる。</p>
 *
 * <pre>
 * (a) tx1 commit 後 〜 Stripe 呼び出し前に落ちた   → operation は CREATED のまま
 * (b) Stripe 成功後 〜 tx2 開始前に落ちた         → operation は CALLING_STRIPE のまま・Stripe は反映済み
 * (c) tx2 失敗後 〜 検疫記録前に落ちた            → operation は CALLING_STRIPE のまま・Stripe と DB が食い違う
 * </pre>
 *
 * <h2>判定表（AC-78/79/80）</h2>
 * <p>(b) と (c) はどちらも {@code CALLING_STRIPE} であり、DB だけを見て区別することはできない。
 * <b>Stripe 側 metadata の {@value #STRIPE_METADATA_OPERATION_ID_KEY}（AC-77）と実物の
 * {@code cancel_at_period_end} の組で区別する</b>。これが「metadata が保存されなければ回収は
 * 原理的に成立しない」（AC-77 が G群の土台である）理由である。</p>
 *
 * <table border="1">
 *   <caption>stale な operation の回収先</caption>
 *   <tr><th>status</th><th>Stripe metadata に自分の operationId</th>
 *       <th>Stripe 実物が反映済み</th><th>回収先</th><th>pointer</th><th>停止窓</th></tr>
 *   <tr><td>CREATED</td><td>無い</td><td>—</td>
 *       <td>{@code CANCELLED}</td><td>解放</td><td>(a)・AC-78</td></tr>
 *   <tr><td>CALLING_STRIPE</td><td>有る</td><td>はい</td>
 *       <td>{@code APPLIED}（tx2 相当を完了）</td><td>解放</td><td>(b)・AC-79</td></tr>
 *   <tr><td>CALLING_STRIPE</td><td>有る</td><td>いいえ</td>
 *       <td>{@code RECONCILIATION_REQUIRED}</td><td>保持</td><td>(c)・AC-80</td></tr>
 * </table>
 *
 * <p><b>(b) を {@code FAILED} にしてはならない</b>（AC-79）。Stripe 側では解約が成立している
 * のだから、FAILED に倒すと「利用者は解約したのに解約されていない」状態が固定される。
 * 回収は利用者の解約を取りこぼさないことを第一とする。</p>
 *
 * <h2>回収は operation を作らない（AC-84）</h2>
 * <p>回収経路は D2（Stripe を伴わない経路は operation を作らない）と同じ扱いとする。
 * 回収が自分用の operation を起票すると、その pointer 取得が「回収しようとしている当の pointer」と
 * 衝突し、自縄自縛で永久に回収できなくなる。回収は既存 operation を遷移させるだけである。</p>
 *
 * <h2>第10隊への発注（走査条件・しきい値）</h2>
 * <ul>
 *   <li>走査対象: {@code billing_contract_operations} のうち status が {@code CREATED} または
 *       {@code CALLING_STRIPE} かつ {@code deleted_at IS NULL} かつ
 *       {@code updated_at < now() - staleThreshold}。
 *       {@code BillingContractOperationRepository} へ
 *       {@code findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(Collection, Instant, Pageable)}
 *       相当を足すこと（1周の件数に上限を置き、無制限に読み込まない）。</li>
 *   <li>しきい値の既定は {@link #DEFAULT_STALE_THRESHOLD}。設定で上書き可能にしてよいが、
 *       <b>進行中の正常な operation を横取りしてはならない</b>（AC-81）。Stripe 呼び出しの
 *       タイムアウトより十分に長く取ること。</li>
 *   <li>時刻は必ず注入された {@link Clock} から取ること（引数なしの now() 直呼びは禁止。
 *       stale 判定が経過時間に依るため、固定 Clock で測れない実装は検証できない）。</li>
 *   <li>再入・並行実行で pointer を二度解放してはならない（AC-82）。
 *       {@code ActiveBillingContractOperationPointerRepository
 *       #hardDeleteByContractIdAndOperationId} の削除件数と、operation の status CAS の
 *       更新件数のどちらかを「自分が勝った」の唯一の根拠にすること。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingContractOperationRecoveryService {

    /**
     * Stripe Subscription の metadata に operationId を書くキー（AC-77）。
     *
     * <p>引継の {@code handoverRequestId} とは別キーであり、同じ subscription に両方が並ぶことが
     * ありうる（上書きしてはならない）。</p>
     */
    public static final String STRIPE_METADATA_OPERATION_ID_KEY = "billingOperationId";

    /** stale 判定の既定しきい値（AC-81）。 */
    public static final Duration DEFAULT_STALE_THRESHOLD = Duration.ofMinutes(5);

    /**
     * 回収の入口として受け取る Stripe イベント種別（AC-83）。
     *
     * <p><b>PR6a では回収の入口として使うだけである。</b>プラン変更（{@code items} 差し替え・
     * {@code pending_update}）の {@code APPLIED} 判定は PR6b の担当であり、本 PR で実装しない。</p>
     */
    public static final String RECOVERY_ENTRY_EVENT_TYPE = "customer.subscription.updated";

    private final BillingContractOperationRepository operationRepository;
    private final ActiveBillingContractOperationPointerRepository pointerRepository;
    private final BillingContractRepository billingContractRepository;
    private final BillingContractOperationSagaService sagaService;
    private final BillingPaymentGateway billingPaymentGateway;
    private final Clock clock;

    /**
     * 回収1周で読み込む上限件数（無制限に読み込まない・発注書の走査条件）。
     *
     * <p>取り切れなかった分は次周が拾う（走査は古い順であり置き去りにならない）。</p>
     */
    static final int MAX_SCAN_BATCH = 200;

    /** 走査対象の status（{@code RECONCILIATION_REQUIRED} は検疫であり対象外・AC-8/AC-81）。 */
    private static final Set<BillingOperationStatus> SCAN_STATUSES =
            Set.of(BillingOperationStatus.CREATED, BillingOperationStatus.CALLING_STRIPE);

    /** 停止窓(a) として取り消したときの {@code error_code}。 */
    static final String ERROR_STALE_BEFORE_STRIPE = "RECOVERED_STALE_NO_STRIPE_CALL";
    /** 停止窓(c) として検疫へ倒したときの {@code error_code}。 */
    static final String ERROR_STRIPE_MISMATCH = "RECOVERED_STRIPE_MISMATCH";

    /**
     * トランザクション境界。
     *
     * <p><b>なぜフィールド注入なのか</b>: 本クラスのコンストラクタ引数は試練Dの発注書で固定されており
     * （{@code BillingOperationStaleThresholdTest} が 6 引数で直接 {@code new} する）、増やせない。
     * 純 UT はしきい値判定しか呼ばないためここが {@code null} でも成立し、DB を触る経路は
     * すべて Spring 管理下（IT・本番）で注入される。</p>
     */
    @Autowired(required = false)
    private TransactionTemplate transactionTemplate;

    /**
     * 停止窓(b) の「tx2 相当」の反映を借りる先（AC-79）。
     *
     * <p>解約／撤回の反映（{@code cancelled_at}・{@code current_period_end}・entitlements の
     * {@code valid_until}・キャッシュ evict）は {@link BillingContractCancelService} が正本であり、
     * 回収が自前で書き写すと二重実装になって片方だけ直る事故を生む。フィールド注入の理由は
     * {@link #transactionTemplate} と同じである。</p>
     */
    @Autowired(required = false)
    private BillingContractCancelService cancelService;

    /**
     * 回収1周の結果（成果物で測るための内訳）。
     *
     * @param scanned          走査対象として拾った stale な operation 件数
     * @param cancelledStale   停止窓(a) として {@code CANCELLED} へ終端化した件数（AC-78）
     * @param appliedFromStripe 停止窓(b) として {@code APPLIED} へ完了させた件数（AC-79）
     * @param quarantined      停止窓(c) として {@code RECONCILIATION_REQUIRED} へ倒した件数（AC-80）
     */
    public record RecoveryOutcome(
            int scanned, int cancelledStale, int appliedFromStripe, int quarantined) {

        /** 何かしら回収したか。 */
        public int recovered() {
            return cancelledStale + appliedFromStripe + quarantined;
        }
    }

    /**
     * stale な operation を1周走査して回収する（AC-78/79/80/81/84）。
     *
     * <p>しきい値未満の operation には<b>一切触らない</b>（AC-81）。回収のために新しい operation を
     * 起票しない（AC-84）。</p>
     *
     * @return 回収の内訳
     */
    public RecoveryOutcome recoverStaleOperations() {
        // updated_at は「最後に状態が動いた瞬間」であり Instant（日時方針 §1）。
        Instant staleBefore = Instant.now(clock).minus(staleThreshold());
        List<BillingContractOperationEntity> stale = operationRepository
                .findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(
                        SCAN_STATUSES, staleBefore,
                        PageRequest.of(0, MAX_SCAN_BATCH,
                                Sort.by(Sort.Direction.ASC, "updatedAt")));

        int cancelledStale = 0;
        int appliedFromStripe = 0;
        int quarantined = 0;
        for (BillingContractOperationEntity operation : stale) {
            Optional<RecoveryWindow> window = recoverInternal(operation.getId());
            if (window.isEmpty()) {
                continue;
            }
            switch (window.get()) {
                case A_CANCELLED -> cancelledStale++;
                case B_APPLIED -> appliedFromStripe++;
                case C_QUARANTINED -> quarantined++;
            }
        }
        if (cancelledStale + appliedFromStripe + quarantined > 0) {
            log.info("PR6a 停止窓の回収: scanned={}, cancelled={}, applied={}, quarantined={}",
                    stale.size(), cancelledStale, appliedFromStripe, quarantined);
        }
        return new RecoveryOutcome(stale.size(), cancelledStale, appliedFromStripe, quarantined);
    }

    /**
     * 単一 operation を回収する（再入可能・AC-82）。
     *
     * <p>既に terminal／既に他スレッドが回収済みなら何もせず {@code false} を返す。
     * pointer の解放は<b>一度だけ</b>起きなければならない。</p>
     *
     * @param operationId 対象 operation
     * @return 本呼び出しが実際に回収したなら {@code true}
     */
    public boolean recoverOperation(UUID operationId) {
        return recoverInternal(operationId).isPresent();
    }

    /**
     * {@code customer.subscription.updated} からの回収入口（AC-83）。
     *
     * <p>Stripe subscription ref から契約を逆引きし、その契約に非終端 operation が
     * 残っていれば {@link #recoverOperation(UUID)} へ回す。<b>stale しきい値は適用しない</b>
     * （Stripe が「反映した」と言ってきているのだから待つ理由が無い）。</p>
     *
     * <p><b>PR6a の範囲</b>: 解約（{@code CANCEL} / {@code RESUME}）の回収のみ。プラン変更の
     * {@code APPLIED} 判定は PR6b が足す。</p>
     *
     * @param subscriptionRef Stripe Subscription ID（{@code sub_xxx}）
     * @return 本呼び出しが実際に回収したなら {@code true}
     */
    public boolean recoverBySubscriptionRef(String subscriptionRef) {
        if (subscriptionRef == null || subscriptionRef.isBlank()) {
            return false;
        }
        Optional<BillingContractEntity> contract = billingContractRepository
                .findByPspSubscriptionRefAndDeletedAtIsNull(subscriptionRef);
        if (contract.isEmpty()) {
            return false;
        }
        // 進行中 operation は pointer が一意に指す（契約あたり最大1件）。無ければ回収するものは無い。
        return pointerRepository.findById(contract.get().getId())
                .map(pointer -> recoverOperation(pointer.getOperationId()))
                .orElse(false);
    }

    /**
     * operation が stale（回収対象）か（AC-81）。
     *
     * <p>判定は注入された {@link Clock} 基準で行う。</p>
     *
     * <p><b>public にしない</b>のは、番人 {@code ServiceApiEntityBoundaryArchTest}（D-1 API 境界）が
     * 「{@code @Service} の public メソッドは引数・戻り値に Entity を公開してはならない」を課しており、
     * 本メソッドは判定式そのものを測るために Entity を受け取るためである。呼び出し元は同一ドメイン
     * （billing パッケージ）内に限る。</p>
     *
     * @param operation 対象 operation
     * @return しきい値を超えて放置されているなら {@code true}
     */
    boolean isStale(BillingContractOperationEntity operation) {
        if (operation == null || operation.getUpdatedAt() == null
                || !SCAN_STATUSES.contains(operation.getStatus())) {
            // terminal も検疫も走査対象ではない（AC-81）。
            return false;
        }
        // 半開区間: しきい値ちょうどは stale にしない（AC-24 / AC-37b と同じ流儀）。
        return operation.getUpdatedAt().isBefore(Instant.now(clock).minus(staleThreshold()));
    }

    /**
     * 現在の stale 判定しきい値（AC-81）。
     *
     * @return しきい値
     */
    public Duration staleThreshold() {
        return DEFAULT_STALE_THRESHOLD;
    }


    // ================================================================
    // 内部実装
    // ================================================================

    /** 回収先（判定表の3行）。 */
    private enum RecoveryWindow {
        /** (a) Stripe をまだ呼んでいない → CANCELLED ＋ pointer 解放。 */
        A_CANCELLED,
        /** (b) Stripe は反映済み → APPLIED（tx2 相当）＋ pointer 解放。 */
        B_APPLIED,
        /** (c) Stripe と DB が食い違う → RECONCILIATION_REQUIRED（pointer 保持）。 */
        C_QUARANTINED
    }

    /**
     * 1件を回収する。回収できたときだけ回収先を返す。
     *
     * <p>回収の可否は<b>status CAS の更新件数</b>だけで決める（AC-82）。読んだ時点の status で
     * 判断して後から書くと、2本が同時に到達したときに二重に効く。CAS に負けた側は例外を投げず
     * 空を返す（例外で落ちると次周で拾えなくなる）。</p>
     *
     * @param operationId 対象 operation
     * @return 回収できた窓（回収しなかったなら空）
     */
    private Optional<RecoveryWindow> recoverInternal(UUID operationId) {
        BillingContractOperationEntity operation =
                operationRepository.findByIdAndDeletedAtIsNull(operationId).orElse(null);
        if (operation == null || !SCAN_STATUSES.contains(operation.getStatus())) {
            // 既に terminal／検疫。再入で二度触らない（AC-82）。
            return Optional.empty();
        }

        StripeTrace trace = inspectStripe(operation);
        if (trace == null) {
            // Stripe を参照できなかった。状態を推測で倒さず次周へ委ねる（AC-79 の取りこぼし防止）。
            return Optional.empty();
        }

        return switch (decide(operation, trace)) {
            case A_CANCELLED -> inTransaction(() -> casAndRelease(
                    operation, BillingOperationStatus.CANCELLED,
                    ERROR_STALE_BEFORE_STRIPE, true, null));
            case B_APPLIED -> inTransaction(() -> casAndRelease(
                    operation, BillingOperationStatus.APPLIED, null, true, trace));
            case C_QUARANTINED -> inTransaction(() -> casAndRelease(
                    operation, BillingOperationStatus.RECONCILIATION_REQUIRED,
                    ERROR_STRIPE_MISMATCH, false, null));
        };
    }

    /**
     * 判定表（AC-78/79/80）。
     *
     * <p>{@code CREATED} なのに Stripe 側へ自分の痕跡があるのは「DB は呼んでいないと言い、Stripe は
     * 呼ばれたと言う」食い違いである。取り消すと Stripe 側の変更が孤児になるため、判定表に無い
     * この組は検疫へ倒して人手の reconcile に委ねる。</p>
     *
     * @param operation 対象 operation
     * @param trace     Stripe 実物から読んだ判定材料
     * @return 回収先
     */
    private RecoveryWindow decide(BillingContractOperationEntity operation, StripeTrace trace) {
        if (operation.getStatus() == BillingOperationStatus.CREATED) {
            return trace.traceMatches() ? RecoveryWindow.C_QUARANTINED : RecoveryWindow.A_CANCELLED;
        }
        // CALLING_STRIPE。痕跡があり、かつ Stripe 実物が意図どおり反映されているときだけ (b)。
        if (trace.traceMatches() && isEffectApplied(operation.getKind(), trace)) {
            return RecoveryWindow.B_APPLIED;
        }
        return RecoveryWindow.C_QUARANTINED;
    }

    /**
     * Stripe 実物が当該 kind の意図どおり反映されているか。
     *
     * <p>PR6a が反映を完了させられるのは {@code CANCEL}（{@code cancel_at_period_end=true}）と
     * {@code RESUME}（同 {@code false}）だけである。プラン変更等の判定は PR6b の担当であり、
     * ここで推測して APPLIED にすると誤った投影を確定させるため、常に検疫へ倒す。</p>
     *
     * @param kind  操作種別
     * @param trace Stripe 実物から読んだ判定材料
     * @return 反映済みなら true
     */
    private boolean isEffectApplied(BillingOperationKind kind, StripeTrace trace) {
        return switch (kind) {
            case CANCEL -> trace.cancelAtPeriodEnd();
            case RESUME -> !trace.cancelAtPeriodEnd();
            default -> false;
        };
    }

    /**
     * CAS で status を進め、勝者だけが反映と pointer 解放を行う。
     *
     * @param operation      対象（読み取り時点のスナップショット）
     * @param to             遷移先
     * @param errorCode      {@code error_code} へ記録する値（不要なら {@code null}）
     * @param releasePointer terminal 確定に伴い pointer を解放するか
     * @param applyTrace     停止窓(b) の反映に用いる Stripe 実物（それ以外は {@code null}）
     * @return 勝者なら回収先・敗者なら空
     */
    private Optional<RecoveryWindow> casAndRelease(
            BillingContractOperationEntity operation, BillingOperationStatus to,
            String errorCode, boolean releasePointer, StripeTrace applyTrace) {

        BillingOperationStatus from = operation.getStatus();
        BillingOperationTransitions.requireAllowed(from, to);
        int updated = operationRepository.compareAndSetStatus(
                operation.getId(), from, to,
                BillingOperationTransitions.stepFor(operation.getKind(), to),
                errorCode, Instant.now(clock));
        if (updated == 0) {
            // 他の回収（または通常経路）が先に進めた。二重に効かせない（AC-82）。
            return Optional.empty();
        }

        if (applyTrace != null) {
            applyRecoveredReflection(operation, applyTrace);
        }
        if (releasePointer) {
            // AC-7: terminal 確定と同一トランザクションで lease を解放する。
            pointerRepository.hardDeleteByContractIdAndOperationId(
                    operation.getContractId(), operation.getId());
        }
        return Optional.of(switch (to) {
            case CANCELLED -> RecoveryWindow.A_CANCELLED;
            case APPLIED -> RecoveryWindow.B_APPLIED;
            default -> RecoveryWindow.C_QUARANTINED;
        });
    }

    /**
     * 停止窓(b) の「tx2 相当」の反映（AC-79）。反映の正本は {@link BillingContractCancelService} にある。
     *
     * @param operation 対象 operation
     * @param trace     Stripe 実物（期末の権威・AC-34）
     */
    private void applyRecoveredReflection(
            BillingContractOperationEntity operation, StripeTrace trace) {
        LocalDateTime endAt = trace.periodEnd();
        switch (operation.getKind()) {
            case CANCEL -> cancelService.applyRecoveredCancel(operation.getContractId(), endAt);
            case RESUME -> cancelService.applyRecoveredResume(operation.getContractId(), endAt);
            default -> throw new IllegalStateException(
                    "PR6a の回収が反映できる kind ではない: " + operation.getKind());
        }
    }

    /**
     * Stripe 実物から読み取った回収の判定材料。
     *
     * @param traceMatches      metadata の operationId が自分と一致したか（AC-77）
     * @param cancelAtPeriodEnd Stripe 実物の {@code cancel_at_period_end}
     * @param periodEnd         Stripe 実物の期末（反映に用いる・null 可）
     */
    private record StripeTrace(
            boolean traceMatches, boolean cancelAtPeriodEnd, LocalDateTime periodEnd) {}

    /**
     * Stripe 実物を<b>読み取り専用</b>で引く（変更系は呼ばない）。
     *
     * <p>参照できない（Stripe 障害等）ときは {@code null} を返して回収を見送る。推測で
     * APPLIED / CANCELLED に倒すと、利用者の解約の取りこぼしか二重解約を生む。</p>
     *
     * @param operation 対象 operation
     * @return 判定材料（参照できなければ {@code null}）
     */
    private StripeTrace inspectStripe(BillingContractOperationEntity operation) {
        String subscriptionRef = operation.getStripeSubscriptionRef();
        if (subscriptionRef == null || subscriptionRef.isBlank()) {
            // Stripe を一度も呼べていないことが確実な CREATED だけ、痕跡なしとして扱う。
            return operation.getStatus() == BillingOperationStatus.CREATED
                    ? new StripeTrace(false, false, null) : null;
        }
        try {
            boolean traceMatches = billingPaymentGateway
                    .findOperationIdOnSubscription(subscriptionRef)
                    .filter(operation.getId()::equals)
                    .isPresent();
            BillingPaymentGateway.SubscriptionSnapshot snapshot =
                    billingPaymentGateway.retrieveSubscription(subscriptionRef);
            if (snapshot == null) {
                return null;
            }
            return new StripeTrace(traceMatches, snapshot.cancelAtPeriodEnd(),
                    toLocalDateTime(snapshot.currentPeriodEnd()));
        } catch (RuntimeException e) {
            log.warn("PR6a 停止窓の回収: Stripe 参照に失敗したため次周へ見送る（operationId={}）",
                    operation.getId(), e);
            return null;
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, clock.getZone());
    }

    /**
     * 1件の回収を1トランザクションで完結させる（CAS・反映・pointer 解放が片方だけ残らない）。
     *
     * @param action 回収本体
     * @return 回収先（回収しなかったなら空）
     */
    private Optional<RecoveryWindow> inTransaction(
            java.util.function.Supplier<Optional<RecoveryWindow>> action) {
        Optional<RecoveryWindow> result = transactionTemplate.execute(tx -> action.get());
        return result == null ? Optional.empty() : result;
    }

    /**
     * 回収の入口として受け取るイベント種別か（AC-83）。
     *
     * @param eventType Stripe イベント種別
     * @return {@value #RECOVERY_ENTRY_EVENT_TYPE} なら {@code true}
     */
    public static boolean isRecoveryEntryEvent(String eventType) {
        return RECOVERY_ENTRY_EVENT_TYPE.equals(eventType);
    }
}
