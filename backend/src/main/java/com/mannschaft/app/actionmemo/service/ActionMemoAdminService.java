package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoTagSummary;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.TodoService;
import com.mannschaft.app.todo.service.TodoStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.5 行動メモ 管理者機能サービス。
 *
 * <p>チーム管理者向け機能（TODO差し戻し・メンバーメモ一覧）を担当する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoAdminService {

    /** 1ページあたりのデフォルト/最大件数 */
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    private final ActionMemoRepository memoRepository;
    private final ActionMemoTagRepository tagRepository;
    private final ActionMemoTagLinkRepository tagLinkRepository;
    private final TodoRepository todoRepository;
    private final TodoService todoService;
    private final TodoStatusService todoStatusService;
    private final UserRoleRepository userRoleRepository;
    private final AuditLogService auditLogService;

    /**
     * Phase 4-β: チーム管理者が TODO を OPEN に差し戻す。
     *
     * <p>認可: callerUserId が memo.postedTeamId の ADMIN または DEPUTY_ADMIN であること。
     * completesTodo = false のメモは差し戻し対象外（400）。</p>
     *
     * <p><b>判定順序</b>: メモ取得の直後に<b>認可判定を行い</b>、業務状態
     * （{@code completesTodo} / {@code relatedTodoId}）の検証はその後に行う。
     * これは「スコープ外の利用者にはメモの状態を一切開示せず、一律 403 を返す」ことを
     * 保証するための順序である（業務状態に依存してレスポンスが分岐しないようにする）。
     * 認可のスコープはリクエストではなく <b>メモ entity の {@code postedTeamId}</b>
     * から導出する（BOLA 対策）。</p>
     *
     * @param memoId        対象メモ ID
     * @param callerUserId  呼び出し者 ID（管理者）
     */
    // TODO: actionmemoドメインがtodoドメイン(TodoRepository/TodoService)・roleドメイン(UserRoleRepository)・authドメイン(AuditLogService)をまたいでいる。将来はTodoRevertedByAdminEventで分離予定
    @Transactional
    public void revertTodoCompletion(Long memoId, Long callerUserId) {
        // メモ取得（@SQLRestriction で論理削除済みは除外）
        ActionMemoEntity memo = memoRepository.findById(memoId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));

        // 認可: entity 由来の postedTeamId チームの管理者のみ許可（業務状態の検証より前に判定する）
        if (memo.getPostedTeamId() == null
                || userRoleRepository.countTeamAdminByUserIdAndTeamId(callerUserId, memo.getPostedTeamId()) == 0) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_REVERT_NOT_ALLOWED);
        }

        // completesTodo フラグ確認
        if (!Boolean.TRUE.equals(memo.getCompletesTodo())) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_COMPLETED_BY_MEMO);
        }

        Long todoId = memo.getRelatedTodoId();
        if (todoId == null) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_COMPLETED_BY_MEMO);
        }

        // TODO を OPEN に戻す（memo 所有者のIDで操作—TodoService の権限チェックをバイパスするため直接変更）
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId).orElse(null);
        if (todo != null && com.mannschaft.app.todo.TodoStatus.OPEN != todo.getStatus()) {
            todoStatusService.changeStatus(todoId, new TodoStatusChangeRequest("OPEN", null), memo.getUserId());
        }

        // 監査ログ
        auditLogService.record(
                "AUDIT_LOG_TODO_REVERTED_BY_ADMIN",
                callerUserId,
                null,
                null,
                null,
                null,
                null,
                null,
                String.format("{\"source\":\"ACTION_MEMO\",\"memo_id\":%d,\"todo_id\":%d,\"reverted_by\":%d}",
                        memoId, todoId, callerUserId)
        );

        log.info("TODO差し戻し: memoId={}, todoId={}, callerUserId={}", memoId, todoId, callerUserId);
    }

    /**
     * Phase 4-β: 管理職向けダッシュボード — チームメンバーの WORK メモ一覧取得。
     *
     * <p>認可: callerUserId が teamId の ADMIN または DEPUTY_ADMIN であること。
     * フィルタ: category=WORK AND postedTeamId=teamId AND userId=memberId。</p>
     *
     * @param teamId       チーム ID
     * @param memberId     対象メンバーのユーザー ID
     * @param callerUserId 呼び出し者 ID
     * @param cursorId     カーソル（前回最後のメモ ID）
     * @param limit        取得件数
     * @return メモ一覧レスポンス
     */
    public ActionMemoListResponse listTeamMemberMemos(
            Long teamId, Long memberId, Long callerUserId, Long cursorId, int limit) {
        // 管理者権限チェック
        if (userRoleRepository.countTeamAdminByUserIdAndTeamId(callerUserId, teamId) == 0) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_DASHBOARD_FORBIDDEN);
        }

        int effectiveLimit = normalizeLimit(limit);
        List<ActionMemoEntity> memos = memoRepository.findByUserIdAndPostedTeamIdAndCategoryWork(
                memberId, teamId, cursorId, PageRequest.of(0, effectiveLimit + 1));

        boolean hasNext = memos.size() > effectiveLimit;
        List<ActionMemoEntity> page = hasNext ? memos.subList(0, effectiveLimit) : memos;

        List<ActionMemoResponse> responses = page.stream()
                .map(m -> toResponse(m, fetchTagsForMemo(m.getId())))
                .toList();

        String nextCursor = hasNext ? String.valueOf(page.get(page.size() - 1).getId()) : null;
        return new ActionMemoListResponse(responses, nextCursor);
    }

    /**
     * 1メモ分のタグ一覧を取得する。
     *
     * <p>Phase 4: 論理削除済みタグも含めて取得する（設計書 §3「削除済みタグの API レスポンス表現」）。</p>
     */
    private List<ActionMemoTagEntity> fetchTagsForMemo(Long memoId) {
        List<ActionMemoTagLinkEntity> links = tagLinkRepository.findByMemoId(memoId);
        if (links.isEmpty()) {
            return List.of();
        }
        List<Long> tagIds = links.stream().map(ActionMemoTagLinkEntity::getTagId).toList();
        return tagRepository.findByIdInIncludingDeleted(tagIds);
    }

    /**
     * Entity → Response マッピング。
     *
     * <p>Phase 4: 論理削除済みタグも含まれるため {@code deletedAt != null} で
     * {@code deleted} フラグを判定する（設計書 §3「削除済みタグの API レスポンス表現」）。</p>
     *
     * <p>Phase 3: category / durationMinutes / progressRate / completesTodo / postedTeamId を追加。</p>
     */
    private ActionMemoResponse toResponse(ActionMemoEntity memo, List<ActionMemoTagEntity> tags) {
        List<ActionMemoTagSummary> tagSummaries = tags == null ? List.of()
                : tags.stream()
                        .map(t -> new ActionMemoTagSummary(
                                t.getId(), t.getName(), t.getColor(),
                                t.getDeletedAt() != null))
                        .toList();

        return ActionMemoResponse.builder()
                .id(memo.getId())
                .memoDate(memo.getMemoDate())
                .content(memo.getContent())
                .category(memo.getCategory())
                .durationMinutes(memo.getDurationMinutes())
                .progressRate(memo.getProgressRate())
                .mood(memo.getMood())
                .relatedTodoId(memo.getRelatedTodoId())
                .completesTodo(Boolean.TRUE.equals(memo.getCompletesTodo()))
                .timelinePostId(memo.getTimelinePostId())
                .postedTeamId(memo.getPostedTeamId())
                .organizationId(memo.getOrganizationId())
                .orgVisibility(memo.getOrgVisibility())
                .tags(tagSummaries)
                .createdAt(memo.getCreatedAt())
                .updatedAt(memo.getUpdatedAt())
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIST_LIMIT;
        return Math.min(limit, MAX_LIST_LIMIT);
    }
}
