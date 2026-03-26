package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.dto.AssignFolderItemRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignFolderItemsRequest;
import com.mannschaft.app.dashboard.dto.BulkAssignResultResponse;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.CreateChatFolderRequest;
import com.mannschaft.app.dashboard.dto.UpdateChatFolderRequest;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import com.mannschaft.app.dashboard.service.ChatFolderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ChatFolderService} の単体テスト。
 * チャット・連絡先カスタムフォルダのCRUD・アイテム割り当てを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChatFolderService 単体テスト")
class ChatFolderServiceTest {

    @Mock
    private ChatContactFolderRepository folderRepository;

    @Mock
    private ChatContactFolderItemRepository folderItemRepository;

    @Mock
    private DashboardMapper dashboardMapper;

    @InjectMocks
    private ChatFolderService chatFolderService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 10L;
    private static final Long ITEM_ID = 100L;

    private ChatContactFolderEntity createFolder(Long userId, String name, int sortOrder) {
        return ChatContactFolderEntity.builder()
                .userId(userId)
                .name(name)
                .icon("folder")
                .color("#FF0000")
                .sortOrder(sortOrder)
                .build();
    }

    private ChatFolderResponse createFolderResponse(Long id, String name) {
        return new ChatFolderResponse(id, name, "folder", "#FF0000", 0, List.of());
    }

    // ========================================
    // getFolders
    // ========================================

    @Nested
    @DisplayName("getFolders")
    class GetFolders {

        @Test
        @DisplayName("正常系: ユーザーのフォルダ一覧が返却される")
        void getFolders_正常_一覧返却() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            given(folderRepository.findByUserIdOrderBySortOrder(USER_ID)).willReturn(List.of(folder));
            given(folderItemRepository.findByFolderId(any())).willReturn(List.of());
            given(dashboardMapper.toFolderResponse(eq(folder), eq(List.of())))
                    .willReturn(createFolderResponse(FOLDER_ID, "仕事"));

            // When
            List<ChatFolderResponse> result = chatFolderService.getFolders(USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("仕事");
        }

        @Test
        @DisplayName("正常系: フォルダなしの場合は空リストが返却される")
        void getFolders_フォルダなし_空リスト() {
            // Given
            given(folderRepository.findByUserIdOrderBySortOrder(USER_ID)).willReturn(List.of());

            // When
            List<ChatFolderResponse> result = chatFolderService.getFolders(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createFolder
    // ========================================

    @Nested
    @DisplayName("createFolder")
    class CreateFolder {

        @Test
        @DisplayName("正常系: フォルダが作成される")
        void createFolder_正常_フォルダ作成() {
            // Given
            CreateChatFolderRequest request = new CreateChatFolderRequest("仕事", "folder", "#FF0000");
            given(folderRepository.countByUserId(USER_ID)).willReturn(5L);
            given(folderRepository.existsByUserIdAndName(USER_ID, "仕事")).willReturn(false);
            given(folderRepository.save(any(ChatContactFolderEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(dashboardMapper.toFolderResponse(any(ChatContactFolderEntity.class), eq(List.of())))
                    .willReturn(createFolderResponse(FOLDER_ID, "仕事"));

            // When
            ChatFolderResponse result = chatFolderService.createFolder(USER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("仕事");
            verify(folderRepository).save(any(ChatContactFolderEntity.class));
        }

        @Test
        @DisplayName("異常系: フォルダ数上限到達でDASHBOARD_009例外")
        void createFolder_上限到達_DASHBOARD009例外() {
            // Given
            CreateChatFolderRequest request = new CreateChatFolderRequest("新規", "folder", "#FF0000");
            given(folderRepository.countByUserId(USER_ID)).willReturn(20L);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.createFolder(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_009"));
        }

        @Test
        @DisplayName("異常系: 同名フォルダ重複でDASHBOARD_008例外")
        void createFolder_同名重複_DASHBOARD008例外() {
            // Given
            CreateChatFolderRequest request = new CreateChatFolderRequest("仕事", "folder", "#FF0000");
            given(folderRepository.countByUserId(USER_ID)).willReturn(5L);
            given(folderRepository.existsByUserIdAndName(USER_ID, "仕事")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.createFolder(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_008"));
        }
    }

    // ========================================
    // updateFolder
    // ========================================

    @Nested
    @DisplayName("updateFolder")
    class UpdateFolder {

        @Test
        @DisplayName("正常系: フォルダが更新される")
        void updateFolder_正常_フォルダ更新() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            UpdateChatFolderRequest request = new UpdateChatFolderRequest("プライベート", "star", "#00FF00", 1);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.existsByUserIdAndNameAndIdNot(USER_ID, "プライベート", FOLDER_ID)).willReturn(false);
            given(folderItemRepository.findByFolderId(FOLDER_ID)).willReturn(List.of());
            given(dashboardMapper.toFolderResponse(eq(folder), eq(List.of())))
                    .willReturn(createFolderResponse(FOLDER_ID, "プライベート"));

            // When
            ChatFolderResponse result = chatFolderService.updateFolder(USER_ID, FOLDER_ID, request);

            // Then
            assertThat(result.getName()).isEqualTo("プライベート");
        }

        @Test
        @DisplayName("異常系: フォルダ不存在でDASHBOARD_006例外")
        void updateFolder_フォルダ不在_DASHBOARD006例外() {
            // Given
            UpdateChatFolderRequest request = new UpdateChatFolderRequest("プライベート", null, null, null);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.empty());
            given(folderRepository.existsById(FOLDER_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.updateFolder(USER_ID, FOLDER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_006"));
        }

        @Test
        @DisplayName("異常系: 所有者不一致でDASHBOARD_007例外")
        void updateFolder_所有者不一致_DASHBOARD007例外() {
            // Given
            UpdateChatFolderRequest request = new UpdateChatFolderRequest("プライベート", null, null, null);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.empty());
            given(folderRepository.existsById(FOLDER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.updateFolder(USER_ID, FOLDER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_007"));
        }

        @Test
        @DisplayName("異常系: 更新時の同名重複でDASHBOARD_008例外")
        void updateFolder_同名重複_DASHBOARD008例外() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            UpdateChatFolderRequest request = new UpdateChatFolderRequest("既存フォルダ", null, null, null);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.existsByUserIdAndNameAndIdNot(USER_ID, "既存フォルダ", FOLDER_ID)).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.updateFolder(USER_ID, FOLDER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_008"));
        }
    }

    // ========================================
    // deleteFolder
    // ========================================

    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: フォルダが削除される")
        void deleteFolder_正常_フォルダ削除() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));

            // When
            chatFolderService.deleteFolder(USER_ID, FOLDER_ID);

            // Then
            verify(folderRepository).delete(folder);
        }

        @Test
        @DisplayName("異常系: フォルダ不存在でDASHBOARD_006例外")
        void deleteFolder_フォルダ不在_DASHBOARD006例外() {
            // Given
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.empty());
            given(folderRepository.existsById(FOLDER_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> chatFolderService.deleteFolder(USER_ID, FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_006"));
        }
    }

    // ========================================
    // assignItem
    // ========================================

    @Nested
    @DisplayName("assignItem")
    class AssignItem {

        @Test
        @DisplayName("正常系: アイテムがフォルダに割り当てられる")
        void assignItem_正常_アイテム割り当て() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            AssignFolderItemRequest request = new AssignFolderItemRequest("DM_CHANNEL", ITEM_ID);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));
            given(folderItemRepository.findByItemTypeAndItemId(FolderItemType.DM_CHANNEL, ITEM_ID))
                    .willReturn(Optional.empty());
            given(folderItemRepository.save(any(ChatContactFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(folderItemRepository.findByFolderId(FOLDER_ID)).willReturn(List.of());
            given(dashboardMapper.toFolderResponse(eq(folder), any())).willReturn(createFolderResponse(FOLDER_ID, "仕事"));

            // When
            ChatFolderResponse result = chatFolderService.assignItem(USER_ID, FOLDER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(folderItemRepository).save(any(ChatContactFolderItemEntity.class));
        }

        @Test
        @DisplayName("正常系: 既存割り当てが移動される（別フォルダから移動）")
        void assignItem_既存あり_移動() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            AssignFolderItemRequest request = new AssignFolderItemRequest("DM_CHANNEL", ITEM_ID);
            ChatContactFolderItemEntity existingItem = ChatContactFolderItemEntity.builder()
                    .folderId(999L)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(ITEM_ID)
                    .build();
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));
            given(folderItemRepository.findByItemTypeAndItemId(FolderItemType.DM_CHANNEL, ITEM_ID))
                    .willReturn(Optional.of(existingItem));
            given(folderItemRepository.save(any(ChatContactFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(folderItemRepository.findByFolderId(FOLDER_ID)).willReturn(List.of());
            given(dashboardMapper.toFolderResponse(eq(folder), any())).willReturn(createFolderResponse(FOLDER_ID, "仕事"));

            // When
            chatFolderService.assignItem(USER_ID, FOLDER_ID, request);

            // Then
            verify(folderItemRepository).deleteByItemTypeAndItemId(FolderItemType.DM_CHANNEL, ITEM_ID);
            verify(folderItemRepository).save(any(ChatContactFolderItemEntity.class));
        }

        @Test
        @DisplayName("異常系: 無効なアイテム種別でDASHBOARD_010例外")
        void assignItem_無効なアイテム種別_DASHBOARD010例外() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            AssignFolderItemRequest request = new AssignFolderItemRequest("INVALID_TYPE", ITEM_ID);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));

            // When / Then
            assertThatThrownBy(() -> chatFolderService.assignItem(USER_ID, FOLDER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_010"));
        }
    }

    // ========================================
    // removeItem
    // ========================================

    @Nested
    @DisplayName("removeItem")
    class RemoveItem {

        @Test
        @DisplayName("正常系: アイテムがフォルダから外される")
        void removeItem_正常_アイテム解除() {
            // Given
            ChatContactFolderItemEntity item = ChatContactFolderItemEntity.builder()
                    .folderId(FOLDER_ID)
                    .itemType(FolderItemType.DM_CHANNEL)
                    .itemId(ITEM_ID)
                    .build();
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            given(folderItemRepository.findByItemTypeAndItemId(FolderItemType.DM_CHANNEL, ITEM_ID))
                    .willReturn(Optional.of(item));
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));

            // When
            chatFolderService.removeItem(USER_ID, "DM_CHANNEL", ITEM_ID);

            // Then
            verify(folderItemRepository).deleteByItemTypeAndItemId(FolderItemType.DM_CHANNEL, ITEM_ID);
        }

        @Test
        @DisplayName("正常系: アイテムが存在しない場合は何もしない")
        void removeItem_アイテム不在_何もしない() {
            // Given
            given(folderItemRepository.findByItemTypeAndItemId(FolderItemType.CONTACT, ITEM_ID))
                    .willReturn(Optional.empty());

            // When
            chatFolderService.removeItem(USER_ID, "CONTACT", ITEM_ID);

            // Then
            verify(folderItemRepository, never()).deleteByItemTypeAndItemId(any(), any());
        }

        @Test
        @DisplayName("異常系: 無効なアイテム種別でDASHBOARD_010例外")
        void removeItem_無効なアイテム種別_DASHBOARD010例外() {
            // When / Then
            assertThatThrownBy(() -> chatFolderService.removeItem(USER_ID, "INVALID", ITEM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_010"));
        }
    }

    // ========================================
    // bulkAssignItems
    // ========================================

    @Nested
    @DisplayName("bulkAssignItems")
    class BulkAssignItems {

        @Test
        @DisplayName("正常系: 複数アイテムが一括割り当てされる")
        void bulkAssignItems_正常_一括割り当て() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));

            List<AssignFolderItemRequest> items = List.of(
                    new AssignFolderItemRequest("DM_CHANNEL", 1L),
                    new AssignFolderItemRequest("CONTACT", 2L)
            );
            BulkAssignFolderItemsRequest request = new BulkAssignFolderItemsRequest(items);

            given(folderItemRepository.findByItemTypeAndItemId(any(), any())).willReturn(Optional.empty());
            given(folderItemRepository.save(any(ChatContactFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            BulkAssignResultResponse result = chatFolderService.bulkAssignItems(USER_ID, FOLDER_ID, request);

            // Then
            assertThat(result.getAssignedCount()).isEqualTo(2);
            assertThat(result.getSkippedCount()).isZero();
        }

        @Test
        @DisplayName("正常系: 不正なアイテム種別はスキップされる")
        void bulkAssignItems_不正アイテムスキップ() {
            // Given
            ChatContactFolderEntity folder = createFolder(USER_ID, "仕事", 0);
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.of(folder));

            List<AssignFolderItemRequest> items = List.of(
                    new AssignFolderItemRequest("DM_CHANNEL", 1L),
                    new AssignFolderItemRequest("INVALID_TYPE", 2L)
            );
            BulkAssignFolderItemsRequest request = new BulkAssignFolderItemsRequest(items);

            given(folderItemRepository.findByItemTypeAndItemId(FolderItemType.DM_CHANNEL, 1L))
                    .willReturn(Optional.empty());
            given(folderItemRepository.save(any(ChatContactFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            BulkAssignResultResponse result = chatFolderService.bulkAssignItems(USER_ID, FOLDER_ID, request);

            // Then
            assertThat(result.getAssignedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("異常系: フォルダ不存在でDASHBOARD_006例外")
        void bulkAssignItems_フォルダ不在_DASHBOARD006例外() {
            // Given
            given(folderRepository.findByIdAndUserId(FOLDER_ID, USER_ID)).willReturn(Optional.empty());
            given(folderRepository.existsById(FOLDER_ID)).willReturn(false);

            BulkAssignFolderItemsRequest request = new BulkAssignFolderItemsRequest(
                    List.of(new AssignFolderItemRequest("DM_CHANNEL", 1L)));

            // When / Then
            assertThatThrownBy(() -> chatFolderService.bulkAssignItems(USER_ID, FOLDER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_006"));
        }
    }
}
