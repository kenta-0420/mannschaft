package com.mannschaft.app.survey.listener;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.event.SurveyReminderNotificationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * アンケートの督促・締切延長通知の配送リスナー（Issue #2997 / CMP-260827-1152 G5）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>{@code SurveyRemindService#remind} / {@code SurveyService#extendDeadline} は業務トランザクションの内側で
 * {@code NotificationHelper#notifyAllPreAuthorizedLocalized} を呼んでいた。この経路のチャンク INSERT は
 * {@code REQUIRES_NEW} で<b>業務コミットの前に確定</b>するため、業務TXがロールバックしても通知だけが残った
 * （督促カウンタ・締切延長が巻き戻っても受信者には届いてしまう）。</p>
 *
 * <h2>是正後</h2>
 * <p>業務TXは受信者を確定して {@link SurveyReminderNotificationEvent} を publish するだけに留め、本リスナーが
 * {@code AFTER_COMMIT} + {@code @Async("event-pool")} で受け取って、公開通知
 * （{@link SurveyPublishNotificationListener}）と同じ事前認可バルク経路で配信する。locale 別・500 人チャンクごとの
 * 独立トランザクション分離は {@code NotificationHelper} が従来どおり担う。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SurveyReminderNotificationListener {

    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "アンケートの督促・締切延長通知は対応する gate_key が無く停止条件を宣言できない。"
                    + "落とすと受信者は督促・延長の事実を知らされず、イベントは再生されず取りこぼしは復旧不能であるため常時実行する")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSurveyReminderNotification(SurveyReminderNotificationEvent event) {
        List<Long> recipients = event.recipientUserIds();
        if (event.surveyId() == null || recipients.isEmpty()) {
            return;
        }
        try {
            NotificationScopeType notifScope = "TEAM".equals(event.scopeType())
                    ? NotificationScopeType.TEAM
                    : NotificationScopeType.ORGANIZATION;
            notificationHelper.notifyAllPreAuthorizedLocalized(
                    recipients,
                    SurveyNotificationType.SURVEY_RESPONSE_REMINDER.name(),
                    "SURVEY",
                    event.surveyId(),
                    notifScope,
                    event.scopeId(),
                    "/surveys/" + event.surveyId(),
                    event.actorId(),
                    (userId, locale) -> buildMessage(event, locale));
        } catch (Exception e) {
            // 非同期イベント失敗の監査記録。catch は業務TX外なので rollback で消えない。
            log.error("アンケート通知の配送に失敗しました: kind={}, surveyId={}, recipientCount={}",
                    event.kind(), event.surveyId(), recipients.size(), e);
        }
    }

    private NotificationHelper.LocalizedMessage buildMessage(SurveyReminderNotificationEvent event, Locale locale) {
        String title = event.title();
        if (event.kind() == SurveyReminderNotificationEvent.Kind.DEADLINE_EXTENDED) {
            // AC-7: newDeadline の表記もパターンを locale 鍵に持たせてロケール化する（時刻の値自体は変えない）。
            String deadlinePattern = messageSource.getMessage(
                    "notification.survey.deadlineExtended.deadlinePattern", null,
                    "yyyy年M月d日 HH:mm", locale);
            // 元データは JVM 既定ゾーンの壁時計（surveys.expires_at）。同じゾーンへ戻すので表記は従来と同一。
            String formattedDeadline = LocalDateTime.ofInstant(event.newDeadline(), com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser.SERVER_ZONE)
                    .format(DateTimeFormatter.ofPattern(deadlinePattern, locale));
            return new NotificationHelper.LocalizedMessage(
                    messageSource.getMessage(
                            "notification.survey.deadlineExtended.title", null,
                            "アンケート締切が延長されました", locale),
                    messageSource.getMessage(
                            "notification.survey.deadlineExtended.body",
                            new Object[]{title, formattedDeadline},
                            "「" + title + "」の回答締切が " + formattedDeadline + " に延長されました。",
                            locale));
        }
        return new NotificationHelper.LocalizedMessage(
                messageSource.getMessage(
                        "notification.survey.remind.title", null,
                        "アンケート未回答のお知らせ", locale),
                messageSource.getMessage(
                        "notification.survey.remind.body",
                        new Object[]{title},
                        "「" + title + "」が未回答です。回答にご協力ください。",
                        locale));
    }
}
