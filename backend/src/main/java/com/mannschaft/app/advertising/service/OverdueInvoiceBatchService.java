package com.mannschaft.app.advertising.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.InvoiceStatus;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 広告請求書の OVERDUE 自動化バッチ（Issue #2834 / CMP-056 第2群ロット1で単位トランザクション化）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は本クラスの {@code markOverdueInvoices} に {@code @Transactional} が付いており、
 * <b>バッチ全体を 1 トランザクションで包んだままループ内で 1 件ずつ catch</b> していた。
 * 1 件の失敗は握りつぶされたように見えて、実際には rollback-only が残るため
 * <b>コミット時に全件が巻き戻っていた</b>（CMP-035 で潰した形と同一）。</p>
 *
 * <h2>是正後の形</h2>
 * <pre>
 * 非トランザクションのオーケストレータ（このクラス）
 *   for each 請求書:
 *     try { overdueInvoiceMarkRunner.markOne(id) }   // REQUIRES_NEW の別 Bean
 *     catch { 記録して次へ }                          // catch は必ず TX 外
 * </pre>
 * <p>通知は Runner の独立トランザクションが commit された後に
 * {@code OverdueInvoiceNotificationListener}（{@code AFTER_COMMIT} + {@code @Async}）が配送する。
 * よって「Runner がロールバックすれば通知は作られない」「通知が失敗しても OVERDUE 遷移は残る」の
 * 両方が成り立つ。</p>
 *
 * <h2>分類の判定</h2>
 * <p>本バッチは通知だけでなく<b>業務状態（{@code ad_invoices.status}）を更新する</b>。よって確定設計の
 * 「バッチで業務状態も更新する」に該当し、非TXループ → 項目ごと REQUIRES_NEW → その中の
 * {@code AFTER_COMMIT} で通知、を採る。</p>
 *
 * <h2>外向き契約</h2>
 * <p>{@code markOverdueInvoices} は是正前後とも戻り値 {@code void}。{@code @BatchEndpoint} 経由の
 * 管理コンソール実行も戻り値を持たないため、FE / OpenAPI への波及はない。</p>
 *
 * <h2>F08.12 §5.0: 後払い廃止に伴う定期実行の停止</h2>
 * <p>後払い（{@code BillingMethod.INVOICE}）を新規登録で選べなくしたため、{@code due_date}
 * を設定していた唯一の経路（{@code MonthlyInvoiceBatchService} の INVOICE 分岐）が無くなり、
 * 本バッチの抽出条件（{@code status = ISSUED かつ due_date < today}）に該当する行は
 * 恒久的にゼロ件になる。毎朝 0 件を舐めるだけのバッチを回し続けないため
 * {@code @Scheduled} を外し、{@code @BatchEndpoint} からの手動実行のみ残す
 * （将来、与信審査つきの後払いを復活させる場合に再開する）。クラス自体は削除しない
 * （ArchUnit 凍結ストア・{@code no_arg_now_freeze.txt}・{@code shard-weights.properties}
 * にクラス名が載っており、削除は本戦役の目的外の変更を混ぜることになるため）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OverdueInvoiceBatchService {

    private final AdInvoiceRepository adInvoiceRepository;
    private final OverdueInvoiceMarkRunner overdueInvoiceMarkRunner;

    /**
     * OVERDUE 自動化バッチ。
     * status = ISSUED かつ due_date &lt; TODAY の請求書を 1 件ずつ独立トランザクションで OVERDUE に更新する。
     *
     * <p>F08.12 §5.0 により後払い廃止に伴い定期実行（{@code @Scheduled}）は停止した。
     * {@code @BatchEndpoint} からの手動実行のみ残す。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.SKIP_WHEN_DISABLED,
            gateKeys = "FEATURE_PROMOTION_ENABLED",
            reason = "支払期限切れ判定は due_date が本日より前という時刻条件のみで冪等に決まるため、止めても再開後の初回実行で同じ請求書をまとめて OVERDUE にできる")
    @BatchEndpoint(name = "advertising-invoice-overdue-mark-daily", description = "支払期限切れの広告請求書を OVERDUE に更新する（手動実行のみ。後払い廃止によりスケジュール停止）")
    @SchedulerLock(name = "overdueInvoiceMark", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void markOverdueInvoices() {
        LocalDate today = LocalDate.now();
        // 対象抽出はオーケストレータ側（TX 外）。Spring Data が読み取り用の短いトランザクションを開くだけで、
        // 以降の更新はここには参加しない。
        List<Long> targetIds = adInvoiceRepository
                .findByStatusAndDueDateBefore(InvoiceStatus.ISSUED, today)
                .stream()
                .map(AdInvoiceEntity::getId)
                .toList();

        if (targetIds.isEmpty()) {
            return;
        }

        log.info("OVERDUE バッチ開始: 対象件数={}", targetIds.size());

        int updated = 0;
        int skipped = 0;
        int failed = 0;
        Long firstFailedInvoiceId = null;
        for (Long invoiceId : targetIds) {
            try {
                if (overdueInvoiceMarkRunner.markOne(invoiceId)) {
                    updated++;
                } else {
                    // 抽出後に支払い等で状態が変わっていた（再実行時も同じ経路に入る）。
                    skipped++;
                }
            } catch (Exception e) {
                // catch は必ずオーケストレータ側（TX 外）で行う。Runner の内側で catch すると
                // rollback-only のトランザクションで記録が消える。
                failed++;
                if (firstFailedInvoiceId == null) {
                    firstFailedInvoiceId = invoiceId;
                }
                log.error("OVERDUE 更新エラー: invoiceId={}", invoiceId, e);
            }
        }

        String summary = "OVERDUE バッチ完了: 対象={}, 更新={}, スキップ={}, 失敗={}, firstFailedInvoiceId={}";
        if (failed > 0) {
            log.error(summary, targetIds.size(), updated, skipped, failed, firstFailedInvoiceId);
        } else {
            log.info(summary, targetIds.size(), updated, skipped, failed, firstFailedInvoiceId);
        }
    }
}
