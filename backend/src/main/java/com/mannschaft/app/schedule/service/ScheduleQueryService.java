package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * スケジュールの取得系・カレンダー集約を担当するサービス。
 *
 * <p>ScheduleService から分割。リファクタリング第6弾（2026-05-17）で
 * 取得系・カレンダー集約ロジックを切り出して責務を分離した。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleQueryService {

    private static final String SCOPE_TYPE_PERSONAL = "PERSONAL";

    private final ScheduleRepository scheduleRepository;
    private final NameResolverService nameResolverService;
    private final UserRoleRepository userRoleRepository;
    private final ScheduleEventCategoryService eventCategoryService;

    /**
     * チームスコープのスケジュール一覧を取得する。
     *
     * @param teamId チームID
     * @param from   期間開始
     * @param to     期間終了
     * @return スケジュール一覧
     */
    public List<ScheduleResponse> listTeamSchedules(Long teamId, LocalDateTime from, LocalDateTime to) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, to);
        return schedules.stream().map(this::toScheduleResponse).toList();
    }

    /**
     * 組織スコープのスケジュール一覧を取得する。
     *
     * @param orgId 組織ID
     * @param from  期間開始
     * @param to    期間終了
     * @return スケジュール一覧
     */
    public List<ScheduleResponse> listOrgSchedules(Long orgId, LocalDateTime from, LocalDateTime to) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, to);
        return schedules.stream().map(this::toScheduleResponse).toList();
    }

    /**
     * ユーザーの横断カレンダーを取得する。個人・チーム・組織スコープのスケジュールを統合して返す。
     *
     * @param userId ユーザーID
     * @param from   期間開始
     * @param to     期間終了
     * @return カレンダーエントリー一覧
     */
    // TODO: scheduleドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    @Timed(value = "mannschaft.repository.query", extraTags = {"operation", "ScheduleService.getMyCalendar"})
    public List<CalendarEntryResponse> getMyCalendar(Long userId, LocalDateTime from, LocalDateTime to) {
        List<CalendarEntryResponse> entries = new ArrayList<>();

        // 個人スケジュール
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to);
        personalSchedules.forEach(s -> entries.add(toCalendarEntry(s, SCOPE_TYPE_PERSONAL, userId)));

        // 所属チームのスケジュールを取得
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        for (UserRoleEntity role : teamRoles) {
            List<ScheduleEntity> teamSchedules = scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), from, to);
            teamSchedules.forEach(s -> entries.add(toCalendarEntry(s, "TEAM", role.getTeamId())));
        }

        // 所属組織のスケジュールを取得
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        for (UserRoleEntity role : orgRoles) {
            List<ScheduleEntity> orgSchedules = scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(role.getOrganizationId(), from, to);
            orgSchedules.forEach(s -> entries.add(toCalendarEntry(s, "ORGANIZATION", role.getOrganizationId())));
        }

        return entries;
    }

    /**
     * エンティティをスケジュール一覧用レスポンスDTOに変換する。
     */
    ScheduleResponse toScheduleResponse(ScheduleEntity entity) {
        EventCategoryResponse categoryResponse = resolveEventCategoryResponse(entity.getEventCategoryId());
        return new ScheduleResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getAllDay(),
                entity.getEventType().name(),
                entity.getStatus().name(),
                entity.getAttendanceRequired(),
                entity.getLocation(),
                entity.getCreatedAt(),
                categoryResponse,
                entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null,
                entity.getSourceScheduleId(),
                null, null, null, null);
    }

    /**
     * eventCategoryId から EventCategoryResponse を生成する。null の場合は null を返す。
     */
    private EventCategoryResponse resolveEventCategoryResponse(Long eventCategoryId) {
        if (eventCategoryId == null) {
            return null;
        }
        try {
            var cat = eventCategoryService.getById(eventCategoryId);
            String scope = cat.isTeamScope() ? "TEAM" : "ORGANIZATION";
            return new EventCategoryResponse(
                    cat.getId(), cat.getName(), cat.getColor(), cat.getIcon(),
                    cat.getIsDayOffCategory(), cat.getSortOrder(), scope);
        } catch (Exception e) {
            // カテゴリが削除済みの場合は null を返す
            return null;
        }
    }

    /**
     * エンティティをカレンダーエントリーレスポンスDTOに変換する。
     */
    private CalendarEntryResponse toCalendarEntry(ScheduleEntity entity, String scopeType, Long scopeId) {
        String iconUrl = nameResolverService.resolveIconUrl(scopeType, scopeId);
        return new CalendarEntryResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStartAt(),
                entity.getEndAt(),
                entity.getAllDay(),
                entity.getEventType().name(),
                entity.getStatus().name(),
                scopeType,
                scopeId,
                nameResolverService.resolveScopeName(scopeType, scopeId),
                null,
                iconUrl);
    }
}
