package com.mannschaft.app.shiftbudget.listener;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.service.RoleService;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetHourlyRateMissingMessages;
import com.mannschaft.app.shiftbudget.event.ShiftBudgetHourlyRateMissingEvent;
import com.mannschaft.app.shiftbudget.service.ShiftBudgetFailedEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F08.7 「時給未設定で消化記録できなかった」ことを予算管理者へ知らせる配送リスナー（CMP-260910-1555）。
 *
 * <h2>なぜこの通知が要るのか（是正前の欠陥）</h2>
 * <p>是正前の {@code ShiftBudgetConsumptionRecordListener} は時給が未登録のとき
 * {@code findEffectiveRate(...).orElse(BigDecimal.ZERO)} で<b>黙って 0 円を採用</b>し、
 * 単価 0 円の消化行を「成功」として記録していた。消化額はいつまでも 0 のままで、
 * 消化率が 0% なので 80/100/120% の閾値警告も永久に発火せず、画面にもログにも異常が出ない——
 * 予算管理が丸ごと無効なのに誰にも分からない状態だった。0 円を捏造せず記録をスキップし、
 * <b>この通知で是正を促すことが修正の本体</b>である。</p>
 *
 * <h2>なぜ Service ではなくリスナーなのか（番人 NotificationTransactionBoundaryGuardTest）</h2>
 * <p>当初は {@code ShiftBudgetHourlyRateMissingNotifier} という Service が
 * {@link NotificationDeliveryRunner#sendOne} を直接呼んでいたが、これは
 * CMP-056 / Issue #2990 の契約に反する（{@code DIRECT_RUNNER_CALL}）。配送は
 * <b>{@code AFTER_COMMIT} を構文として明示した入口</b>からのみ行う、というのが契約であり、
 * 「呼び出し元がたまたま AFTER_COMMIT リスナーである」ことは静的には保証されない。
 * よって金型 {@code ShiftBudgetThresholdAlertNotificationListener} と同じく、
 * 業務側は {@link ShiftBudgetHourlyRateMissingEvent} を publish するに留め、配送は本リスナーが行う。</p>
 *
 * <h2>{@code fallbackExecution = true} を付けている理由</h2>
 * <p>publish 元の消化記録 hook は既に {@code AFTER_COMMIT} + {@code @Async} で動いており、
 * 自分自身はトランザクションを開いていない。{@code @TransactionalEventListener} は
 * 既定ではトランザクションが無い publish を<b>黙って捨てる</b>ため、それでは通知が一度も出ない。
 * {@code fallbackExecution = true} なら「待つべきコミットが無い」場合に即時実行される
 * （先例: {@code ScheduleKeepAnonymizationEventListener}）。因果は失われない——
 * 本イベントが publish される時点で、待つべきシフト公開のコミットは既に完了している。</p>
 *
 * <h2>配送の作法</h2>
 * <p>受信者解決は外側で 1 回だけ行い、受信者ごとの {@link NotificationDeliveryRunner#sendOne}
 * （1 件ごと {@code REQUIRES_NEW}）で配送する。1 名の失敗で残りを諦めず、
 * 失敗した受信者だけを {@code NOTIFICATION_SEND} の failed event として再送経路へ載せる。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftBudgetHourlyRateMissingNotificationListener {

    private static final String NOTIFICATION_TYPE = "SHIFT_BUDGET_HOURLY_RATE_MISSING";

    /**
     * ソース種別。F00 visibility resolver の {@code ReferenceType} には対応させない
     * （対応しない sourceType は fail-soft で判定対象外として通過する）。
     * 受信者は当該組織の予算管理者に限定済みであり、コンテンツ本体への到達手段も含まないため。
     */
    private static final String SOURCE_TYPE = "SHIFT_BUDGET_HOURLY_RATE_MISSING";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final RoleService roleService;
    private final ShiftBudgetFailedEventService failedEventService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    /**
     * 時給未設定により消化記録をスキップしたことを予算管理者へ通知する。
     *
     * @param event 時給未設定イベント（ID と対象ユーザーのみ）
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "時給未登録は予算の消化記録が丸ごと欠落していることの唯一の知らせであり、"
                    + "棚卸し台帳に停止用の gate_key を持たない。落とすと是正前と同じ『静かな 0 円』へ戻る")
    @Async("event-pool")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHourlyRateMissing(ShiftBudgetHourlyRateMissingEvent event) {
        List<Long> missingUserIds = event.missingUserIds();
        if (missingUserIds == null || missingUserIds.isEmpty()) {
            return;
        }
        Long organizationId = event.organizationId();
        Long scheduleId = event.scheduleId();

        auditLogService.record(
                "SHIFT_BUDGET_HOURLY_RATE_MISSING",
                null, null,
                event.teamId(), organizationId,
                null, null, null,
                String.format("{\"shift_schedule_id\":%d,\"missing_user_ids\":%s,\"missing_count\":%d}",
                        scheduleId, missingUserIds, missingUserIds.size()));

        List<Long> recipientUserIds = resolveRecipients(organizationId);
        if (recipientUserIds.isEmpty()) {
            log.warn("F08.7 時給未設定警告: 受信ロール 0 名のため通知配送スキップ: "
                            + "orgId={}, teamId={}, scheduleId={}, missing={}",
                    organizationId, event.teamId(), scheduleId, missingUserIds.size());
            return;
        }

        String actionUrl = event.teamSlug() == null
                ? null : "/teams/" + event.teamSlug() + "/settings/hourly-rate";

        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(recipientUserIds);
        } catch (Exception e) {
            // locale の一括解決で総崩れした。1 名も配送できていないので全員を再送対象として記録する。
            log.error("F08.7 時給未設定警告: 受信者 locale の一括解決に失敗（全員を再送対象として記録）: "
                            + "orgId={}, scheduleId={}, recipients={}",
                    organizationId, scheduleId, recipientUserIds.size(), e);
            recordFailure(organizationId, scheduleId, actionUrl, recipientUserIds,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        List<Long> failedUserIds = new ArrayList<>();
        String lastError = null;
        for (Long userId : recipientUserIds) {
            try {
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(userId, "ja"));
                notificationDeliveryRunner.sendOne(new NotificationDeliveryRequest(
                        userId,
                        NOTIFICATION_TYPE,
                        NotificationPriority.HIGH,
                        title(locale),
                        body(locale),
                        SOURCE_TYPE,
                        scheduleId,
                        NotificationScopeType.ORGANIZATION,
                        organizationId,
                        actionUrl,
                        null));  // システム自動発火、actor なし
            } catch (Exception e) {
                // 1 名の失敗で残りの受信者を諦めない（被害半径の分離）。
                failedUserIds.add(userId);
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.error("F08.7 時給未設定警告の配送に失敗（当該受信者のみスキップして継続）: "
                        + "userId={}, orgId={}, scheduleId={}", userId, organizationId, scheduleId, e);
            }
        }

        if (!failedUserIds.isEmpty()) {
            // 失敗した受信者だけを記録する（成功済みを混ぜると再送で重複配信になる）。
            recordFailure(organizationId, scheduleId, actionUrl, failedUserIds, lastError);
            return;
        }
        log.warn("F08.7 時給未設定のため消化記録をスキップし予算管理者へ通知: "
                        + "orgId={}, teamId={}, scheduleId={}, missingUsers={}, recipients={}",
                organizationId, event.teamId(), scheduleId, missingUserIds.size(), recipientUserIds.size());
    }

    /**
     * 受信ロール解決: ADMIN/DEPUTY_ADMIN ∪ BUDGET_ADMIN 保有者。
     *
     * <p>閾値超過警告（{@code ThresholdAlertEvaluationService#resolveRecipients}）と同じ集合。
     * 「予算が壊れている」という同種の事象を同じ相手へ届けるため、受信者の定義を揃える。</p>
     *
     * <p>{@code role} ドメインの Repository を直接掴まず {@link RoleService} 経由で解決する
     * （CLAUDE.md ドメイン境界の原則 / 番人 {@code CrossDomainRepositoryDependencyArchTest} D-5）。</p>
     */
    private List<Long> resolveRecipients(Long organizationId) {
        Set<Long> uniq = new HashSet<>();
        uniq.addAll(roleService.getAdminUserIdsByOrganizationId(organizationId));
        uniq.addAll(roleService.getUserIdsByOrganizationIdAndPermissionName(
                organizationId, "BUDGET_ADMIN"));
        List<Long> sorted = new ArrayList<>(uniq);
        sorted.sort(Long::compareTo);
        return sorted;
    }

    /**
     * 配送に失敗した受信者を {@code NOTIFICATION_SEND} の failed event として記録する。
     *
     * <p>記録に載らなければ再送の仕組みは起動しないので、ここで失敗を握り潰してはならない。
     * 記録自体が失敗したらもう打つ手が無いので ERROR ログだけ残す。</p>
     */
    private void recordFailure(Long organizationId, Long scheduleId, String actionUrl,
                               List<Long> failedUserIds, String errorMessage) {
        try {
            failedEventService.recordFailure(
                    organizationId,
                    ShiftBudgetFailedEventType.NOTIFICATION_SEND,
                    scheduleId,
                    Map.ofEntries(
                            Map.entry("user_ids", failedUserIds),
                            Map.entry("type", NOTIFICATION_TYPE),
                            // 運用ログ・フォレンジック用の固定文言（再送は title_key / body_key から組み立て直す）
                            Map.entry("title", title(Locale.JAPANESE)),
                            Map.entry("body", body(Locale.JAPANESE)),
                            Map.entry("title_key", ShiftBudgetHourlyRateMissingMessages.TITLE_KEY),
                            Map.entry("body_key", ShiftBudgetHourlyRateMissingMessages.BODY_KEY),
                            Map.entry("source_type", SOURCE_TYPE),
                            Map.entry("source_id", scheduleId),
                            Map.entry("scope_id", organizationId),
                            Map.entry("action_url", actionUrl == null ? "" : actionUrl)
                    ),
                    errorMessage
            );
        } catch (Exception recEx) {
            log.error("F08.7 時給未設定警告: NOTIFICATION_SEND failed_events 記録自体も失敗（諦め）: "
                    + "orgId={}, scheduleId={}, failedUserIds={}", organizationId, scheduleId,
                    failedUserIds, recEx);
        }
    }

    /** 通知件名（i18n）。 */
    private String title(Locale locale) {
        return messageSource.getMessage(
                ShiftBudgetHourlyRateMissingMessages.TITLE_KEY, null,
                ShiftBudgetHourlyRateMissingMessages.DEFAULT_TITLE, locale);
    }

    /** 通知本文（i18n）。 */
    private String body(Locale locale) {
        return messageSource.getMessage(
                ShiftBudgetHourlyRateMissingMessages.BODY_KEY, null,
                ShiftBudgetHourlyRateMissingMessages.DEFAULT_BODY, locale);
    }
}
