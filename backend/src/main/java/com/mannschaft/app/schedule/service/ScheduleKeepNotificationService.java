package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.membership.fanout.ScheduleKeepTeamFanoutRecipientSource;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.service.NotificationService;
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
 * <h2>本クラスは業務コミット後に呼ばれる（Issue #2990 L8 で是正）</h2>
 * <p>本クラス自身に {@code @Transactional} は付けない。是正前は
 * {@code ScheduleKeepService#convert} の業務TXの内側から同期で呼ばれており、
 * 「宛先判定は未コミットのキープを読む必要があるので外側 TX の中で行い、永続化だけを
 * {@code REQUIRES_NEW} の publisher へ逃がす」という構成だった。しかし fan-out の
 * {@link NotificationFanoutJobService#enqueue} は外側 TX に残っており、その INSERT が落ちると
 * rollback-only が立って<b>変換ごと巻き戻っていた</b>（呼び出し側の catch は
 * 「変換自体は成立」と嘘のログを残す）。</p>
 *
 * <p>是正後は {@link ScheduleKeepConvertedNotificationListener}（{@code AFTER_COMMIT} +
 * {@code @Async("event-pool")}）が唯一の呼び出し元である。キープも予定も既にコミット済みのため
 * 「未コミットで見えない」という制約が消え、{@code REQUIRES_NEW} の publisher は不要になった
 * （役目を終えたので削除した）。ここで開かれるトランザクションは
 * {@code createNotificationPreAuthorized} / {@code enqueue} それぞれの内側だけであり、
 * 巻き添えにする業務トランザクションはもう存在しない。</p>
 *
 * <p><b>直送と fan-out は互いに巻き添えにしない</b>: 直送の失敗で fan-out を落とさないよう、
 * 2 つのステップはそれぞれ独立に例外を捕捉してログに残す（握りつぶしではなく ERROR 記録）。</p>
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

    private final NotificationService notificationService;
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
            // 個人スコープは自分しかいない。fan-out も直送も出さない（§6.1・AC-5）。
            return;
        }

        String title = "「" + keep.getTitle() + "」の日程が決まりました";
        String body = keep.getTitle() + " が予定になりました。カレンダーで確認できます。";
        String actionUrl = actionUrlFor(scope, schedule);
        Long creatorId = keep.getCreatedBy();

        // ① 作成者必達（直送）。変換は MEMBER 全員に開放されており「言い出しっぺの知らぬ間に予定化」しうるため、
        //    作成者への通知は §2.1.1 の代償として必須（§6.1）。ただし次のいずれかでは送らない:
        //    - 作成者自身が操作者（自分の操作を自分に通知しない）
        //    - created_by が NULL＝匿名化済み（届け先が存在しない）
        //    - 作成者にキープの閲覧権が無い（降格・脱退。SCHEDULE_KEEP=MEMBERS_AND_ABOVE で判定。
        //      通らなければタイトルを漏らさない・§6.1・AC-4）
        //    直送は fan-out enqueue より前に行う。enqueue が失敗しても作成者は受領済みになる（best-effort・AC-9）。
        if (creatorId != null && !Objects.equals(creatorId, actorUserId)
                && contentVisibilityChecker.canViewUuid(ReferenceType.SCHEDULE_KEEP, keep.getId(), creatorId)) {
            // 可視性は上で ReferenceType.SCHEDULE_KEEP により判定済みのため、NotificationService 内蔵の
            // F00 ガード（sourceType="SCHEDULE" → SCOPE_AFFILIATED で SUPPORTER が通る）は使わない。
            try {
                notificationService.createNotificationPreAuthorized(
                        creatorId,
                        NOTIFICATION_TYPE_CONVERTED,
                        NotificationPriority.NORMAL,
                        title,
                        body,
                        SOURCE_TYPE_SCHEDULE,
                        schedule.getId(),
                        notificationScopeTypeOf(scope),
                        scope.id(),
                        actionUrl,
                        actorUserId);
            } catch (Exception e) {
                // 直送の失敗で TEAM 全員への fan-out まで落とさない（AC-9 の best-effort 契約）。
                log.error("キープ変換通知（作成者への直送）に失敗しました: keepId={}, creatorId={}",
                        keep.getId(), creatorId, e);
            }
        } else if (creatorId != null && !Objects.equals(creatorId, actorUserId)) {
            log.debug("キープ作成者に閲覧権が無いため作成者への変換通知（直送）は発行しません: keepId={}, creatorId={}",
                    keep.getId(), creatorId);
        }

        // ② TEAM スコープは MEMBER 以上 全員（操作者・作成者を除く）へ CMP-001 の耐久 fan-out で配信する
        //    （§6.1・AC-1）。母集団の SUPPORTER/GUEST 除外・keyset 供給は受信者ソースへ委ねる。
        //    enqueue は母集団を数えず親ジョブ 1 行を INSERT する O(1)（AC-8）。冪等キーはキープ ID。
        if (scope.type() == ScheduleKeepScopeType.TEAM) {
            long excludedCreator = creatorId == null ? 0L : creatorId;
            String scopeRef = scope.id() + ":" + actorUserId + ":" + excludedCreator;
            // Issue #2871: fan-out 経路は描画済み文字列ではなく「文面種別＋利用者が書いた中身」を運ぶ。
            // 引数はキープ（予定）のタイトル 1 つで、title/body 双方の枠に同じものを差し込む。
            // ※ 上の作成者向け直送は受信者が 1 名に確定しているため従来どおり
            //    ここで組み立てた日本語の title/body をそのまま使う（fan-out とは別経路）。
            scheduleKeepFanoutJobService.enqueue(
                    ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE,
                    scopeRef,
                    NOTIFICATION_TYPE_CONVERTED,
                    keep.getId(),
                    null,
                    com.mannschaft.app.notification.fanout.FanoutMessageKind.SCHEDULE_KEEP_CONVERTED,
                    new String[]{keep.getTitle()},   // 利用者が書いたキープ名（翻訳しない）
                    NotificationPriority.NORMAL,
                    SOURCE_TYPE_SCHEDULE,
                    schedule.getId(),
                    actionUrl,
                    actorUserId);
        }
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
