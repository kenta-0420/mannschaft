package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ReflectionTodayResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 今日の振り返りビューのサービス（F06.5・§4.3 / §7 #12）。
 *
 * <p>「今日の全コマ」を {@link PersonalTimetableDashboardService#getTimetableToday} の
 * <b>実呼び出し</b>で列挙し（TEAM の {@code timetable_changes} 等は既に反映済み）、各コマに
 * {@code linked_slot_kind}（TEAM/PERSONAL）/{@code linked_slot_id} を実レスポンス item の
 * {@code source_kind}(String)/{@code slot_id}(Long) で照合して theme／当日エントリの有無・マスク状態を
 * 付与する。空きコマ（theme 未設定）も item 化し（AC-17 空コマ編集可）、時間割に紐づかない自由テーマ
 * （PROJECT/DIARY/FREE）の当日エントリ／テーマは {@code slotKind=null} item として列挙する（§4.3）。</p>
 *
 * <p><b>AC-19（マスタを壊さない）</b>: 時間割マスタ（personal_timetable_slots / timetable_slots）は
 * 一切書き換えず、当日差分は reflection エントリ側（Wave1 upsert）に保持済み。本サービスはビューの
 * 組み立て（読み取り）のみを行う。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReflectionTodayService {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;
    private final PersonalTimetableDashboardService personalTimetableDashboardService;
    private final ReflectionMaskEvaluator maskEvaluator;
    private final UserTimezoneCache userTimezoneCache;

    /**
     * 今日の振り返りビュー（§7 #12・AC-17/AC-19）。
     *
     * @param userId 認証ユーザーID
     * @param date   対象日（null ならサービスがユーザー TZ の今日を採用・§4.3）。指定時は範囲（§2.5.1 c）を検証。
     */
    public ReflectionTodayResponse getToday(Long userId, LocalDate date) {
        LocalDate today = todayOf(userId);
        LocalDate target = date != null ? validateDateInRange(date, today) : today;

        // コマ列挙は dashboard を実呼び出し（TEAM の timetable_changes 等を反映済み・§4.3）。
        DashboardTimetableTodayResponse dashboard =
                personalTimetableDashboardService.getTimetableToday(userId, target);

        // 当日の全テーマ・当日エントリを 1 度ずつ引いてメモリ上で照合する（N+1 回避）。
        List<ReflectionThemeEntity> themes = reflectionThemeRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<ReflectionEntryEntity> entries = reflectionEntryRepository.findByUserIdAndTargetDate(userId, target);

        // themeId → 当日エントリ。
        Map<UUID, ReflectionEntryEntity> entryByThemeId = new HashMap<>();
        for (ReflectionEntryEntity e : entries) {
            entryByThemeId.put(e.getThemeId(), e);
        }
        // (slotKind文字列, slotId) → theme（時間割コマ紐付けテーマ）。
        Map<String, ReflectionThemeEntity> themeBySlotKey = new HashMap<>();
        List<ReflectionThemeEntity> freeThemes = new ArrayList<>();
        for (ReflectionThemeEntity t : themes) {
            if (t.getLinkedSlotKind() != null && t.getLinkedSlotId() != null) {
                themeBySlotKey.put(slotKey(t.getLinkedSlotKind().name(), t.getLinkedSlotId()), t);
            } else {
                freeThemes.add(t);
            }
        }

        List<ReflectionTodayResponse.ReflectionTodayItem> items = new ArrayList<>();
        Set<UUID> consumedThemeIds = new HashSet<>();

        // ① 時間割コマ由来 item（空きコマも item 化＝AC-17）。
        for (DashboardTimetableTodayResponse.TimetableTodayItem slot : dashboard.items()) {
            ReflectionThemeEntity linkedTheme =
                    themeBySlotKey.get(slotKey(slot.sourceKind(), slot.slotId()));
            ReflectionEntryEntity todayEntry =
                    linkedTheme != null ? entryByThemeId.get(linkedTheme.getId()) : null;
            if (linkedTheme != null) {
                consumedThemeIds.add(linkedTheme.getId());
            }
            items.add(buildSlotItem(slot, linkedTheme, todayEntry, target));
        }

        // ② 自由テーマ由来 item（slotKind=null・時間割非依存ユーザーの主導線＝§4.3）。
        //    コマに照合済みでない自由テーマ（linked_slot 無し）を列挙する。
        for (ReflectionThemeEntity t : freeThemes) {
            if (consumedThemeIds.contains(t.getId())) {
                continue;
            }
            ReflectionEntryEntity todayEntry = entryByThemeId.get(t.getId());
            items.add(buildFreeThemeItem(t, todayEntry, target));
        }

        return ReflectionTodayResponse.builder()
                .date(target)
                .items(items)
                .build();
    }

    private ReflectionTodayResponse.ReflectionTodayItem buildSlotItem(
            DashboardTimetableTodayResponse.TimetableTodayItem slot,
            ReflectionThemeEntity linkedTheme,
            ReflectionEntryEntity todayEntry,
            LocalDate today) {
        return ReflectionTodayResponse.ReflectionTodayItem.builder()
                .slotKind(slot.sourceKind())
                .slotId(slot.slotId())
                .periodLabel(slot.periodLabel())
                .subjectName(linkedTheme != null ? linkedTheme.getTitle() : slot.subjectName())
                .themeId(linkedTheme != null ? linkedTheme.getId().toString() : null)
                .hasEntryToday(todayEntry != null)
                .entryId(todayEntry != null ? todayEntry.getId().toString() : null)
                .isMasked(todayEntry != null && maskEvaluator.isMasked(todayEntry, linkedTheme, today))
                .build();
    }

    private ReflectionTodayResponse.ReflectionTodayItem buildFreeThemeItem(
            ReflectionThemeEntity theme, ReflectionEntryEntity todayEntry, LocalDate today) {
        return ReflectionTodayResponse.ReflectionTodayItem.builder()
                .slotKind(null) // 自由テーマ（コマ非依存・§4.3）
                .slotId(null)
                .periodLabel(null)
                .subjectName(theme.getTitle())
                .themeId(theme.getId().toString())
                .hasEntryToday(todayEntry != null)
                .entryId(todayEntry != null ? todayEntry.getId().toString() : null)
                .isMasked(todayEntry != null && maskEvaluator.isMasked(todayEntry, theme, today))
                .build();
    }

    private String slotKey(String sourceKind, Long slotId) {
        return sourceKind + ":" + slotId;
    }

    /** ?date= の範囲検証（過去365〜未来30日・§2.5.1 c）。範囲外は 400。 */
    private LocalDate validateDateInRange(LocalDate date, LocalDate today) {
        LocalDate min = today.minusDays(ReflectionConstants.TARGET_DATE_PAST_DAYS);
        LocalDate max = today.plusDays(ReflectionConstants.TARGET_DATE_FUTURE_DAYS);
        if (date.isBefore(min) || date.isAfter(max)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_TARGET_DATE_OUT_OF_RANGE);
        }
        return date;
    }

    /** ユーザー TZ の今日（§4.3 / §2.5.1 c の基準）。 */
    private LocalDate todayOf(Long userId) {
        ZoneId zone;
        try {
            zone = ZoneId.of(userTimezoneCache.getTimezone(userId));
        } catch (Exception e) {
            zone = DEFAULT_ZONE;
        }
        return LocalDate.now(zone);
    }
}
