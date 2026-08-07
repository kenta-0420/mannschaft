package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.dto.EventCategoryResponse;
import com.mannschaft.app.schedule.dto.ScheduleResponse;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ScheduleAttendanceRepository attendanceRepository;

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
        Set<Long> visibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.SCHEDULE, idsOf(schedules), viewerUserId);
        return toVisibleScheduleResponses(schedules, visibleIds, viewerUserId);
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
        Set<Long> visibleIds = contentVisibilityChecker.filterAccessible(
                ReferenceType.SCHEDULE, idsOf(schedules), viewerUserId);
        return toVisibleScheduleResponses(schedules, visibleIds, viewerUserId);
    }

    /** スケジュール群の ID 一覧を返す（可視性判定を 1 回の呼び出しにまとめるための材料）。 */
    private List<Long> idsOf(List<ScheduleEntity> schedules) {
        return schedules.stream().map(ScheduleEntity::getId).toList();
    }

    /**
     * チーム/組織スコープのスケジュール群を、判定済みの可視 ID 集合で絞り込み
     * レスポンス DTO に変換する。
     *
     * <p>可視性判定そのものは呼び出し元の public 入口
     * （{@link #listTeamSchedules} / {@link #listOrgSchedules}）が
     * {@link ContentVisibilityChecker#filterAccessible} を 1 回だけ呼んで行う
     * （N+1 を避けるとともに、認可判定を入口に置く）。fail-closed 原則
     * （Resolver 未登録時は空 Set）に従い、集合に無いスケジュールは一切返さない。</p>
     *
     * <p>あわせて {@code myAttendanceStatus}（閲覧者自身の出欠状態）も
     * {@link #fetchMyAttendanceStatusByScheduleId} で 1 クエリバッチ取得し合流する
     * （一覧APIが出欠回答済みでも常に null を返していた欠陥の是正。#2453 後続）。
     * 詳細GET（{@code TeamScheduleController#getSchedule} 等）と同じ意味論
     * （出欠レコード自体が存在しない = null、存在すれば実ステータス文字列）に揃える。</p>
     *
     * @param schedules    取得済みスケジュール（同一スコープ前提）
     * @param visibleIds   閲覧可能と判定されたスケジュール ID 集合
     * @param viewerUserId 閲覧者ユーザーID
     * @return 可視なスケジュールの DTO 一覧（元の並び順を維持）
     */
    private List<ScheduleResponse> toVisibleScheduleResponses(
            List<ScheduleEntity> schedules, Set<Long> visibleIds, Long viewerUserId) {
        if (schedules.isEmpty()) {
            return List.of();
        }
        Map<Long, String> myAttendanceStatusByScheduleId =
                fetchMyAttendanceStatusByScheduleId(idsOf(schedules), viewerUserId);
        return schedules.stream()
                .filter(s -> visibleIds.contains(s.getId()))
                .map(s -> toScheduleResponse(s, myAttendanceStatusByScheduleId.get(s.getId())))
                .toList();
    }

    /**
     * スケジュールID群に対する閲覧者自身の出欠状態を 1 クエリでバッチ取得する。
     *
     * <p><b>認可（fail-closed）</b>: userId は常に呼び出し元の {@code viewerUserId} 固定。
     * 他ユーザーの出欠は一切読まない。scheduleIds は呼び出し元（{@link #toVisibleScheduleResponses}）で
     * 既にスコープ内かつ可視性フィルタ済みの ID のみが渡される前提。</p>
     *
     * <p><b>性能</b>: {@link ScheduleAttendanceRepository#findByScheduleIdInAndUserId} を
     * ループ外で 1 本だけ発行する（N+1 回避）。scheduleId をキーに status 文字列を保持する
     * Map を返し、出欠レコードが存在しない scheduleId は Map に存在しない
     * （= {@code get} が null を返す = 詳細GETの「未回答/募集対象外は null」と同じ意味論）。</p>
     *
     * @param scheduleIds 対象スケジュールID群
     * @param viewerUserId 閲覧者ユーザーID
     * @return scheduleId → 出欠ステータス文字列（レコード無しのIDは含まれない）
     */
    private Map<Long, String> fetchMyAttendanceStatusByScheduleId(
            List<Long> scheduleIds, Long viewerUserId) {
        if (scheduleIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ScheduleAttendanceEntity> attendances =
                attendanceRepository.findByScheduleIdInAndUserId(scheduleIds, viewerUserId);
        return attendances.stream()
                .collect(Collectors.toMap(
                        ScheduleAttendanceEntity::getScheduleId,
                        a -> a.getStatus().name(),
                        (a, b) -> a));
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
     *
     * @param entity             変換対象のスケジュール
     * @param myAttendanceStatus 閲覧者自身の出欠ステータス文字列（レコード無し/未対象なら null）。
     *                           呼び出し元（{@link #toVisibleScheduleResponses}）が
     *                           {@link #fetchMyAttendanceStatusByScheduleId} で 1 クエリバッチ取得した
     *                           値を渡す（N+1 回避のため本メソッド内では出欠を取得しない）。
     */
    ScheduleResponse toScheduleResponse(ScheduleEntity entity, String myAttendanceStatus) {
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
                .myAttendanceStatus(myAttendanceStatus)
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
