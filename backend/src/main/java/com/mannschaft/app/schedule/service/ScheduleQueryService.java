package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
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
import java.util.Set;

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
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 横断カレンダーへの追加合流 SPI（F06.5 §6.2）。schedule 以外のドメイン（例 reflection）が
     * 自前の可視性フィルタを通したカレンダー印を {@code getMyCalendar} の return 直前に合流する。
     * Spring が全 {@link CalendarEnricher} Bean を収集する（実装が無ければ空リスト）。
     */
    private final List<CalendarEnricher> calendarEnrichers;

    /**
     * チームスコープのスケジュール一覧を取得する。
     *
     * <p>F00 認可基盤連携（2026-05-29）: 取得したスケジュールの ID 群を
     * {@link ContentVisibilityChecker#filterAccessible} に通し、閲覧者
     * {@code viewerUserId} が可視なものだけを返す。これにより
     * {@code minViewRole=ADMIN_ONLY} や {@code visibility=CUSTOM_TEMPLATE} の
     * チーム予定が一覧でも詳細 GET と同じ認可で絞り込まれる
     * （従来は一覧系が {@code assertCanView} をバイパスしていた認可漏れ）。</p>
     *
     * @param teamId       チームID
     * @param from         期間開始
     * @param to           期間終了
     * @param viewerUserId 閲覧者ユーザーID
     * @return 閲覧可能なスケジュール一覧
     */
    public List<ScheduleResponse> listTeamSchedules(
            Long teamId, LocalDateTime from, LocalDateTime to, Long viewerUserId) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(teamId, from, to);
        return toVisibleScheduleResponses(schedules, viewerUserId);
    }

    /**
     * 組織スコープのスケジュール一覧を取得する。
     *
     * <p>F00 認可基盤連携（2026-05-29）: {@link #listTeamSchedules} と同様に
     * 可視性フィルタを適用する。</p>
     *
     * @param orgId        組織ID
     * @param from         期間開始
     * @param to           期間終了
     * @param viewerUserId 閲覧者ユーザーID
     * @return 閲覧可能なスケジュール一覧
     */
    public List<ScheduleResponse> listOrgSchedules(
            Long orgId, LocalDateTime from, LocalDateTime to, Long viewerUserId) {
        List<ScheduleEntity> schedules = scheduleRepository
                .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(orgId, from, to);
        return toVisibleScheduleResponses(schedules, viewerUserId);
    }

    /**
     * チーム/組織スコープのスケジュール群を可視性フィルタにかけ、閲覧可能なものだけを
     * レスポンス DTO に変換する。
     *
     * <p>取得済み ID 群を 1 回の {@link ContentVisibilityChecker#filterAccessible} 呼び出しで
     * 判定する（N+1 を避ける）。fail-closed 原則（Resolver 未登録時は空 Set）に従う。</p>
     *
     * @param schedules    取得済みスケジュール（同一スコープ前提）
     * @param viewerUserId 閲覧者ユーザーID
     * @return 可視なスケジュールの DTO 一覧（元の並び順を維持）
     */
    private List<ScheduleResponse> toVisibleScheduleResponses(
            List<ScheduleEntity> schedules, Long viewerUserId) {
        if (schedules.isEmpty()) {
            return List.of();
        }
        List<Long> ids = schedules.stream().map(ScheduleEntity::getId).toList();
        Set<Long> visibleIds = contentVisibilityChecker
                .filterAccessible(ReferenceType.SCHEDULE, ids, viewerUserId);
        return schedules.stream()
                .filter(s -> visibleIds.contains(s.getId()))
                .map(this::toScheduleResponse)
                .toList();
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

        // 個人スケジュールは所有者本人（userId）でのみ取得するため可視性フィルタ対象外。
        List<ScheduleEntity> personalSchedules = scheduleRepository
                .findByUserIdAndStartAtBetweenOrderByStartAtAsc(userId, from, to);
        personalSchedules.forEach(s -> entries.add(toCalendarEntry(s, SCOPE_TYPE_PERSONAL, userId)));

        // 所属チームのスケジュールを取得（スコープ別の集約に scopeId を保持する）。
        List<ScopedSchedule> teamScoped = new ArrayList<>();
        List<UserRoleEntity> teamRoles = userRoleRepository.findByUserIdAndTeamIdIsNotNull(userId);
        for (UserRoleEntity role : teamRoles) {
            scheduleRepository
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(role.getTeamId(), from, to)
                    .forEach(s -> teamScoped.add(new ScopedSchedule(s, "TEAM", role.getTeamId())));
        }

        // 所属組織のスケジュールを取得。
        List<ScopedSchedule> orgScoped = new ArrayList<>();
        List<UserRoleEntity> orgRoles = userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(userId);
        for (UserRoleEntity role : orgRoles) {
            scheduleRepository
                    .findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(role.getOrganizationId(), from, to)
                    .forEach(s -> orgScoped.add(new ScopedSchedule(s, "ORGANIZATION", role.getOrganizationId())));
        }

        // F00 認可基盤連携（2026-05-29）: team/org のチーム横断スケジュールは
        // visibility 無視で表示されていた認可漏れがあったため、ID 群を
        // filterAccessible に通して可視なものだけを採用する（team で 1 回・org で 1 回、
        // ループ内 per-item 呼び出しは避ける）。個人予定は本人取得のため対象外。
        addVisibleEntries(teamScoped, userId, entries);
        addVisibleEntries(orgScoped, userId, entries);

        // F06.5 §6.2: 既存 schedule 合流（Long 経路）を一切改変せず、return 直前に独立 enrich パスで
        // 他ドメイン（reflection 等）のカレンダー印を合流する。各 enricher は自ドメインの UUID 経路
        // 可視性フィルタ（F00 filterAccessibleUuid）を通したエントリ（id=null＋referenceUuid 識別）を返す。
        // enricher 個別の失敗で本体カレンダー（schedule 行）が落ちないよう fail-safe で握る（漏洩でなく欠落側に倒す）。
        if (calendarEnrichers != null) {
            for (CalendarEnricher enricher : calendarEnrichers) {
                try {
                    entries.addAll(enricher.enrich(userId, from, to));
                } catch (RuntimeException e) {
                    log.warn("カレンダー enrich 失敗（schedule 本体は継続）: enricher={}, userId={}",
                            enricher.getClass().getName(), userId, e);
                }
            }
        }

        return entries;
    }

    /**
     * スコープ付きスケジュール群を {@link ContentVisibilityChecker#filterAccessible} で
     * 一括判定し、可視なものだけを {@code entries} に追加する。
     *
     * @param scoped  判定対象のスコープ付きスケジュール
     * @param userId  閲覧者ユーザーID
     * @param entries 追加先のエントリーリスト
     */
    private void addVisibleEntries(
            List<ScopedSchedule> scoped, Long userId, List<CalendarEntryResponse> entries) {
        if (scoped.isEmpty()) {
            return;
        }
        List<Long> ids = scoped.stream().map(sc -> sc.schedule().getId()).toList();
        Set<Long> visibleIds = contentVisibilityChecker
                .filterAccessible(ReferenceType.SCHEDULE, ids, userId);
        scoped.stream()
                .filter(sc -> visibleIds.contains(sc.schedule().getId()))
                .forEach(sc -> entries.add(
                        toCalendarEntry(sc.schedule(), sc.scopeType(), sc.scopeId())));
    }

    /**
     * 可視性フィルタ用に、スケジュールと表示スコープ（TEAM/ORGANIZATION）を束ねる内部レコード。
     */
    private record ScopedSchedule(ScheduleEntity schedule, String scopeType, Long scopeId) {
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
