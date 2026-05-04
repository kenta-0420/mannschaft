package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.PersonalTimetableVisibility;
import com.mannschaft.app.timetable.personal.dto.FamilyWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetablePeriodResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableShareTargetRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F03.15 Phase 5 家族からの個人時間割閲覧サービス。
 *
 * <p>全エンドポイントで設計書 §6.1 / §6.9 に従い、以下の検証条件を <strong>すべて満たす場合のみ</strong>
 * 200 を返す（不一致は <strong>すべて 404 統一</strong> で情報漏洩を防ぐ）:</p>
 * <ul>
 *   <li>{@code teamId} のチームが存在し、{@code template = 'family'}</li>
 *   <li>currentUser がそのチームの MEMBER+</li>
 *   <li>{@code userId}（対象ユーザー）も同じチームの MEMBER+</li>
 *   <li>対象 {@code personal_timetables.visibility = 'FAMILY_SHARED'}
 *       かつ status = 'ACTIVE' かつ未削除</li>
 *   <li>{@code personal_timetable_share_targets} に当該家族チームが含まれる</li>
 * </ul>
 *
 * <p>レスポンス DTO は {@code FamilyPersonalTimetableResponse} と
 * {@code FamilyWeeklyViewResponse} を使用し、メモ・添付・カスタムフィールド・
 * リンク先情報をすべて除外する（DTO レイヤーで強制）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FamilyPersonalTimetableService {

    private static final List<String> WEEK_DOWS =
            List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetableSlotRepository slotRepository;
    private final PersonalTimetablePeriodRepository periodRepository;
    private final PersonalTimetableShareTargetRepository shareTargetRepository;
    private final TeamRepository teamRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * 指定家族チームに共有された対象ユーザーの個人時間割一覧を取得する。
     *
     * <p>条件不一致は全て 404 ({@code PERSONAL_TIMETABLE_NOT_FOUND}) で返す。
     * これにより「対象ユーザーが本機能を使っているか」「共有されているか」を
     * 区別できないようにし、情報漏洩を防ぐ。</p>
     */
    public List<PersonalTimetableEntity> listForFamily(
            Long teamId, Long userId, Long currentUserId) {
        validateAccess(teamId, userId, currentUserId);

        // 当該家族チームに共有されている個人時間割 ID を取得
        List<Long> sharedTimetableIds = shareTargetRepository.findPersonalTimetableIdsByTeamId(teamId);
        if (sharedTimetableIds.isEmpty()) {
            return List.of();
        }

        // 対象ユーザーの ACTIVE 個人時間割のうち、上記 ID + visibility=FAMILY_SHARED のものを返す
        return personalTimetableRepository.findActiveByUserId(userId).stream()
                .filter(pt -> pt.getVisibility() == PersonalTimetableVisibility.FAMILY_SHARED)
                .filter(pt -> sharedTimetableIds.contains(pt.getId()))
                .toList();
    }

    /**
     * 指定家族チームに共有された対象ユーザーの個人時間割の週間ビューを取得する。
     */
    public FamilyWeeklyViewResponse getWeeklyViewForFamily(
            Long teamId, Long userId, Long timetableId, Long currentUserId, LocalDate weekOf) {
        validateAccess(teamId, userId, currentUserId);

        // 対象個人時間割を取得（条件不一致は 404）
        PersonalTimetableEntity timetable = personalTimetableRepository
                .findActiveByIdAndUserId(timetableId, userId)
                .filter(pt -> pt.getVisibility() == PersonalTimetableVisibility.FAMILY_SHARED)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));

        // share_targets に当該家族チームが含まれることを検証
        if (shareTargetRepository
                .findByPersonalTimetableIdAndTeamId(timetable.getId(), teamId)
                .isEmpty()) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }

        LocalDate weekStart = weekOf.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        List<PersonalTimetableSlotEntity> allSlots = slotRepository
                .findByPersonalTimetableIdOrderByDayOfWeekAscPeriodNumberAsc(timetable.getId());
        List<PersonalTimetablePeriodEntity> periods = periodRepository
                .findByPersonalTimetableIdOrderByPeriodNumberAsc(timetable.getId());

        WeekPattern current = resolveWeekPattern(timetable, weekStart);

        Map<String, FamilyWeeklyViewResponse.FamilyDayInfo> days = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate date = weekStart.plusDays(i);
            String dow = WEEK_DOWS.get(i);
            WeekPattern dayPattern = resolveWeekPattern(timetable, date);

            List<FamilyWeeklyViewResponse.FamilySlotInfo> daySlots = allSlots.stream()
                    .filter(s -> dow.equals(s.getDayOfWeek()))
                    .filter(s -> s.getWeekPattern() == WeekPattern.EVERY
                            || s.getWeekPattern() == dayPattern)
                    .map(s -> new FamilyWeeklyViewResponse.FamilySlotInfo(
                            s.getId(),
                            s.getPeriodNumber(),
                            s.getWeekPattern() != null ? s.getWeekPattern().name() : null,
                            s.getSubjectName(),
                            s.getCourseCode(),
                            s.getTeacherName(),
                            s.getRoomName(),
                            s.getCredits(),
                            s.getColor()))
                    .toList();

            days.put(dow, new FamilyWeeklyViewResponse.FamilyDayInfo(date, daySlots));
        }

        return new FamilyWeeklyViewResponse(
                timetable.getId(),
                timetable.getName(),
                weekStart,
                weekEnd,
                Boolean.TRUE.equals(timetable.getWeekPatternEnabled()),
                current.name(),
                periods.stream()
                        .map(p -> new PersonalTimetablePeriodResponse(
                                p.getId(),
                                p.getPeriodNumber(),
                                p.getLabel(),
                                p.getStartTime(),
                                p.getEndTime(),
                                p.getIsBreak()))
                        .toList(),
                days);
    }

    // ---- 共通検証 ----

    /**
     * 家族閲覧 API 共通の事前検証。
     *
     * <ul>
     *   <li>teamId が family テンプレ＆未削除でなければ 404</li>
     *   <li>currentUser がそのチーム MEMBER+ でなければ 404</li>
     *   <li>userId も同じチーム MEMBER+ でなければ 404</li>
     * </ul>
     */
    private void validateAccess(Long teamId, Long userId, Long currentUserId) {
        TeamEntity team = teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));
        if (!PersonalTimetableShareTargetService.FAMILY_TEMPLATE_SLUG.equals(team.getTemplate())) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
        if (!userRoleRepository.existsByUserIdAndTeamId(currentUserId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
        if (!userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND);
        }
    }

    /**
     * A/B 週パターン判定（{@code PersonalTimetableSlotService} と同等のロジック）。
     */
    private WeekPattern resolveWeekPattern(PersonalTimetableEntity timetable, LocalDate date) {
        if (!Boolean.TRUE.equals(timetable.getWeekPatternEnabled())
                || timetable.getWeekPatternBaseDate() == null) {
            return WeekPattern.EVERY;
        }
        long weeksBetween = ChronoUnit.WEEKS.between(
                timetable.getWeekPatternBaseDate().with(DayOfWeek.MONDAY),
                date.with(DayOfWeek.MONDAY));
        return (Math.floorMod(weeksBetween, 2) == 0) ? WeekPattern.A : WeekPattern.B;
    }
}
