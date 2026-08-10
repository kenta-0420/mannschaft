package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * キープ変換通知の<b>永続化だけ</b>を独立トランザクションで行う publisher（F03.17 §6.2.1）。
 *
 * <p><b>なぜ独立した Bean か</b>: {@code REQUIRES_NEW} は Spring の AOP プロキシ越しでなければ
 * 効かない。{@link ScheduleKeepNotificationService} 内の自己呼び出しにすると伝播指定が
 * 素通りし、「別 TX にしたつもりで同一 TX のまま」という最も気づきにくい退行になる。</p>
 *
 * <h2>なぜ独立トランザクションが必要か（同一 TX では変換ごと失われる）</h2>
 * <p>{@link NotificationService#createNotification} は伝播指定なし＝{@code REQUIRED} であり、
 * 同一 TX に参加する。この経路で永続化例外が起きると Spring/Hibernate は<b>その TX を
 * rollback-only にマークする</b>。呼び出し側が例外を catch して WARN に落としても TX の汚染は
 * 残り、変換処理から戻った直後のコミットが {@code UnexpectedRollbackException} で 500 になる。
 * 結果として<b>通知の失敗が変換そのものを巻き戻す</b>——「通知は best-effort、変換は成立させる」
 * という §6.2 の契約が、try/catch では守れていなかった。</p>
 *
 * <p>{@code REQUIRES_NEW} なら内側 TX だけが独立してロールバックし、rollback-only は外側へ
 * 伝播しない。ここで初めて呼び出し側の try/catch が意味を持つ。</p>
 *
 * <h2>内側 TX からは外側の未コミットデータが見えない</h2>
 * <p>そのため本 publisher は<b>一切再検索をしない</b>。通知本文・遷移先・宛先はすべて呼び出し側が
 * 確定させた値を引数で受け取る。同じ理由で {@link NotificationService#createNotification} の
 * F00 ガード（{@code sourceType} から予定を SQL で引く）も使えない——変換先の予定行は外側 TX で
 * 未コミットのため内側からは<b>存在しない</b>ように見え、通知が黙って捨てられる。
 * よって可視性判定は呼び出し側（外側 TX）で {@code ReferenceType.SCHEDULE_KEEP} に対して
 * 済ませ、ここでは {@link NotificationService#createNotificationPreAuthorized} を使う。</p>
 *
 * <p>設計: {@code docs/features/F03.17_schedule_keep.md} §6.2.1</p>
 */
@Service
@RequiredArgsConstructor
public class ScheduleKeepNotificationPublisher {

    private final NotificationService notificationService;

    /**
     * 通知を独立トランザクションで永続化する。
     *
     * <p>引数はすべて呼び出し側（外側 TX）で確定済みの値であること。ここで DB を引き直すと
     * 外側の未コミット分が見えず、誤った本文・取りこぼしを生む。</p>
     *
     * @param recipientUserId 宛先（キープ作成者）
     * @param notificationType 通知種別
     * @param title           通知タイトル
     * @param body            通知本文
     * @param sourceType      ソース種別
     * @param sourceId        ソース ID（変換先 {@code schedules.id}）
     * @param scopeType       通知スコープ種別
     * @param scopeId         通知スコープ ID
     * @param actionUrl       遷移先
     * @param actorUserId     変換操作者
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishConverted(Long recipientUserId, String notificationType,
                                  String title, String body,
                                  String sourceType, Long sourceId,
                                  NotificationScopeType scopeType, Long scopeId,
                                  String actionUrl, Long actorUserId) {
        notificationService.createNotificationPreAuthorized(
                recipientUserId,
                notificationType,
                NotificationPriority.NORMAL,
                title,
                body,
                sourceType,
                sourceId,
                scopeType,
                scopeId,
                actionUrl,
                actorUserId);
    }
}
