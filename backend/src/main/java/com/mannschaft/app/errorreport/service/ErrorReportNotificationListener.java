package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.event.ErrorReportAssignedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRaisedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportRegressionDetectedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportResolvedEvent;
import com.mannschaft.app.errorreport.event.ErrorReportSeverityEscalatedEvent;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * エラーレポート通知の配送リスナー（Issue #2990 L11・errorreport ドメイン 5件の是正）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前は {@link ErrorReportService#createOrAggregate} / {@link ErrorReportService#updateStatus} /
 * {@link ErrorReportTimelineService#assign} / {@link ErrorReportAsyncExecutor#doRecordBackendException}
 * の {@code @Transactional} の内側から {@code errorReportNotifier.notifyXxx(...)} を
 * try 無しで直接呼んでいた（{@code TX_NOTIFY_BARE}）。</p>
 *
 * <p><b>巻き戻りは起きていなかった</b>。{@code ErrorReportNotifier} の各メソッドは
 * 是正前から {@code @Async("event-pool")} が付いており、しかも別 Bean への呼び出しなので
 * プロキシを確実に通る。通知は業務スレッドとは別のスレッド・別のトランザクションで走るため、
 * 通知の失敗が業務トランザクションを巻き戻すことはない。実害は次の 2 つである。</p>
 * <ol>
 *   <li><b>因果順序が保証されていない</b>（{@code ORDERING_ONLY} と同じ形）。非同期スレッドは
 *       commit を待たずに走り出すので、業務トランザクションが後でロールバックしても
 *       Slack / SYSTEM_ADMIN / 報告者への通知だけが残る。届く通知は
 *       {@code /system-admin/error-reports/&lt;id&gt;} への導線を持つが、その行はもう存在しない。</li>
 *   <li><b>管理下の JPA エンティティをスレッド跨ぎで渡していた</b>。
 *       {@code errorReportNotifier.notifySlack(saved)} の {@code saved} は
 *       呼び出し元の永続化コンテキストに属する管理エンティティであり、
 *       非同期スレッドはそれを未コミットの状態のまま読んでいた。
 *       {@link ErrorReportService#createOrAggregate} の重複集約経路で
 *       {@code notifyEscalation} へ渡していた {@code updated} も同じ性質を持つ。</li>
 * </ol>
 *
 * <h2>是正後</h2>
 * <p>業務トランザクションの内側では ID と種別だけを載せたイベントを publish するに留め、
 * 本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取って、
 * コミット済みの {@code error_reports} 行を読み直してから配送する。
 * 3 つの技法はそれぞれ別の問題を解く: {@code AFTER_COMMIT}=因果、
 * {@code @Async}=遅延の切り離し、{@link ErrorReportNotifier} 内の受信者ごとの try/catch=被害半径。</p>
 *
 * <h2>なぜ配送本体を {@link ErrorReportNotifier} に残したか</h2>
 * <p>本文の組み立て（i18n の {@code MessageSource} 引き当て・Slack ペイロード整形）は
 * 既存の単体テスト {@code ErrorReportNotifierTest} が固定している資産であり、
 * 移設すると通知内容の回帰検証をまるごと書き直すことになる。境界の宣言（AFTER_COMMIT）を
 * 本リスナーが持ち、本文と受信者解決は Notifier が持つ、という分担にした。
 * それに伴い {@link ErrorReportNotifier} の当該 6 メソッドからは {@code @Async} を外した
 * （非同期化の責務は本リスナーが持つ。二重のスレッドホップは配送順序を無意味に乱すだけである）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ErrorReportNotificationListener {

    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportNotifier errorReportNotifier;

    /**
     * 新規エラーレポート記録イベントを受け取り、Slack と SYSTEM_ADMIN へ配信する。
     *
     * @param event 新規記録イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "本番障害の一次検知そのものであり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと CRITICAL/HIGH の障害が誰にも通知されないまま埋もれる。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErrorReportRaised(ErrorReportRaisedEvent event) {
        ErrorReportEntity report = load(event.reportId(), "新規記録");
        if (report == null) {
            return;
        }
        // Slack と SYSTEM_ADMIN プッシュは独立した配送先であり、片方の失敗を他方へ波及させない
        // （被害半径の分離）。まとめて try で括ると Slack の失敗が SYSTEM_ADMIN 通知を巻き添えにする。
        if (event.slackEnabled()) {
            try {
                errorReportNotifier.notifySlack(report);
            } catch (Exception e) {
                log.error("エラーレポート新規記録の Slack 通知に失敗しました: reportId={}", event.reportId(), e);
            }
        }
        try {
            errorReportNotifier.notifySystemAdmins(report);
        } catch (Exception e) {
            log.error("エラーレポート新規記録の SYSTEM_ADMIN 通知に失敗しました: reportId={}", event.reportId(), e);
        }
    }

    /**
     * 重要度昇格イベントを受け取り、Slack と SYSTEM_ADMIN へ配信する。
     *
     * @param event 昇格イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "障害の重要度が上がったことの通知であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと悪化に気づけない。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSeverityEscalated(ErrorReportSeverityEscalatedEvent event) {
        ErrorReportEntity report = load(event.reportId(), "重要度昇格");
        if (report == null) {
            return;
        }
        try {
            errorReportNotifier.notifyEscalation(report, event.oldSeverity(), event.newSeverity());
        } catch (Exception e) {
            log.error("エラーレポート昇格通知の配送に失敗しました: reportId={}", event.reportId(), e);
        }
    }

    /**
     * リグレッション検知イベントを受け取り、Slack と SYSTEM_ADMIN へ配信する。
     *
     * @param event リグレッション検知イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "解決済みとした障害の再発通知であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと解決済みのまま放置される。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRegressionDetected(ErrorReportRegressionDetectedEvent event) {
        ErrorReportEntity report = load(event.reportId(), "リグレッション");
        if (report == null) {
            return;
        }
        try {
            errorReportNotifier.notifyRegression(report);
        } catch (Exception e) {
            log.error("エラーレポート再発通知の配送に失敗しました: reportId={}", event.reportId(), e);
        }
    }

    /**
     * 解決イベントを受け取り、報告者へ解決通知を配信する。
     *
     * @param event 解決イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "不具合を報告した利用者への完了連絡であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと報告が黙殺されたように見える。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErrorReportResolved(ErrorReportResolvedEvent event) {
        ErrorReportEntity report = load(event.reportId(), "解決");
        if (report == null) {
            return;
        }
        try {
            errorReportNotifier.notifyResolution(report);
        } catch (Exception e) {
            log.error("エラーレポート解決通知の配送に失敗しました: reportId={}", event.reportId(), e);
        }
    }

    /**
     * 担当者割り当てイベントを受け取り、担当者へ通知を配信する。
     *
     * @param event 担当者割り当てイベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "担当者本人への割り当て連絡であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすと担当者は自分に割り当てられたことに気づけない。イベントは再生されない")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onErrorReportAssigned(ErrorReportAssignedEvent event) {
        if (event.assigneeId() == null) {
            return;
        }
        ErrorReportEntity report = load(event.reportId(), "担当者割り当て");
        if (report == null) {
            return;
        }
        try {
            errorReportNotifier.notifyAssignment(report, event.assigneeId());
        } catch (Exception e) {
            log.error("エラーレポート担当者割り当て通知の配送に失敗しました: reportId={}, assigneeId={}",
                    event.reportId(), event.assigneeId(), e);
        }
    }

    /**
     * コミット済みのエラーレポート行を読み直す。
     *
     * <p>読み直しに失敗した場合（イベント発行直後に物理削除された等）は本文も受信者も
     * 組み立てられないため、配送を中止して WARN を残す。</p>
     *
     * @param reportId 読み直す {@code error_reports.id}
     * @param label    ログ用の通知種別ラベル
     * @return エラーレポート。見つからなければ {@code null}
     */
    private ErrorReportEntity load(Long reportId, String label) {
        if (reportId == null) {
            log.warn("エラーレポート通知のイベントに reportId が無いため配送を中止: 種別={}", label);
            return null;
        }
        ErrorReportEntity report = errorReportRepository.findById(reportId).orElse(null);
        if (report == null) {
            log.warn("エラーレポート通知の読み直しで対象が見つからないため配送を中止: reportId={}, 種別={}",
                    reportId, label);
        }
        return report;
    }
}
