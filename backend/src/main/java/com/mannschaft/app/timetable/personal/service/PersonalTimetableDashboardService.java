package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.timetable.TimetableChangeType;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.entity.TimetableChangeEntity;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.notes.TimetableSlotKind;
import com.mannschaft.app.timetable.notes.entity.TimetableSlotUserNoteEntity;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteAttachmentRepository;
import com.mannschaft.app.timetable.notes.repository.TimetableSlotUserNoteRepository;
import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.dto.DashboardTimetableTodayResponse;
import com.mannschaft.app.timetable.personal.dto.TimetableWeekSlotInfo;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableChangeRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * F03.15 Phase 3 ダッシュボード集約サービス。
 *
 * <p>個人ダッシュボードに表示する「今日の時間割」を、所属チーム時間割と個人時間割を
 * マージして時刻順で返す。Phase 3 ではチームリンク連動による臨時変更反映は未実装のため、
 * {@code is_changed} は常に false / {@code change} は常に null。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableDashboardService {

    private static final List<String> WEEK_DOWS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final PersonalTimetablePeriodRepository personalPeriodRepository;
    private final TimetableRepository teamTimetableRepository;
    private final TimetableSlotRepository teamSlotRepository;
    private final TimetableSlotUserNoteRepository userNoteRepository;
    private final TimetableSlotUserNoteAttachmentRepository attachmentRepository;
    private final MembershipScopeQueryService membershipScopeQueryService;
    private final TimetableChangeRepository timetableChangeRepository;

    /**
     * F06.5 Phase 2: 本人の週全体の時間割スロットを科目単位で重複排除した情報を返す（§11.3）。
     *
     * <p>母集合: 本人の「ACTIVE かつ today が effective 期間内」の個人時間割の全曜日スロット
     * ＋ 所属 TEAM の全曜日スロット。メモ・臨時変更・添付カウント等の重い副問い合わせを含めない<b>軽量版</b>。
     * dedup キー = {@code (kind, subjectName, courseCode)}。subjectName が空・NULL のコマは除外。
     * 返り型は {@link TimetableWeekSlotInfo}（timetable ドメイン内部DTO）。
     * reflection ドメインへのマッピングは呼び出し元（{@code ReflectionLinkableSlotService}）が担う。</p>
     *
     * @param userId 対象ユーザー
     * @param today  今日の日付（effective 期間判定の基準日）
     * @return 重複排除済みスロット情報一覧（時間割ゼロの場合は空リスト）
     */
    public List<TimetableWeekSlotInfo> listAllWeekSlots(Long userId, LocalDate today) {
        // dedup 管理: キー = "kind:subjectName:courseCode"
        Set<String> seenKeys = new LinkedHashSet<>();
        List<TimetableWeekSlotInfo> result = new ArrayList<>();

        // ---- PERSONAL: 自分の ACTIVE・today effective 範囲内の個人時間割の全曜日スロット ----
        List<PersonalTimetableEntity> personals = effectivePersonals(userId, today);
        // 時間割数に比例して SQL が増えないよう、スロット・時限は ID 群でまとめて取得する
        Map<Long, List<PersonalTimetableSlotEntity>> slotsByPersonal = new HashMap<>();
        Map<Long, Map<Integer, PersonalTimetablePeriodEntity>> periodsByPersonal = new HashMap<>();
        if (!personals.isEmpty()) {
            List<Long> ids = personals.stream().map(PersonalTimetableEntity::getId).toList();
            for (PersonalTimetableSlotEntity slot : personalSlotRepository
                    .findByPersonalTimetableIdInOrderByDayOfWeekAscPeriodNumberAsc(ids)) {
                slotsByPersonal.computeIfAbsent(slot.getPersonalTimetableId(), k -> new ArrayList<>()).add(slot);
            }
            periodsByPersonal = loadPeriods(ids);
        }
        for (PersonalTimetableEntity personal : personals) {
            Map<Integer, PersonalTimetablePeriodEntity> periodMap =
                    periodsByPersonal.getOrDefault(personal.getId(), Map.of());
            for (PersonalTimetableSlotEntity slot : slotsByPersonal.getOrDefault(personal.getId(), List.of())) {
                String subjectName = slot.getSubjectName();
                if (subjectName == null || subjectName.isBlank()) continue;
                String courseCode = slot.getCourseCode();
                String key = "PERSONAL:" + subjectName + ":" + Objects.toString(courseCode, "");
                if (seenKeys.add(key)) {
                    PersonalTimetablePeriodEntity period = periodMap.get(slot.getPeriodNumber());
                    result.add(new TimetableWeekSlotInfo(
                            "PERSONAL",
                            slot.getId(),
                            subjectName,
                            courseCode,
                            slot.getTeacherName(),
                            period != null ? period.getLabel() : null
                    ));
                }
            }
        }

        // ---- TEAM: 所属チームの ACTIVE 時間割（today effective）の全曜日スロット ----
        List<Long> joinedTeamIds = listJoinedTeamIds(userId);
        Map<Long, List<TimetableEntity>> timetablesByTeam = loadEffectiveTimetables(joinedTeamIds, today);
        List<Long> timetableIds = timetablesByTeam.values().stream()
                .flatMap(List::stream).map(TimetableEntity::getId).toList();
        // timetableId -> dayOfWeek -> slots（曜日はメモリ上で振り分ける）
        Map<Long, Map<String, List<TimetableSlotEntity>>> slotsByTimetable = new HashMap<>();
        if (!timetableIds.isEmpty()) {
            for (TimetableSlotEntity slot : teamSlotRepository
                    .findByTimetableIdInOrderByTimetableIdAscIdAsc(timetableIds)) {
                slotsByTimetable.computeIfAbsent(slot.getTimetableId(), k -> new HashMap<>())
                        .computeIfAbsent(slot.getDayOfWeek(), k -> new ArrayList<>()).add(slot);
            }
        }
        for (Long teamId : joinedTeamIds) {
            for (TimetableEntity timetable : timetablesByTeam.getOrDefault(teamId, List.of())) {
                Map<String, List<TimetableSlotEntity>> byDow =
                        slotsByTimetable.getOrDefault(timetable.getId(), Map.of());
                for (String dow : WEEK_DOWS) {
                    for (TimetableSlotEntity slot : byDow.getOrDefault(dow, List.of())) {
                        String subjectName = slot.getSubjectName();
                        if (subjectName == null || subjectName.isBlank()) continue;
                        // TEAM は courseCode なし → キーは subjectName のみ
                        String key = "TEAM:" + subjectName;
                        if (seenKeys.add(key)) {
                            result.add(new TimetableWeekSlotInfo(
                                    "TEAM",
                                    slot.getId(),
                                    subjectName,
                                    null,          // TEAM は courseCode 常に null
                                    slot.getTeacherName(),
                                    null           // TEAM は periodLabel なし
                            ));
                        }
                    }
                }
            }
        }

        return result;
    }

    /** 今日の 1 コマ（個人）。一括取得前の素材。 */
    private record PersonalEntry(PersonalTimetableEntity personal, PersonalTimetableSlotEntity slot,
                                 PersonalTimetablePeriodEntity period) {
    }

    /** 今日の 1 コマ（チーム）。一括取得前の素材。 */
    private record TeamEntry(Long teamId, TimetableEntity timetable, TimetableSlotEntity slot) {
    }

    /**
     * 「今日の時間割」を取得する。
     *
     * <p>SQL 数は所属チーム数・時間割数・コマ数に依存しない。コマごとのメモ・添付・臨時変更・
     * 所属判定は、スロット ID 群／時間割 ID 群でまとめて取得して Map 化し、メモリ上で突き合わせる。</p>
     *
     * @param userId   対象ユーザー
     * @param today    今日の日付（テスト容易性のため引数化）
     */
    public DashboardTimetableTodayResponse getTimetableToday(Long userId, LocalDate today) {
        String todayDow = WEEK_DOWS.get(today.getDayOfWeek().getValue() - 1);

        WeekPattern firstActivePattern = WeekPattern.EVERY;

        // ---- PERSONAL: 自分のACTIVE個人時間割の今日のコマ ----
        List<PersonalTimetableEntity> personals = effectivePersonals(userId, today);
        List<PersonalEntry> personalEntries = new ArrayList<>();
        if (!personals.isEmpty()) {
            List<Long> ids = personals.stream().map(PersonalTimetableEntity::getId).toList();
            Map<Long, List<PersonalTimetableSlotEntity>> slotsByPersonal = new HashMap<>();
            for (PersonalTimetableSlotEntity slot : personalSlotRepository
                    .findByPersonalTimetableIdInAndDayOfWeekOrderByPeriodNumberAsc(ids, todayDow)) {
                slotsByPersonal.computeIfAbsent(slot.getPersonalTimetableId(), k -> new ArrayList<>()).add(slot);
            }
            Map<Long, Map<Integer, PersonalTimetablePeriodEntity>> periodsByPersonal = loadPeriods(ids);
            for (PersonalTimetableEntity personal : personals) {
                WeekPattern current = resolveWeekPattern(personal, today);
                if (firstActivePattern == WeekPattern.EVERY && current != WeekPattern.EVERY) {
                    firstActivePattern = current;
                }
                Map<Integer, PersonalTimetablePeriodEntity> periodMap =
                        periodsByPersonal.getOrDefault(personal.getId(), Map.of());
                for (PersonalTimetableSlotEntity slot : slotsByPersonal.getOrDefault(personal.getId(), List.of())) {
                    if (slot.getWeekPattern() != WeekPattern.EVERY && slot.getWeekPattern() != current) {
                        continue;
                    }
                    personalEntries.add(new PersonalEntry(personal, slot, periodMap.get(slot.getPeriodNumber())));
                }
            }
        }

        // ---- TEAM: 所属チームの ACTIVE 時間割の今日のコマ ----
        // ユーザーが所属する team_id 一覧（link_revoked 判定にも使い回す）
        List<Long> joinedTeamIds = listJoinedTeamIds(userId);
        Set<Long> joinedTeamIdSet = new HashSet<>(joinedTeamIds);
        Map<Long, List<TimetableEntity>> timetablesByTeam = loadEffectiveTimetables(joinedTeamIds, today);
        List<Long> timetableIds = timetablesByTeam.values().stream()
                .flatMap(List::stream).map(TimetableEntity::getId).toList();
        Map<Long, List<TimetableSlotEntity>> teamSlotsByTimetable = new HashMap<>();
        if (!timetableIds.isEmpty()) {
            for (TimetableSlotEntity slot : teamSlotRepository
                    .findByTimetableIdInAndDayOfWeekOrderByTimetableIdAscIdAsc(timetableIds, todayDow)) {
                teamSlotsByTimetable.computeIfAbsent(slot.getTimetableId(), k -> new ArrayList<>()).add(slot);
            }
        }
        List<TeamEntry> teamEntries = new ArrayList<>();
        for (Long teamId : joinedTeamIds) {
            for (TimetableEntity timetable : timetablesByTeam.getOrDefault(teamId, List.of())) {
                for (TimetableSlotEntity slot : teamSlotsByTimetable.getOrDefault(timetable.getId(), List.of())) {
                    teamEntries.add(new TeamEntry(teamId, timetable, slot));
                }
            }
        }

        // ---- コマごとの付随情報を一括取得（ループ内クエリ禁止） ----
        Map<Long, Long> personalNotes = loadNoteIds(userId, TimetableSlotKind.PERSONAL,
                personalEntries.stream().map(e -> e.slot().getId()).toList(), today);
        Map<Long, Long> teamNotes = loadNoteIds(userId, TimetableSlotKind.TEAM,
                teamEntries.stream().map(e -> e.slot().getId()).toList(), today);
        Set<Long> noteIdsWithAttachments = loadNoteIdsHavingAttachments(personalNotes, teamNotes);
        Map<Long, List<TimetableChangeEntity>> changesByTimetable = loadChanges(
                personalEntries.stream().map(e -> e.slot().getLinkedTimetableId()).toList(), today);

        List<DashboardTimetableTodayResponse.TimetableTodayItem> items = new ArrayList<>();
        for (PersonalEntry e : personalEntries) {
            Long noteId = personalNotes.get(e.slot().getId());
            items.add(buildPersonalItem(e.personal(), e.slot(), e.period(), noteId,
                    noteId != null && noteIdsWithAttachments.contains(noteId),
                    // 未リンク(null)で検索すると、空時の Map.of() が null キーで NPE を投げるため分岐する
                    e.slot().getLinkedTimetableId() == null
                            ? List.of()
                            : changesByTimetable.getOrDefault(e.slot().getLinkedTimetableId(), List.of()),
                    joinedTeamIdSet));
        }
        for (TeamEntry e : teamEntries) {
            Long noteId = teamNotes.get(e.slot().getId());
            items.add(buildTeamItem(e.teamId(), e.timetable(), e.slot(), noteId,
                    noteId != null && noteIdsWithAttachments.contains(noteId)));
        }

        // 開始時刻ソート（null は末尾）
        items.sort(Comparator.comparing(
                (DashboardTimetableTodayResponse.TimetableTodayItem i) -> i.startTime(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        return new DashboardTimetableTodayResponse(today, firstActivePattern.name(), items);
    }

    /** 本人の ACTIVE かつ today が適用期間内の個人時間割。 */
    private List<PersonalTimetableEntity> effectivePersonals(Long userId, LocalDate today) {
        List<PersonalTimetableEntity> result = new ArrayList<>();
        for (PersonalTimetableEntity personal : personalTimetableRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, PersonalTimetableStatus.ACTIVE)) {
            if (personal.getEffectiveFrom() != null && today.isBefore(personal.getEffectiveFrom())) continue;
            if (personal.getEffectiveUntil() != null && today.isAfter(personal.getEffectiveUntil())) continue;
            result.add(personal);
        }
        return result;
    }

    /** 個人時間割 ID 群の時限を ID 別の period_number マップにする（同一番号の重複は先頭を採用）。 */
    private Map<Long, Map<Integer, PersonalTimetablePeriodEntity>> loadPeriods(List<Long> personalIds) {
        Map<Long, Map<Integer, PersonalTimetablePeriodEntity>> result = new HashMap<>();
        for (PersonalTimetablePeriodEntity p : personalPeriodRepository
                .findByPersonalTimetableIdInOrderByPeriodNumberAsc(personalIds)) {
            result.computeIfAbsent(p.getPersonalTimetableId(), k -> new HashMap<>())
                    .putIfAbsent(p.getPeriodNumber(), p);
        }
        return result;
    }

    /** 所属チーム群の有効なチーム時間割をチーム ID 別に返す（チームが空なら SQL を撃たない）。 */
    private Map<Long, List<TimetableEntity>> loadEffectiveTimetables(List<Long> teamIds, LocalDate today) {
        Map<Long, List<TimetableEntity>> result = new HashMap<>();
        if (teamIds.isEmpty()) {
            return result;
        }
        for (TimetableEntity t : teamTimetableRepository.findEffectiveByTeamIds(teamIds, today)) {
            result.computeIfAbsent(t.getTeamId(), k -> new ArrayList<>()).add(t);
        }
        return result;
    }

    /** スロット ID -> メモ ID（当日限定メモを常設メモより優先。重複行は先頭を採用）。 */
    private Map<Long, Long> loadNoteIds(Long userId, TimetableSlotKind kind, List<Long> slotIds, LocalDate today) {
        if (slotIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> dated = new HashMap<>();
        Map<Long, Long> standing = new HashMap<>();
        for (TimetableSlotUserNoteEntity n : userNoteRepository.findForSlots(userId, kind, slotIds, today)) {
            if (n.getTargetDate() != null) {
                dated.putIfAbsent(n.getSlotId(), n.getId());
            } else {
                standing.putIfAbsent(n.getSlotId(), n.getId());
            }
        }
        Map<Long, Long> result = new HashMap<>(standing);
        result.putAll(dated);
        return result;
    }

    /** 未削除の添付を 1 件以上持つメモ ID の集合。 */
    private Set<Long> loadNoteIdsHavingAttachments(Map<Long, Long> personalNotes, Map<Long, Long> teamNotes) {
        Set<Long> noteIds = new HashSet<>(personalNotes.values());
        noteIds.addAll(teamNotes.values());
        if (noteIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(attachmentRepository.findNoteIdsHavingAttachments(noteIds));
    }

    /** 時間割 ID -> 当日の臨時変更一覧（linked_timetable_id が null のものは対象外）。 */
    private Map<Long, List<TimetableChangeEntity>> loadChanges(List<Long> linkedTimetableIds, LocalDate today) {
        Set<Long> ids = new HashSet<>();
        for (Long id : linkedTimetableIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TimetableChangeEntity>> result = new HashMap<>();
        for (TimetableChangeEntity c : timetableChangeRepository
                .findByTimetableIdInAndTargetDateOrderByPeriodNumber(ids, today)) {
            result.computeIfAbsent(c.getTimetableId(), k -> new ArrayList<>()).add(c);
        }
        return result;
    }

    /**
     * 所属チーム ID 一覧を取得する（teamId が NULL でないもの）。
     */
    private List<Long> listJoinedTeamIds(Long userId) {
        // CMP-027: user_roles ∪ memberships の在籍チーム ID（素メンバー/応援者を取りこぼさない）
        return membershipScopeQueryService.findActiveTeamIds(userId);
    }

    private DashboardTimetableTodayResponse.TimetableTodayItem buildPersonalItem(
            PersonalTimetableEntity personal,
            PersonalTimetableSlotEntity slot,
            PersonalTimetablePeriodEntity period,
            Long noteId,
            boolean hasAttachments,
            List<TimetableChangeEntity> changes,
            Set<Long> joinedTeamIds) {
        LocalTime startTime = period != null ? period.getStartTime() : null;
        LocalTime endTime = period != null ? period.getEndTime() : null;
        String periodLabel = period != null ? period.getLabel() : null;

        // F03.15 Phase 4: linked_timetable_id 経由で当日の臨時変更を引く
        Boolean isChanged = Boolean.FALSE;
        Object change = null;
        if (slot.getLinkedTimetableId() != null) {
            // DAY_OFF 優先
            TimetableChangeEntity dayOff = changes.stream()
                    .filter(c -> c.getChangeType() == TimetableChangeType.DAY_OFF)
                    .findFirst()
                    .orElse(null);
            TimetableChangeEntity periodMatch = changes.stream()
                    .filter(c -> c.getPeriodNumber() != null
                            && c.getPeriodNumber().equals(slot.getPeriodNumber()))
                    .findFirst()
                    .orElse(null);
            TimetableChangeEntity effective = dayOff != null ? dayOff : periodMatch;
            if (effective != null) {
                isChanged = Boolean.TRUE;
                change = buildChangeMap(effective);
            }
        }

        // F03.15 Phase 4: linked_team_id がセットされているのに自分が現在 MEMBER でない場合は link_revoked
        // （所属判定は existsByUserIdAndTeamId と同一条件の findTeamIdsByUserId の結果で行う）
        Boolean linkRevoked = Boolean.FALSE;
        if (slot.getLinkedTeamId() != null && !joinedTeamIds.contains(slot.getLinkedTeamId())) {
            linkRevoked = Boolean.TRUE;
        }

        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                "PERSONAL",
                null, null,
                personal.getId(),
                null,
                slot.getId(),
                periodLabel,
                slot.getPeriodNumber(),
                startTime,
                endTime,
                slot.getSubjectName(),
                slot.getCourseCode(),
                slot.getTeacherName(),
                slot.getRoomName(),
                slot.getCredits(),
                slot.getColor(),
                slot.getLinkedTeamId(),
                isChanged,
                change,
                linkRevoked,
                noteId,
                hasAttachments
        );
    }

    private Map<String, Object> buildChangeMap(TimetableChangeEntity change) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("change_type", change.getChangeType().name());
        m.put("reason", change.getReason());
        m.put("subject_name", change.getSubjectName());
        m.put("teacher_name", change.getTeacherName());
        m.put("room_name", change.getRoomName());
        return m;
    }

    private DashboardTimetableTodayResponse.TimetableTodayItem buildTeamItem(
            Long teamId,
            TimetableEntity timetable,
            TimetableSlotEntity slot,
            Long noteId,
            boolean hasAttachments) {
        return new DashboardTimetableTodayResponse.TimetableTodayItem(
                "TEAM",
                teamId,
                timetable.getName(),
                null,
                timetable.getId(),
                slot.getId(),
                null,
                slot.getPeriodNumber(),
                null,
                null,
                slot.getSubjectName(),
                null,
                slot.getTeacherName(),
                slot.getRoomName(),
                null,
                slot.getColor(),
                null,
                Boolean.FALSE,
                null,
                Boolean.FALSE,
                noteId,
                hasAttachments
        );
    }

    /**
     * A/B 週パターン解決（PersonalTimetableSlotService と同ロジック）。
     */
    private WeekPattern resolveWeekPattern(PersonalTimetableEntity tt, LocalDate date) {
        if (!Boolean.TRUE.equals(tt.getWeekPatternEnabled())
                || tt.getWeekPatternBaseDate() == null) {
            return WeekPattern.EVERY;
        }
        long weeksBetween = ChronoUnit.WEEKS.between(
                tt.getWeekPatternBaseDate().with(DayOfWeek.MONDAY),
                date.with(DayOfWeek.MONDAY));
        return (Math.floorMod(weeksBetween, 2) == 0) ? WeekPattern.A : WeekPattern.B;
    }
}
