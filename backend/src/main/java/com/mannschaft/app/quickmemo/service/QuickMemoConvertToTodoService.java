package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoRequest;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoResponse;
import com.mannschaft.app.quickmemo.entity.QuickMemoTagLinkEntity;
import com.mannschaft.app.quickmemo.entity.TodoTagLinkEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import com.mannschaft.app.quickmemo.repository.TodoTagLinkRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ポイっとメモ → TODO 昇格サービス。
 * SERIALIZABLE + Deadlock Retry で、並行する削除と二重実行を防ぐ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuickMemoConvertToTodoService {

    private final QuickMemoRepository memoRepository;
    private final TodoRepository todoRepository;
    private final QuickMemoTagLinkRepository memoTagLinkRepository;
    private final TodoTagLinkRepository todoTagLinkRepository;
    private final TagRepository tagRepository;
    private final AuditLogService auditLogService;

    /**
     * メモを TODO に昇格させる。
     * - メモの status を CONVERTED に変更
     * - タグを todo_tag_links にコピー
     * - 排他制御で二重変換・同時削除と競合しない
     */
    @Retryable(
            retryFor = {DeadlockLoserDataAccessException.class, CannotAcquireLockException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 100, multiplier = 2, maxDelay = 1000)
    )
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ConvertToTodoResponse convertToTodo(Long memoId, Long userId, ConvertToTodoRequest req) {
        // SELECT FOR UPDATE で同時削除・同時変換をブロック
        var memo = memoRepository.findByIdAndUserIdForUpdate(memoId, userId)
                .orElseThrow(() -> new BusinessException(QuickMemoErrorCode.MEMO_NOT_FOUND));

        if ("CONVERTED".equals(memo.getStatus())) {
            throw new BusinessException(QuickMemoErrorCode.MEMO_ALREADY_CONVERTED);
        }

        // TODO を作成（PERSONAL スコープ）
        TodoPriority priority = parsePriority(req.priority());
        TodoEntity todo = TodoEntity.builder()
                .scopeType(TodoScopeType.PERSONAL)
                .scopeId(userId)
                .projectId(req.projectId())
                .title(memo.getTitle())
                .description(memo.getBody())
                .status(com.mannschaft.app.todo.TodoStatus.OPEN)
                .priority(priority)
                .dueDate(req.dueDate())
                .createdBy(userId)
                .sortOrder(0)
                .build();
        TodoEntity savedTodo = todoRepository.save(todo);

        // メモのタグを TODO にコピー（スコープ検証込み）
        List<Long> tagIds = memoTagLinkRepository.findTagIdsByMemoId(memoId);
        for (Long tagId : tagIds) {
            if (!todoTagLinkRepository.existsByTodoIdAndTagId(savedTodo.getId(), tagId)) {
                todoTagLinkRepository.save(TodoTagLinkEntity.builder()
                        .todoId(savedTodo.getId())
                        .tagId(tagId)
                        .build());
                tagRepository.incrementUsageCount(tagId);
            }
        }

        // メモを CONVERTED に更新
        memo.convertToTodo(savedTodo.getId());
        memoRepository.save(memo);

        auditLogService.record("QUICK_MEMO_CONVERTED", userId, null, null, null, null, null, null,
                "{\"memoId\":" + memoId + ",\"todoId\":" + savedTodo.getId() + "}");

        log.info("メモ→TODO昇格: memoId={}, todoId={}, userId={}", memoId, savedTodo.getId(), userId);
        return new ConvertToTodoResponse(memoId, savedTodo.getId(), "CONVERTED");
    }

    private TodoPriority parsePriority(String priority) {
        if (priority == null) return TodoPriority.MEDIUM;
        try {
            return TodoPriority.valueOf(priority.toUpperCase());
        } catch (IllegalArgumentException e) {
            return TodoPriority.MEDIUM;
        }
    }
}
