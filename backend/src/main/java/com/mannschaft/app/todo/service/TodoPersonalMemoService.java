package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.dto.PersonalMemoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoResponse;
import com.mannschaft.app.todo.entity.TodoPersonalMemoEntity;
import com.mannschaft.app.todo.repository.TodoPersonalMemoRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TODO個人メモサービス。
 * 1TODO × 1ユーザー = 1レコードのプライベートメモ管理を担当する。
 * 本人のみ参照・編集・削除可能。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoPersonalMemoService {

    private final TodoPersonalMemoRepository personalMemoRepository;
    private final TodoRepository todoRepository;

    /**
     * 個人メモを取得する（本人のみ）。
     *
     * @param todoId 対象TODO ID
     * @param userId 操作ユーザーID
     * @return 個人メモ
     */
    public ApiResponse<PersonalMemoResponse> getPersonalMemo(Long todoId, Long userId) {
        verifyTodoExists(todoId);
        TodoPersonalMemoEntity memo = personalMemoRepository.findByTodoIdAndUserId(todoId, userId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PERSONAL_MEMO_NOT_FOUND));
        return ApiResponse.of(toResponse(memo));
    }

    /**
     * 個人メモをUPSERTする（存在すれば UPDATE、なければ INSERT）。
     *
     * @param todoId  対象TODO ID
     * @param userId  操作ユーザーID
     * @param request 作成・更新リクエスト
     * @return 作成・更新されたメモ
     */
    @Transactional
    public ApiResponse<PersonalMemoResponse> upsertPersonalMemo(Long todoId, Long userId,
                                                                  PersonalMemoRequest request) {
        verifyTodoExists(todoId);

        TodoPersonalMemoEntity memo = personalMemoRepository
                .findByTodoIdAndUserId(todoId, userId)
                .orElseGet(() -> TodoPersonalMemoEntity.builder()
                        .todoId(todoId)
                        .userId(userId)
                        .memo(request.getMemo())
                        .build());

        // 既存の場合は本文を更新
        if (memo.getId() != null) {
            memo.updateMemo(request.getMemo());
        }

        memo = personalMemoRepository.save(memo);
        log.info("個人メモUPSERT: id={}, todoId={}, userId={}", memo.getId(), todoId, userId);
        return ApiResponse.of(toResponse(memo));
    }

    /**
     * 個人メモを物理削除する。
     *
     * @param todoId 対象TODO ID
     * @param userId 操作ユーザーID
     */
    @Transactional
    public void deletePersonalMemo(Long todoId, Long userId) {
        verifyTodoExists(todoId);
        // 存在確認（存在しなければ404）
        personalMemoRepository.findByTodoIdAndUserId(todoId, userId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PERSONAL_MEMO_NOT_FOUND));

        personalMemoRepository.deleteByTodoIdAndUserId(todoId, userId);
        log.info("個人メモ削除: todoId={}, userId={}", todoId, userId);
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
     * エンティティをレスポンスDTOに変換する。
     */
    private PersonalMemoResponse toResponse(TodoPersonalMemoEntity entity) {
        return new PersonalMemoResponse(
                entity.getId(),
                entity.getTodoId(),
                entity.getMemo(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
