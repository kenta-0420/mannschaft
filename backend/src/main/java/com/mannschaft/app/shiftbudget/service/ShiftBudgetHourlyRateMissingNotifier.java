package com.mannschaft.app.shiftbudget.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetHourlyRateMissingMessages;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * F08.7 「時給未設定で消化記録できなかった」ことを管理者へ知らせる通知サービス（CMP-260910-1555）。
 *
 * <h2>なぜこのクラスが要るのか（是正前の欠陥）</h2>
 * <p>是正前の {@code ShiftBudgetConsumptionRecordListener} は時給が未登録のとき
 * {@code findEffectiveRate(...).orElse(BigDecimal.ZERO)} で<b>黙って 0 円を採用</b>し、
 * 単価 0 円の消化行を「成功」として記録していた。結果として</p>
 * <ul>
 *   <li>消化額はいつまでも 0 のまま（{@code recorded=N, skipped=0} と成功ログが出る）</li>
 *   <li>消化率が 0% のままなので 80/100/120% の閾値警告も永久に発火しない</li>
 *   <li>画面にもログにも異常が出ないため、利用者は「予算機能が動いている」と誤認する</li>
 * </ul>
 * <p>この「静かな 0 円」は握りつぶしそのものであり、症状を隠して予算管理を丸ごと無効化していた。</p>
 *
 * <h2>是正方針</h2>
 * <p>時給が引けないときは 0 円を捏造せず<b>消化記録を行わない</b>（誤った金額を残すより、
 * 記録しないほうが台帳としては正しい。時給登録後に再公開すれば正しい金額で記録される）。
 * そのうえで本サービスが予算管理者へ通知を出し、監査ログを残す。
 * 「記録しない」だけでは是正前と同じく静かなままなので、<b>通知が是正の本体</b>である。</p>
 *
 * <h2>配送の作法</h2>
 * <p>呼び出し元は {@code AFTER_COMMIT} + {@code @Async("event-pool")} のリスナーであり、
 * 業務トランザクションの外にいる。受信者ごとの
 * {@link NotificationDeliveryRunner#sendOne}（1 件ごと {@code REQUIRES_NEW}）で配送し、
 * 失敗した受信者だけを {@code NOTIFICATION_SEND} の failed event として残して
 * リトライバッチ / 管理 API の再送経路へ載せる
 * （{@code ShiftBudgetThresholdAlertNotificationListener} と同一の金型）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShiftBudgetHourlyRateMissingNotifier {

    private static final String NOTIFICATION_TYPE = "SHIFT_BUDGET_HOURLY_RATE_MISSING";

    /**
     * ソース種別。F00 visibility resolver の {@code ReferenceType} には対応させない
     * （対応しない sourceType は fail-soft で判定対象外として通過する）。
     * 受信者は当該組織の予算管理者に限定済みであり、コンテンツ本体への到達手段も含まないため。
     */
    private static final String SOURCE_TYPE = "SHIFT_BUDGET_HOURLY_RATE_MISSING";

    private final NotificationDeliveryRunner notificationDeliveryRunner;
    private final UserLocaleCache userLocaleCache;
    private final UserRoleRepository userRoleRepository;
    private final ShiftBudgetFailedEventService failedEventService;
    private final AuditLogService auditLogService;
    private final MessageSource messageSource;

    /**
     * 時給未設定により消化記録をスキップしたことを予算管理者へ通知する。
     *
     * @param organizationId 組織ID
     * @param teamId         チームID
     * @param teamSlug       チームの slug（通知のアクション URL 用。{@code null} なら URL 無し）
     * @param scheduleId     対象のシフトスケジュールID
     * @param missingUserIds 時給が未設定だったユーザーID（重複なし）
     */
    public void notifyHourlyRateMissing(Long organizationId, Long teamId, String teamSlug,
                                        Long scheduleId, List<Long> missingUserIds) {
        if (missingUserIds == null || missingUserIds.isEmpty()) {
            return;
        }

        auditLogService.record(
                "SHIFT_BUDGET_HOURLY_RATE_MISSING",
                null, null,
                teamId, organizationId,
                null, null, null,
                String.format("{\"shift_schedule_id\":%d,\"missing_user_ids\":%s,\"missing_count\":%d}",
                        scheduleId, missingUserIds, missingUserIds.size()));

        List<Long> recipientUserIds = resolveRecipients(organizationId);
        if (recipientUserIds.isEmpty()) {
            log.warn("F08.7 時給未設定警告: 受信ロール 0 名のため通知配送スキップ: "
                            + "orgId={}, teamId={}, scheduleId={}, missing={}",
                    organizationId, teamId, scheduleId, missingUserIds.size());
            return;
        }

        String actionUrl = teamSlug == null ? null : "/teams/" + teamSlug + "/settings/hourly-rate";

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
                organizationId, teamId, scheduleId, missingUserIds.size(), recipientUserIds.size());
    }

    /**
     * 受信ロール解決: ADMIN/DEPUTY_ADMIN ∪ BUDGET_ADMIN 保有者。
     *
     * <p>閾値超過警告（{@code ThresholdAlertEvaluationService#resolveRecipients}）と同じ集合。
     * 「予算が壊れている」という同種の事象を同じ相手へ届けるため、受信者の定義を揃える。</p>
     */
    private List<Long> resolveRecipients(Long organizationId) {
        Set<Long> uniq = new HashSet<>();
        uniq.addAll(userRoleRepository.findAdminUserIdsByOrganizationId(organizationId));
        uniq.addAll(userRoleRepository.findUserIdsByOrganizationIdAndPermissionName(
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
