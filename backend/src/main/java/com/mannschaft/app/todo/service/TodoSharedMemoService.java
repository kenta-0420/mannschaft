package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.dto.SharedMemoEntryRequest;
import com.mannschaft.app.todo.dto.SharedMemoEntryResponse;
import com.mannschaft.app.todo.entity.TodoSharedMemoEntryEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.repository.TodoSharedMemoEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * TODO共有メモサービス。
 * チームメンバー全員が参照・投稿可能なスレッド形式のメモ管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoSharedMemoService {

    private static final int MAX_MEMO_COUNT = 500;
    private static final int QUOTED_MEMO_PREVIEW_LENGTH = 100;

    private final TodoSharedMemoEntryRepository sharedMemoRepository;
    private final TodoRepository todoRepository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /**
     * 共有メモ一覧を取得する（時系列昇順、ページネーション）。
     *
     * @param todoId        対象TODO ID
     * @param page          ページ番号（1始まり）
     * @param perPage       ページサイズ
     * @param currentUserId 現在のユーザーID
     * @return 共有メモ一覧
     */
    public PagedResponse<SharedMemoEntryResponse> getSharedMemos(Long todoId, int page, int perPage,
                                                                   Long currentUserId) {
        verifyTodoExists(todoId);
        Page<TodoSharedMemoEntryEntity> pageResult = sharedMemoRepository
                .findByTodoIdOrderByCreatedAtAsc(todoId, PageRequest.of(page - 1, perPage));

        // 全投稿者IDを一括収集して名前解決（N+1防止）
        Set<Long> userIds = pageResult.getContent().stream()
                .map(TodoSharedMemoEntryEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(userIds);

        // 引用元エントリを一括取得
        Set<Long> quotedIds = pageResult.getContent().stream()
                .map(TodoSharedMemoEntryEntity::getQuotedEntryId)
                .filter(id -> id != null)
                .collect(Collectors.toSet());
        Map<Long, TodoSharedMemoEntryEntity> quotedMap = quotedIds.isEmpty()
                ? Map.of()
                : sharedMemoRepository.findAllById(quotedIds).stream()
                        .collect(Collectors.toMap(TodoSharedMemoEntryEntity::getId, e -> e));

        List<SharedMemoEntryResponse> responses = pageResult.getContent().stream()
                .map(entry -> toResponse(entry, nameMap, quotedMap, currentUserId))
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, perPage, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * 共有メモを追加する。
     *
     * @param todoId        対象TODO ID
     * @param userId        投稿者ユーザーID
     * @param request       作成リクエスト
     * @param currentUserId 現在のユーザーID（isOwnMemo判定に使用）
     * @return 作成されたメモ
     */
    @Transactional
    public ApiResponse<SharedMemoEntryResponse> addSharedMemo(Long todoId, Long userId,
                                                               SharedMemoEntryRequest request,
                                                               Long currentUserId) {
        verifyTodoExists(todoId);

        // 500件上限チェック
        long memoCount = sharedMemoRepository.countByTodoId(todoId);
        if (memoCount >= MAX_MEMO_COUNT) {
            throw new BusinessException(TodoErrorCode.SHARED_MEMO_LIMIT_EXCEEDED);
        }

        // 引用元エントリの存在確認と同一TODOスコープチェック（IDOR防止）
        Long quotedEntryId = request.getQuotedEntryId();
        if (quotedEntryId != null) {
            TodoSharedMemoEntryEntity quotedEntry = sharedMemoRepository.findById(quotedEntryId)
                    .orElseThrow(() -> new BusinessException(TodoErrorCode.SHARED_MEMO_NOT_FOUND));
            // 引用元が同じTODOに属していることを確認（IDOR防止）
            if (!quotedEntry.getTodoId().equals(todoId)) {
                throw new BusinessException(TodoErrorCode.SHARED_MEMO_NOT_FOUND);
            }
        }

        // XSSエスケープ
        String escapedMemo = HtmlUtils.htmlEscape(request.getMemo());

        TodoSharedMemoEntryEntity entry = TodoSharedMemoEntryEntity.builder()
                .todoId(todoId)
                .userId(userId)
                .memo(escapedMemo)
                .quotedEntryId(quotedEntryId)
                .build();

        entry = sharedMemoRepository.save(entry);
        log.info("共有メモ追加: id={}, todoId={}, userId={}", entry.getId(), todoId, userId);

        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(userId));
        Map<Long, TodoSharedMemoEntryEntity> quotedMap = quotedEntryId != null
                ? sharedMemoRepository.findById(quotedEntryId).stream()
                        .collect(Collectors.toMap(TodoSharedMemoEntryEntity::getId, e -> e))
                : Map.of();

        return ApiResponse.of(toResponse(entry, nameMap, quotedMap, currentUserId));
    }

    /**
     * 共有メモを編集する。投稿者本人のみ編集可能。
     *
     * @param todoId    対象TODO ID
     * @param memoId    メモID
     * @param userId    操作ユーザーID
     * @param request   更新リクエスト
     * @return 更新されたメモ
     */
    @Transactional
    public ApiResponse<SharedMemoEntryResponse> updateSharedMemo(Long todoId, Long memoId,
                                                                   Long userId,
                                                                   SharedMemoEntryRequest request) {
        TodoSharedMemoEntryEntity entry = findMemoOrThrow(memoId, todoId);

        if (!entry.getUserId().equals(userId)) {
            throw new BusinessException(TodoErrorCode.SHARED_MEMO_NOT_OWNER);
        }

        // 24時間以内のみ編集可能
        if (entry.getCreatedAt().isBefore(LocalDateTime.now().minusHours(24))) {
            throw new BusinessException(TodoErrorCode.SHARED_MEMO_EDIT_EXPIRED);
        }

        // XSSエスケープ
        String escapedMemo = HtmlUtils.htmlEscape(request.getMemo());

        entry.updateMemo(escapedMemo);
        entry = sharedMemoRepository.save(entry);

        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(entry.getUserId()));
        Map<Long, TodoSharedMemoEntryEntity> quotedMap = buildQuotedMap(entry);

        log.info("共有メモ更新: id={}, todoId={}", memoId, todoId);
        return ApiResponse.of(toResponse(entry, nameMap, quotedMap, userId));
    }

    /**
     * 共有メモを論理削除する。投稿者本人またはADMIN/DEPUTY_ADMINが削除可能。
     *
     * @param todoId    対象TODO ID
     * @param memoId    メモID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteSharedMemo(Long todoId, Long memoId, Long userId) {
        TodoSharedMemoEntryEntity entry = findMemoOrThrow(memoId, todoId);

        // 本人またはADMIN/DEPUTY_ADMINが削除可能
        if (!entry.getUserId().equals(userId)) {
            var todo = todoRepository.findById(todoId).orElse(null);
            if (todo == null || !accessControlService.isAdminOrAbove(userId, todo.getScopeId(), todo.getScopeType().name())) {
                throw new BusinessException(TodoErrorCode.SHARED_MEMO_NOT_OWNER);
            }
        }

        entry.softDelete();
        sharedMemoRepository.save(entry);
        log.info("共有メモ論理削除: id={}, todoId={}", memoId, todoId);
    }

    // --- プライベートメソッド ---

    /**
     * TODOの存在を確認する。
     */
    private void verifyTodoExists(Long todoId) {
        todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
    }

    /**
     * 共有メモを取得する。存在しない場合は例外をスローする。
     */
    private TodoSharedMemoEntryEntity findMemoOrThrow(Long memoId, Long todoId) {
        return sharedMemoRepository.findById(memoId)
                .filter(e -> e.getTodoId().equals(todoId))
                .orElseThrow(() -> new BusinessException(TodoErrorCode.SHARED_MEMO_NOT_FOUND));
    }

    /**
     * 引用元エントリのMapを構築する。
     */
    private Map<Long, TodoSharedMemoEntryEntity> buildQuotedMap(TodoSharedMemoEntryEntity entry) {
        if (entry.getQuotedEntryId() == null) {
            return Map.of();
        }
        return sharedMemoRepository.findById(entry.getQuotedEntryId()).stream()
                .collect(Collectors.toMap(TodoSharedMemoEntryEntity::getId, e -> e));
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     *
     * @param entity        エンティティ
     * @param nameMap       ユーザーID→表示名マップ
     * @param quotedMap     引用元エントリマップ
     * @param currentUserId 現在のユーザーID（isOwnMemo判定に使用）
     * @return レスポンスDTO
     */
    private SharedMemoEntryResponse toResponse(TodoSharedMemoEntryEntity entity,
                                                Map<Long, String> nameMap,
                                                Map<Long, TodoSharedMemoEntryEntity> quotedMap,
                                                Long currentUserId) {
        String quotedMemoPreview = null;
        Long quotedEntryId = entity.getQuotedEntryId();
        if (quotedEntryId != null) {
            TodoSharedMemoEntryEntity quoted = quotedMap.get(quotedEntryId);
            if (quoted != null && quoted.getMemo() != null) {
                String raw = quoted.getMemo();
                quotedMemoPreview = raw.length() > QUOTED_MEMO_PREVIEW_LENGTH
                        ? raw.substring(0, QUOTED_MEMO_PREVIEW_LENGTH) + "..."
                        : raw;
            }
        }

        boolean isEditable = entity.getCreatedAt().isAfter(LocalDateTime.now().minusHours(24));
        boolean isOwnMemo = entity.getUserId().equals(currentUserId);

        return new SharedMemoEntryResponse(
                entity.getId(),
                entity.getTodoId(),
                entity.getUserId(),
                nameMap.getOrDefault(entity.getUserId(), ""),
                entity.getMemo(),
                quotedEntryId,
                quotedMemoPreview,
                isEditable,
                isOwnMemo,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeletedAt());
    }
}
