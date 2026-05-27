package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
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
    private final ScheduleEventCategoryRepository categoryRepository;

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
        return ScheduleResponse.builder()
                .id(entity.getId())
                .content(new ScheduleResponse.ScheduleContentDto(
                        entity.getTitle(),
                        entity.getStatus().name(),
                        entity.getEventType().name(),
                        entity.getLocation(),
                        entity.getAttendanceRequired()))
                .time(new ScheduleResponse.ScheduleTimeDto(
                        entity.getStartAt(), entity.getEndAt(), entity.getAllDay()))
                .scope(new ScheduleResponse.ScheduleScopeDto(null, null))
                .academic(new ScheduleResponse.ScheduleAcademicDto(
                        categoryResponse,
                        entity.getAcademicYear() != null ? entity.getAcademicYear().intValue() : null,
                        entity.getSourceScheduleId()))
                .audit(new ScheduleResponse.ScheduleAuditDto(entity.getCreatedAt(), null))
                .myAttendanceStatus(null)
                .build();
    }

    /**
     * eventCategoryId から EventCategoryResponse を生成する。null または削除済みの場合は null を返す。
     *
     * <p>ScheduleEventCategoryService.getById() は存在しない場合に BusinessException をスローする。
     * 呼び出し元と同一トランザクションで例外が発生すると rollback-only フラグが立ち、
     * UnexpectedRollbackException を引き起こす。
     * Repository の findById() (Optional を返す) を直接使用することで、
     * トランザクションを汚染せずに安全に null を返せる。</p>
     */
    private EventCategoryResponse resolveEventCategoryResponse(Long eventCategoryId) {
        if (eventCategoryId == null) {
            return null;
        }
        return categoryRepository.findById(eventCategoryId)
                .map(cat -> {
                    String scope = cat.isTeamScope() ? "TEAM" : "ORGANIZATION";
                    return new EventCategoryResponse(
                            cat.getId(), cat.getName(), cat.getColor(), cat.getIcon(),
                            cat.getIsDayOffCategory(), cat.getSortOrder(), scope);
                })
                .orElse(null);
    }

    /**
     * エンティティをカレンダーエントリーレスポンスDTOに変換する。
     */
    private CalendarEntryResponse toCalendarEntry(ScheduleEntity entity, String scopeType, Long scopeId) {
        String scopeName = nameResolverService.resolveScopeName(scopeType, scopeId);
        String iconUrl = nameResolverService.resolveIconUrl(scopeType, scopeId);
        return CalendarEntryResponse.builder()
                .id(entity.getId())
                .content(new CalendarEntryResponse.CalendarContentDto(
                        entity.getTitle(), entity.getEventType().name(), entity.getStatus().name()))
                .time(new CalendarEntryResponse.CalendarTimeDto(
                        entity.getStartAt(), entity.getEndAt(), entity.getAllDay()))
                .scope(new CalendarEntryResponse.CalendarScopeDto(scopeType, scopeId, scopeName, iconUrl))
                .myAttendanceStatus(null)
                .build();
    }
}
