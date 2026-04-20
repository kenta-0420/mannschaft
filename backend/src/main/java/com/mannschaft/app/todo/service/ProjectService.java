package com.mannschaft.app.todo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.ProjectVisibility;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.CreateMilestoneRequest;
import com.mannschaft.app.todo.dto.CreateProjectRequest;
import com.mannschaft.app.todo.dto.GatesSummaryResponse;
import com.mannschaft.app.todo.dto.MilestoneResponse;
import com.mannschaft.app.todo.dto.ProjectDetailResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.UpdateMilestoneRequest;
import com.mannschaft.app.todo.dto.UpdateProjectRequest;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import com.mannschaft.app.todo.repository.ProjectMilestoneRepository;
import com.mannschaft.app.todo.repository.ProjectRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * プロジェクトサービス。プロジェクトのCRUD・マイルストーン管理・進捗計算を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService {

    private static final int MAX_ACTIVE_PROJECTS = 20;
    private static final int MAX_MILESTONES_PER_PROJECT = 50;

    private final ProjectRepository projectRepository;
    private final ProjectMilestoneRepository milestoneRepository;
    private final TodoRepository todoRepository;
    private final NameResolverService nameResolverService;
    private final MilestoneGateService milestoneGateService;
    private final AuditLogService auditLogService;

    /**
     * プロジェクト一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータスフィルタ
     * @param page      ページ番号（1始まり）
     * @param size      ページサイズ
     * @return プロジェクト一覧
     */
    public PagedResponse<ProjectResponse> listProjects(TodoScopeType scopeType, Long scopeId,
                                                        ProjectStatus status, int page, int size) {
        PageRequest pageable = PageRequest.of(page - 1, size, Sort.by("dueDate").ascending());
        Page<ProjectEntity> pageResult = projectRepository
                .findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(scopeType, scopeId, status, pageable);

        List<ProjectResponse> responses = pageResult.getContent().stream()
                .map(this::toProjectResponse)
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * プロジェクト詳細を取得する。
     *
     * @param projectId プロジェクトID
     * @return プロジェクト詳細
     */
    public ApiResponse<ProjectDetailResponse> getProject(Long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        List<ProjectMilestoneEntity> milestones = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(projectId);

        List<ProjectDetailResponse.MilestoneDetail> milestoneDetails = milestones.stream()
                .map(m -> {
                    long totalInMilestone = todoRepository.countByMilestoneIdAndDeletedAtIsNull(m.getId());
                    long completedInMilestone = todoRepository.countByMilestoneIdAndStatusAndDeletedAtIsNull(
                            m.getId(), TodoStatus.COMPLETED);
                    BigDecimal rate = totalInMilestone > 0
                            ? BigDecimal.valueOf(completedInMilestone * 100.0 / totalInMilestone)
                                    .setScale(2, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new ProjectDetailResponse.MilestoneDetail(
                            m.getId(), m.getTitle(), m.getDueDate(), m.getIsCompleted(),
                            m.getCompletedAt(), rate, totalInMilestone, completedInMilestone, m.getSortOrder());
                })
                .toList();

        long unassignedTotal = todoRepository.countByProjectIdAndMilestoneIdIsNullAndDeletedAtIsNull(projectId);
        long unassignedCompleted = todoRepository.countByProjectIdAndMilestoneIdIsNullAndStatusAndDeletedAtIsNull(
                projectId, TodoStatus.COMPLETED);

        ProjectDetailResponse response = new ProjectDetailResponse(
                project.getId(), project.getTitle(), project.getDescription(),
                project.getEmoji(), project.getColor(), project.getDueDate(),
                calculateDaysRemaining(project.getDueDate()),
                project.getStatus().name(), project.getProgressRate(),
                project.getTotalTodos(), project.getCompletedTodos(),
                project.getVisibility().name(),
                milestoneDetails,
                new ProjectDetailResponse.UnassignedTodos(unassignedTotal, unassignedCompleted),
                resolveUserInfo(project.getCreatedBy()));

        return ApiResponse.of(response);
    }

    /**
     * プロジェクトを作成する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param request   作成リクエスト
     * @param userId    作成者ID
     * @return 作成されたプロジェクト
     */
    @Transactional
    public ApiResponse<ProjectResponse> createProject(TodoScopeType scopeType, Long scopeId,
                                                       CreateProjectRequest request, Long userId) {
        // ACTIVEプロジェクト上限チェック
        long activeCount = projectRepository.countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
                scopeType, scopeId, ProjectStatus.ACTIVE);
        if (activeCount >= MAX_ACTIVE_PROJECTS) {
            throw new BusinessException(TodoErrorCode.PROJECT_LIMIT_EXCEEDED);
        }

        // 同名チェック
        if (projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                scopeType, scopeId, request.getTitle())) {
            throw new BusinessException(TodoErrorCode.PROJECT_TITLE_DUPLICATE);
        }

        // visibility バリデーション
        ProjectVisibility visibility = request.getVisibility() != null
                ? ProjectVisibility.valueOf(request.getVisibility())
                : ProjectVisibility.MEMBERS_ONLY;
        validateVisibility(scopeType, visibility);

        ProjectEntity project = ProjectEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title(request.getTitle())
                .description(request.getDescription())
                .emoji(request.getEmoji())
                .color(request.getColor())
                .dueDate(request.getDueDate())
                .visibility(visibility)
                .createdBy(userId)
                .build();

        project = projectRepository.save(project);
        log.info("プロジェクト作成: id={}, title={}, scope={}:{}", project.getId(), project.getTitle(), scopeType, scopeId);
        return ApiResponse.of(toProjectResponse(project));
    }

    /**
     * プロジェクトを更新する。
     *
     * @param projectId プロジェクトID
     * @param request   更新リクエスト
     * @return 更新されたプロジェクト
     */
    @Transactional
    public ApiResponse<ProjectResponse> updateProject(Long projectId, UpdateProjectRequest request) {
        ProjectEntity project = findProjectOrThrow(projectId);

        // タイトル変更時の重複チェック
        if (!project.getTitle().equals(request.getTitle()) &&
                projectRepository.existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
                        project.getScopeType(), project.getScopeId(), request.getTitle())) {
            throw new BusinessException(TodoErrorCode.PROJECT_TITLE_DUPLICATE);
        }

        ProjectVisibility visibility = request.getVisibility() != null
                ? ProjectVisibility.valueOf(request.getVisibility())
                : project.getVisibility();
        validateVisibility(project.getScopeType(), visibility);

        ProjectStatus status = request.getStatus() != null
                ? ProjectStatus.valueOf(request.getStatus())
                : project.getStatus();

        project = project.toBuilder()
                .title(request.getTitle())
                .description(request.getDescription())
                .emoji(request.getEmoji())
                .color(request.getColor())
                .dueDate(request.getDueDate())
                .visibility(visibility)
                .status(status)
                .build();

        project = projectRepository.save(project);
        return ApiResponse.of(toProjectResponse(project));
    }

    /**
     * プロジェクトを論理削除する。
     *
     * @param projectId プロジェクトID
     */
    @Transactional
    public void deleteProject(Long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        project.softDelete();
        projectRepository.save(project);
        log.info("プロジェクト削除: id={}", projectId);
    }

    /**
     * プロジェクトを手動完了にする。
     *
     * @param projectId プロジェクトID
     * @return 完了後のプロジェクト
     */
    @Transactional
    public ApiResponse<ProjectResponse> completeProject(Long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        if (project.getStatus() == ProjectStatus.COMPLETED) {
            throw new BusinessException(TodoErrorCode.PROJECT_ALREADY_COMPLETED);
        }
        project.complete();
        project = projectRepository.save(project);
        log.info("プロジェクト完了: id={}", projectId);
        return ApiResponse.of(toProjectResponse(project));
    }

    /**
     * 完了プロジェクトを再開する。
     *
     * @param projectId プロジェクトID
     * @return 再開後のプロジェクト
     */
    @Transactional
    public ApiResponse<ProjectResponse> reopenProject(Long projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        if (project.getStatus() != ProjectStatus.COMPLETED) {
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_COMPLETED);
        }
        project.reopen();
        project = projectRepository.save(project);
        log.info("プロジェクト再開: id={}", projectId);
        return ApiResponse.of(toProjectResponse(project));
    }

    // --- マイルストーン ---

    /**
     * マイルストーン一覧を取得する。
     *
     * @param projectId プロジェクトID
     * @return マイルストーン一覧
     */
    public ApiResponse<List<MilestoneResponse>> listMilestones(Long projectId) {
        findProjectOrThrow(projectId);
        List<MilestoneResponse> responses = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .map(this::toMilestoneResponse)
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * マイルストーンを作成する。
     *
     * @param projectId プロジェクトID
     * @param request   作成リクエスト
     * @return 作成されたマイルストーン
     */
    @Transactional
    public ApiResponse<MilestoneResponse> createMilestone(Long projectId, CreateMilestoneRequest request) {
        findProjectOrThrow(projectId);

        // 上限チェック
        long count = milestoneRepository.countByProjectId(projectId);
        if (count >= MAX_MILESTONES_PER_PROJECT) {
            throw new BusinessException(TodoErrorCode.MILESTONE_LIMIT_EXCEEDED);
        }

        // 同名チェック
        if (milestoneRepository.existsByProjectIdAndTitle(projectId, request.getTitle())) {
            throw new BusinessException(TodoErrorCode.MILESTONE_TITLE_DUPLICATE);
        }

        ProjectMilestoneEntity milestone = ProjectMilestoneEntity.builder()
                .projectId(projectId)
                .title(request.getTitle())
                .dueDate(request.getDueDate())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : (short) 0)
                .build();

        milestone = milestoneRepository.save(milestone);
        return ApiResponse.of(toMilestoneResponse(milestone));
    }

    /**
     * マイルストーンを更新する。
     *
     * @param projectId   プロジェクトID
     * @param milestoneId マイルストーンID
     * @param request     更新リクエスト
     * @return 更新されたマイルストーン
     */
    @Transactional
    public ApiResponse<MilestoneResponse> updateMilestone(Long projectId, Long milestoneId,
                                                           UpdateMilestoneRequest request) {
        findProjectOrThrow(projectId);
        ProjectMilestoneEntity milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND));

        // タイトル変更時の重複チェック
        if (!milestone.getTitle().equals(request.getTitle()) &&
                milestoneRepository.existsByProjectIdAndTitleAndIdNot(projectId, request.getTitle(), milestoneId)) {
            throw new BusinessException(TodoErrorCode.MILESTONE_TITLE_DUPLICATE);
        }

        milestone = milestone.toBuilder()
                .title(request.getTitle())
                .dueDate(request.getDueDate())
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : milestone.getSortOrder())
                .build();

        milestone = milestoneRepository.save(milestone);
        return ApiResponse.of(toMilestoneResponse(milestone));
    }

    /**
     * マイルストーンを削除する。
     *
     * @param projectId   プロジェクトID
     * @param milestoneId マイルストーンID
     */
    @Transactional
    public void deleteMilestone(Long projectId, Long milestoneId) {
        findProjectOrThrow(projectId);
        ProjectMilestoneEntity milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND));
        milestoneRepository.delete(milestone);

        // F02.7: 削除後にロック連鎖を再構築（ON DELETE SET NULL で NULL 化された後続を再評価）
        milestoneGateService.rebuildChain(projectId);
    }

    /**
     * マイルストーンを並び替え、ロック状態を再計算する（F02.7）。
     *
     * <p>リクエストで指定された順序で sort_order を 0, 1, 2, ... に再割当し、
     * 続いて {@link MilestoneGateService#rebuildChain(Long)} を呼んでロック連鎖を再構築する。</p>
     *
     * @param projectId             プロジェクト ID
     * @param milestoneIdsInOrder   並び替え後のマイルストーン ID リスト
     * @return 並び替え後のマイルストーンエンティティリスト（sort_order 昇順）
     */
    @Transactional
    public List<ProjectMilestoneEntity> reorderMilestones(Long projectId, List<Long> milestoneIdsInOrder) {
        findProjectOrThrow(projectId);

        List<ProjectMilestoneEntity> existing = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(projectId);
        Map<Long, ProjectMilestoneEntity> byId = new java.util.HashMap<>();
        for (ProjectMilestoneEntity m : existing) {
            byId.put(m.getId(), m);
        }

        // 全件がプロジェクト内に存在し、件数一致することを検証
        if (milestoneIdsInOrder.size() != existing.size()
                || !byId.keySet().containsAll(milestoneIdsInOrder)) {
            throw new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND);
        }

        for (int i = 0; i < milestoneIdsInOrder.size(); i++) {
            Long mid = milestoneIdsInOrder.get(i);
            ProjectMilestoneEntity m = byId.get(mid);
            ProjectMilestoneEntity updated = m.toBuilder().sortOrder((short) i).build();
            milestoneRepository.save(updated);
        }

        milestoneGateService.rebuildChain(projectId);
        log.info("マイルストーン並び替え完了: projectId={}, count={}", projectId, milestoneIdsInOrder.size());
        return milestoneRepository.findByProjectIdOrderBySortOrderAsc(projectId);
    }

    /**
     * マイルストーンを完了にする。
     *
     * @param projectId   プロジェクトID
     * @param milestoneId マイルストーンID
     * @return 完了後のマイルストーン
     */
    @Transactional
    public ApiResponse<MilestoneResponse> completeMilestone(Long projectId, Long milestoneId) {
        findProjectOrThrow(projectId);
        ProjectMilestoneEntity milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND));

        if (milestone.getIsCompleted()) {
            throw new BusinessException(TodoErrorCode.MILESTONE_ALREADY_COMPLETED);
        }

        milestone.complete();
        milestone = milestoneRepository.save(milestone);

        // F02.7: マイルストーン手動完了時、後続マイルストーンを自動アンロック
        milestoneGateService.unlockSuccessors(milestoneId);

        return ApiResponse.of(toMilestoneResponse(milestone));
    }

    // --- F02.7 ゲート関連 ---

    /**
     * プロジェクトのゲート状態サマリーを取得する（F02.7）。
     *
     * <p>{@code GET /api/v1/teams/{teamId}/projects/{id}/gates} のレスポンスを構築する。
     * 全体進捗率・ゲート完了率・次の関所情報・マイルストーン別サマリーを返す。</p>
     *
     * @param projectId プロジェクト ID
     * @return ゲートサマリー
     */
    public ApiResponse<GatesSummaryResponse> getGatesSummary(Long projectId) {
        findProjectOrThrow(projectId);
        List<ProjectMilestoneEntity> milestones = milestoneRepository
                .findByProjectIdOrderBySortOrderAsc(projectId);

        int totalMilestones = milestones.size();
        int completedMilestones = (int) milestones.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsCompleted()))
                .count();
        int lockedMilestones = (int) milestones.stream()
                .filter(m -> Boolean.TRUE.equals(m.getIsLocked()))
                .count();

        // 全体進捗率: プロジェクト内全 TODO を対象に算出
        long totalTodosAll = 0L;
        long completedTodosAll = 0L;
        java.util.Map<Long, long[]> todoCountsByMilestone = new java.util.HashMap<>();
        for (ProjectMilestoneEntity m : milestones) {
            long total = todoRepository.countByMilestoneIdAndDeletedAtIsNull(m.getId());
            long completed = todoRepository.countByMilestoneIdAndStatusAndDeletedAtIsNull(
                    m.getId(), TodoStatus.COMPLETED);
            todoCountsByMilestone.put(m.getId(), new long[]{total, completed});
            totalTodosAll += total;
            completedTodosAll += completed;
        }
        // マイルストーン未割り当ての TODO も含める
        totalTodosAll += todoRepository.countByProjectIdAndMilestoneIdIsNullAndDeletedAtIsNull(projectId);
        completedTodosAll += todoRepository.countByProjectIdAndMilestoneIdIsNullAndStatusAndDeletedAtIsNull(
                projectId, TodoStatus.COMPLETED);

        BigDecimal overallProgressRate = totalTodosAll > 0
                ? BigDecimal.valueOf(completedTodosAll * 100.0 / totalTodosAll)
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal gateCompletionRate = totalMilestones > 0
                ? BigDecimal.valueOf(completedMilestones * 100.0 / totalMilestones)
                        .setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // マイルストーン ID → Entity のマップ（lockedBy タイトル解決用）
        java.util.Map<Long, ProjectMilestoneEntity> byId = new java.util.HashMap<>();
        for (ProjectMilestoneEntity m : milestones) {
            byId.put(m.getId(), m);
        }

        // 次のゲート（最初のロック中マイルストーン）を探す
        GatesSummaryResponse.NextGate nextGate = null;
        for (ProjectMilestoneEntity m : milestones) {
            if (Boolean.TRUE.equals(m.getIsLocked())) {
                ProjectMilestoneEntity lockedBy = m.getLockedByMilestoneId() != null
                        ? byId.get(m.getLockedByMilestoneId()) : null;
                nextGate = new GatesSummaryResponse.NextGate(
                        m.getId(), m.getTitle(),
                        m.getLockedByMilestoneId(),
                        lockedBy != null ? lockedBy.getTitle() : null,
                        lockedBy != null ? lockedBy.getProgressRate() : null);
                break;
            }
        }

        List<GatesSummaryResponse.MilestoneGateInfo> infos = milestones.stream()
                .map(m -> {
                    long[] counts = todoCountsByMilestone.getOrDefault(m.getId(), new long[]{0L, 0L});
                    long lockedCount = todoRepository
                            .countByMilestoneIdAndMilestoneLockedTrueAndDeletedAtIsNull(m.getId());
                    String lockedByTitle = m.getLockedByMilestoneId() != null
                            ? (byId.containsKey(m.getLockedByMilestoneId())
                                    ? byId.get(m.getLockedByMilestoneId()).getTitle() : null)
                            : null;
                    return new GatesSummaryResponse.MilestoneGateInfo(
                            m.getId(), m.getTitle(), m.getSortOrder(),
                            Boolean.TRUE.equals(m.getIsCompleted()),
                            Boolean.TRUE.equals(m.getIsLocked()),
                            m.getLockedByMilestoneId(), lockedByTitle,
                            m.getProgressRate(), m.getCompletionMode(),
                            counts[0], counts[1], lockedCount,
                            m.getLockedAt(), m.getCompletedAt());
                })
                .toList();

        GatesSummaryResponse response = new GatesSummaryResponse(
                projectId, overallProgressRate, gateCompletionRate,
                totalMilestones, completedMilestones, lockedMilestones,
                nextGate, infos);
        return ApiResponse.of(response);
    }

    /**
     * マイルストーンの完了判定モード（AUTO / MANUAL）を変更する（F02.7）。
     *
     * <p>AUTO → MANUAL は即時反映。MANUAL → AUTO は即座に現在の TODO 完了状況を評価し、
     * 全完了なら自動完了 + 後続アンロックを実行する。</p>
     *
     * @param projectId      プロジェクト ID
     * @param milestoneId    マイルストーン ID
     * @param completionMode 新しい完了モード（AUTO / MANUAL）
     * @return 更新後のマイルストーン
     */
    @Transactional
    public ApiResponse<MilestoneResponse> changeMilestoneCompletionMode(
            Long projectId, Long milestoneId, String completionMode) {
        findProjectOrThrow(projectId);
        ProjectMilestoneEntity milestone = milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND));

        String oldMode = milestone.getCompletionMode();
        ProjectMilestoneEntity updated = milestone.toBuilder()
                .completionMode(completionMode)
                .build();
        updated = milestoneRepository.save(updated);
        log.info("マイルストーン完了モード変更: milestoneId={}, {} -> {}", milestoneId, oldMode, completionMode);

        // 監査ログ: MILESTONE_COMPLETION_MODE_CHANGED（F10.3 連携）
        auditLogService.record(
                "MILESTONE_COMPLETION_MODE_CHANGED",
                null, null, null, null, null, null, null,
                String.format("{\"projectId\":%d,\"milestoneId\":%d,\"oldMode\":\"%s\",\"newMode\":\"%s\"}",
                        projectId, milestoneId, oldMode, completionMode));

        // MANUAL → AUTO の場合、即座に現在の TODO 完了状況を評価する
        if ("AUTO".equals(completionMode) && !"AUTO".equals(oldMode)
                && !Boolean.TRUE.equals(updated.getIsCompleted())) {
            // マイルストーン配下の TODO が無ければ no-op。あれば 1 件拾って評価ルートに乗せる
            Long anyTodoId = pickAnyTodoIdInMilestone(milestoneId);
            if (anyTodoId != null) {
                milestoneGateService.evaluateOnTodoStatusChanged(anyTodoId, null);
            }
        }

        return ApiResponse.of(toMilestoneResponse(updated));
    }

    /**
     * マイルストーン内 TODO を並び替える（F02.7）。
     *
     * <p>指定された todoIds の順序で position を 0, 1, 2, ... に再割当。
     * 全 TODO が当該マイルストーンに属し、論理削除されていないことを検証する。</p>
     *
     * @param projectId   プロジェクト ID
     * @param milestoneId マイルストーン ID
     * @param todoIds     並び替え後の TODO ID リスト
     */
    @Transactional
    public void reorderTodosInMilestone(Long projectId, Long milestoneId, List<Long> todoIds) {
        findProjectOrThrow(projectId);
        milestoneRepository.findByIdAndProjectId(milestoneId, projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.MILESTONE_NOT_FOUND));

        List<com.mannschaft.app.todo.entity.TodoEntity> todos =
                todoRepository.findByMilestoneIdAndDeletedAtIsNull(milestoneId);
        java.util.Set<Long> validIds = new java.util.HashSet<>();
        for (com.mannschaft.app.todo.entity.TodoEntity t : todos) {
            validIds.add(t.getId());
        }
        for (Long id : todoIds) {
            if (!validIds.contains(id)) {
                throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
            }
        }

        java.util.Map<Long, com.mannschaft.app.todo.entity.TodoEntity> byId = new java.util.HashMap<>();
        for (com.mannschaft.app.todo.entity.TodoEntity t : todos) {
            byId.put(t.getId(), t);
        }
        for (int i = 0; i < todoIds.size(); i++) {
            com.mannschaft.app.todo.entity.TodoEntity t = byId.get(todoIds.get(i));
            t.setPosition(i);
            todoRepository.save(t);
        }
        log.info("マイルストーン内 TODO 並び替え完了: milestoneId={}, count={}", milestoneId, todoIds.size());
    }

    /**
     * 指定マイルストーン配下の TODO を 1 件拾う。存在しない場合は null。
     *
     * <p>MANUAL → AUTO モード変更時の進捗再評価トリガーとして使用。TODO が 0 件の場合は評価不要。</p>
     */
    private Long pickAnyTodoIdInMilestone(Long milestoneId) {
        List<com.mannschaft.app.todo.entity.TodoEntity> todos =
                todoRepository.findByMilestoneIdAndDeletedAtIsNull(milestoneId);
        return todos.isEmpty() ? null : todos.get(0).getId();
    }

    // --- プライベートメソッド ---

    /**
     * プロジェクトを取得する。存在しない場合は例外をスローする。
     */
    ProjectEntity findProjectOrThrow(Long projectId) {
        return projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
    }

    /**
     * 残日数を算出する。
     */
    private Long calculateDaysRemaining(LocalDate dueDate) {
        if (dueDate == null) {
            return null;
        }
        return ChronoUnit.DAYS.between(LocalDate.now(), dueDate);
    }

    /**
     * PRIVATEはPERSONALスコープのみ許可のバリデーション。
     */
    private void validateVisibility(TodoScopeType scopeType, ProjectVisibility visibility) {
        if (visibility == ProjectVisibility.PRIVATE && scopeType != TodoScopeType.PERSONAL) {
            throw new BusinessException(TodoErrorCode.PRIVATE_ONLY_FOR_PERSONAL);
        }
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private ProjectResponse toProjectResponse(ProjectEntity entity) {
        long milestoneTotal = milestoneRepository.countByProjectId(entity.getId());
        long milestoneCompleted = milestoneRepository.countByProjectIdAndIsCompletedTrue(entity.getId());

        return new ProjectResponse(
                entity.getId(), entity.getTitle(), entity.getEmoji(), entity.getColor(),
                entity.getDueDate(), calculateDaysRemaining(entity.getDueDate()),
                entity.getStatus().name(), entity.getProgressRate(),
                entity.getTotalTodos(), entity.getCompletedTodos(),
                new ProjectResponse.MilestoneSummary(milestoneTotal, milestoneCompleted),
                resolveUserInfo(entity.getCreatedBy()),
                entity.getCreatedAt());
    }

    /**
     * マイルストーンエンティティをレスポンスDTOに変換する（F02.7 ゲート関連フィールドを含む）。
     *
     * <p>locked_by_milestone_title は同一プロジェクト内のマイルストーンから ID 解決する。
     * locked_todo_count は TodoRepository から取得する。</p>
     */
    MilestoneResponse toMilestoneResponse(ProjectMilestoneEntity entity) {
        String lockedByTitle = null;
        if (entity.getLockedByMilestoneId() != null) {
            lockedByTitle = milestoneRepository.findById(entity.getLockedByMilestoneId())
                    .map(ProjectMilestoneEntity::getTitle)
                    .orElse(null);
        }
        long lockedTodoCount = todoRepository
                .countByMilestoneIdAndMilestoneLockedTrueAndDeletedAtIsNull(entity.getId());

        return new MilestoneResponse(
                entity.getId(), entity.getProjectId(), entity.getTitle(),
                entity.getDueDate(), entity.getSortOrder(), entity.getIsCompleted(),
                entity.getCompletedAt(), entity.getCreatedAt(), entity.getUpdatedAt(),
                entity.getProgressRate(),
                Boolean.TRUE.equals(entity.getIsLocked()),
                entity.getLockedByMilestoneId(),
                lockedByTitle,
                entity.getCompletionMode(),
                lockedTodoCount,
                Boolean.TRUE.equals(entity.getForceUnlocked()),
                entity.getLockedAt(),
                entity.getUnlockedAt());
    }

    private ProjectResponse.UserInfo resolveUserInfo(Long userId) {
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(userId));
        return new ProjectResponse.UserInfo(userId, nameMap.getOrDefault(userId, ""));
    }
}
