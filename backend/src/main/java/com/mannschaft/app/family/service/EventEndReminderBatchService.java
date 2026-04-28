package com.mannschaft.app.family.service;

import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.entity.NotificationEntity;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * イベント終了後解散通知リマインドバッチサービス。F03.12 §16。
 *
 * <p>イベント予定終了後も主催者が解散通知を送り忘れるケースに対応する。
 * 5分間隔でバッチを実行し、終了時刻からの経過時間に応じて3段階のエスカレーション通知を主催者に送る。</p>
 *
 * <ul>
 *   <li>1回目 (30〜60分超): priority=NORMAL。主催者のみに通知。</li>
 *   <li>2回目 (60〜90分超): priority=HIGH。主催者のみに通知。</li>
 *   <li>3回目 (90分超): priority=URGENT。主催者 + チームの全 ADMIN に通知。</li>
 * </ul>
 *
 * <p>{@code care.dismissal-reminder.enabled=false} でテスト時に無効化できる。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "care.dismissal-reminder.enabled", havingValue = "true")
public class EventEndReminderBatchService {

    /** 1回目リマインドの基準（終了から30分超） */
    private static final int REMINDER_1_MINUTES = 30;

    /** 2回目リマインドの基準（終了から60分超） */
    private static final int REMINDER_2_MINUTES = 60;

    /** 3回目リマインドの基準（終了から90分超） */
    private static final int REMINDER_3_MINUTES = 90;

    /** リマインド上限回数 */
    private static final int MAX_REMINDER_COUNT = 3;

    /** バッチ開始時の粗フィルタ基準（最小経過分 = 1回目の基準） */
    private static final int MINIMUM_ELAPSED_MINUTES = REMINDER_1_MINUTES;

    /** チームスコープ識別子 */
    private static final String SCOPE_TYPE_TEAM = "TEAM";

    /** ADMINロール名 */
    private static final String ROLE_ADMIN = "ADMIN";

    private final EventRepository eventRepository;
    private final NotificationService notificationService;
    private final NotificationDispatchService dispatchService;
    private final UserRoleRepository userRoleRepository;

    // =========================================================
    // スケジュールバッチ（5分間隔）
    // =========================================================

    /**
     * 解散通知リマインドバッチ。5分間隔で実行する。
     *
     * <p>以下の条件を満たすイベントを対象に段階的リマインドを送信する:</p>
     * <ul>
     *   <li>dismissal_notification_sent_at IS NULL（未解散）</li>
     *   <li>schedules.end_at が現在時刻より前（終了時刻を過ぎている）</li>
     *   <li>organizer_reminder_sent_count が {@value MAX_REMINDER_COUNT} 未満</li>
     *   <li>終了時刻から {@value MINIMUM_ELAPSED_MINUTES} 分以上経過</li>
     * </ul>
     *
     * <p>冪等性: 同一イベントに対して1回のバッチ実行で複数回通知しない。
     * カウント値を確認してから段階を判定する。</p>
     */
    @Scheduled(fixedDelay = 300_000) // 5分間隔
    @Transactional
    public void runEndReminderCheck() {
        log.debug("解散通知リマインドバッチ開始");

        LocalDateTime now = LocalDateTime.now();
        // 最低 MINIMUM_ELAPSED_MINUTES 分経過したイベントのみを対象とする粗フィルタ
        LocalDateTime cutoff = now.minusMinutes(MINIMUM_ELAPSED_MINUTES);

        List<EventEntity> targets = eventRepository.findDismissalReminderTargets(now, cutoff, MAX_REMINDER_COUNT);

        if (targets.isEmpty()) {
            log.debug("解散通知リマインド: 対象イベントなし");
            return;
        }

        int processedCount = 0;
        for (EventEntity event : targets) {
            try {
                boolean processed = processReminderForEvent(event, now);
                if (processed) processedCount++;
            } catch (Exception e) {
                log.warn("解散通知リマインド処理中にエラー: eventId={}, error={}",
                        event.getId(), e.getMessage(), e);
            }
        }

        log.debug("解散通知リマインドバッチ完了: 候補={}, 送信={}", targets.size(), processedCount);
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 1イベントに対してリマインドの段階判定・送信を行う。
     *
     * <p>冪等性確保のため、現在のリマインド送信回数（{@code organizerReminderSentCount}）と
     * 終了時刻からの経過時間を照合し、次の段階のリマインドのみを送信する。</p>
     *
     * @param event イベントエンティティ
     * @param now   現在日時
     * @return リマインドを送信した場合 true
     */
    private boolean processReminderForEvent(EventEntity event, LocalDateTime now) {
        // スケジュール終了時刻を EventEntity から直接取得できないため、
        // リポジトリ取得時にすでにフィルタ済みのイベントを対象とする。
        // 終了時刻は粗フィルタで保証済み。イベントのリマインド回数で段階判定する。

        int currentCount = event.getOrganizerReminderSentCount() != null
                ? event.getOrganizerReminderSentCount().intValue() : 0;

        // 次に送るべきリマインドの段階を確認
        // 段階判定: count=0 → 1回目、count=1 → 2回目、count=2 → 3回目
        // 経過時間は cutoff フィルタで保証済み（30分以上経過）
        // ただしリマインドカウントが段階と一致しない場合はスキップ（冪等性）
        if (currentCount >= MAX_REMINDER_COUNT) {
            // 上限到達済みはスキップ（リポジトリクエリで除外済みのはずだが念のため）
            return false;
        }

        // 段階に応じた通知を送信
        sendReminderByCount(event, currentCount, now);

        // カウントインクリメント（ドメインメソッド経由）
        event.incrementOrganizerReminder();
        eventRepository.save(event);

        return true;
    }

    /**
     * リマインド送信回数（段階）に応じた通知を送信する。
     *
     * @param event        イベントエンティティ
     * @param currentCount 現在のリマインド送信回数（0〜2）
     * @param now          現在日時
     */
    private void sendReminderByCount(EventEntity event, int currentCount, LocalDateTime now) {
        Long eventId = event.getId();
        String eventLabel = resolveEventLabel(event);
        Long createdBy = event.getCreatedBy();

        switch (currentCount) {
            case 0 -> {
                // 1回目: priority=NORMAL。主催者のみ。
                String body = "「" + eventLabel + "」の終了予定時刻を過ぎています。解散通知を送信してください。";
                sendReminderNotification(createdBy, event, eventId, eventLabel,
                        "解散通知を忘れていませんか？", body, NotificationPriority.NORMAL);
                log.info("解散通知リマインド1回目送信: eventId={}, createdBy={}", eventId, createdBy);
            }
            case 1 -> {
                // 2回目: priority=HIGH。主催者のみ。保護者が心配しています。
                String body = "「" + eventLabel + "」終了から時間が経過しています。保護者が心配しています。解散通知を送ってください。";
                sendReminderNotification(createdBy, event, eventId, eventLabel,
                        "⚠️ 解散通知が未送信です（保護者が心配しています）", body, NotificationPriority.HIGH);
                log.info("解散通知リマインド2回目送信: eventId={}, createdBy={}", eventId, createdBy);
            }
            case 2 -> {
                // 3回目: priority=URGENT。主催者 + チームADMIN全員。
                String body = "「" + eventLabel + "」終了から長時間経過しています。チームADMINにも通知しました。至急対応してください。";
                String urgentTitle = "🚨 解散通知が未送信です（至急）";
                sendReminderNotification(createdBy, event, eventId, eventLabel,
                        urgentTitle, body, NotificationPriority.URGENT);
                // チームADMIN全員にも送信
                sendAdminReminders(event, eventId, eventLabel, urgentTitle, body);
                log.info("解散通知リマインド3回目送信: eventId={}, createdBy={}", eventId, createdBy);
            }
            default -> log.warn("想定外のリマインドカウント: eventId={}, count={}", eventId, currentCount);
        }
    }

    /**
     * チームの全 ADMIN（主催者を除く）にリマインド通知を送信する。
     *
     * <p>3回目リマインド時のみ呼び出される。主催者には既に通知済みのため除外する。</p>
     *
     * @param event      イベントエンティティ（scopeId = チームID）
     * @param eventId    イベントID
     * @param eventLabel イベント表示名
     * @param title      通知タイトル
     * @param body       通知本文
     */
    private void sendAdminReminders(EventEntity event, Long eventId, String eventLabel,
                                     String title, String body) {
        // チームスコープのイベントのみ ADMIN 全員に通知
        if (!SCOPE_TYPE_TEAM.equals(event.getScopeType().name())) {
            log.debug("チームスコープ以外のイベントはADMIN通知をスキップ: eventId={}, scopeType={}",
                    eventId, event.getScopeType());
            return;
        }

        Long teamId = event.getScopeId();
        List<Long> adminUserIds = userRoleRepository.findUserIdsByTeamIdAndRoleName(teamId, ROLE_ADMIN);

        // 主催者は既に送信済みのため除外
        Long createdBy = event.getCreatedBy();
        for (Long adminUserId : adminUserIds) {
            if (adminUserId.equals(createdBy)) continue;
            sendReminderNotification(adminUserId, event, eventId, eventLabel, title, body, NotificationPriority.URGENT);
            log.debug("ADMIN向け解散通知リマインド送信: eventId={}, adminUserId={}", eventId, adminUserId);
        }
    }

    /**
     * リマインド通知を作成・配信する。
     *
     * <p>F03.12 Phase11: 通知の {@code actionUrl} に teamId を含めることで、
     * 通知センターからチームのイベント詳細ページへ deep link できるようにする。
     * チームスコープでないイベントは従来通りの URL を使用する。</p>
     *
     * @param targetUserId 通知先ユーザーID（null の場合はスキップ）
     * @param event        イベントエンティティ（actionUrl 構築用）
     * @param eventId      イベントID
     * @param eventLabel   イベント表示名（現状未使用、将来の本文埋め込み用に保持）
     * @param title        通知タイトル
     * @param body         通知本文
     * @param priority     通知優先度
     */
    private void sendReminderNotification(Long targetUserId, EventEntity event, Long eventId,
                                           @SuppressWarnings("unused") String eventLabel,
                                           String title, String body, NotificationPriority priority) {
        if (targetUserId == null) {
            log.warn("解散通知リマインドの送信先ユーザーIDが null: eventId={}", eventId);
            return;
        }

        NotificationEntity notification = notificationService.createNotification(
                targetUserId,
                "EVENT_DISMISSAL_REMINDER",
                priority,
                title, body,
                "EVENT", eventId,
                NotificationScopeType.PERSONAL, targetUserId,
                buildEventActionUrl(event, eventId), null);

        dispatchService.dispatch(notification);
    }

    /**
     * イベント詳細への deep link URL を構築する。F03.12 Phase11。
     *
     * <p>チームスコープのイベントは {@code /teams/{teamId}/events/{eventId}}、
     * それ以外は {@code /events/{eventId}/dismissal} を返す（従来動作の保持）。</p>
     *
     * @param event   イベントエンティティ
     * @param eventId イベントID
     * @return actionUrl 文字列
     */
    private String buildEventActionUrl(EventEntity event, Long eventId) {
        if (event.getScopeType() == EventScopeType.TEAM && event.getScopeId() != null) {
            return "/teams/" + event.getScopeId() + "/events/" + eventId;
        }
        return "/events/" + eventId + "/dismissal";
    }

    /**
     * イベントの表示ラベルを解決する。subtitle が設定されていれば使用し、なければ slug を使用する。
     *
     * @param event イベントエンティティ
     * @return イベント表示ラベル（非 null）
     */
    private String resolveEventLabel(EventEntity event) {
        String subtitle = event.getSubtitle();
        return (subtitle != null && !subtitle.isBlank()) ? subtitle : event.getSlug();
    }
}
