package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CommentResponse;
import com.mannschaft.app.todo.dto.CreateCommentRequest;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.dto.UpdateCommentRequest;
import com.mannschaft.app.todo.entity.TodoCommentEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoCommentRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /**
     * コメント一覧を取得する。
     *
     * <p>認可根治（早馬・BOLA 閉塞）: 呼び出し元 Controller の path scope
     * （{@code teamId}/{@code organizationId}）を受け取り、対象 TODO が当該 scope に
     * 属することの束縛（IDOR 秘匿 404）と、操作ユーザーが当該 scope のメンバーである
     * ことの検証（非メンバー 403）を行う。従来は {@code verifyTodoExists} で
     * 存在確認しかしておらず、TODO の内部 id を知る任意の認証ユーザーが所属外
     * チーム/組織のコメントを閲覧できる BOLA/IDOR が成立していた。</p>
     *
     * @param todoId    Todo ID
     * @param scopeType path のスコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   path のスコープ ID（内部 teamId / organizationId）
     * @param userId    操作ユーザー ID
     * @param page      ページ番号（0始まり）
     * @param size      ページサイズ
     * @return コメント一覧
     */
    public PagedResponse<CommentResponse> listComments(Long todoId, TodoScopeType scopeType, Long scopeId,
                                                       Long userId, int page, int size) {
        verifyScopeAndMembership(todoId, scopeType, scopeId, userId);
        Page<TodoCommentEntity> pageResult = commentRepository
                .findByTodoIdOrderByCreatedAtAsc(todoId, PageRequest.of(page, size));

        List<CommentResponse> responses = pageResult.getContent().stream()
                .map(this::toCommentResponse)
                .toList();

        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                pageResult.getTotalElements(), pageResult.getNumber(), pageResult.getSize(), pageResult.getTotalPages());
        return PagedResponse.of(responses, meta);
    }

    /**
     * コメントを追加する。
     *
     * <p>認可根治（早馬・BOLA 閉塞）: {@link #listComments} と同様に path scope 束縛＋
     * membership 検証を行う。従来は存在確認のみで、所属外チーム/組織の TODO へ
     * 任意の認証ユーザーがコメントを投稿できる BOLA/IDOR が成立していた。</p>
     *
     * @param todoId    Todo ID
     * @param scopeType path のスコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   path のスコープ ID（内部 teamId / organizationId）
     * @param request   作成リクエスト
     * @param userId    投稿者ID
     * @return 作成されたコメント
     */
    @Transactional
    public ApiResponse<CommentResponse> addComment(Long todoId, TodoScopeType scopeType, Long scopeId,
                                                   CreateCommentRequest request, Long userId) {
        verifyScopeAndMembership(todoId, scopeType, scopeId, userId);

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
     * <p>認可根治（早馬・BOLA 閉塞）: 本人照合（{@code COMMENT_NOT_OWNER}）に加え、
     * path scope 束縛＋membership 検証を先行して行う。従来は scope 束縛が欠落しており、
     * 所属外チーム/組織の TODO のコメントを（本人であれば）越境編集できる余地があった。</p>
     *
     * @param todoId    Todo ID
     * @param scopeType path のスコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   path のスコープ ID（内部 teamId / organizationId）
     * @param commentId コメントID
     * @param request   更新リクエスト
     * @param userId    操作ユーザーID
     * @return 更新されたコメント
     */
    @Transactional
    public ApiResponse<CommentResponse> updateComment(Long todoId, TodoScopeType scopeType, Long scopeId,
                                                       Long commentId, UpdateCommentRequest request, Long userId) {
        verifyScopeAndMembership(todoId, scopeType, scopeId, userId);

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
     * @param todoId    Todo ID
     * @param commentId コメントID
     * @param userId    操作ユーザーID
     */
    @Transactional
    public void deleteComment(Long todoId, Long commentId, Long userId) {
        TodoCommentEntity comment = commentRepository.findByIdAndTodoId(commentId, todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.COMMENT_NOT_FOUND));

        // 本人またはADMIN/DEPUTY_ADMINが削除可能
        if (!comment.getUserId().equals(userId)) {
            var todo = todoRepository.findById(todoId).orElse(null);
            if (todo == null || !accessControlService.isAdminOrAbove(userId, todo.getScopeId(), todo.getScopeType().name())) {
                throw new BusinessException(TodoErrorCode.COMMENT_NOT_OWNER);
            }
        }

        commentRepository.delete(comment);
        log.info("コメント削除: id={}, todoId={}", commentId, todoId);
    }

    // --- プライベートメソッド ---

    /**
     * 対象 TODO が path scope に属することを束縛し、操作ユーザーが当該 scope の
     * メンバーであることを検証する（認可根治・早馬 BOLA 閉塞の中核）。
     *
     * <p>検証順:</p>
     * <ol>
     *   <li><b>存在＋scope 束縛</b>: {@code findByIdAndDeletedAtIsNull} で TODO を取得し、
     *       {@code scopeType}/{@code scopeId} が path と一致しなければ
     *       {@link TodoErrorCode#TODO_NOT_FOUND}（404）。他 scope での ID 存在を
     *       漏らさないため 403 ではなく 404 で秘匿する
     *       （{@code TodoService#assertTodoScope} と同一方針）。</li>
     *   <li><b>membership 認可</b>: {@code accessControlService.checkMembership} で
     *       当該 scope の非メンバーを 403（{@code COMMON_002}）にする。
     *       {@code assertTodoScope} は「TODO が指定 scope に属するか」しか見ず
     *       「ユーザーが当該 scope のメンバーか」を検証しないため、scope 束縛だけでは
     *       非メンバーが正しい teamId/orgId を推測して叩くと通ってしまう。ここで
     *       membership を併せて検証して閉塞する。</li>
     * </ol>
     *
     * @param todoId    Todo ID
     * @param scopeType path のスコープ種別（TEAM / ORGANIZATION）
     * @param scopeId   path のスコープ ID（内部 teamId / organizationId）
     * @param userId    操作ユーザー ID
     */
    private void verifyScopeAndMembership(Long todoId, TodoScopeType scopeType, Long scopeId, Long userId) {
        TodoEntity todo = todoRepository.findByIdAndDeletedAtIsNull(todoId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.TODO_NOT_FOUND));
        if (todo.getScopeType() != scopeType
                || !java.util.Objects.equals(todo.getScopeId(), scopeId)) {
            // IDOR 秘匿: 他 scope の TODO id を推測して叩くケースを 404 にまとめる。
            throw new BusinessException(TodoErrorCode.TODO_NOT_FOUND);
        }
        accessControlService.checkMembership(userId, scopeId, scopeType.name());
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private CommentResponse toCommentResponse(TodoCommentEntity entity) {
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(Set.of(entity.getUserId()));
        return new CommentResponse(
                entity.getId(), entity.getTodoId(),
                new ProjectResponse.UserInfo(entity.getUserId(), nameMap.getOrDefault(entity.getUserId(), "")),
                entity.getBody(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
