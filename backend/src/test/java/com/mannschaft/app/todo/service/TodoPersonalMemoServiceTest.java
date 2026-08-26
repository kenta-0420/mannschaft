package com.mannschaft.app.todo.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.PersonalMemoRequest;
import com.mannschaft.app.todo.dto.PersonalMemoResponse;
import com.mannschaft.app.todo.entity.TodoPersonalMemoEntity;
import com.mannschaft.app.todo.repository.TodoPersonalMemoRepository;
import com.mannschaft.app.todo.security.TodoAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * {@link TodoPersonalMemoService} の単体テスト。
 * 個人メモのCRUD・存在確認を検証する。
 *
 * <p>認可根治（Wave5 todo硬化B）で TODO 存在＋scope 束縛＋membership 検証は
 * {@link TodoAccessGuard#verifyScopeAndMembership} へ集約された。旧 {@code verifyTodoExists} は撤去済みのため、
 * 「TODO 不在→TODO_010」はガードが送出する例外を Service が透過することで担保する（ガードを {@code doThrow} で駆動）。
 * scope 束縛・membership 判定そのものは {@code TodoScopePartBContractIT} が実 MySQL で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TodoPersonalMemoService 単体テスト")
class TodoPersonalMemoServiceTest {

    @Mock
    private TodoPersonalMemoRepository personalMemoRepository;

    @Mock
    private TodoAccessGuard todoAccessGuard;

    @InjectMocks
    private TodoPersonalMemoService todoPersonalMemoService;

    private static final Long TODO_ID = 1L;
    private static final Long MEMO_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 50L;
    private static final TodoScopeType SCOPE_TYPE = TodoScopeType.TEAM;

    private TodoPersonalMemoEntity createPersonalMemo() {
        TodoPersonalMemoEntity entity = TodoPersonalMemoEntity.builder()
                .todoId(TODO_ID)
                .userId(USER_ID)
                .memo("テスト個人メモ")
                .build();
        // IDをリフレクションでセット
        try {
            java.lang.reflect.Field idField = TodoPersonalMemoEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, MEMO_ID);
            java.lang.reflect.Field createdAtField = TodoPersonalMemoEntity.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(entity, LocalDateTime.now());
            java.lang.reflect.Field updatedAtField = TodoPersonalMemoEntity.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(entity, LocalDateTime.now());
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("フィールドのセットに失敗しました", e);
        }
        return entity;
    }

    @BeforeEach
    void setUp() {
        lenient().when(personalMemoRepository.save(any(TodoPersonalMemoEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    // ========================================
    // getPersonalMemo
    // ========================================

    @Nested
    @DisplayName("getPersonalMemo（個人メモ取得）")
    class GetPersonalMemo {

        @Test
        @DisplayName("正常系: 存在するメモを返す")
        void getPersonalMemo_正常_メモを返す() {
            // Given
            TodoPersonalMemoEntity memo = createPersonalMemo();
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.of(memo));

            // When
            ApiResponse<PersonalMemoResponse> response =
                    todoPersonalMemoService.getPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);

            // Then
            assertThat(response.getData()).isNotNull();
            assertThat(response.getData().getMemo()).isEqualTo("テスト個人メモ");
            assertThat(response.getData().getTodoId()).isEqualTo(TODO_ID);
            verify(todoAccessGuard).verifyScopeAndMembership(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);
        }

        @Test
        @DisplayName("異常系: メモが存在しない場合はTODO_060例外")
        void getPersonalMemo_メモなし_TODO060例外() {
            // Given
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() ->
                    todoPersonalMemoService.getPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_060"));
        }

        @Test
        @DisplayName("異常系: TODO不在（ガードがTODO_010を送出）で透過")
        void getPersonalMemo_TODO不在_TODO010例外() {
            // Given: ガードが scope 束縛で TODO_010 を送出する
            doThrow(new BusinessException(TodoErrorCode.TODO_NOT_FOUND))
                    .when(todoAccessGuard).verifyScopeAndMembership(eq(TODO_ID), eq(SCOPE_TYPE), eq(SCOPE_ID), anyLong());

            // When / Then
            assertThatThrownBy(() ->
                    todoPersonalMemoService.getPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // upsertPersonalMemo
    // ========================================

    @Nested
    @DisplayName("upsertPersonalMemo（個人メモUPSERT）")
    class UpsertPersonalMemo {

        @Test
        @DisplayName("正常系: メモが存在しない場合は新規作成する")
        void upsertPersonalMemo_正常_新規作成() {
            // Given
            PersonalMemoRequest request = new PersonalMemoRequest("新規メモ");
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When
            ApiResponse<PersonalMemoResponse> response =
                    todoPersonalMemoService.upsertPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(response.getData().getMemo()).isEqualTo("新規メモ");
            verify(personalMemoRepository).save(any(TodoPersonalMemoEntity.class));
            verify(todoAccessGuard).verifyScopeAndMembership(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);
        }

        @Test
        @DisplayName("正常系: メモが存在する場合は更新する")
        void upsertPersonalMemo_正常_更新() {
            // Given
            TodoPersonalMemoEntity existingMemo = createPersonalMemo();
            PersonalMemoRequest request = new PersonalMemoRequest("更新後メモ");
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.of(existingMemo));

            // When
            ApiResponse<PersonalMemoResponse> response =
                    todoPersonalMemoService.upsertPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(response.getData().getMemo()).isEqualTo("更新後メモ");
            verify(personalMemoRepository).save(any(TodoPersonalMemoEntity.class));
        }

        @Test
        @DisplayName("異常系: TODO不在（ガードがTODO_010を送出）で透過")
        void upsertPersonalMemo_TODO不在_TODO010例外() {
            // Given
            PersonalMemoRequest request = new PersonalMemoRequest("メモ");
            doThrow(new BusinessException(TodoErrorCode.TODO_NOT_FOUND))
                    .when(todoAccessGuard).verifyScopeAndMembership(eq(TODO_ID), eq(SCOPE_TYPE), eq(SCOPE_ID), anyLong());

            // When / Then
            assertThatThrownBy(() ->
                    todoPersonalMemoService.upsertPersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }

    // ========================================
    // deletePersonalMemo
    // ========================================

    @Nested
    @DisplayName("deletePersonalMemo（個人メモ削除）")
    class DeletePersonalMemo {

        @Test
        @DisplayName("正常系: 本人がメモを物理削除できる")
        void deletePersonalMemo_正常_本人削除() {
            // Given
            TodoPersonalMemoEntity memo = createPersonalMemo();
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.of(memo));

            // When
            todoPersonalMemoService.deletePersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);

            // Then: deleteByTodoIdAndUserIdが呼ばれる（物理削除）
            verify(personalMemoRepository).deleteByTodoIdAndUserId(TODO_ID, USER_ID);
            verify(todoAccessGuard).verifyScopeAndMembership(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID);
        }

        @Test
        @DisplayName("異常系: メモが存在しない場合はTODO_060例外")
        void deletePersonalMemo_メモなし_TODO060例外() {
            // Given
            given(personalMemoRepository.findByTodoIdAndUserId(TODO_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() ->
                    todoPersonalMemoService.deletePersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_060"));
        }

        @Test
        @DisplayName("異常系: TODO不在（ガードがTODO_010を送出）で透過")
        void deletePersonalMemo_TODO不在_TODO010例外() {
            // Given
            doThrow(new BusinessException(TodoErrorCode.TODO_NOT_FOUND))
                    .when(todoAccessGuard).verifyScopeAndMembership(eq(TODO_ID), eq(SCOPE_TYPE), eq(SCOPE_ID), anyLong());

            // When / Then
            assertThatThrownBy(() ->
                    todoPersonalMemoService.deletePersonalMemo(TODO_ID, SCOPE_TYPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("TODO_010"));
        }
    }
}
