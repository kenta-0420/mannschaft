package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.dashboard.controller.ChatFolderController;
import com.mannschaft.app.dashboard.dto.AssignFolderItemRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignFolderItemsRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignResultResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.CreateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.UpdateChatFolderRequest;
import com.mannschaft.app.dashboard.service.ChatFolderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatFolderController} の単体テスト。
 * セキュリティコンテキストを設定してコントローラーを直接呼び出す。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatFolderController 単体テスト")
class ChatFolderControllerTest {

    @Mock
    private ChatFolderService chatFolderService;

    @InjectMocks
    private ChatFolderController chatFolderController;

    private static final Long USER_ID = 1L;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(USER_ID.toString(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // getFolders
    // ========================================

    @Nested
    @DisplayName("getFolders")
    class GetFolders {

        @Test
        @DisplayName("正常系: フォルダ一覧が取得される")
        void getFolders_正常_一覧取得() {
            // Given
            ChatFolderResponse folder = new ChatFolderResponse(1L, "お気に入り", "star", "#FFD700", 0, List.of());
            given(chatFolderService.getFolders(USER_ID)).willReturn(List.of(folder));

            // When
            ResponseEntity<ApiResponse<List<ChatFolderResponse>>> response = chatFolderController.getFolders();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getName()).isEqualTo("お気に入り");
            verify(chatFolderService).getFolders(USER_ID);
        }
    }

    // ========================================
    // createFolder
    // ========================================

    @Nested
    @DisplayName("createFolder")
    class CreateFolder {

        @Test
        @DisplayName("正常系: フォルダが作成されて201が返る")
        void createFolder_正常_作成201() {
            // Given
            CreateChatFolderRequest request = new CreateChatFolderRequest("新フォルダ", "folder", "#FF0000");
            ChatFolderResponse created = new ChatFolderResponse(2L, "新フォルダ", "folder", "#FF0000", 0, List.of());
            given(chatFolderService.createFolder(USER_ID, request)).willReturn(created);

            // When
            ResponseEntity<ApiResponse<ChatFolderResponse>> response = chatFolderController.createFolder(request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().getData().getName()).isEqualTo("新フォルダ");
        }
    }

    // ========================================
    // updateFolder
    // ========================================

    @Nested
    @DisplayName("updateFolder")
    class UpdateFolder {

        @Test
        @DisplayName("正常系: フォルダが更新されて200が返る")
        void updateFolder_正常_更新200() {
            // Given
            UpdateChatFolderRequest request = new UpdateChatFolderRequest("更新名", "star", "#00FF00", 1);
            ChatFolderResponse updated = new ChatFolderResponse(1L, "更新名", "star", "#00FF00", 1, List.of());
            given(chatFolderService.updateFolder(USER_ID, 1L, request)).willReturn(updated);

            // When
            ResponseEntity<ApiResponse<ChatFolderResponse>> response = chatFolderController.updateFolder(1L, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getName()).isEqualTo("更新名");
        }
    }

    // ========================================
    // deleteFolder
    // ========================================

    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: フォルダが削除されて204が返る")
        void deleteFolder_正常_204() {
            // When
            ResponseEntity<Void> response = chatFolderController.deleteFolder(1L);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(chatFolderService).deleteFolder(USER_ID, 1L);
        }
    }

    // ========================================
    // assignItem
    // ========================================

    @Nested
    @DisplayName("assignItem")
    class AssignItem {

        @Test
        @DisplayName("正常系: アイテムが割り当てられて200が返る")
        void assignItem_正常_200() {
            // Given
            AssignFolderItemRequest request = new AssignFolderItemRequest("DM_CHANNEL", 50L);
            ChatFolderResponse result = new ChatFolderResponse(1L, "お気に入り", "star", "#FFD700", 0, List.of());
            given(chatFolderService.assignItem(USER_ID, 1L, request)).willReturn(result);

            // When
            ResponseEntity<ApiResponse<ChatFolderResponse>> response = chatFolderController.assignItem(1L, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getId()).isEqualTo(1L);
        }
    }

    // ========================================
    // removeItem
    // ========================================

    @Nested
    @DisplayName("removeItem")
    class RemoveItem {

        @Test
        @DisplayName("正常系: アイテムが解除されて204が返る")
        void removeItem_正常_204() {
            // When
            ResponseEntity<Void> response = chatFolderController.removeItem("DM_CHANNEL", 50L);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            verify(chatFolderService).removeItem(USER_ID, "DM_CHANNEL", 50L);
        }
    }

    // ========================================
    // bulkAssignItems
    // ========================================

    @Nested
    @DisplayName("bulkAssignItems")
    class BulkAssignItems {

        @Test
        @DisplayName("正常系: アイテムが一括割り当てされて200が返る")
        void bulkAssignItems_正常_200() {
            // Given
            BulkAssignFolderItemsRequest request = new BulkAssignFolderItemsRequest(
                    List.of(new AssignFolderItemRequest("DM_CHANNEL", 10L))
            );
            BulkAssignResultResponse result = new BulkAssignResultResponse(1, 0);
            given(chatFolderService.bulkAssignItems(USER_ID, 1L, request)).willReturn(result);

            // When
            ResponseEntity<ApiResponse<BulkAssignResultResponse>> response =
                    chatFolderController.bulkAssignItems(1L, request);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getAssignedCount()).isEqualTo(1);
        }
    }
}
