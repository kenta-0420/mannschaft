package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.schedule.ScheduleKeepScopeType;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * キープ変換時の通知発行（F03.17 §6）。
 *
 * <p><b>なぜ別 Bean か</b>: {@code ScheduleKeepService} から見て通知は notification ドメインへの
 * 越境であり、変換本体とは失敗の扱いが違う（通知が失敗しても変換は成立させる）。
 * 責務と例外の境界を型で分けておくと、呼び出し側の try/catch が「何を守っているか」で読める。</p>
 *
 * <h2>作成者への通知が必須である理由（§2.1.1 / §6.1）</h2>
 * <p>変換は MEMBER 全員に開放されている。裏を返すと<b>言い出しっぺの知らないうちに自分の
 * キープが予定になりうる</b>ということであり、作成者への通知はその代償として設計上必須である。
 * 通知を省くと「勝手に日程が決まっていた」体験になり、機能の信頼が崩れる。</p>
 *
 * <h2>本クラスは呼び出し側の TX に乗り、永続化だけを別 TX に逃がす（§6.2.1）</h2>
 * <p>本クラス自身に {@code @Transactional} は付けない。宛先の判定は<b>外側 TX の中で</b>
 * 行う必要があるからである（変換中のキープ・予定は未コミットで、独立 TX からは見えない）。
 * 一方、rollback-only 汚染を外側へ持ち込みうる<b>永続化だけ</b>は
 * {@link ScheduleKeepNotificationPublisher}（{@code REQUIRES_NEW}）へ委ねる。</p>
 *
 * <h2>届け先として無効な作成者はスキップする（§6.1）</h2>
 * <p>作成者が退会済み・スコープを脱退済み・SUPPORTER へ降格して<b>キープ自体が見えなくなっている</b>
 * 場合は通知しない。§4.6.2 で応援者に不可視としている以上、降格後に通知だけ届くのは認可上も矛盾し、
 * さらに通知本文はキープのタイトルを含むため、<b>キープ本体では 404 で秘匿しているタイトルが
 * 通知経由で漏れる</b>。</p>
 *
 * <p>判定は自前の述語を書かず F00 の {@link ContentVisibilityChecker} に委ねるが、
 * <b>{@code ReferenceType.SCHEDULE_KEEP} で明示的に</b>行う。
 * {@link com.mannschaft.app.notification.service.NotificationService} 内蔵のガードに任せると
 * {@code sourceType="SCHEDULE"} → {@code MEMBERS_ONLY} → {@code SCOPE_AFFILIATED}
 * （応援者を含む直接所属軸）へ写像され、<b>SUPPORTER が通過してしまう</b>
 * （{@code docs/task-list.md} CMP-017b の既存欠陥）。キープの正準は
 * {@code ScheduleKeepVisibilityResolver}（{@code MEMBERS_AND_ABOVE}）である。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleKeepNotificationService {

    /** §6.2: 核 {@code NotificationType} enum は改変せず、schedule ドメイン独自の文字列種別を使う。 */
    private static final String NOTIFICATION_TYPE_CONVERTED = "SCHEDULE_KEEP_CONVERTED";

    /** 変換先の予定を指す（遷移先・出所の記録用。可視性判定には使わない）。 */
    private static final String SOURCE_TYPE_SCHEDULE = "SCHEDULE";

    private final ScheduleKeepNotificationPublisher scheduleKeepNotificationPublisher;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final TeamService teamService;
    /** CMP-017c: TEAM スコープ MEMBER 以上 全員への耐久 fan-out 配信の enqueue 口（出陣で結線）。 */
    private final NotificationFanoutJobService scheduleKeepFanoutJobService;

    /**
     * キープ作成者へ「日程が決まった」通知を送る。
     *
     * <p>操作者自身が作成者だった場合は送らない（自分の操作を自分に通知しない）。
     * 個人スコープのキープも送らない（自分しかいない・§6.1）。
     * キープが作成者から見えなくなっている場合も送らない（上記クラス Javadoc）。</p>
     *
     * <p><b>呼び出し側の TX で実行されること</b>を前提とする（未コミットのキープ状態を
     * 可視性判定が読む必要はないが、キープ行そのものは外側 TX の文脈で引く）。</p>
     *
     * @param scope         キープのスコープ
     * @param keep          変換後のキープ
     * @param schedule      変換で生成された予定
     * @param actorUserId   変換操作者
     */
    public void notifyConverted(ScheduleKeepScope scope, ScheduleKeepEntity keep,
                                 ScheduleEntity schedule, Long actorUserId) {
        if (scope.type() == ScheduleKeepScopeType.PERSONAL) {
            return;
        }
        Long creatorId = keep.getCreatedBy();
        if (creatorId == null || Objects.equals(creatorId, actorUserId)) {
            // created_by が NULL＝匿名化済み。届け先が存在しないのでスキップする（§6.1）。
            return;
        }

        // キープ側の正準（SCHEDULE_KEEP = MEMBERS_AND_ABOVE）で明示的に判定する。
        // 通らなければ通知を作らない＝タイトルを漏らさない。
        if (!contentVisibilityChecker.canViewUuid(ReferenceType.SCHEDULE_KEEP, keep.getId(), creatorId)) {
            log.debug("キープ作成者に閲覧権が無いため変換通知を発行しません: keepId={}, creatorId={}",
                    keep.getId(), creatorId);
            return;
        }

        String title = "「" + keep.getTitle() + "」の日程が決まりました";
        String body = keep.getTitle() + " が予定になりました。カレンダーで確認できます。";

        // 以降の値はすべてここで確定させる。publisher は別 TX のため再検索できない（§6.2.1）。
        scheduleKeepNotificationPublisher.publishConverted(
                creatorId,
                NOTIFICATION_TYPE_CONVERTED,
                title,
                body,
                SOURCE_TYPE_SCHEDULE,
                schedule.getId(),
                notificationScopeTypeOf(scope),
                scope.id(),
                actionUrlFor(scope, schedule),
                actorUserId);
    }

    private NotificationScopeType notificationScopeTypeOf(ScheduleKeepScope scope) {
        return switch (scope.type()) {
            case TEAM -> NotificationScopeType.TEAM;
            case ORGANIZATION -> NotificationScopeType.ORGANIZATION;
            case PERSONAL -> NotificationScopeType.PERSONAL;
        };
    }

    /** 変換通知の遷移先は<b>変換先の予定</b>（キープ一覧ではない・§6.2）。決まった日程をすぐ見せる。 */
    private String actionUrlFor(ScheduleKeepScope scope, ScheduleEntity schedule) {
        if (scope.type() == ScheduleKeepScopeType.TEAM) {
            String slug = teamService.getSlugById(scope.id());
            return "/teams/" + slug + "/schedules/" + schedule.getId();
        }
        return "/schedules/" + schedule.getId();
    }
}
