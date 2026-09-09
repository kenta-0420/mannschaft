package com.mannschaft.app.billing;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 柱③-B 請求担当引継（CMP-260901-1538）PR-4: 引継フローを<b>自動で前へ進める駆動主体</b>
 * （設計書 {@code billing_payer_handover_design.md} §3.6 (b)・§3.6.1(a)・§5.3）。
 *
 * <h2>なぜこのクラスが要るのか（PR-2 のリリース依存の解消）</h2>
 * <p>PR-2 は「抽出クエリ」と「1件を解決するメソッド」だけを実装し、{@code @Scheduled} を結線しなかった。
 * つまり <b>PR-2 単体では引継は自動では一切前へ進まない</b>——旧期末が来ても pointer は切り替わらず、
 * {@code cancel_at_period_end} の設定漏れも検出されず、期限超過の承諾も誰にも触られないまま
 * 非終端で残り続ける（非終端であるため purge の期末解約フォールバックも §5.4 によりスキップされ続け、
 * 旧 payer への課金が止まらない）。本クラスがその駆動を担い、リリース依存を解消する。</p>
 *
 * <h2>トランザクション設計</h2>
 * <p><b>本クラスに {@code @Transactional} は付けない。</b> バッチ全体を1トランザクションで包むと、
 * ループ内で catch していても rollback-only が残り、最後のコミットで<b>成功した全件が巻き戻る</b>
 * （{@code ShiftCleanupBatchService} が踏んだ事故）。抽出は tx 外で行い、1件ずつ
 * {@link BillingPayerHandoverService}（内部でさらに {@code BillingPayerHandoverTxService} の
 * 独立トランザクションへ分解される）へ渡す。catch は必ず tx の外側であるこのループで行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingPayerHandoverBatchService {

    /**
     * 1回の実行で処理する上限件数（PR-4 Codex検分1巡目 P2-2）。
     *
     * <p>対象1件ごとに複数の Stripe API を同期実行するため、無制限に流すと
     * {@code lockAtMostFor} を超えて<b>次回起動と並走し得る</b>。上限を設けても
     * 抽出条件は毎回 DB の状態から導出されるため、あふれた分は次回の実行が拾い直す
     * （取りこぼしにはならない）。</p>
     */
    static final int MAX_TARGETS_PER_RUN = 200;

    private final BillingPayerHandoverService handoverService;
    private final Clock clock;

    /** 1回の実行で処理する件数を {@link #MAX_TARGETS_PER_RUN} で頭打ちにする。 */
    private List<UUID> limit(List<UUID> targets) {
        if (targets.size() <= MAX_TARGETS_PER_RUN) {
            return targets;
        }
        log.warn("柱③-B: 対象が上限を超えたため今回は {} 件だけ処理します（残りは次回実行が拾う）: 対象={}",
                MAX_TARGETS_PER_RUN, targets.size());
        return targets.subList(0, MAX_TARGETS_PER_RUN);
    }

    /**
     * 旧期末に到達した引継の pointer 切替を実行する（設計書 §3.6 (b)・AC-27/AC-30/AC-35）。
     *
     * <p><b>本バッチが唯一の切替TX実行者</b>である（webhook 等の他経路はこの判定・実行を代行しない）。
     * 毎時実行にしているのは、旧期末は Stripe 側の課金サイクルに従って任意の時刻に到来するためであり、
     * 日次にすると最大で1日ぶん {@code PENDING_HANDOVER} が伸びる（entitlement は旧 pointer が
     * 担保し続けるため利用者影響は無いが、{@code pending_setup_intent} 未解決の最終確定も遅れる）。</p>
     *
     * <p>{@code lockAtMostFor} を起動間隔（1時間）より長く取っているのは、
     * ロックが実行途中で失効して次回起動と並走するのを防ぐためである（番人
     * {@code ScheduledBatchGuardTest} のルール4）。並走しても行ロックで直列化されるが、
     * Stripe 呼び出しが二重に走る余地を残さない。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "決済を閉栓している間は引継の承諾も新サブスク作成も起こらず、切替待ちの行は DB に残るだけである。抽出条件は「旧期末に到達したか」という時刻条件のみで毎回作り直されるため、再開後の最初の実行が取りこぼしごと拾い直す")
    @BatchEndpoint(name = "billing-payer-handover-switch",
            description = "旧期末に到達した請求担当引継の pointer 切替を実行する（柱③-B・毎時）")
    @Scheduled(cron = "${mannschaft.billing.payer-handover.switch-cron:0 10 * * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "billing_payer_handover_switch",
            lockAtMostFor = "PT90M", lockAtLeastFor = "PT1M")
    public void runPayerHandoverSwitch() {
        Instant now = clock.instant();
        List<UUID> targets = handoverService.findSwitchDueHandoverIds(now);
        if (targets.isEmpty()) {
            log.debug("柱③-B 切替バッチ: 対象なし");
            return;
        }

        int failed = 0;
        for (UUID handoverRequestId : limit(targets)) {
            try {
                handoverService.executeSwitch(handoverRequestId);
            } catch (Exception e) {
                // 1件の失敗で他件を止めない。executeSwitch は自身の失敗を
                // PARTIALLY_COMPLETED（非終端・次回リトライ対象）として記録済みだが、
                // その記録自体が落ちる可能性もあるためここでも必ず ERROR で残す。
                failed++;
                log.error("柱③-B 切替バッチ: 切替に失敗しました handoverRequestId={}", handoverRequestId, e);
            }
        }
        log.info("柱③-B 切替バッチ完了: 対象={}, 失敗={}", targets.size(), failed);
    }

    /**
     * 夜次照合（設計書 §3.6.1(a) 第一防衛・§5.3・AC-34）。
     *
     * <ol>
     *   <li>猶予期限を過ぎたまま誰にも承諾されなかった要求を {@code EXPIRED} で終端化する（§5.3・AC-21）</li>
     *   <li>期限超過のまま未解決の承諾を Stripe と照合して決着させる（§5.3）</li>
     *   <li>{@code old_cancel_scheduled_at} が未確認の行を Stripe 実物と突合し整合を回復する（AC-34）</li>
     * </ol>
     *
     * <p>①が要る理由: 期限切れの判定は従来<b>承諾操作が来たときにしか行われなかった</b>。
     * AC-21 が定めるのはまさに「全 ADMIN が承諾を無視した」ケースであり、承諾操作は永久に来ない。
     * 放置すると {@code REQUESTED} 行が非終端のまま残り、§5.4 により purge の期末解約フォールバックが
     * 止まり続け、<b>旧 payer への課金が止まらないまま固まる</b>。</p>
     *
     * <p>3つの照合は<b>互いに独立</b>である。前者が全滅しても後者は必ず実行する——
     * 後者は「旧サブスクが解約予約されないまま課金を続ける」という金銭事故の検出であり、
     * 他の照合の失敗を理由にスキップしてよいものではない。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_BILLING_PAYMENT_ENABLED",
            reason = "照合対象は毎回 DB から再抽出する（期限超過・old_cancel_scheduled_at が NULL 等の状態そのものが条件）ため、閉栓中にスキップしても対象が失われない。再開後の最初の夜次実行がまとめて突合し直す")
    @BatchEndpoint(name = "billing-payer-handover-reconcile",
            description = "請求担当引継の期限超過承諾と cancel_at_period_end 設定漏れを Stripe と照合する（柱③-B・日次）")
    @Scheduled(cron = "${mannschaft.billing.payer-handover.reconcile-cron:0 40 2 * * *}", zone = "Asia/Tokyo")
    @SchedulerLock(name = "billing_payer_handover_reconcile",
            lockAtMostFor = "PT60M", lockAtLeastFor = "PT1M")
    public void runPayerHandoverNightlyReconcile() {
        expireOverdueUnaccepted();
        reconcileExpiredAcceptances();
        reconcileOldCancelSchedules();
        reconcileStalledSwitching();
    }

    /**
     * §5.5 ④・AC-20: {@code SWITCHING} の滞留を Stripe 実物と突合し、追加認証が期限内に
     * 完了していなければ {@code FAILED} を確定して他 ADMIN へ再通知する（PR-4 Codex検分1巡目 P1-6）。
     */
    private void reconcileStalledSwitching() {
        List<UUID> targets;
        try {
            targets = handoverService.findStalledSwitchingIds();
        } catch (Exception e) {
            log.error("柱③-B 夜次照合: SWITCHING 滞留の抽出に失敗しました", e);
            return;
        }
        int failedOut = 0;
        int errors = 0;
        for (UUID handoverRequestId : limit(targets)) {
            try {
                if (handoverService.reconcileStalledSwitching(handoverRequestId)) {
                    failedOut++;
                }
            } catch (Exception e) {
                errors++;
                log.error("柱③-B 夜次照合: SWITCHING 滞留の照合に失敗しました handoverRequestId={}",
                        handoverRequestId, e);
            }
        }
        if (!targets.isEmpty()) {
            log.warn("柱③-B 夜次照合（SWITCHING 滞留）: 対象={}, 終端化={}, 失敗={}"
                    + "（滞留が積み上がる場合は追加認証の導線を調査すること）",
                    targets.size(), failedOut, errors);
        }
    }

    /** §5.3・AC-21: 猶予期限を過ぎたまま誰にも承諾されなかった要求を EXPIRED で終端化する。 */
    private void expireOverdueUnaccepted() {
        List<UUID> targets;
        try {
            targets = handoverService.findOverdueUnacceptedIds(clock.instant());
        } catch (Exception e) {
            log.error("柱③-B 夜次照合: 期限切れ未承諾要求の抽出に失敗しました", e);
            return;
        }
        int expired = 0;
        int failed = 0;
        for (UUID handoverRequestId : limit(targets)) {
            try {
                if (handoverService.expireOverdueUnaccepted(handoverRequestId)) {
                    expired++;
                }
            } catch (Exception e) {
                failed++;
                log.error("柱③-B 夜次照合: 期限切れ未承諾要求の終端化に失敗しました handoverRequestId={}",
                        handoverRequestId, e);
            }
        }
        if (!targets.isEmpty()) {
            log.info("柱③-B 夜次照合（期限切れ未承諾）: 対象={}, 終端化={}, 失敗={}",
                    targets.size(), expired, failed);
        }
    }

    /** §5.3: 期限超過のまま未解決の承諾を Stripe と照合して決着させる。 */
    private void reconcileExpiredAcceptances() {
        List<UUID> targets;
        try {
            targets = handoverService.findExpiredUnresolvedAcceptanceIds(clock.instant());
        } catch (Exception e) {
            log.error("柱③-B 夜次照合: 期限超過承諾の抽出に失敗しました", e);
            return;
        }
        int failed = 0;
        for (UUID handoverRequestId : limit(targets)) {
            try {
                handoverService.reconcileExpiredAcceptance(handoverRequestId);
            } catch (Exception e) {
                failed++;
                log.error("柱③-B 夜次照合: 期限超過承諾の照合に失敗しました handoverRequestId={}",
                        handoverRequestId, e);
            }
        }
        if (!targets.isEmpty()) {
            log.info("柱③-B 夜次照合（期限超過承諾）: 対象={}, 失敗={}", targets.size(), failed);
        }
    }

    /** AC-34: {@code cancel_at_period_end} の設定が確認できていない行を Stripe 実物と突合する。 */
    private void reconcileOldCancelSchedules() {
        List<UUID> targets;
        try {
            targets = handoverService.findOldCancelScheduleUnconfirmedIds();
        } catch (Exception e) {
            log.error("柱③-B 夜次照合: cancel_at_period_end 未確認行の抽出に失敗しました", e);
            return;
        }
        int failed = 0;
        for (UUID handoverRequestId : limit(targets)) {
            try {
                handoverService.reconcileOldCancelSchedule(handoverRequestId);
            } catch (Exception e) {
                failed++;
                log.error("柱③-B 夜次照合: cancel_at_period_end の照合に失敗しました handoverRequestId={}",
                        handoverRequestId, e);
            }
        }
        if (!targets.isEmpty()) {
            log.warn("柱③-B 夜次照合（cancel_at_period_end 未確認）: 対象={}, 失敗={}"
                    + "（対象が常時0件でない場合は承諾確定時の設定経路を調査すること）", targets.size(), failed);
        }
    }
}
