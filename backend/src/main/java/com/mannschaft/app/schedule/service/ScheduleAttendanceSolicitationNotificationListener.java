package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.AttendanceSolicitationOpenedEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * 出欠募集の通知配送リスナー（機能55 / Issue #2990 L8 ROLLBACK_COUPLED 是正）。
 *
 * <h2>是正前の欠陥 — 何が巻き戻っていたか</h2>
 * <p>是正前は {@link ScheduleAttendanceService#openAttendanceSolicitation} の
 * {@code @Transactional} の内側で、受信者ごとに
 * {@code notificationService.createNotificationPreAuthorized} + {@code dispatch} を
 * <b>try 無しで</b>呼んでいた（{@code TX_NOTIFY_BARE}）。{@code createNotificationPreAuthorized} は
 * 既定の {@code REQUIRED} 伝播で業務トランザクションにそのまま参加するため、通知の INSERT が
 * 1 件でも DB 例外で落ちると<b>業務側ごとロールバックする</b>。失われるのは:</p>
 * <ul>
 *   <li>{@code generateAttendanceRecords} が一括生成した {@code schedule_attendances} の全行
 *       （＝誰にも出欠を訊けない状態に戻る）</li>
 *   <li>予約出欠募集（{@code payload_json}）で利用者が指定した回答締切・コメント要否・
 *       最低応答ロールの適用（{@code schedules} への save）</li>
 * </ul>
 * <p>しかも即時経路の呼び出し元 {@code ScheduleAttendanceSolicitationEventListener} は
 * {@code AFTER_COMMIT} + {@code @Async} のイベントリスナーであり<b>再試行されない</b>。
 * つまり「予定は作られたのに出欠募集だけが黙って行われない」状態が残り、次に誰かが
 * 手で募集し直すまで気づけない。</p>
 *
 * <h2>是正後</h2>
 * <p>業務トランザクションの内側では {@link AttendanceSolicitationOpenedEvent}（予定 ID のみ）を
 * publish するだけに留め、本リスナーが {@code AFTER_COMMIT} + {@code @Async("event-pool")} で
 * 受け取って配送する。受信者はコミット済みの {@code schedule_attendances} から読み直すため、
 * 是正前と同一の集合になる。</p>
 *
 * <h2>{@code createNotificationPreAuthorized}（バルク経路）を維持する</h2>
 * <p>配送には {@link NotificationHelper#notifyAllPreAuthorized} を使い、
 * {@code NotificationDeliveryRunner#sendOne}（= {@code createNotification}・visibility 判定あり）
 * には<b>寄せない</b>。受信者は配信母集団（ORG={@code resolveOrgDistributionUserIds} の
 * includeSupporters トグル準拠 / TEAM={@code findUserIdsByScope}）として既に事前認可済みであり、
 * ここで canView を二重に通すと組織スケジュールの出欠募集が配下チームの直属一般メンバーへ
 * 誤 deny で届かなくなる（(B) レグの再発）。数万規模の組織配信をチャンク単位のバルク INSERT で
 * 捌く性能上の理由も同じ経路を選ぶ根拠である。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleAttendanceSolicitationNotificationListener {

    /** 出欠募集通知の通知種別（是正前の {@code ScheduleAttendanceService} の定数と同一）。 */
    static final String NOTIFICATION_TYPE = "SCHEDULE_ATTENDANCE_REQUEST";

    /** 通知ソース種別（visibility ガードのソース判定にも使用）。 */
    private static final String SOURCE_TYPE = "SCHEDULE";

    private final NotificationHelper notificationHelper;
    private final ScheduleRepository scheduleRepository;
    private final ScheduleAttendanceRepository attendanceRepository;

    /**
     * 出欠募集開始イベントを受け取り、対象メンバーへ募集通知（IN_APP + PUSH）を配信する。
     *
     * @param event 出欠募集開始イベント
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "出欠募集はスケジュール（CORE）の一部であり棚卸し台帳に停止用の gate_key を持たない。"
                    + "落とすとメンバーは出欠を訊かれたことに気づけず、予定当日まで回答が集まらない。"
                    + "イベントは再生されないため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAttendanceSolicitationOpened(AttendanceSolicitationOpenedEvent event) {
        if (event.scheduleId() == null) {
            return;
        }
        Long scheduleId = event.scheduleId();
        try {
            ScheduleEntity schedule = scheduleRepository.findById(scheduleId).orElse(null);
            if (schedule == null) {
                // 募集直後に予定が削除された等。通知先も本文も無いので配送を中止する。
                log.warn("出欠募集通知の読み直しで予定が見つからないため配送を中止: scheduleId={}", scheduleId);
                return;
            }

            List<Long> recipientUserIds = attendanceRepository
                    .findByScheduleIdOrderByUserIdAsc(scheduleId).stream()
                    .map(ScheduleAttendanceEntity::getUserId)
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
            if (recipientUserIds.isEmpty()) {
                log.info("出欠募集通知の対象者が0名のため配送しない: scheduleId={}", scheduleId);
                return;
            }

            NotificationScopeType notifScope = schedule.isOrganizationScope()
                    ? NotificationScopeType.ORGANIZATION : NotificationScopeType.TEAM;
            Long scopeId = schedule.isOrganizationScope()
                    ? schedule.getOrganizationId() : schedule.getTeamId();

            notificationHelper.notifyAllPreAuthorized(
                    recipientUserIds,
                    NOTIFICATION_TYPE,
                    NotificationPriority.NORMAL,
                    "出欠の回答をお願いします",
                    "「" + schedule.getTitle() + "」の出欠回答が募集されています。期日までに回答してください。",
                    SOURCE_TYPE,
                    scheduleId,
                    notifScope,
                    scopeId,
                    "/schedules/" + scheduleId,
                    schedule.getCreatedBy());

            log.info("出欠募集通知配信: scheduleId={}, scope={}:{}, 対象={}名",
                    scheduleId, notifScope, scopeId, recipientUserIds.size());
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録（規約上必須）。catch は業務TXの外なので rollback で消えない。
            log.error("出欠募集通知の配送に失敗しました: scheduleId={}", scheduleId, e);
        }
    }
}
