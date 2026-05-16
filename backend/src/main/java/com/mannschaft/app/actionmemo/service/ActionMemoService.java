package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.dto.ActionMemoListResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoResponse;
import com.mannschaft.app.actionmemo.dto.ActionMemoTagSummary;
import com.mannschaft.app.actionmemo.dto.CreateActionMemoRequest;
import com.mannschaft.app.actionmemo.dto.LinkTodoRequest;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoRequest;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagEntity;
import com.mannschaft.app.actionmemo.entity.ActionMemoTagLinkEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.TodoStatusChangeRequest;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.TodoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F02.5 行動メモサービス。
 *
 * <p>設計書 §5 §6 に厳密に従い、以下を保証する:</p>
 * <ul>
 *   <li>{@code currentUser.id == memo.userId} の所有者一致検証（不一致は 404）</li>
 *   <li>{@code mood_enabled = false} ユーザーの mood を silent に NULL 化（400 を返さない）</li>
 *   <li>{@code related_todo_id} のスコープ整合性検証（PERSONAL かつ自分所有）</li>
 *   <li>1日 200 件上限</li>
 *   <li>未来日付のバリデーション</li>
 *   <li>{@code memo_date} 省略時は JST の今日に自動セット</li>
 *   <li>ログ出力時の content マスキング（INFO: memoId/userId/length のみ、ERROR: 先頭20文字+...）</li>
 * </ul>
 *
 * <p><b>Phase 1 スコープ外</b>: {@code publishDaily} メソッドは Phase 2 で実装する。
 * タグ系の作成・更新 API は Phase 4 で実装する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoService {

    /** 設計書 §3: 1日あたりのメモ数上限 */
    private static final int DAILY_MEMO_LIMIT = 200;

    /** 1ページあたりのデフォルト/最大件数 */
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 200;

    /** ログ出力時の content マスキング上限（ERROR レベル用） */
    private static final int CONTENT_ERROR_LOG_MAX_LENGTH = 20;

    /** JST タイムゾーン（設計書 §3 memo_date の自動セット） */
    private static final ZoneId ZONE_JST = ZoneId.of("Asia/Tokyo");

    private final ActionMemoRepository memoRepository;
    private final ActionMemoTagRepository tagRepository;
    private final ActionMemoTagLinkRepository tagLinkRepository;
    private final TodoRepository todoRepository;
    private final TodoService todoService;
    private final UserRoleRepository userRoleRepository;
    private final ActionMemoSettingsService settingsService;
    private final AuditLogService auditLogService;
    private final ActionMemoMetrics metrics;

    // ==================================================================
    // 作成
    // ==================================================================

    /**
     * 行動メモを1件作成する。
     *
     * <p>Phase 3 拡張: category / duration_minutes / progress_rate / completes_todo に対応。</p>
     */
    // TODO: actionmemoドメインがtodoドメイン(TodoRepository/TodoService)・timelineドメイン(TimelinePostRepository)・roleドメイン(UserRoleRepository)・organizationドメイン(OrganizationRepository)・authドメイン(AuditLogService)をまたいでいる。将来はイベント駆動で分離予定
    @Transactional
    public ActionMemoResponse createMemo(CreateActionMemoRequest request, Long userId) {
        // 1. 本文の空チェック（@NotBlank に加えて Service 層でも保険）
        if (request.getContent() == null || request.getContent().isBlank()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_EMPTY);
        }
        if (request.getContent().length() > 5000) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_TOO_LONG);
        }

        // 2. memo_date のデフォルト設定 + 未来日付バリデーション
        LocalDate today = LocalDate.now(ZONE_JST);
        LocalDate memoDate = request.getMemoDate() != null ? request.getMemoDate() : today;
        if (memoDate.isAfter(today)) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_FUTURE_DATE);
        }

        // 3. 1日 200 件上限チェック
        long dailyCount = memoRepository.countByUserIdAndMemoDateAndDeletedAtIsNull(userId, memoDate);
        if (dailyCount >= DAILY_MEMO_LIMIT) {
            metrics.incrementDailyLimitExceeded();
            log.warn("行動メモ1日上限到達: userId={}, memoDate={}, currentCount={}",
                    userId, memoDate, dailyCount);
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_DAILY_LIMIT_EXCEEDED);
        }

        // 4. mood の silent ignore 処理
        ActionMemoMood mood = resolveMood(userId, request.getMood());

        // 5. related_todo_id のスコープ整合性検証
        if (request.getRelatedTodoId() != null) {
            validateTodoScope(request.getRelatedTodoId(), userId);
        }

        // 6. タグの所有権検証
        List<ActionMemoTagEntity> tagEntities = validateAndFetchTags(request.getTagIds(), userId);

        // Phase 3: progress_rate / completes_todo バリデーション
        validatePhase3Fields(request.getProgressRate(), request.isCompletesTodo(),
                request.getRelatedTodoId());

        // Phase 3: category 解決（省略時は設定の defaultCategory を適用）
        ActionMemoCategory category = resolveCategory(request.getCategory(), userId);

        // Phase 4-α: 組織スコープ検証
        if (request.getOrganizationId() != null) {
            if (!userRoleRepository.existsByUserIdAndOrganizationId(userId, request.getOrganizationId())) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ORG_NOT_FOUND);
            }
        }
        com.mannschaft.app.actionmemo.enums.OrgVisibility orgVisibility =
                (request.getOrganizationId() != null && request.getOrgVisibility() != null)
                        ? request.getOrgVisibility()
                        : (request.getOrganizationId() != null
                                ? com.mannschaft.app.actionmemo.enums.OrgVisibility.TEAM_ONLY
                                : null);

        // 7. エンティティ保存
        ActionMemoEntity entity = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(request.getContent())
                .category(category)
                .durationMinutes(request.getDurationMinutes())
                .progressRate(request.getProgressRate())
                .mood(mood)
                .relatedTodoId(request.getRelatedTodoId())
                .completesTodo(request.isCompletesTodo())
                .organizationId(request.getOrganizationId())
                .orgVisibility(orgVisibility)
                .build();
        ActionMemoEntity saved = memoRepository.save(entity);

        // 8. タグ紐付け保存
        if (!tagEntities.isEmpty()) {
            for (ActionMemoTagEntity tag : tagEntities) {
                tagLinkRepository.save(ActionMemoTagLinkEntity.builder()
                        .memoId(saved.getId())
                        .tagId(tag.getId())
                        .build());
            }
        }

        // Phase 3: progress_rate 伝播（同一トランザクション内）
        if (request.getProgressRate() != null && request.getRelatedTodoId() != null) {
            todoService.setProgressRate(request.getRelatedTodoId(), request.getProgressRate());
        }

        // Phase 3: completes_todo → TODO を COMPLETED に遷移（同一トランザクション内）
        if (request.isCompletesTodo() && request.getRelatedTodoId() != null) {
            completeTodoFromMemo(saved.getId(), request.getRelatedTodoId(), userId);
        }

        // 9. メトリクス + ログ（content マスキング）
        metrics.incrementCreated();
        log.info("行動メモ作成: memoId={}, userId={}, memoDate={}, length={}, category={}",
                saved.getId(), userId, memoDate, saved.getContent().length(), category);

        // 10. 監査ログ記録（非同期 fire-and-forget）
        auditLogService.record(
                "ACTION_MEMO_CREATED",
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                String.format("{\"source\":\"ACTION_MEMO\",\"source_id\":%d,\"event\":\"CREATED\",\"category\":\"%s\"}",
                        saved.getId(), category != null ? category.name() : "")
        );

        return toResponse(saved, tagEntities);
    }

    // ==================================================================
    // 取得
    // ==================================================================

    /**
     * 自分のメモ1件を取得する。他人のメモは 404。
     */
    public ActionMemoResponse getMemo(Long memoId, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(memo, tags);
    }

    /**
     * Phase 4-α: 自分のメモに紐付く監査ログを取得する（折りたたみUI用）。
     *
     * <p>所有者チェック後、{@code audit_logs.metadata} に {@code "source":"ACTION_MEMO","source_id":N}
     * を含むレコードを最新10件返す。</p>
     */
    public List<com.mannschaft.app.auth.dto.AuditLogResponse> getMemoAuditLogs(Long memoId, Long userId) {
        findOwnMemoOrThrow(memoId, userId);
        return auditLogService.findBySourceAndSourceId("ACTION_MEMO", memoId, 10);
    }

    /**
     * 自分のメモ一覧を取得する（クエリフィルタ + カーソルページネーション）。
     *
     * @param userId   現在のユーザー
     * @param date     単日指定（任意、from/to と排他）
     * @param from     期間開始（任意）
     * @param to       期間終了（任意）
     * @param tagId    タグフィルタ（任意。Phase 1 では未使用でも受け取る）
     * @param cursor   カーソル（任意、Long の id）
     * @param limit    取得件数
     */
    public ActionMemoListResponse listMemos(
            Long userId,
            LocalDate date,
            LocalDate from,
            LocalDate to,
            Long tagId,
            String cursor,
            Integer limit) {

        int effectiveLimit = normalizeLimit(limit);
        Long cursorId = parseCursor(cursor);
        // limit+1 件を取って次カーソル有無を判定する
        PageRequest pageable = PageRequest.of(0, effectiveLimit + 1);

        List<ActionMemoEntity> memos;
        if (date != null) {
            memos = memoRepository.findByUserIdAndDateWithCursor(userId, date, cursorId, pageable);
        } else if (from != null && to != null) {
            memos = memoRepository.findByUserIdAndDateRangeWithCursor(userId, from, to, cursorId, pageable);
        } else {
            memos = memoRepository.findByUserIdWithCursor(userId, cursorId, pageable);
        }

        // tagId フィルタ（Phase 1 では簡易実装: アプリ層で絞る）
        if (tagId != null && !memos.isEmpty()) {
            Set<Long> memosWithTag = tagLinkRepository.findByMemoIdIn(
                            memos.stream().map(ActionMemoEntity::getId).toList())
                    .stream()
                    .filter(l -> Objects.equals(l.getTagId(), tagId))
                    .map(ActionMemoTagLinkEntity::getMemoId)
                    .collect(Collectors.toSet());
            memos = memos.stream().filter(m -> memosWithTag.contains(m.getId())).toList();
        }

        // 次カーソル判定
        String nextCursor = null;
        if (memos.size() > effectiveLimit) {
            ActionMemoEntity last = memos.get(effectiveLimit - 1);
            nextCursor = String.valueOf(last.getId());
            memos = memos.subList(0, effectiveLimit);
        }

        // タグの一括取得（N+1 対策）
        List<Long> memoIds = memos.stream().map(ActionMemoEntity::getId).toList();
        Map<Long, List<ActionMemoTagEntity>> tagsByMemoId = fetchTagsForMemos(memoIds);

        List<ActionMemoResponse> data = memos.stream()
                .map(m -> toResponse(m, tagsByMemoId.getOrDefault(m.getId(), List.of())))
                .toList();

        return new ActionMemoListResponse(data, nextCursor);
    }

    // ==================================================================
    // 更新
    // ==================================================================

    /**
     * 自分のメモを更新する。他人のメモは 404。
     *
     * <p>Phase 3 拡張: category / duration_minutes / progress_rate / completes_todo に対応。</p>
     */
    // TODO: actionmemoドメインがtodoドメイン(TodoService)・roleドメイン(UserRoleRepository)・authドメイン(AuditLogService)をまたいでいる。将来はイベント駆動で分離予定
    @Transactional
    public ActionMemoResponse updateMemo(Long memoId, UpdateActionMemoRequest request, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);

        if (request.getContent() != null) {
            if (request.getContent().isBlank()) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_EMPTY);
            }
            if (request.getContent().length() > 5000) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_CONTENT_TOO_LONG);
            }
            memo.setContent(request.getContent());
        }

        if (request.getMemoDate() != null) {
            LocalDate today = LocalDate.now(ZONE_JST);
            if (request.getMemoDate().isAfter(today)) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_FUTURE_DATE);
            }
            memo.setMemoDate(request.getMemoDate());
        }

        if (request.getMood() != null) {
            memo.setMood(resolveMood(userId, request.getMood()));
        }

        if (request.getRelatedTodoId() != null) {
            validateTodoScope(request.getRelatedTodoId(), userId);
            memo.setRelatedTodoId(request.getRelatedTodoId());
        }

        // Phase 3: category 更新
        if (request.getCategory() != null) {
            memo.setCategory(request.getCategory());
        }

        // Phase 3: duration_minutes 更新
        if (request.getDurationMinutes() != null) {
            memo.setDurationMinutes(request.getDurationMinutes());
        }

        // Phase 3: progress_rate バリデーション（更新後の relatedTodoId を参照）
        Long effectiveTodoId = request.getRelatedTodoId() != null
                ? request.getRelatedTodoId() : memo.getRelatedTodoId();
        boolean effectiveCompletesTodo = request.getCompletesTodo() != null
                ? request.getCompletesTodo() : (memo.getCompletesTodo() != null && memo.getCompletesTodo());

        validatePhase3Fields(request.getProgressRate(), effectiveCompletesTodo, effectiveTodoId);

        if (request.getProgressRate() != null) {
            memo.setProgressRate(request.getProgressRate());
        }
        if (request.getCompletesTodo() != null) {
            memo.setCompletesTodo(request.getCompletesTodo());
        }

        // タグの差し替え（送信された場合のみ）
        if (request.getTagIds() != null) {
            List<ActionMemoTagEntity> tagEntities = validateAndFetchTags(request.getTagIds(), userId);
            // 既存リンクを削除して再作成（シンプルな全置換）
            List<ActionMemoTagLinkEntity> existing = tagLinkRepository.findByMemoId(memoId);
            tagLinkRepository.deleteAll(existing);
            for (ActionMemoTagEntity tag : tagEntities) {
                tagLinkRepository.save(ActionMemoTagLinkEntity.builder()
                        .memoId(memoId)
                        .tagId(tag.getId())
                        .build());
            }
        }

        // Phase 4-α: 組織スコープ更新
        if (request.getOrganizationId() != null) {
            long orgId = request.getOrganizationId();
            if (orgId == 0L) {
                memo.setOrganizationId(null);
                memo.setOrgVisibility(null);
            } else {
                if (!userRoleRepository.existsByUserIdAndOrganizationId(userId, orgId)) {
                    throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_ORG_NOT_FOUND);
                }
                memo.setOrganizationId(orgId);
                memo.setOrgVisibility(request.getOrgVisibility() != null
                        ? request.getOrgVisibility()
                        : com.mannschaft.app.actionmemo.enums.OrgVisibility.TEAM_ONLY);
            }
        } else if (request.getOrgVisibility() != null && memo.getOrganizationId() != null) {
            memo.setOrgVisibility(request.getOrgVisibility());
        }

        ActionMemoEntity saved = memoRepository.save(memo);

        // Phase 3: progress_rate 伝播（同一トランザクション内）
        if (request.getProgressRate() != null && effectiveTodoId != null) {
            todoService.setProgressRate(effectiveTodoId, request.getProgressRate());
        }

        // Phase 3: completes_todo → TODO を COMPLETED に遷移（同一トランザクション内）
        if (Boolean.TRUE.equals(request.getCompletesTodo()) && effectiveTodoId != null) {
            completeTodoFromMemo(saved.getId(), effectiveTodoId, userId);
        }

        log.info("行動メモ更新: memoId={}, userId={}, length={}, category={}",
                saved.getId(), userId, saved.getContent().length(), saved.getCategory());

        // 監査ログ記録: 変更フィールドを列挙（非同期 fire-and-forget）
        List<String> changedFields = new ArrayList<>();
        if (request.getContent() != null) changedFields.add("content");
        if (request.getMemoDate() != null) changedFields.add("memo_date");
        if (request.getMood() != null) changedFields.add("mood");
        if (request.getRelatedTodoId() != null) changedFields.add("related_todo_id");
        if (request.getCategory() != null) changedFields.add("category");
        if (request.getDurationMinutes() != null) changedFields.add("duration_minutes");
        if (request.getProgressRate() != null) changedFields.add("progress_rate");
        if (request.getCompletesTodo() != null) changedFields.add("completes_todo");
        if (request.getTagIds() != null) changedFields.add("tag_ids");
        if (request.getOrganizationId() != null) changedFields.add("organization_id");
        auditLogService.record(
                "ACTION_MEMO_UPDATED",
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                String.format("{\"source\":\"ACTION_MEMO\",\"source_id\":%d,\"event\":\"UPDATED\",\"fields_changed\":\"%s\"}",
                        memoId, String.join(",", changedFields))
        );

        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(saved, tags);
    }

    // ==================================================================
    // 削除
    // ==================================================================

    /**
     * 自分のメモを論理削除する。他人のメモは 404。
     */
    // TODO: actionmemoドメインとauthドメイン(AuditLogService)をまたいでいる。将来はActionMemoDeletedEventで分離予定
    @Transactional
    public void deleteMemo(Long memoId, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        memo.softDelete();
        memoRepository.save(memo);
        log.info("行動メモ削除: memoId={}, userId={}", memoId, userId);

        // 監査ログ記録（非同期 fire-and-forget）
        auditLogService.record(
                "ACTION_MEMO_DELETED",
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                String.format("{\"source\":\"ACTION_MEMO\",\"source_id\":%d,\"event\":\"DELETED\"}", memoId)
        );
    }

    // ==================================================================
    // TODO 紐付け
    // ==================================================================

    /**
     * 自分のメモに TODO を紐付ける。他人の TODO / スコープ違反は 404。
     */
    // TODO: actionmemoドメインとtodoドメイン(TodoRepository)をまたいでいる。将来はTodoLinkedEventで分離予定
    @Transactional
    public ActionMemoResponse linkTodo(Long memoId, LinkTodoRequest request, Long userId) {
        ActionMemoEntity memo = findOwnMemoOrThrow(memoId, userId);
        validateTodoScope(request.getTodoId(), userId);

        memo.setRelatedTodoId(request.getTodoId());
        ActionMemoEntity saved = memoRepository.save(memo);

        log.info("行動メモ TODO 紐付け: memoId={}, todoId={}, userId={}",
                memoId, request.getTodoId(), userId);

        List<ActionMemoTagEntity> tags = fetchTagsForMemo(memoId);
        return toResponse(saved, tags);
    }

    // ==================================================================
    // プライベートヘルパー
    // ==================================================================

    /**
     * 所有者一致検証付きのメモ取得。不一致・存在しない・論理削除済みは全て 404。
     */
    private ActionMemoEntity findOwnMemoOrThrow(Long memoId, Long userId) {
        return memoRepository.findByIdAndUserId(memoId, userId)
                .orElseThrow(() -> new BusinessException(ActionMemoErrorCode.ACTION_MEMO_NOT_FOUND));
    }

    /**
     * 設定 OFF のユーザーが mood を送ってきた場合に silent に NULL 化する。
     * 設定 ON の場合はそのまま通す（NULL も許容）。
     */
    private ActionMemoMood resolveMood(Long userId, ActionMemoMood requestedMood) {
        if (requestedMood == null) {
            return null;
        }
        boolean enabled = settingsService.getMoodEnabled(userId);
        return enabled ? requestedMood : null;
    }

    /**
     * 紐付け対象 TODO のスコープを検証する（Phase 4-β 拡張）。
     *
     * <ul>
     *   <li>PERSONAL スコープ: 自分の TODO のみ許可</li>
     *   <li>TEAM スコープ: 自分が所属するチームの TODO のみ許可</li>
     * </ul>
     * 違反時は 404（IDOR 対策）。
     */
    private void validateTodoScope(Long todoId, Long userId) {
        Optional<TodoEntity> todoOpt = todoRepository.findByIdAndDeletedAtIsNull(todoId);
        if (todoOpt.isEmpty()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }
        TodoEntity todo = todoOpt.get();
        if (todo.getScopeType() == TodoScopeType.PERSONAL) {
            if (!Objects.equals(todo.getScopeId(), userId)) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
            }
        } else if (todo.getScopeType() == TodoScopeType.TEAM) {
            // Phase 4-β: TEAM スコープ TODO は所属チームのもののみ許可
            if (!userRoleRepository.existsByUserIdAndTeamId(userId, todo.getScopeId())) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
            }
        } else {
            // ORGANIZATION スコープは未対応
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TODO_NOT_FOUND);
        }
    }

    /**
     * メモ起因の TODO 完了遷移と監査ログ記録を行う。
     *
     * @param memoId   起因メモ ID（監査ログ用）
     * @param todoId   完了させる TODO ID
     * @param userId   操作ユーザー ID
     */
    private void completeTodoFromMemo(Long memoId, Long todoId, Long userId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId).orElse(null);
        if (todo == null) {
            return;
        }
        // 既に COMPLETED なら skip
        if (com.mannschaft.app.todo.TodoStatus.COMPLETED == todo.getStatus()) {
            return;
        }
        todoService.changeStatus(todoId, new TodoStatusChangeRequest("COMPLETED", null), userId);

        // 監査ログ: source = "ACTION_MEMO", source_id = memoId
        auditLogService.record(
                "AUDIT_LOG_TODO_STATUS_CHANGED",
                userId,
                null,
                null,
                null,
                null,
                null,
                null,
                String.format("{\"source\":\"ACTION_MEMO\",\"source_id\":%d,\"todo_id\":%d}", memoId, todoId)
        );
    }

    /**
     * Phase 3 バリデーション。
     *
     * @param progressRate   進捗率（null可）
     * @param completesTodo  TODO完了フラグ
     * @param relatedTodoId  関連TODO ID（null可）
     */
    private void validatePhase3Fields(BigDecimal progressRate, boolean completesTodo, Long relatedTodoId) {
        if (progressRate != null && relatedTodoId == null) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_PROGRESS_REQUIRES_TODO);
        }
        if (completesTodo && relatedTodoId == null) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_COMPLETES_REQUIRES_TODO);
        }
    }

    /**
     * カテゴリを解決する。
     * リクエストで指定されていれば そのまま使用。省略時は settings の defaultCategory を適用。
     */
    private ActionMemoCategory resolveCategory(ActionMemoCategory requestedCategory, Long userId) {
        if (requestedCategory != null) {
            return requestedCategory;
        }
        return settingsService.findSettings(userId)
                .map(s -> s.getDefaultCategory() != null ? s.getDefaultCategory() : ActionMemoCategory.PRIVATE)
                .orElse(ActionMemoCategory.PRIVATE);
    }

    /**
     * タグ ID リストの所有権を検証し、取得する。存在しない/他人のタグが含まれていたら 404。
     */
    private List<ActionMemoTagEntity> validateAndFetchTags(List<Long> tagIds, Long userId) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> distinctIds = tagIds.stream().distinct().toList();
        List<ActionMemoTagEntity> tags = tagRepository.findByIdInAndUserId(distinctIds, userId);
        if (tags.size() != distinctIds.size()) {
            throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_TAG_NOT_FOUND);
        }
        return tags;
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
     * 一括: 複数メモ分のタグ一覧を一度に取得する（N+1 対策）。
     *
     * <p>Phase 4: 論理削除済みタグも含めて取得する。設計書 §3「削除済みタグの API レスポンス表現」に従い、
     * {@code @SQLRestriction} を回避するネイティブクエリ {@code findByIdInIncludingDeleted} を使用する。
     * メモ取得時には削除済みタグも {@code deleted: true} フラグ付きで返す。</p>
     */
    private Map<Long, List<ActionMemoTagEntity>> fetchTagsForMemos(List<Long> memoIds) {
        if (memoIds == null || memoIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<ActionMemoTagLinkEntity> links = tagLinkRepository.findByMemoIdIn(memoIds);
        if (links.isEmpty()) {
            return Collections.emptyMap();
        }
        Set<Long> tagIdSet = links.stream().map(ActionMemoTagLinkEntity::getTagId).collect(Collectors.toSet());
        // Phase 4: 論理削除済みタグも含めて取得（@SQLRestriction を回避）
        List<ActionMemoTagEntity> tags = tagRepository.findByIdInIncludingDeleted(new ArrayList<>(tagIdSet));
        Map<Long, ActionMemoTagEntity> tagById = tags.stream()
                .collect(Collectors.toMap(ActionMemoTagEntity::getId, t -> t));

        Map<Long, List<ActionMemoTagEntity>> result = new HashMap<>();
        for (ActionMemoTagLinkEntity link : links) {
            ActionMemoTagEntity tag = tagById.get(link.getTagId());
            if (tag != null) {
                result.computeIfAbsent(link.getMemoId(), k -> new ArrayList<>()).add(tag);
            }
        }
        return result;
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

    private Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * ERROR ログ用に content を先頭 {@value #CONTENT_ERROR_LOG_MAX_LENGTH} 文字 + "..." で打ち切る。
     * INFO 系では本ヘルパーを使わず length のみを出力する。
     */
    @SuppressWarnings("unused")
    private String maskContentForError(String content) {
        if (content == null) return "";
        if (content.length() <= CONTENT_ERROR_LOG_MAX_LENGTH) return content;
        return content.substring(0, CONTENT_ERROR_LOG_MAX_LENGTH) + "...";
    }
}
