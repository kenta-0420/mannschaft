package com.mannschaft.app.todo;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.dto.CommentResponse;
import com.mannschaft.app.todo.dto.CreateCommentRequest;
import com.mannschaft.app.todo.dto.UpdateCommentRequest;
import com.mannschaft.app.todo.entity.TodoCommentEntity;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoCommentRepository;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.service.TodoCommentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoCommentService} の単体テスト。
 * コメントのCRUD・権限チェックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoCommentService 単体テスト")
class TodoCommentServiceTest {

    @Mock
    private TodoCommentRepository commentRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private TodoCommentService todoCommentService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TODO_ID = 1L;
    private static final Long COMMENT_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long SCOPE_ID = 50L;

    private TodoCommentEntity createComment(Long userId) {
        return TodoCommentEntity.builder()
                .todoId(TODO_ID)
                .userId(userId)
                .body("テストコメント")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private TodoEntity createTodo() {
        return TodoEntity.builder()
                .scopeType(TodoScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .title("テストTODO")
                .status(TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .sortOrder(0)
                .createdBy(USER_ID)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ========================================
    // listComments
    // ========================================

    @Nested
    @DisplayName("listComments")
    class ListComments {

        @Test
        @DisplayName("正常系: コメント一覧が返却される")
        void listComments_正常_一覧返却() {
            // Given
            TodoEntity todo = createTodo();
            TodoCommentEntity comment = createComment(USER_ID);
            Page<TodoCommentEntity> page = new PageImpl<>(List.of(comment));
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(commentRepository.findByTodoIdOrderByCreatedAtAsc(eq(TODO_ID), any(Pageable.class)))
                    .willReturn(page);

            // When
            PagedResponse<CommentResponse> response = todoCommentService.listComments(TODO_ID, 1, 20);

            // Then
            assertThat(response.getData()).hasSize(1);
            assertThat(response.getData().get(0).getBody()).isEqualTo("テストコメント");
            assertThat(response.getMeta().getPage()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void listComments_TODO不在_TODO010例外() {
            // Given
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoCommentService.listComments(TODO_ID, 1, 20))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }

        @Test
        @DisplayName("正常系: コメントなしで空リスト返却")
        void listComments_コメントなし_空リスト() {
            // Given
            TodoEntity todo = createTodo();
            Page<TodoCommentEntity> page = new PageImpl<>(List.of());
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(commentRepository.findByTodoIdOrderByCreatedAtAsc(eq(TODO_ID), any(Pageable.class)))
                    .willReturn(page);

            // When
            PagedResponse<CommentResponse> response = todoCommentService.listComments(TODO_ID, 1, 20);

            // Then
            assertThat(response.getData()).isEmpty();
        }
    }

    // ========================================
    // addComment
    // ========================================

    @Nested
    @DisplayName("addComment")
    class AddComment {

        @Test
        @DisplayName("正常系: コメントが追加される")
        void addComment_正常_追加成功() {
            // Given
            TodoEntity todo = createTodo();
            CreateCommentRequest request = new CreateCommentRequest("新規コメント");
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(commentRepository.save(any(TodoCommentEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<CommentResponse> response = todoCommentService.addComment(TODO_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getBody()).isEqualTo("新規コメント");
            verify(commentRepository).save(any(TodoCommentEntity.class));
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void addComment_TODO不在_TODO010例外() {
            // Given
            CreateCommentRequest request = new CreateCommentRequest("コメント");
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoCommentService.addComment(TODO_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // updateComment
    // ========================================

    @Nested
    @DisplayName("updateComment")
    class UpdateComment {

        @Test
        @DisplayName("正常系: 本人がコメントを更新できる")
        void updateComment_正常_本人更新() {
            // Given
            TodoCommentEntity comment = createComment(USER_ID);
            UpdateCommentRequest request = new UpdateCommentRequest("更新コメント");
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));
            given(commentRepository.save(any(TodoCommentEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // When
            ApiResponse<CommentResponse> response = todoCommentService.updateComment(
                    TODO_ID, COMMENT_ID, request, USER_ID);

            // Then
            assertThat(response.getData().getBody()).isEqualTo("更新コメント");
            verify(commentRepository).save(any(TodoCommentEntity.class));
        }

        @Test
        @DisplayName("異常系: コメント不在でTODO_016例外")
        void updateComment_不在_TODO016例外() {
            // Given
            UpdateCommentRequest request = new UpdateCommentRequest("更新");
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoCommentService.updateComment(TODO_ID, COMMENT_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_016"));
        }

        @Test
        @DisplayName("異常系: 他人のコメント更新でTODO_017例外")
        void updateComment_他人のコメント_TODO017例外() {
            // Given
            TodoCommentEntity comment = createComment(OTHER_USER_ID);
            UpdateCommentRequest request = new UpdateCommentRequest("不正更新");
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));

            // When / Then
            assertThatThrownBy(() -> todoCommentService.updateComment(TODO_ID, COMMENT_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_017"));
        }
    }

    // ========================================
    // deleteComment
    // ========================================

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("正常系: 本人がコメントを削除できる")
        void deleteComment_正常_本人削除() {
            // Given
            TodoCommentEntity comment = createComment(USER_ID);
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));

            // When
            todoCommentService.deleteComment(TODO_ID, COMMENT_ID, USER_ID);

            // Then
            verify(commentRepository).delete(comment);
        }

        @Test
        @DisplayName("正常系: ADMINが他人のコメントを削除できる")
        void deleteComment_正常_ADMIN削除() {
            // Given
            Long adminUserId = 999L;
            TodoCommentEntity comment = createComment(OTHER_USER_ID);
            TodoEntity todo = createTodo();
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));
            given(todoRepository.findById(TODO_ID)).willReturn(Optional.of(todo));
            given(accessControlService.isAdminOrAbove(adminUserId, SCOPE_ID, "TEAM")).willReturn(true);

            // When
            todoCommentService.deleteComment(TODO_ID, COMMENT_ID, adminUserId);

            // Then
            verify(commentRepository).delete(comment);
        }

        @Test
        @DisplayName("異常系: 他人でADMINでもない場合にTODO_017例外")
        void deleteComment_他人非ADMIN_TODO017例外() {
            // Given
            Long nonAdminUserId = 888L;
            TodoCommentEntity comment = createComment(OTHER_USER_ID);
            TodoEntity todo = createTodo();
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));
            given(todoRepository.findById(TODO_ID)).willReturn(Optional.of(todo));
            given(accessControlService.isAdminOrAbove(nonAdminUserId, SCOPE_ID, "TEAM")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> todoCommentService.deleteComment(TODO_ID, COMMENT_ID, nonAdminUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_017"));
        }

        @Test
        @DisplayName("異常系: コメント不在でTODO_016例外")
        void deleteComment_不在_TODO016例外() {
            // Given
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoCommentService.deleteComment(TODO_ID, COMMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_016"));
        }

        @Test
        @DisplayName("異常系: 他人のコメントでTODOが見つからない場合にTODO_017例外")
        void deleteComment_他人でTODO不在_TODO017例外() {
            // Given
            Long nonOwnerUserId = 777L;
            TodoCommentEntity comment = createComment(OTHER_USER_ID);
            given(commentRepository.findByIdAndTodoId(COMMENT_ID, TODO_ID))
                    .willReturn(Optional.of(comment));
            given(todoRepository.findById(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoCommentService.deleteComment(TODO_ID, COMMENT_ID, nonOwnerUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_017"));
        }
    }
}
