package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoRequest;
import com.mannschaft.app.quickmemo.dto.ConvertToTodoResponse;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.entity.TodoTagLinkEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import com.mannschaft.app.quickmemo.repository.TodoTagLinkRepository;
import com.mannschaft.app.todo.TodoPriority;
import com.mannschaft.app.todo.entity.TodoEntity;
import com.mannschaft.app.todo.repository.TodoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoConvertToTodoService} の単体テスト。
 * F02.5 ポイっとメモ機能のTODO昇格サービス層を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoConvertToTodoService 単体テスト")
class QuickMemoConvertToTodoServiceTest {

    @Mock
    private QuickMemoRepository memoRepository;

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private QuickMemoTagLinkRepository memoTagLinkRepository;

    @Mock
    private TodoTagLinkRepository todoTagLinkRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private QuickMemoConvertToTodoService convertToTodoService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long MEMO_ID = 10L;
    private static final Long TODO_ID = 100L;
    private static final Long TAG_ID = 200L;

    /**
     * UNSORTEDステータスの基本メモエンティティを生成する。
     */
    private QuickMemoEntity buildUnsortedMemo() {
        return QuickMemoEntity.builder()
                .userId(USER_ID)
                .title("テストメモタイトル")
                .body("テストメモ本文")
                .status("UNSORTED")
                .build();
    }

    /**
     * 保存後のTODOエンティティ（ID付き）を生成する。
     * {@code @GeneratedValue} フィールドはDBが払い出すため、リフレクションでIDを設定する。
     */
    private TodoEntity buildSavedTodoEntity(Long id) {
        TodoEntity entity = TodoEntity.builder()
                .scopeType(com.mannschaft.app.todo.TodoScopeType.PERSONAL)
                .scopeId(USER_ID)
                .title("テストメモタイトル")
                .status(com.mannschaft.app.todo.TodoStatus.OPEN)
                .priority(TodoPriority.MEDIUM)
                .createdBy(USER_ID)
                .sortOrder(0)
                .build();
        try {
            Field idField = TodoEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("TodoEntityのIDフィールド設定に失敗しました", e);
        }
        return entity;
    }

    // ========================================
    // convertToTodo
    // ========================================

    @Nested
    @DisplayName("convertToTodo")
    class ConvertToTodo {

        @Test
        @DisplayName("convertToTodo_正常_TODOが作成されメモがCONVERTEDになる")
        void convertToTodo_正常_TODOが作成されメモがCONVERTEDになる() {
            // Given
            QuickMemoEntity memo = buildUnsortedMemo();
            TodoEntity savedTodo = buildSavedTodoEntity(TODO_ID);
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(todoRepository.save(any(TodoEntity.class))).willReturn(savedTodo);
            given(memoTagLinkRepository.findTagIdsByMemoId(MEMO_ID)).willReturn(List.of());
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(memo);

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When
            ConvertToTodoResponse result = convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req);

            // Then
            assertThat(result.memoId()).isEqualTo(MEMO_ID);
            assertThat(result.memoStatus()).isEqualTo("CONVERTED");
            // メモの状態がCONVERTEDに変更されていることを確認
            assertThat(memo.getStatus()).isEqualTo("CONVERTED");
            assertThat(memo.getConvertedToTodoId()).isNotNull();
            verify(todoRepository).save(any(TodoEntity.class));
            verify(memoRepository).save(memo);
            verify(auditLogService).record(
                    eq("QUICK_MEMO_CONVERTED"), eq(USER_ID),
                    any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("convertToTodo_存在しないmemoId_BusinessException(QM_001)が投げられる")
        void convertToTodo_存在しないmemoId_BusinessException_QM001が投げられる() {
            // Given
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When / Then
            assertThatThrownBy(() -> convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode().getCode()).isEqualTo("QM_001");
                    });

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("convertToTodo_既にCONVERTED済み_BusinessException(QM_003)が投げられる")
        void convertToTodo_既にCONVERTED済み_BusinessException_QM003が投げられる() {
            // Given
            QuickMemoEntity convertedMemo = QuickMemoEntity.builder()
                    .userId(USER_ID)
                    .title("変換済みメモ")
                    .status("CONVERTED")
                    .build();
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(convertedMemo));

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When / Then
            assertThatThrownBy(() -> convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(QuickMemoErrorCode.MEMO_ALREADY_CONVERTED);
                        assertThat(be.getErrorCode().getCode()).isEqualTo("QM_003");
                    });

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("convertToTodo_論理削除済みメモ_BusinessException(QM_001)が投げられる")
        void convertToTodo_論理削除済みメモ_BusinessException_QM001が投げられる() {
            // Given
            // findByIdAndUserIdForUpdate は deletedAt IS NULL 条件のため、
            // 論理削除済みメモは見つからず MEMO_NOT_FOUND (QM_001) が投げられる
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When / Then
            assertThatThrownBy(() -> convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getErrorCode()).isEqualTo(QuickMemoErrorCode.MEMO_NOT_FOUND);
                        assertThat(be.getErrorCode().getCode()).isEqualTo("QM_001");
                    });

            verify(todoRepository, never()).save(any());
        }

        @Test
        @DisplayName("convertToTodo_priorityがnull_TodoPriority.MEDIUMで作成される")
        void convertToTodo_priorityがnull_TodoPriorityMEDIUMで作成される() {
            // Given
            QuickMemoEntity memo = buildUnsortedMemo();
            TodoEntity savedTodo = buildSavedTodoEntity(TODO_ID);
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(todoRepository.save(any(TodoEntity.class))).willReturn(savedTodo);
            given(memoTagLinkRepository.findTagIdsByMemoId(MEMO_ID)).willReturn(List.of());
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(memo);

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When
            convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req);

            // Then — save に渡されたTODOエンティティの priority が MEDIUM であることを確認
            ArgumentCaptor<TodoEntity> todoCaptor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository).save(todoCaptor.capture());
            assertThat(todoCaptor.getValue().getPriority()).isEqualTo(TodoPriority.MEDIUM);
        }

        @Test
        @DisplayName("convertToTodo_priorityが\"HIGH\"_TodoPriority.HIGHで作成される")
        void convertToTodo_priorityがHIGH_TodoPriorityHIGHで作成される() {
            // Given
            QuickMemoEntity memo = buildUnsortedMemo();
            TodoEntity savedTodo = buildSavedTodoEntity(TODO_ID);
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(todoRepository.save(any(TodoEntity.class))).willReturn(savedTodo);
            given(memoTagLinkRepository.findTagIdsByMemoId(MEMO_ID)).willReturn(List.of());
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(memo);

            ConvertToTodoRequest req = new ConvertToTodoRequest("HIGH", null, null);

            // When
            convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req);

            // Then
            ArgumentCaptor<TodoEntity> todoCaptor = ArgumentCaptor.forClass(TodoEntity.class);
            verify(todoRepository).save(todoCaptor.capture());
            assertThat(todoCaptor.getValue().getPriority()).isEqualTo(TodoPriority.HIGH);
        }

        @Test
        @DisplayName("convertToTodo_tagがあるメモ_タグがtodoTagLinksにコピーされる")
        void convertToTodo_tagがあるメモ_タグがtodoTagLinksにコピーされる() {
            // Given
            QuickMemoEntity memo = buildUnsortedMemo();
            TodoEntity savedTodo = buildSavedTodoEntity(TODO_ID);
            Long tagId1 = 201L;
            Long tagId2 = 202L;

            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(todoRepository.save(any(TodoEntity.class))).willReturn(savedTodo);
            given(memoTagLinkRepository.findTagIdsByMemoId(MEMO_ID))
                    .willReturn(List.of(tagId1, tagId2));
            given(todoTagLinkRepository.existsByTodoIdAndTagId(anyLong(), eq(tagId1)))
                    .willReturn(false);
            given(todoTagLinkRepository.existsByTodoIdAndTagId(anyLong(), eq(tagId2)))
                    .willReturn(false);
            given(todoTagLinkRepository.save(any(TodoTagLinkEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(memo);

            ConvertToTodoRequest req = new ConvertToTodoRequest(null, null, null);

            // When
            convertToTodoService.convertToTodo(MEMO_ID, USER_ID, req);

            // Then — 2つのタグリンクが保存されている
            verify(todoTagLinkRepository, times(2)).save(any(TodoTagLinkEntity.class));
            verify(tagRepository, times(2)).incrementUsageCount(anyLong());
        }
    }
}
