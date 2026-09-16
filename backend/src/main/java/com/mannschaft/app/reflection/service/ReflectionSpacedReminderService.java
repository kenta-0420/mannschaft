package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionReminderKind;
import com.mannschaft.app.reflection.ReflectionReminderStatus;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionSpacedReminderEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 間隔反復リマインダーの生成・キャンセル・送信のサービス（F06.5・§5）。
 *
 * <p>remind_at はユーザー TZ ×通知時刻で算出し JST（{@link #STORAGE_ZONE}）正規化して保存する（§5.3）。
 * due 走査（{@link #processDueReminders}）は孤児 fail-safe＋status 遷移で二重送信を防止する（§5.2・AC-10）。</p>
 *
 * <p>TZ 解決は {@code common} 共有ドメインの {@link UserTimezoneCache} 経由（auth の Repository を直接
 * 参照せず越境を避ける・D-3 番人遵守）。親存在確認は同一 reflection ドメインの Repository のみ参照する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionSpacedReminderService {

    /** 保存時刻の基準 TZ（{@code ScheduleReminderService} と一致）。 */
    private static final ZoneId STORAGE_ZONE = ZoneId.of("Asia/Tokyo");
    private static final ZoneId ZONE_FALLBACK = ZoneId.of("Asia/Tokyo");

    private final ReflectionSpacedReminderRepository reflectionSpacedReminderRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionSettingsService reflectionSettingsService;
    private final UserTimezoneCache userTimezoneCache;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;

    /**
     * エントリ保存時に SPACED 行（recall_interval_days 分）を生成する（§5.3・AC-9）。
     *
     * <p>呼び出し元（エントリ upsert）のトランザクション内で実行される前提（本メソッドに @Transactional は付与しない）。</p>
     */
    public void generateSpacedReminders(ReflectionEntryEntity entry, ReflectionThemeEntity theme) {
        if (entry == null || theme == null) {
            return;
        }
        Long userId = entry.getUserId();
        ZoneId zone = resolveUserZone(userId);
        int remindHour = reflectionSettingsService.remindHour(userId);
        List<Integer> intervals = parseIntervals(theme.getRecallIntervalDays());
        for (int i : intervals) {
            LocalDate dueDate = entry.getTargetDate().plusDays(i);
            LocalDateTime remindAt = toRemindAt(dueDate, remindHour, zone);
            reflectionSpacedReminderRepository.save(ReflectionSpacedReminderEntity.builder()
                    .entryId(entry.getId())
                    .themeId(null)
                    .userId(userId)
                    .remindAt(remindAt)
                    .intervalDays(i)
                    .kind(ReflectionReminderKind.SPACED)
                    .status(ReflectionReminderStatus.PENDING)
                    .build());
        }
    }

    /**
     * FORGOT 時に翌日（recall_date+1）の SPACED 行を追加生成する（§3.1・AC-22）。
     */
    public void scheduleNextDaySpacedReminder(ReflectionEntryEntity entry, LocalDate recallDate) {
        if (entry == null || recallDate == null) {
            return;
        }
        Long userId = entry.getUserId();
        ZoneId zone = resolveUserZone(userId);
        int remindHour = reflectionSettingsService.remindHour(userId);
        LocalDate dueDate = recallDate.plusDays(1);
        LocalDateTime remindAt = toRemindAt(dueDate, remindHour, zone);
        reflectionSpacedReminderRepository.save(ReflectionSpacedReminderEntity.builder()
                .entryId(entry.getId())
                .themeId(null)
                .userId(userId)
                .remindAt(remindAt)
                .intervalDays(null)
                .kind(ReflectionReminderKind.SPACED)
                .status(ReflectionReminderStatus.PENDING)
                .build());
    }

    /**
     * exam_date 設定時に PRE_EXAM 行（14/7/3/1 日前）を生成する（過去日ガード・§5.5・AC-12）。
     *
     * <p>remind_at が現在時刻より過去になる行は生成しない（過去日の即時 due 発火スパム防止）。
     * exam_date 自体が過去日なら 4 行すべてスキップされ 0 件となる。</p>
     */
    public void generatePreExamReminders(ReflectionThemeEntity theme) {
        if (theme == null || theme.getExamDate() == null) {
            return;
        }
        Long userId = theme.getUserId();
        ZoneId zone = resolveUserZone(userId);
        int remindHour = reflectionSettingsService.remindHour(userId);
        LocalDateTime now = LocalDateTime.now(STORAGE_ZONE);
        for (int n : ReflectionConstants.PRE_EXAM_DAYS_BEFORE) {
            LocalDate dueDate = theme.getExamDate().minusDays(n);
            LocalDateTime remindAt = toRemindAt(dueDate, remindHour, zone);
            if (remindAt.isBefore(now)) {
                continue; // 過去日ガード（§5.5）
            }
            reflectionSpacedReminderRepository.save(ReflectionSpacedReminderEntity.builder()
                    .entryId(null)
                    .themeId(theme.getId())
                    .userId(userId)
                    .remindAt(remindAt)
                    .intervalDays(n)
                    .kind(ReflectionReminderKind.PRE_EXAM)
                    .status(ReflectionReminderStatus.PENDING)
                    .build());
        }
    }

    /**
     * エントリ削除/復活・テーマ削除時に当該エントリ由来の PENDING SPACED 行を CANCELLED 化する（§5.5）。
     */
    public void cancelPendingForEntry(UUID entryId) {
        if (entryId == null) {
            return;
        }
        List<ReflectionSpacedReminderEntity> pendings = reflectionSpacedReminderRepository
                .findByEntryIdAndStatus(entryId, ReflectionReminderStatus.PENDING);
        for (ReflectionSpacedReminderEntity r : pendings) {
            r.cancel();
        }
        reflectionSpacedReminderRepository.saveAll(pendings);
    }

    /**
     * exam_date 変更・テーマ削除時に PRE_EXAM の PENDING 行を CANCELLED 化する（§5.5）。
     */
    public void cancelPendingPreExamForTheme(UUID themeId) {
        if (themeId == null) {
            return;
        }
        List<ReflectionSpacedReminderEntity> pendings = reflectionSpacedReminderRepository
                .findByThemeIdAndStatus(themeId, ReflectionReminderStatus.PENDING);
        for (ReflectionSpacedReminderEntity r : pendings) {
            r.cancel();
        }
        reflectionSpacedReminderRepository.saveAll(pendings);
    }

    /** ユーザーの PENDING リマインダー総数（§2.5.1(a) 上限判定）。 */
    public long countPendingReminders(Long userId) {
        return reflectionSpacedReminderRepository
                .countByUserIdAndStatus(userId, ReflectionReminderStatus.PENDING);
    }

    /**
     * due（remind_at<=now・PENDING）を走査し、孤児 fail-safe＋status 遷移で送信する（§5.2・AC-10）。
     *
     * <p><b>@Transactional 必須</b>: status 遷移を 1 トランザクションで確定し二重送信を防ぐ。
     * 親存在チェック・通知・status 遷移はすべて reflection ドメイン内 + notification ファサード経由
     * （別ドメイン Repository には直接依存しない・D-3 番人遵守）。</p>
     */
    @Transactional
    public void processDueReminders() {
        LocalDateTime now = LocalDateTime.now(STORAGE_ZONE);
        List<ReflectionSpacedReminderEntity> due = reflectionSpacedReminderRepository
                .findByStatusAndRemindAtLessThanEqual(ReflectionReminderStatus.PENDING, now);
        // Issue #2715 CMP-055 ロットC-6: 受信者ごとに locale が異なるため、ループの外で一括解決する（N+1 防止）。
        // Codex 検分是正（PR #2873）: バルク取得自体を try で隔離し、失敗時は既定 locale ("ja") で継続する。
        Map<Long, String> locales;
        try {
            locales = userLocaleCache.getLocales(
                    due.stream().map(ReflectionSpacedReminderEntity::getUserId).toList());
        } catch (Exception e) {
            log.warn("locale 一括解決に失敗（既定 locale で継続）: error={}", e.getMessage());
            locales = Map.of();
        }
        for (ReflectionSpacedReminderEntity r : due) {
            try {
                Optional<ReflectionThemeEntity> theme = resolveTheme(r);
                if (theme.isEmpty()) {
                    // 親不在 or 論理削除済み → 孤児 fail-safe（§5.2）。
                    r.cancel();
                    reflectionSpacedReminderRepository.save(r);
                    continue;
                }
                Locale locale = Locale.forLanguageTag(locales.getOrDefault(r.getUserId(), "ja"));
                String title = messageSource.getMessage(
                        "notification.reflection.recallReminder.title", null,
                        "振り返りの想起テスト", locale);
                // 通知 body はテーマ名のみ（振り返り本文は含めない＝プライバシー・§5.4）。
                String body = messageSource.getMessage(
                        "notification.reflection.recallReminder.body",
                        new Object[]{theme.get().getTitle()},
                        theme.get().getTitle() + "の振り返りを思い出してみましょう", locale);
                String actionUrl = r.getEntryId() != null
                        ? "/reflections/recall?entry=" + r.getEntryId()
                        : "/reflections/themes/" + r.getThemeId();
                notificationHelper.notify(
                        r.getUserId(),
                        ReflectionConstants.NOTIFICATION_TYPE_RECALL_REMINDER,
                        title,
                        body,
                        ReflectionConstants.NOTIFICATION_SOURCE_TYPE,
                        null, // sourceId は UUID 非対応のため null（actionUrl で entry を運ぶ・§5.4）
                        NotificationScopeType.PERSONAL,
                        r.getUserId(),
                        actionUrl,
                        null);
                r.markAsSent(); // status=SENT, sent_at=now（二重送信防止・AC-10）
                reflectionSpacedReminderRepository.save(r);
            } catch (Exception e) {
                log.warn("リマインダー送信失敗（継続）: reminderId={}, error={}", r.getId(), e.getMessage());
            }
        }
    }

    // ─── 内部ヘルパ ───────────────────────────────────────────────

    /** リマインダーの親（テーマ）を解決する。SPACED は entry→theme、PRE_EXAM は theme 直参照。 */
    private Optional<ReflectionThemeEntity> resolveTheme(ReflectionSpacedReminderEntity r) {
        if (r.getKind() == ReflectionReminderKind.SPACED && r.getEntryId() != null) {
            Optional<ReflectionEntryEntity> entry = reflectionEntryRepository.findById(r.getEntryId());
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            return reflectionThemeRepository.findById(entry.get().getThemeId());
        }
        if (r.getThemeId() != null) {
            return reflectionThemeRepository.findById(r.getThemeId());
        }
        return Optional.empty();
    }

    /** dueDate の所定時刻（ユーザー TZ）を JST 正規化した LocalDateTime に変換する（§5.3）。 */
    private LocalDateTime toRemindAt(LocalDate dueDate, int remindHour, ZoneId zone) {
        ZonedDateTime zdt = dueDate.atTime(remindHour, 0).atZone(zone);
        return zdt.withZoneSameInstant(STORAGE_ZONE).toLocalDateTime();
    }

    /** ユーザー TZ を common 共有キャッシュ経由で解決（不正値は Asia/Tokyo フォールバック）。 */
    private ZoneId resolveUserZone(Long userId) {
        try {
            return ZoneId.of(userTimezoneCache.getTimezone(userId));
        } catch (Exception e) {
            log.warn("不正なタイムゾーン設定: userId={}, フォールバック={}", userId, ZONE_FALLBACK);
            return ZONE_FALLBACK;
        }
    }

    /** recall_interval_days CSV を昇順・重複排除・正値の List<Integer> にパース（§2.6）。 */
    private List<Integer> parseIntervals(String csv) {
        List<Integer> intervals = new java.util.ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return intervals;
        }
        for (String token : csv.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            try {
                int v = Integer.parseInt(t);
                if (v >= 1 && !intervals.contains(v)) {
                    intervals.add(v);
                }
            } catch (NumberFormatException ignored) {
                log.warn("recall_interval_days パース失敗: csv={}, token={}", csv, t);
            }
        }
        intervals.sort(Integer::compareTo);
        return intervals;
    }
}
