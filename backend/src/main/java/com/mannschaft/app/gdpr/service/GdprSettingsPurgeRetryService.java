package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.actionmemo.event.ActionMemoAnonymizationEventListener;
import com.mannschaft.app.appearance.event.AppearanceSettingsPurgeEventListener;
import com.mannschaft.app.auth.event.AuthAnonymizationEventListener;
import com.mannschaft.app.chat.event.ChatBookmarkPurgeEventListener;
import com.mannschaft.app.cms.event.UserBlogSettingsPurgeEventListener;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.contact.event.ContactRequestBlockPurgeEventListener;
import com.mannschaft.app.dashboard.event.DashboardSettingsPurgeEventListener;
import com.mannschaft.app.favorite.event.FavoriteAnonymizationEventListener;
import com.mannschaft.app.filesharing.event.SharedFileStarPurgeEventListener;
import com.mannschaft.app.gamification.event.GamificationSettingsPurgeEventListener;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.inbox.event.InboxAnonymizationEventListener;
import com.mannschaft.app.knowledgebase.event.KbPageFavoritePurgeEventListener;
import com.mannschaft.app.membership.event.ScopeMemberCalendarSettingAnonymizationEventListener;
import com.mannschaft.app.navsettings.event.NavSettingsPurgeEventListener;
import com.mannschaft.app.notification.event.NotificationAnonymizationEventListener;
import com.mannschaft.app.pointcard.event.PointCardAnonymizationEventListener;
import com.mannschaft.app.quickmemo.event.QuickMemoSettingsPurgeEventListener;
import com.mannschaft.app.reflection.event.ReflectionSettingsPurgeEventListener;
import com.mannschaft.app.schedule.listener.CalendarLayerCleanupExecutor;
import com.mannschaft.app.scopefolder.event.MyScopeFolderPurgeEventListener;
import com.mannschaft.app.seal.event.SealScopeDefaultsPurgeEventListener;
import com.mannschaft.app.search.event.SearchAnonymizationEventListener;
import com.mannschaft.app.timeline.event.TimelineBookmarkAnonymizationEventListener;
import com.mannschaft.app.timetable.notes.event.TimetableNoteFieldsPurgeEventListener;
import com.mannschaft.app.timetable.personal.event.PersonalTimetableSettingsPurgeEventListener;
import com.mannschaft.app.user.event.UserBlockPurgeEventListener;
import com.mannschaft.app.village.event.VillageUserCleanerEventListener;
import com.mannschaft.app.weather.event.WeatherLocationCleanupListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;

/**
 * 本人設定28ドメインの GDPR パージ手動 retry サービス。
 *
 * <p>システム管理者が管理画面から PENDING 状態のドメインパージを手動で再実行する機能を提供する。
 * 各ドメインリスナーの {@code retryPurge(userId)} を呼び出し、完了後に
 * {@code account_purge_completion_status} の retry_count / last_retried_at / status を更新する。</p>
 *
 * <p>本サービスはトランザクションを開始しない。owner の REQUIRES_NEW がコミットして
 * 戻った後、GDPR リポジトリ自身のトランザクションで結果を保存する。保存失敗は呼び出し元へ返す。</p>
 *
 * <h2>責務の分担</h2>
 * <ul>
 *   <li>各 {@code *PurgeEventListener#retryPurge(userId)} — ドメイン操作（completionStatus 更新なし）</li>
 *   <li>本サービス — completionStatus の retry_count / last_retried_at / status 更新を一元管理</li>
 * </ul>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase F</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprSettingsPurgeRetryService {

    private final AccountPurgeCompletionStatusRepository completionStatusRepository;
    private final Clock clock;

    private final ActionMemoAnonymizationEventListener actionMemoAnonymizationEventListener;
    private final PointCardAnonymizationEventListener pointCardAnonymizationEventListener;
    private final TimelineBookmarkAnonymizationEventListener timelineBookmarkAnonymizationEventListener;
    private final SearchAnonymizationEventListener searchAnonymizationEventListener;
    private final DashboardSettingsPurgeEventListener dashboardSettingsPurgeEventListener;
    private final MyScopeFolderPurgeEventListener myScopeFolderPurgeEventListener;
    private final QuickMemoSettingsPurgeEventListener quickMemoSettingsPurgeEventListener;
    private final AuthAnonymizationEventListener authAnonymizationEventListener;
    private final NotificationAnonymizationEventListener notificationAnonymizationEventListener;
    private final SharedFileStarPurgeEventListener sharedFileStarPurgeEventListener;
    private final ContactRequestBlockPurgeEventListener contactRequestBlockPurgeEventListener;
    private final UserBlockPurgeEventListener userBlockPurgeEventListener;
    private final AppearanceSettingsPurgeEventListener appearanceSettingsPurgeEventListener;
    private final NavSettingsPurgeEventListener navSettingsPurgeEventListener;
    private final GamificationSettingsPurgeEventListener gamificationSettingsPurgeEventListener;
    private final ReflectionSettingsPurgeEventListener reflectionSettingsPurgeEventListener;
    private final PersonalTimetableSettingsPurgeEventListener personalTimetableSettingsPurgeEventListener;
    private final UserBlogSettingsPurgeEventListener userBlogSettingsPurgeEventListener;
    private final ChatBookmarkPurgeEventListener chatBookmarkPurgeEventListener;
    private final KbPageFavoritePurgeEventListener kbPageFavoritePurgeEventListener;
    private final FavoriteAnonymizationEventListener favoriteAnonymizationEventListener;
    private final ScopeMemberCalendarSettingAnonymizationEventListener scopeMemberCalendarSettingAnonymizationEventListener;
    private final WeatherLocationCleanupListener weatherLocationCleanupListener;
    private final InboxAnonymizationEventListener inboxAnonymizationEventListener;
    private final TimetableNoteFieldsPurgeEventListener timetableNoteFieldsPurgeEventListener;
    private final SealScopeDefaultsPurgeEventListener sealScopeDefaultsPurgeEventListener;
    private final CalendarLayerCleanupExecutor calendarLayerCleanupExecutor;
    private final VillageUserCleanerEventListener villageUserCleanerEventListener;

    /** 受け付けるドメイン名の集合。不明なドメイン名は即時 IllegalArgumentException。 */
    private static final Set<String> VALID_DOMAINS =
            Set.of("actionmemo", "pointcard", "timeline", "search", "dashboard",
                    "scopefolder", "quickmemo", "auth", "notification", "filesharing",
                    "contact", "user", "appearance", "navsettings", "gamification",
                    "reflection", "timetable.personal", "cms", "chat", "knowledgebase",
                    "favorite", "membership", "weather", "inbox", "timetable.notes",
                    "seal", "schedule", "village.settings");

    /** 設定専用の retry 対象か判定する。 */
    public boolean supports(String domainName) {
        return VALID_DOMAINS.contains(domainName);
    }

    /**
     * 指定ユーザー × ドメインの GDPR パージを手動で retry する。
     *
     * <p>対象の completionStatus レコードが存在しない場合、または既に SUCCESS の場合は
     * ドメイン操作を実行せず即座に返す。retry 後は retry_count と last_retried_at を必ず更新し、
     * retry 成功時は status を SUCCESS に更新する。</p>
     *
     * @param userId     retry 対象ユーザー ID
     * @param domainName retry 対象の本人設定ドメイン
     * @return retry 結果
     * @throws IllegalArgumentException 不明なドメイン名、または対象レコードが存在しない場合
     */
    public RetryResultResponse retryDomainPurge(Long userId, String domainName) {
        if (!VALID_DOMAINS.contains(domainName)) {
            throw new IllegalArgumentException("不明なドメイン名: " + domainName);
        }

        AccountPurgeCompletionStatusEntity entity =
                completionStatusRepository.findByUserIdAndDomainName(userId, domainName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "対象レコードが見つかりません userId=" + userId + " domain=" + domainName));

        // 既に SUCCESS の場合はドメイン操作を実行せず即座に返す
        if ("SUCCESS".equals(entity.getStatus())) {
            return new RetryResultResponse(
                    true, domainName, "SUCCESS", entity.getRetryCount(), "既に処理済みです");
        }

        // ドメイン操作を実行（completionStatusRepository の更新はここでは行わない）
        boolean succeeded = executeSettingsRetry(userId, domainName);

        // retry_count / last_retried_at を必ず更新（成功・失敗いずれの場合も）
        entity.setRetryCount(entity.getRetryCount() + 1);
        // 発生した瞬間はInstantで取得し、既存JPAのアプリ層JST値へ保存境界で変換する。
        Instant retriedAt = clock.instant();
        entity.setLastRetriedAt(LocalDateTime.ofInstant(retriedAt, UserZoneLocalDateTimeParser.SERVER_ZONE));

        if (succeeded) {
            entity.setStatus("SUCCESS");
            Instant completedAt = clock.instant();
            entity.setCompletedAt(LocalDateTime.ofInstant(completedAt, UserZoneLocalDateTimeParser.SERVER_ZONE));
            log.info("GDPR パージ retry 成功: userId={} domain={} retryCount={}",
                    userId, domainName, entity.getRetryCount());
        } else {
            log.warn("GDPR パージ retry 失敗（PENDING 継続）: userId={} domain={} retryCount={}",
                    userId, domainName, entity.getRetryCount());
        }

        completionStatusRepository.save(entity);

        return new RetryResultResponse(
                succeeded,
                domainName,
                entity.getStatus(),
                entity.getRetryCount(),
                succeeded ? "retry 成功" : "retry 失敗（PENDING 継続）");
    }

    /** owner proxy のコミット失敗を非TXの呼び出し元で捕捉し、PENDINGを維持する。 */
    private boolean executeSettingsRetry(Long userId, String domainName) {
        try {
            return switch (domainName) {
                case "actionmemo" -> actionMemoAnonymizationEventListener.retryPurge(userId);
                case "pointcard" -> pointCardAnonymizationEventListener.retryPurge(userId);
                case "timeline" -> timelineBookmarkAnonymizationEventListener.retryPurge(userId);
                case "search" -> searchAnonymizationEventListener.retryPurge(userId);
                case "dashboard" -> dashboardSettingsPurgeEventListener.retryPurge(userId);
                case "scopefolder" -> myScopeFolderPurgeEventListener.retryPurge(userId);
                case "quickmemo" -> quickMemoSettingsPurgeEventListener.retryPurge(userId);
                case "auth" -> authAnonymizationEventListener.retryPurge(userId);
                case "notification" -> notificationAnonymizationEventListener.retryPurge(userId);
                case "filesharing" -> sharedFileStarPurgeEventListener.retryPurge(userId);
                case "contact" -> contactRequestBlockPurgeEventListener.retryPurge(userId);
                case "user" -> userBlockPurgeEventListener.retryPurge(userId);
                case "appearance" -> appearanceSettingsPurgeEventListener.retryPurge(userId);
                case "navsettings" -> navSettingsPurgeEventListener.retryPurge(userId);
                case "gamification" -> gamificationSettingsPurgeEventListener.retryPurge(userId);
                case "reflection" -> reflectionSettingsPurgeEventListener.retryPurge(userId);
                case "timetable.personal" -> personalTimetableSettingsPurgeEventListener.retryPurge(userId);
                case "cms" -> userBlogSettingsPurgeEventListener.retryPurge(userId);
                case "chat" -> chatBookmarkPurgeEventListener.retryPurge(userId);
                case "knowledgebase" -> kbPageFavoritePurgeEventListener.retryPurge(userId);
                case "favorite" -> favoriteAnonymizationEventListener.retryPurge(userId);
                case "membership" -> scopeMemberCalendarSettingAnonymizationEventListener.retryPurge(userId);
                case "weather" -> weatherLocationCleanupListener.retryPurge(userId);
                case "inbox" -> inboxAnonymizationEventListener.retryPurge(userId);
                case "timetable.notes" -> timetableNoteFieldsPurgeEventListener.retryPurge(userId);
                case "seal" -> sealScopeDefaultsPurgeEventListener.retryPurge(userId);
                case "schedule" -> calendarLayerCleanupExecutor.retryPurge(userId);
                case "village.settings" -> villageUserCleanerEventListener.retryPurge(userId);
                default -> throw new IllegalStateException("到達不能: " + domainName);
            };
        } catch (Exception ex) {
            log.warn("個人設定の強消去retry失敗: userId={} domain={}", userId, domainName, ex);
            return false;
        }
    }
}
