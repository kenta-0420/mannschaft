package com.mannschaft.app.billing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/**
 * Billing Center PR6a: <b>プロセス停止窓の回収</b>（D8・AC-77〜AC-84）。
 *
 * <p><b>本クラスは第4b隊（試練D）が置いた発注書であり、中身は未実装である。</b>
 * 全メソッドが {@link UnsupportedOperationException} を投げる。第10隊（回収 worker）が
 * これを実装で置き換え、{@code BillingContractOperationRecoveryIT} 他の red を green にする。</p>
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
 *       {@code findByStatusInAndDeletedAtIsNullAndUpdatedAtLessThan(Collection, LocalDateTime, Pageable)}
 *       相当を足すこと（1周の件数に上限を置き、無制限に読み込まない）。</li>
 *   <li>しきい値の既定は {@link #DEFAULT_STALE_THRESHOLD}。設定で上書き可能にしてよいが、
 *       <b>進行中の正常な operation を横取りしてはならない</b>（AC-81）。Stripe 呼び出しの
 *       タイムアウトより十分に長く取ること。</li>
 *   <li>時刻は必ず注入された {@link Clock} から取ること（{@code LocalDateTime.now()} 直呼び禁止。
 *       stale 判定が経過時間に依るため、固定 Clock で測れない実装は検証できない）。</li>
 *   <li>再入・並行実行で pointer を二度解放してはならない（AC-82）。
 *       {@code ActiveBillingContractOperationPointerRepository
 *       #hardDeleteByContractIdAndOperationId} の削除件数と、operation の status CAS の
 *       更新件数のどちらかを「自分が勝った」の唯一の根拠にすること。</li>
 * </ul>
 */
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
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第10隊が実装する（試練Dの発注書）");
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
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第10隊が実装する（試練Dの発注書）");
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
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第10隊が実装する（試練Dの発注書）");
    }

    /**
     * operation が stale（回収対象）か（AC-81）。
     *
     * <p>判定は注入された {@link Clock} 基準で行う。</p>
     *
     * @param operation 対象 operation
     * @return しきい値を超えて放置されているなら {@code true}
     */
    public boolean isStale(BillingContractOperationEntity operation) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第10隊が実装する（試練Dの発注書）");
    }

    /**
     * 現在の stale 判定しきい値（AC-81）。
     *
     * @return しきい値
     */
    public Duration staleThreshold() {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第10隊が実装する（試練Dの発注書）");
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
