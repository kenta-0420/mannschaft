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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // Phase 2: 2系統 Map で照合（§11.1）。
        // bySlotKey  : "slotKind:slotId" → テーマ（条件A: linked_slot_id 優先経路・既存 Phase1 動作）
        // bySubjectKey: "PERSONAL:subjectName:courseCode" or "TEAM:subjectName" → テーマリスト
        //              （条件B: linked_subject_name 経路・linked_slot_id なしのみ登録）
        Map<String, ReflectionThemeEntity> bySlotKey = new HashMap<>();
        Map<String, List<ReflectionThemeEntity>> bySubjectKey = new HashMap<>();
        List<ReflectionThemeEntity> freeThemes = new ArrayList<>();
        for (ReflectionThemeEntity t : themes) {
            if (t.getLinkedSlotKind() != null && t.getLinkedSlotId() != null) {
                // 条件A: linked_slot_id あり → bySlotKey のみ（両方持つ場合も条件A優先）
                bySlotKey.put(slotKey(t.getLinkedSlotKind().name(), t.getLinkedSlotId()), t);
            } else if (t.getLinkedSlotKind() != null && t.getLinkedSubjectName() != null) {
                // 条件B: linked_subject_name あり・linked_slot_id なし → bySubjectKey
                // courseCode が非 null の場合は精密キー（subjectName:courseCode）にも登録し、
                // courseCode なしスロットとの照合に備えて subjectName のみのフォールバックキーにも登録する。
                // これにより:
                //   スロット side courseCode あり → 精密キーで同 courseCode のテーマのみヒット (AC-31)
                //   スロット side courseCode なし → フォールバックキーで全 subjectName 一致テーマがヒット (AC-29)
                String preciseKey = subjectKey(t.getLinkedSlotKind().name(), t.getLinkedSubjectName(),
                        t.getLinkedCourseCode());
                bySubjectKey.computeIfAbsent(preciseKey, k -> new ArrayList<>()).add(t);
                if (t.getLinkedCourseCode() != null && !t.getLinkedCourseCode().isEmpty()) {
                    // courseCode あり → フォールバックキー（courseCode なし扱い）にも登録
                    String fallbackKey = subjectKey(t.getLinkedSlotKind().name(), t.getLinkedSubjectName(), null);
                    bySubjectKey.computeIfAbsent(fallbackKey, k -> new ArrayList<>()).add(t);
                }
            } else {
                // 自由テーマ（linked_slot なし・linked_subject なし）
                freeThemes.add(t);
            }
        }

        List<ReflectionTodayResponse.ReflectionTodayItem> items = new ArrayList<>();
        Set<UUID> consumedThemeIds = new HashSet<>();

        // ① 時間割コマ由来 item（空きコマも item 化＝AC-17）。
        for (DashboardTimetableTodayResponse.TimetableTodayItem slot : dashboard.items()) {
            // 条件A: bySlotKey でヒットするか確認
            ReflectionThemeEntity linkedThemeA =
                    bySlotKey.get(slotKey(slot.sourceKind(), slot.slotId()));
            if (linkedThemeA != null) {
                // 条件A ヒット → 既存 Phase1 動作（subjectName をテーマ名で上書き）
                ReflectionEntryEntity todayEntry = entryByThemeId.get(linkedThemeA.getId());
                consumedThemeIds.add(linkedThemeA.getId());
                items.add(buildSlotItem(slot, linkedThemeA, todayEntry, target, true));
            } else {
                // 条件B: bySubjectKey で照合（条件A未ヒットのコマのみ）
                String key = subjectKey(slot.sourceKind(), slot.subjectName(), slot.courseCode());
                List<ReflectionThemeEntity> subjectThemes =
                        bySubjectKey.getOrDefault(key, Collections.emptyList());
                if (!subjectThemes.isEmpty()) {
                    for (ReflectionThemeEntity subjectTheme : subjectThemes) {
                        ReflectionEntryEntity todayEntry = entryByThemeId.get(subjectTheme.getId());
                        consumedThemeIds.add(subjectTheme.getId());
                        // 条件B: subjectName 上書きしない（コマの科目名を保持・§11.1）
                        items.add(buildSlotItem(slot, subjectTheme, todayEntry, target, false));
                    }
                } else {
                    // 空きコマ（テーマ未設定）→ themeId null で item 化（AC-17）
                    items.add(buildSlotItem(slot, null, null, target, true));
                }
            }
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

        // AC-27: themeId を持つ item 群に対し、最新 targetDate を GROUP BY 1 クエリで一括取得（N+1 回避）。
        Set<UUID> themeIdsInItems = items.stream()
                .filter(i -> i.themeId() != null)
                .map(i -> UUID.fromString(i.themeId()))
                .collect(Collectors.toSet());

        Map<UUID, LocalDate> lastReflectedByThemeId = new HashMap<>();
        if (!themeIdsInItems.isEmpty()) {
            reflectionEntryRepository.findLatestTargetDateByThemeIds(themeIdsInItems)
                    .forEach(v -> lastReflectedByThemeId.put(v.getThemeId(), v.getLastDate()));
        }

        // themeId → theme メタ（title・createdAt）の Map を構築。
        Map<UUID, ReflectionThemeEntity> themeById = new HashMap<>();
        for (ReflectionThemeEntity t : themes) {
            themeById.put(t.getId(), t);
        }

        // items に themeTitle・themeCreatedAt・lastReflectedAt を付与して再構築（AC-25/AC-26）。
        List<ReflectionTodayResponse.ReflectionTodayItem> enrichedItems = new ArrayList<>(items.size());
        for (ReflectionTodayResponse.ReflectionTodayItem item : items) {
            if (item.themeId() == null) {
                enrichedItems.add(item);
                continue;
            }
            UUID themeId = UUID.fromString(item.themeId());
            ReflectionThemeEntity theme = themeById.get(themeId);
            enrichedItems.add(ReflectionTodayResponse.ReflectionTodayItem.builder()
                    .slotKind(item.slotKind())
                    .slotId(item.slotId())
                    .periodLabel(item.periodLabel())
                    .subjectName(item.subjectName())
                    .themeId(item.themeId())
                    .hasEntryToday(item.hasEntryToday())
                    .entryId(item.entryId())
                    .isMasked(item.isMasked())
                    .themeTitle(theme != null ? theme.getTitle() : null)
                    .themeCreatedAt(theme != null && theme.getCreatedAt() != null
                            ? theme.getCreatedAt().toLocalDate() : null)
                    .lastReflectedAt(lastReflectedByThemeId.get(themeId))
                    .build());
        }

        return ReflectionTodayResponse.builder()
                .date(target)
                .items(enrichedItems)
                .build();
    }

    /**
     * 時間割コマ由来 item を組み立てる。
     *
     * @param overwriteSubjectName true=条件A（テーマ名で subjectName 上書き）、false=条件B（コマの科目名を保持）
     */
    private ReflectionTodayResponse.ReflectionTodayItem buildSlotItem(
            DashboardTimetableTodayResponse.TimetableTodayItem slot,
            ReflectionThemeEntity linkedTheme,
            ReflectionEntryEntity todayEntry,
            LocalDate today,
            boolean overwriteSubjectName) {
        // 条件A: subjectName をテーマ名で上書き（Phase 1 後方互換）。
        // 条件B: コマの科目名を保持（テーマ情報は themeTitle メタで表示・§11.1）。
        String subjectName;
        if (linkedTheme != null && overwriteSubjectName) {
            subjectName = linkedTheme.getTitle();
        } else {
            subjectName = slot.subjectName();
        }
        return ReflectionTodayResponse.ReflectionTodayItem.builder()
                .slotKind(slot.sourceKind())
                .slotId(slot.slotId())
                .periodLabel(slot.periodLabel())
                .subjectName(subjectName)
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

    /**
     * Phase 2: 科目名照合キーを生成する（§11.1）。
     * PERSONAL → "PERSONAL:subjectName:courseCode"（courseCode が null の場合は ""）
     * TEAM     → "TEAM:subjectName"（courseCode は TEAM コマに存在しないため無視）
     */
    private String subjectKey(String sourceKind, String subjectName, String courseCode) {
        if ("TEAM".equals(sourceKind)) {
            return "TEAM:" + (subjectName != null ? subjectName : "");
        }
        // PERSONAL
        return "PERSONAL:" + (subjectName != null ? subjectName : "")
                + ":" + (courseCode != null ? courseCode : "");
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
