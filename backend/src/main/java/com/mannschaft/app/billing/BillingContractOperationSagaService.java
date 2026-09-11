package com.mannschaft.app.billing;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Billing Center PR6a: 契約操作 Saga（AC-1〜AC-21）の入口サービス。
 *
 * <p><b>本クラスは第2隊（試練A）が置いた発注書であり、中身は未実装である。</b>
 * 全メソッドが {@link UnsupportedOperationException} を投げる。第5隊（Saga Service）が
 * これを実装で置き換え、{@code BillingContractOperationSagaIT} 他の red を green にする。</p>
 *
 * <h2>殿の設計判断 D1 — トランザクション分割</h2>
 * <pre>
 * tx1: reserve()           contract FOR UPDATE + version CAS + operation INSERT + pointer INSERT
 *   -- commit --           （AC-2: 片方だけ残らない / AC-4: ここで Stripe を呼ばない）
 *      markCallingStripe() CREATED -&gt; CALLING_STRIPE
 *      Stripe 呼び出し      Idempotency-Key = stripeIdempotencyKeyOf(operationId)（AC-5）
 * tx2: applyAndFinalize()  反映（cancelled_at / valid_until 等）+ APPLIED + pointer DELETE
 *                          tx2 が落ちたら RECONCILIATION_REQUIRED（pointer 保持・AC-19）
 * </pre>
 *
 * <p>409 は全て {@link EntitlementErrorCode#CHANGE_CONFLICT}（{@code ENTITLEMENT_021}）を用いる
 * （AC-38。{@code GlobalExceptionHandler} で 409 に登録済み。新設しない）。</p>
 */
@Service
public class BillingContractOperationSagaService {

    /** Stripe 冪等キーの接頭辞（AC-5）。 */
    public static final String STRIPE_IDEMPOTENCY_KEY_PREFIX = "billing-operation-";

    /**
     * 予約（tx1）の入力。
     *
     * @param contractId              操作対象契約
     * @param kind                    操作種別
     * @param expectedContractVersion 利用者が提示した契約 version（CAS・AC-1）。
     *                                SYSTEM 経路で CAS を行わない場合のみ {@code null}
     * @param actorKind               USER / SYSTEM（AC-13）
     * @param actorUserId             {@code actorKind=USER} のとき必須・{@code SYSTEM} のとき {@code null}
     * @param requestHash             冪等キー使い回し検出用の request body ハッシュ（SHA-256 hex 64桁）
     */
    public record ReserveCommand(
            UUID contractId,
            BillingOperationKind kind,
            Long expectedContractVersion,
            BillingOperationActorKind actorKind,
            Long actorUserId,
            String requestHash) {
    }

    /**
     * 予約（tx1）の結果。
     *
     * @param operationId     採番された {@code billing_contract_operations.id}
     * @param contractId      操作対象契約
     * @param kind            操作種別
     * @param status          予約直後の状態（必ず {@link BillingOperationStatus#CREATED}）
     * @param step            予約直後の step（必ず {@link BillingOperationStep#RECEIVED}・AC-11）
     * @param contractVersion CAS 後の契約 version
     */
    public record OperationReservation(
            UUID operationId,
            UUID contractId,
            BillingOperationKind kind,
            BillingOperationStatus status,
            BillingOperationStep step,
            Long contractVersion) {
    }

    /**
     * tx1: 契約を {@code SELECT ... FOR UPDATE} で取り、version CAS を検証し、operation 行と
     * pointer 行を<b>同一トランザクションで</b> INSERT して commit する（AC-1/AC-2/AC-3/AC-4/AC-13/AC-14）。
     *
     * <p>Stripe はここでは<b>呼ばない</b>（AC-4）。{@code idempotency_key} 列には
     * operationId（UUID 36文字）を格納する（AC-32。任意長の HTTP ヘッダ値を入れない）。</p>
     *
     * @param command 予約要求
     * @return 予約結果
     * @throws com.mannschaft.app.common.BusinessException
     *         {@link EntitlementErrorCode#CHANGE_CONFLICT}（409）—
     *         pointer が既に存在する（AC-3/AC-14）／version CAS 不一致（AC-1）／
     *         契約が検疫中（AC-8）のとき
     * @throws IllegalArgumentException actor_kind と actorUserId の組合せが CHECK 制約に反するとき（AC-13）
     */
    public OperationReservation reserve(ReserveCommand command) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * Stripe 呼び出し直前に {@code CREATED -> CALLING_STRIPE} へ遷移させる（AC-10）。
     * step も kind に応じた値へ進める（AC-11）。
     *
     * @param operationId 対象 operation
     * @throws IllegalStateException 許可されない遷移のとき
     */
    public void markCallingStripe(UUID operationId) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * tx2: 渡された反映処理と、operation の APPLIED 化・pointer DELETE を
     * <b>同一トランザクション</b>で行う（AC-7/AC-18）。
     *
     * <p>反映処理が例外を投げた場合は tx2 全体をロールバックし、<b>別トランザクション</b>で
     * operation を {@link BillingOperationStatus#RECONCILIATION_REQUIRED} へ倒して
     * pointer を保持したまま例外を呼出元へ伝播する（AC-19。黙って成功にしない）。</p>
     *
     * @param operationId 対象 operation
     * @param reflection  DB 反映処理（cancelled_at / valid_until の更新等）
     * @param <T>         反映処理の戻り値型
     * @return 反映処理の戻り値
     */
    public <T> T applyAndFinalize(UUID operationId, Supplier<T> reflection) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * Stripe 失敗時: operation を {@link BillingOperationStatus#FAILED} へ CAS し、
     * 同一トランザクションで pointer を DELETE する（AC-6/AC-7）。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード
     */
    public void failAndRelease(UUID operationId, String errorCode) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * 停止窓(a) の回収: Stripe 呼び出し前の operation を
     * {@link BillingOperationStatus#CANCELLED} へ倒し、同一トランザクションで pointer を DELETE する
     * （AC-7/AC-10 の {@code CREATED -> CANCELLED} 辺・D8）。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード（不要なら {@code null}）
     */
    public void cancelAndRelease(UUID operationId, String errorCode) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * 検疫へ倒す（AC-8）。{@code RECONCILIATION_REQUIRED} は terminal ではないため
     * <b>pointer は保持する</b>。以後その契約への利用者起点の mutation は全て 409 になる。
     *
     * @param operationId 対象 operation
     * @param errorCode   {@code error_code} 列へ記録するコード
     */
    public void quarantine(UUID operationId, String errorCode) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * reconcile: 検疫中の operation を terminal へ確定させる（AC-9/AC-10）。
     * terminal が確定したときだけ pointer が解放される。
     *
     * @param operationId    対象 operation
     * @param terminalStatus 確定先（APPLIED / FAILED / CANCELLED のいずれか）
     * @param errorCode      {@code error_code} 列へ記録するコード（不要なら {@code null}）
     * @throws IllegalStateException {@code terminalStatus} が terminal でないとき
     */
    public void reconcile(
            UUID operationId, BillingOperationStatus terminalStatus, String errorCode) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * 利用者起点の mutation の入口ガード（AC-3/AC-8/AC-20/AC-21）。
     * pointer が存在する契約では 409 を投げる。
     *
     * @param contractId 対象契約
     * @throws com.mannschaft.app.common.BusinessException
     *         {@link EntitlementErrorCode#CHANGE_CONFLICT}（409）
     */
    public void requireNoActiveOperation(UUID contractId) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * D3 — SYSTEM 経路の検疫貫通（AC-16/AC-17/AC-17b）。
     *
     * <p>退会 purge と {@code customer.subscription.deleted} は検疫中でも通す。その際、残っている
     * 非終端 operation を<b>同一トランザクションで</b> {@link BillingOperationStatus#CANCELLED} へ
     * 終端化してから pointer を削除する（孤児を残さない）。再入・並行実行で二重に効いてはならない
     * （AC-17b）。pointer が無ければ何もしない（冪等）。</p>
     *
     * @param contractId 対象契約
     * @return 終端化した operation 件数（0 または 1）
     */
    public int terminateNonTerminalAndRelease(UUID contractId) {
        throw new UnsupportedOperationException(
                "Billing Center PR6a: 第5隊が実装する（試練Aの発注書）");
    }

    /**
     * Stripe 呼び出しの Idempotency-Key（AC-5）。
     *
     * @param operationId 対象 operation
     * @return {@code billing-operation-{operationId}}
     */
    public static String stripeIdempotencyKeyOf(UUID operationId) {
        return STRIPE_IDEMPOTENCY_KEY_PREFIX + operationId;
    }
}
