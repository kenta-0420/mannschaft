package com.mannschaft.app.errorreport.event;

/**
 * エラーレポートが新規に記録されたことを表す業務イベント（Issue #2990 L11）。
 *
 * <p>{@code ErrorReportService#createOrAggregate} および
 * {@code ErrorReportAsyncExecutor#doRecordBackendException} の業務トランザクションの内側で
 * publish し、{@code ErrorReportNotificationListener} が {@code AFTER_COMMIT} で受け取る。</p>
 *
 * <h2>是正前は何が起きていたか</h2>
 * <p>是正前は同じ {@code @Transactional} の内側で {@code errorReportNotifier.notifySlack(saved)} /
 * {@code notifySystemAdmins(saved)} を try 無しで呼んでいた（{@code TX_NOTIFY_BARE}）。
 * 呼び先が {@code @Async("event-pool")} であるため<b>業務が巻き戻ることはない</b>が、
 * 非同期スレッドは commit を待たずに走り出す。つまり</p>
 * <ul>
 *   <li>業務トランザクションが後でロールバックしても Slack 通知と SYSTEM_ADMIN 通知だけが残る
 *       （存在しない {@code error_reports.id} を指すリンク付きで届く）</li>
 *   <li>渡していたのは<b>管理下の JPA エンティティそのもの</b>であり、別スレッドから
 *       未コミットの状態を読んでいた</li>
 * </ul>
 * <p>是正後はイベントに ID と種別だけを載せ、配送側がコミット済みの行を読み直す。</p>
 *
 * @param reportId     記録された {@code error_reports.id}
 * @param slackEnabled Slack 即時通知を行うか（集約バッファが抑制した場合は false）
 */
public record ErrorReportRaisedEvent(Long reportId, boolean slackEnabled) {
}
