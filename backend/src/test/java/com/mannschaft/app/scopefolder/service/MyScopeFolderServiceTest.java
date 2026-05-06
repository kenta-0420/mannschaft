package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MyScopeFolderService} の単体テスト。
 * スコープフォルダのCRUD・アイテム管理・並び替えを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyScopeFolderService 単体テスト")
class MyScopeFolderServiceTest {

    @Mock
    private MyScopeFolderRepository folderRepository;

    @Mock
    private MyScopeFolderItemRepository itemRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private MyScopeFolderService folderService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 10L;
    private static final Long SCOPE_ID = 100L;
    private static final Long OTHER_FOLDER_ID = 20L;

    private MyScopeFolderEntity createFolderEntity(Long id, Long userId, ScopeType scopeType, String name, int sortOrder) {
        return MyScopeFolderEntity.builder()
                .id(id)
                .userId(userId)
                .scopeType(scopeType)
                .name(name)
                .color("#FF0000")
                .sortOrder(sortOrder)
                .build();
    }

    private MyScopeFolderItemEntity createItemEntity(Long id, Long folderId, Long scopeId, int sortOrder) {
        return MyScopeFolderItemEntity.builder()
                .id(id)
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(sortOrder)
                .build();
    }

    // ========================================
    // getFolders
    // ========================================

    @Nested
    @DisplayName("getFolders")
    class GetFolders {

        @Test
        @DisplayName("正常系: フォルダ一覧とアイテムIDが返る")
        void getFolders_正常系_フォルダ一覧とアイテムIDが返る() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "チームA", 0);
            MyScopeFolderItemEntity item = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);

            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(folder));
            given(itemRepository.findByFolderIdIn(List.of(FOLDER_ID)))
                    .willReturn(List.of(item));

            // When
            List<ScopeFolderResponse> result = folderService.getFolders(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).name()).isEqualTo("チームA");
            assertThat(result.get(0).itemScopeIds()).containsExactly(SCOPE_ID);
        }

        @Test
        @DisplayName("正常系: フォルダなしの場合は空リストが返る")
        void getFolders_正常系_フォルダなしで空リスト() {
            // Given
            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of());

            // When
            List<ScopeFolderResponse> result = folderService.getFolders(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result).isEmpty();
            verify(itemRepository, never()).findByFolderIdIn(any());
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
        void createFolder_正常系_フォルダが作成される() {
            // Given
            CreateFolderRequest req = new CreateFolderRequest("新フォルダ", "#FF0000");
            given(folderRepository.countByUserIdAndScopeTypeAndDeletedAtIsNull(USER_ID, ScopeType.TEAM)).willReturn(5L);
            given(folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(USER_ID, ScopeType.TEAM, "新フォルダ"))
                    .willReturn(false);
            given(folderRepository.save(any(MyScopeFolderEntity.class)))
                    .willAnswer(inv -> {
                        MyScopeFolderEntity entity = inv.getArgument(0);
                        return MyScopeFolderEntity.builder()
                                .id(FOLDER_ID)
                                .userId(entity.getUserId())
                                .scopeType(entity.getScopeType())
                                .name(entity.getName())
                                .color(entity.getColor())
                                .sortOrder(entity.getSortOrder())
                                .build();
                    });

            // When
            ScopeFolderResponse result = folderService.createFolder(USER_ID, ScopeType.TEAM, req);

            // Then
            assertThat(result.name()).isEqualTo("新フォルダ");
            assertThat(result.color()).isEqualTo("#FF0000");
            assertThat(result.itemScopeIds()).isEmpty();
            verify(folderRepository).save(any(MyScopeFolderEntity.class));
        }

        @Test
        @DisplayName("異常系: 上限20件超過でSCOPE_FOLDER_LIMIT_EXCEEDED例外")
        void createFolder_異常系_上限20件超過でエラー() {
            // Given
            CreateFolderRequest req = new CreateFolderRequest("新フォルダ", null);
            given(folderRepository.countByUserIdAndScopeTypeAndDeletedAtIsNull(USER_ID, ScopeType.TEAM)).willReturn(20L);

            // When / Then
            assertThatThrownBy(() -> folderService.createFolder(USER_ID, ScopeType.TEAM, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_LIMIT_EXCEEDED.getCode()));
        }

        @Test
        @DisplayName("異常系: 同名フォルダ重複でSCOPE_FOLDER_NAME_DUPLICATE例外")
        void createFolder_異常系_同名フォルダ重複でエラー() {
            // Given
            CreateFolderRequest req = new CreateFolderRequest("既存フォルダ", null);
            given(folderRepository.countByUserIdAndScopeTypeAndDeletedAtIsNull(USER_ID, ScopeType.TEAM)).willReturn(5L);
            given(folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(USER_ID, ScopeType.TEAM, "既存フォルダ"))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> folderService.createFolder(USER_ID, ScopeType.TEAM, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NAME_DUPLICATE.getCode()));
        }
    }

    // ========================================
    // updateFolder
    // ========================================

    @Nested
    @DisplayName("updateFolder")
    class UpdateFolder {

        @Test
        @DisplayName("異常系: 他ユーザーのフォルダへのアクセスでSCOPE_FOLDER_NOT_FOUND例外（IDOR防止）")
        void updateFolder_異常系_他ユーザーのフォルダへのアクセスで403() {
            // Given
            UpdateFolderRequest req = new UpdateFolderRequest("更新フォルダ", null);
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> folderService.updateFolder(USER_ID, FOLDER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND.getCode()));
        }
    }

    // ========================================
    // deleteFolder
    // ========================================

    @Nested
    @DisplayName("deleteFolder")
    class DeleteFolder {

        @Test
        @DisplayName("正常系: ソフト削除される")
        void deleteFolder_正常系_ソフト削除される() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(folderRepository.save(any(MyScopeFolderEntity.class))).willReturn(folder);

            // When
            folderService.deleteFolder(USER_ID, FOLDER_ID);

            // Then
            // softDelete()が呼ばれ、deleted_atがセットされてからsaveされる
            verify(folderRepository).save(folder);
            assertThat(folder.getDeletedAt()).isNotNull();
        }
    }

    // ========================================
    // addItem
    // ========================================

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("正常系: アイテムが追加される")
        void addItem_正常系_アイテムが追加される() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            AddFolderItemRequest req = new AddFolderItemRequest(SCOPE_ID);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID)).willReturn(List.of());
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            // addItem後の再取得
            MyScopeFolderItemEntity savedItem = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of())
                    .willReturn(List.of(savedItem));

            // When
            ScopeFolderResponse result = folderService.addItem(USER_ID, FOLDER_ID, req);

            // Then
            assertThat(result.itemScopeIds()).containsExactly(SCOPE_ID);
            verify(itemRepository).save(any(MyScopeFolderItemEntity.class));
        }

        @Test
        @DisplayName("異常系: 非所属チームへの追加でSCOPE_FOLDER_NOT_MEMBER例外")
        void addItem_異常系_非所属チームへの追加で403() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            AddFolderItemRequest req = new AddFolderItemRequest(SCOPE_ID);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> folderService.addItem(USER_ID, FOLDER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER.getCode()));
        }

        @Test
        @DisplayName("正常系: 既存フォルダから移動される（1アイテム1フォルダ制約）")
        void addItem_正常系_既存フォルダから移動される() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            AddFolderItemRequest req = new AddFolderItemRequest(SCOPE_ID);
            MyScopeFolderItemEntity existingItem = createItemEntity(99L, OTHER_FOLDER_ID, SCOPE_ID, 0);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(userRoleRepository.existsByUserIdAndTeamId(USER_ID, SCOPE_ID)).willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.of(existingItem));
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID)).willReturn(List.of());
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            MyScopeFolderItemEntity savedItem = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of())
                    .willReturn(List.of(savedItem));

            // When
            ScopeFolderResponse result = folderService.addItem(USER_ID, FOLDER_ID, req);

            // Then
            // 既存アイテムが削除されてから新規追加されることを確認
            verify(itemRepository).delete(existingItem);
            assertThat(result.itemScopeIds()).containsExactly(SCOPE_ID);
        }
    }

    // ========================================
    // reorderFolders
    // ========================================

    @Nested
    @DisplayName("reorderFolders")
    class ReorderFolders {

        @Test
        @DisplayName("正常系: 並び順が更新される")
        void reorderFolders_正常系_並び順が更新される() {
            // Given
            MyScopeFolderEntity folderA = createFolderEntity(1L, USER_ID, ScopeType.TEAM, "A", 0);
            MyScopeFolderEntity folderB = createFolderEntity(2L, USER_ID, ScopeType.TEAM, "B", 1);
            MyScopeFolderEntity folderC = createFolderEntity(3L, USER_ID, ScopeType.TEAM, "C", 2);

            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(folderA, folderB, folderC));
            given(folderRepository.save(any(MyScopeFolderEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // orderedIds: [3, 1, 2] → C=0, A=1, B=2
            ReorderFoldersRequest req = new ReorderFoldersRequest(List.of(3L, 1L, 2L));

            // When
            folderService.reorderFolders(USER_ID, ScopeType.TEAM, req);

            // Then
            assertThat(folderC.getSortOrder()).isEqualTo(0);
            assertThat(folderA.getSortOrder()).isEqualTo(1);
            assertThat(folderB.getSortOrder()).isEqualTo(2);
            verify(folderRepository).save(folderC);
            verify(folderRepository).save(folderA);
            verify(folderRepository).save(folderB);
        }
    }
}
