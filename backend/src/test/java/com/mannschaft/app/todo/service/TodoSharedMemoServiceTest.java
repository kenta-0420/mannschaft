package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.dto.SharedMemoEntryRequest;
import com.mannschaft.app.todo.dto.SharedMemoEntryResponse;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.entity.TodoSharedMemoEntryEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import com.mannschaft.app.todo.repository.TodoSharedMemoEntryRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoSharedMemoService} の単体テスト。
 * 共有メモのCRUD・権限チェックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoSharedMemoService 単体テスト")
class TodoSharedMemoServiceTest {

    @Mock
    private TodoSharedMemoEntryRepository sharedMemoRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private TodoSharedMemoService todoSharedMemoService;

    private static final Long TODO_ID = 1L;
    private static final Long MEMO_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 200L;
    private static final Long SCOPE_ID = 50L;

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

    private TodoSharedMemoEntryEntity createMemo(Long userId) {
        TodoSharedMemoEntryEntity entity = TodoSharedMemoEntryEntity.builder()
                .todoId(TODO_ID)
                .userId(userId)
                .body("テスト共有メモ")
                .build();
        // IDをリフレクションでセット
        try {
            java.lang.reflect.Field f = TodoSharedMemoEntryEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, MEMO_ID);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("IDフィールドのセットに失敗しました", e);
        }
        return entity;
    }

    @BeforeEach
    void setUp() {
        given(sharedMemoRepository.save(any(TodoSharedMemoEntryEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(nameResolverService.resolveUserDisplayNames(anySet()))
                .willReturn(Map.of(USER_ID, "テストユーザー", OTHER_USER_ID, "他ユーザー"));
    }

    // ========================================
    // addSharedMemo
    // ========================================

    @Nested
    @DisplayName("addSharedMemo（共有メモ追加）")
    class AddSharedMemo {

        @Test
        @DisplayName("正常系: 共有メモが追加される")
        void addSharedMemo_正常_追加成功() {
            // Given
            TodoEntity todo = createTodo();
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("新規共有メモ", null);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(sharedMemoRepository.countByTodoId(TODO_ID)).willReturn(0L);

            // When
            ApiResponse<SharedMemoEntryResponse> response =
                    todoSharedMemoService.addSharedMemo(TODO_ID, USER_ID, request);

            // Then
            assertThat(response.getData().getBody()).isEqualTo("新規共有メモ");
            assertThat(response.getData().getAuthorId()).isEqualTo(USER_ID);
            verify(sharedMemoRepository).save(any(TodoSharedMemoEntryEntity.class));
        }

        @Test
        @DisplayName("異常系: TODO不在でTODO_010例外")
        void addSharedMemo_TODO不在_TODO010例外() {
            // Given
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("メモ", null);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> todoSharedMemoService.addSharedMemo(TODO_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }

        @Test
        @DisplayName("異常系: メモ件数が500件上限に達した場合TODO_052例外")
        void addSharedMemo_500件上限_TODO052例外() {
            // Given
            TodoEntity todo = createTodo();
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("メモ", null);
            given(todoRepository.findByIdAndDeletedAtIsNull(TODO_ID)).willReturn(Optional.of(todo));
            given(sharedMemoRepository.countByTodoId(TODO_ID)).willReturn(500L);

            // When / Then
            assertThatThrownBy(() -> todoSharedMemoService.addSharedMemo(TODO_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_052"));
        }
    }

    // ========================================
    // updateSharedMemo
    // ========================================

    @Nested
    @DisplayName("updateSharedMemo（共有メモ編集）")
    class UpdateSharedMemo {

        @Test
        @DisplayName("正常系: 本人がメモを編集できる")
        void updateSharedMemo_正常_本人編集() {
            // Given
            TodoSharedMemoEntryEntity memo = createMemo(USER_ID);
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("更新メモ", null);
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            // When
            ApiResponse<SharedMemoEntryResponse> response =
                    todoSharedMemoService.updateSharedMemo(TODO_ID, MEMO_ID, USER_ID, request);

            // Then
            verify(sharedMemoRepository).save(any(TodoSharedMemoEntryEntity.class));
        }

        @Test
        @DisplayName("異常系: 投稿者以外はTODO_051例外")
        void updateSharedMemo_投稿者以外_TODO051例外() {
            // Given
            TodoSharedMemoEntryEntity memo = createMemo(OTHER_USER_ID);
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("不正更新", null);
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            // When / Then
            assertThatThrownBy(() ->
                    todoSharedMemoService.updateSharedMemo(TODO_ID, MEMO_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_051"));
        }

        @Test
        @DisplayName("異常系: メモ不在でTODO_050例外")
        void updateSharedMemo_メモ不在_TODO050例外() {
            // Given
            SharedMemoEntryRequest request = new SharedMemoEntryRequest("更新", null);
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() ->
                    todoSharedMemoService.updateSharedMemo(TODO_ID, MEMO_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_050"));
        }
    }

    // ========================================
    // deleteSharedMemo（論理削除）
    // ========================================

    @Nested
    @DisplayName("deleteSharedMemo（共有メモ論理削除）")
    class DeleteSharedMemo {

        @Test
        @DisplayName("正常系: 本人がメモを論理削除できる")
        void deleteSharedMemo_正常_本人削除() {
            // Given
            TodoSharedMemoEntryEntity memo = createMemo(USER_ID);
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            // When
            todoSharedMemoService.deleteSharedMemo(TODO_ID, MEMO_ID, USER_ID);

            // Then: softDelete後にsaveが呼ばれる（論理削除）
            verify(sharedMemoRepository).save(any(TodoSharedMemoEntryEntity.class));
        }

        @Test
        @DisplayName("正常系: ADMINが他人のメモを論理削除できる")
        void deleteSharedMemo_正常_ADMIN削除() {
            // Given
            Long adminUserId = 999L;
            TodoSharedMemoEntryEntity memo = createMemo(OTHER_USER_ID);
            TodoEntity todo = createTodo();
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));
            given(todoRepository.findById(TODO_ID)).willReturn(Optional.of(todo));
            given(accessControlService.isAdminOrAbove(adminUserId, SCOPE_ID, "TEAM")).willReturn(true);

            // When
            todoSharedMemoService.deleteSharedMemo(TODO_ID, MEMO_ID, adminUserId);

            // Then: softDelete後にsaveが呼ばれる
            verify(sharedMemoRepository).save(any(TodoSharedMemoEntryEntity.class));
        }

        @Test
        @DisplayName("異常系: 投稿者以外でADMINでない場合はTODO_051例外")
        void deleteSharedMemo_他人非ADMIN_TODO051例外() {
            // Given
            Long nonAdminUserId = 888L;
            TodoSharedMemoEntryEntity memo = createMemo(OTHER_USER_ID);
            TodoEntity todo = createTodo();
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));
            given(todoRepository.findById(TODO_ID)).willReturn(Optional.of(todo));
            given(accessControlService.isAdminOrAbove(nonAdminUserId, SCOPE_ID, "TEAM")).willReturn(false);

            // When / Then
            assertThatThrownBy(() ->
                    todoSharedMemoService.deleteSharedMemo(TODO_ID, MEMO_ID, nonAdminUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_051"));
        }

        @Test
        @DisplayName("正常系: 論理削除でdeletedAtが設定される")
        void deleteSharedMemo_正常_論理削除フィールド設定() {
            // Given
            TodoSharedMemoEntryEntity memo = createMemo(USER_ID);
            given(sharedMemoRepository.findById(MEMO_ID)).willReturn(Optional.of(memo));

            // When
            todoSharedMemoService.deleteSharedMemo(TODO_ID, MEMO_ID, USER_ID);

            // Then: softDeleteが呼ばれてdeletedAtが設定される
            assertThat(memo.getDeletedAt()).isNotNull();
        }
    }
}
