package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.dto.CommentResponse;
import com.mannschaft.app.todo.dto.CreateCommentRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.UpdateCommentRequest;
import com.mannschaft.app.todo.entity.TodoCommentEntity;
import com.mannschaft.app.todo.repository.TodoCommentRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * TODOコメントサービス。コメントのCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TodoCommentService {

    private final TodoCommentRepository commentRepository;
    private final TodoRepository todoRepository;

    /**
     * コメント一覧を取得する。
     *
     * @param todoId TODO ID
     * @param page   ページ番号（1始まり）
     * @param size   ページサイズ
     * @return コメント一覧
     */
    public PagedResponse<CommentResponse> listComments(Long todoId, int page, int size) {
        verifyTodoExists(todoId);
        Page<TodoCommentEntity> pageResult = commentRepository
                .findByTodoIdOrderByCreatedAtAsc(todoId, PageRequest.of(page - 1, size));

        List<CommentResponse> responses = pageResult.getContent().stream()
                .map(this::toCommentResponse)
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), page, size, pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * コメントを追加する。
     *
     * @param todoId  TODO ID
     * @param request 作成リクエスト
     * @param userId  投稿者ID
     * @return 作成されたコメント
     */
    @Transactional
    public ApiResponse<CommentResponse> addComment(Long todoId, CreateCommentRequest request, Long userId) {
        verifyTodoExists(todoId);

        TodoCommentEntity comment = TodoCommentEntity.builder()
                .todoId(todoId)
                .userId(userId)
                .body(request.getBody())
                .build();

        comment = commentRepository.save(comment);
        log.info("コメント追加: id={}, todoId={}, userId={}", comment.getId(), todoId, userId);
        return ApiResponse.of(toCommentResponse(comment));
    }

    /**
     * コメントを更新する。本人のみ編集可能。
     *
     * @param todoId    TODO ID
     * @param commentId コメントID
     * @param request   更新リクエスト
     * @param userId    操作ユーザーID
     * @return 更新されたコメント
     */
    @Transactional
    public ApiResponse<CommentResponse> updateComment(Long todoId, Long commentId,
                                                       UpdateCommentRequest request, Long userId) {
        TodoCommentEntity comment = commentRepository.findByIdAndTodoId(commentId, todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.COMMENT_NOT_FOUND));

        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(TodoErrorCode.COMMENT_NOT_OWNER);
        }

        comment.updateBody(request.getBody());
        comment = commentRepository.save(comment);
        return ApiResponse.of(toCommentResponse(comment));
    }

    /**
     * コメントを削除する。本人またはADMINが削除可能。
     *
     * @param todoId    TODO ID
     * @param commentId コメントID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteComment(Long todoId, Long commentId, Long userId) {
        TodoCommentEntity comment = commentRepository.findByIdAndTodoId(commentId, todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.COMMENT_NOT_FOUND));

        // TODO: ADMIN権限チェック。現在は本人のみ削除可能
        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException(TodoErrorCode.COMMENT_NOT_OWNER);
        }

        commentRepository.delete(comment);
        log.info("コメント削除: id={}, todoId={}", commentId, todoId);
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
    private CommentResponse toCommentResponse(TodoCommentEntity entity) {
        return new CommentResponse(
                entity.getId(), entity.getTodoId(),
                new ProjectResponse.UserInfo(entity.getUserId(), "TODO:表示名取得"),
                entity.getBody(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
