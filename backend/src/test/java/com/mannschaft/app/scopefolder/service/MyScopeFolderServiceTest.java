package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.AddFolderItemRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignRequest;
import com.mannschaft.app.scopefolder.dto.BulkAssignResponse;
import com.mannschaft.app.scopefolder.dto.CreateFolderRequest;
import com.mannschaft.app.scopefolder.dto.ReorderFoldersRequest;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.dto.UpdateFolderRequest;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link MyScopeFolderService} の単体テスト。
 * スコープフォルダの CRUD・アイテム管理・並び替え、および
 * F15.3 で追加された未分類フォルダ・一括振り分け・サポータ対応を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyScopeFolderService 単体テスト")
class MyScopeFolderServiceTest {

    @Mock
    private MyScopeFolderRepository folderRepository;

    @Mock
    private MyScopeFolderItemRepository itemRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @InjectMocks
    private MyScopeFolderService folderService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long FOLDER_ID = 10L;
    private static final Long DEFAULT_FOLDER_ID = 11L;
    private static final Long SCOPE_ID = 100L;
    private static final Long SCOPE_ID_2 = 101L;
    private static final Long OTHER_FOLDER_ID = 20L;

    private MyScopeFolderEntity createFolderEntity(Long id, Long userId, ScopeType scopeType, String name, int sortOrder) {
        return MyScopeFolderEntity.builder()
                .id(id)
                .userId(userId)
                .scopeType(scopeType)
                .name(name)
                .color("#FF0000")
                .icon(null)
                .isDefault(Boolean.FALSE)
                .sortOrder(sortOrder)
                .build();
    }

    private MyScopeFolderEntity createDefaultFolderEntity(Long id, Long userId, ScopeType scopeType) {
        return MyScopeFolderEntity.builder()
                .id(id)
                .userId(userId)
                .scopeType(scopeType)
                .name("未分類")
                .color(null)
                .icon(null)
                .isDefault(Boolean.TRUE)
                .sortOrder(9999)
                .build();
    }

    private MyScopeFolderItemEntity createItemEntity(Long id, Long folderId, Long scopeId, int sortOrder) {
        return MyScopeFolderItemEntity.builder()
                .id(id)
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(sortOrder)
                .assignedVia(AssignedVia.MANUAL)
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
            assertThat(result.get(0).isDefault()).isFalse();
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
            CreateFolderRequest req = new CreateFolderRequest("新フォルダ", "#FF0000", "pi-users");
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
                                .icon(entity.getIcon())
                                .isDefault(entity.getIsDefault())
                                .sortOrder(entity.getSortOrder())
                                .build();
                    });

            // When
            ScopeFolderResponse result = folderService.createFolder(USER_ID, ScopeType.TEAM, req);

            // Then
            assertThat(result.name()).isEqualTo("新フォルダ");
            assertThat(result.color()).isEqualTo("#FF0000");
            assertThat(result.icon()).isEqualTo("pi-users");
            assertThat(result.isDefault()).isFalse();
            assertThat(result.itemScopeIds()).isEmpty();
            verify(folderRepository).save(any(MyScopeFolderEntity.class));
        }

        @Test
        @DisplayName("異常系: 上限20件超過でSCOPE_FOLDER_LIMIT_EXCEEDED例外")
        void createFolder_異常系_上限20件超過でエラー() {
            // Given
            CreateFolderRequest req = new CreateFolderRequest("新フォルダ", null, null);
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
            CreateFolderRequest req = new CreateFolderRequest("既存フォルダ", null, null);
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
            UpdateFolderRequest req = new UpdateFolderRequest("更新フォルダ", null, null);
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> folderService.updateFolder(USER_ID, FOLDER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("F15.3 異常系: 未分類フォルダの改名で SCOPE_FOLDER_DEFAULT_IMMUTABLE 例外")
        void updateFolder_異常系_未分類フォルダ改名拒否() {
            // Given
            MyScopeFolderEntity defaultFolder = createDefaultFolderEntity(DEFAULT_FOLDER_ID, USER_ID, ScopeType.TEAM);
            UpdateFolderRequest req = new UpdateFolderRequest("勝手な名前", null, null);
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(DEFAULT_FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(defaultFolder));

            // When / Then
            assertThatThrownBy(() -> folderService.updateFolder(USER_ID, DEFAULT_FOLDER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_DEFAULT_IMMUTABLE.getCode()));
            verify(folderRepository, never()).save(any());
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
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID)).willReturn(List.of());
            given(folderRepository.save(any(MyScopeFolderEntity.class))).willReturn(folder);

            // When
            folderService.deleteFolder(USER_ID, FOLDER_ID);

            // Then
            verify(folderRepository).save(folder);
            assertThat(folder.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("F15.3 異常系: 未分類フォルダの削除で SCOPE_FOLDER_DEFAULT_IMMUTABLE 例外")
        void deleteFolder_異常系_未分類フォルダ削除拒否() {
            // Given
            MyScopeFolderEntity defaultFolder = createDefaultFolderEntity(DEFAULT_FOLDER_ID, USER_ID, ScopeType.TEAM);
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(DEFAULT_FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(defaultFolder));

            // When / Then
            assertThatThrownBy(() -> folderService.deleteFolder(USER_ID, DEFAULT_FOLDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_DEFAULT_IMMUTABLE.getCode()));
            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("F15.3 正常系: フォルダ削除前に所属アイテムが未分類フォルダへ自動再配置される")
        void deleteFolder_正常系_削除前に未分類へ再配置() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            MyScopeFolderEntity defaultFolder =
                    createDefaultFolderEntity(DEFAULT_FOLDER_ID, USER_ID, ScopeType.TEAM);
            MyScopeFolderItemEntity item1 = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            MyScopeFolderItemEntity item2 = createItemEntity(2L, FOLDER_ID, SCOPE_ID_2, 1);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            // 1回目: 削除対象のアイテム一覧, 2回目: 未分類フォルダ内既存アイテム件数取得
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of(item1, item2));
            given(itemRepository.findByFolderIdOrderBySortOrder(DEFAULT_FOLDER_ID))
                    .willReturn(List.of()); // 既存 0 件
            given(folderRepository.findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(USER_ID, ScopeType.TEAM))
                    .willReturn(Optional.of(defaultFolder));
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(folderRepository.save(any(MyScopeFolderEntity.class))).willReturn(folder);

            // When
            folderService.deleteFolder(USER_ID, FOLDER_ID);

            // Then: 2 件分の再配置 save + 元フォルダの softDelete save
            ArgumentCaptor<MyScopeFolderItemEntity> captor = ArgumentCaptor.forClass(MyScopeFolderItemEntity.class);
            verify(itemRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(MyScopeFolderItemEntity::getFolderId, MyScopeFolderItemEntity::getAssignedVia)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(DEFAULT_FOLDER_ID, AssignedVia.DEFAULT),
                            org.assertj.core.groups.Tuple.tuple(DEFAULT_FOLDER_ID, AssignedVia.DEFAULT));
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
        @DisplayName("正常系: アイテムが追加される（サポータ含む F00.5 メンバーシップ確認）")
        void addItem_正常系_アイテムが追加される() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            AddFolderItemRequest req = new AddFolderItemRequest(SCOPE_ID);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            // 1回目（件数算出時）は空、2回目（保存後再取得時）は新規アイテム入り
            MyScopeFolderItemEntity savedItem = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of())
                    .willReturn(List.of(savedItem));

            // When
            ScopeFolderResponse result = folderService.addItem(USER_ID, FOLDER_ID, req);

            // Then
            assertThat(result.itemScopeIds()).containsExactly(SCOPE_ID);
            ArgumentCaptor<MyScopeFolderItemEntity> captor = ArgumentCaptor.forClass(MyScopeFolderItemEntity.class);
            verify(itemRepository).save(captor.capture());
            assertThat(captor.getValue().getAssignedVia()).isEqualTo(AssignedVia.MANUAL);
        }

        @Test
        @DisplayName("異常系: 非所属チームへの追加でSCOPE_FOLDER_NOT_MEMBER例外")
        void addItem_異常系_非所属チームへの追加で403() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            AddFolderItemRequest req = new AddFolderItemRequest(SCOPE_ID);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID))
                    .willReturn(false);

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
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.of(existingItem));
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            MyScopeFolderItemEntity savedItem = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of())
                    .willReturn(List.of(savedItem));

            // When
            ScopeFolderResponse result = folderService.addItem(USER_ID, FOLDER_ID, req);

            // Then
            verify(itemRepository).delete(existingItem);
            assertThat(result.itemScopeIds()).containsExactly(SCOPE_ID);
        }

        @Test
        @DisplayName("F15.3 正常系: addItemWithAssignedVia で INVITE 区分が保持される（招待画面経由）")
        void addItemWithAssignedVia_正常系_INVITE() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of())
                    .willReturn(List.of(createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0)));
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            folderService.addItemWithAssignedVia(USER_ID, FOLDER_ID, SCOPE_ID, AssignedVia.INVITE);

            // Then
            ArgumentCaptor<MyScopeFolderItemEntity> captor = ArgumentCaptor.forClass(MyScopeFolderItemEntity.class);
            verify(itemRepository).save(captor.capture());
            assertThat(captor.getValue().getAssignedVia()).isEqualTo(AssignedVia.INVITE);
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

        @Test
        @DisplayName("F15.3 正常系: 未分類フォルダは並び替え対象外（末尾固定）")
        void reorderFolders_未分類フォルダは並び替え対象外() {
            // Given
            MyScopeFolderEntity folderA = createFolderEntity(1L, USER_ID, ScopeType.TEAM, "A", 0);
            MyScopeFolderEntity folderB = createFolderEntity(2L, USER_ID, ScopeType.TEAM, "B", 1);
            MyScopeFolderEntity defaultFolder = createDefaultFolderEntity(DEFAULT_FOLDER_ID, USER_ID, ScopeType.TEAM);
            int originalDefaultSortOrder = defaultFolder.getSortOrder();

            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(folderA, folderB, defaultFolder));
            given(folderRepository.save(any(MyScopeFolderEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // 未分類フォルダ ID も含めてリクエストするが、未分類は無視されるはず
            ReorderFoldersRequest req = new ReorderFoldersRequest(List.of(2L, 1L, DEFAULT_FOLDER_ID));

            // When
            folderService.reorderFolders(USER_ID, ScopeType.TEAM, req);

            // Then
            assertThat(folderB.getSortOrder()).isEqualTo(0);
            assertThat(folderA.getSortOrder()).isEqualTo(1);
            // 未分類フォルダは元の sortOrder=9999 を維持
            assertThat(defaultFolder.getSortOrder()).isEqualTo(originalDefaultSortOrder);
            verify(folderRepository, never()).save(defaultFolder);
        }
    }

    // ========================================
    // F15.3 追加: findOrCreateDefault
    // ========================================

    @Nested
    @DisplayName("findOrCreateDefault (F15.3)")
    class FindOrCreateDefault {

        @Test
        @DisplayName("正常系: 既存の未分類フォルダがあればそれを返す")
        void findOrCreateDefault_既存() {
            // Given
            MyScopeFolderEntity defaultFolder = createDefaultFolderEntity(DEFAULT_FOLDER_ID, USER_ID, ScopeType.TEAM);
            given(folderRepository.findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(USER_ID, ScopeType.TEAM))
                    .willReturn(Optional.of(defaultFolder));
            given(itemRepository.findByFolderIdOrderBySortOrder(DEFAULT_FOLDER_ID)).willReturn(List.of());

            // When
            ScopeFolderResponse result = folderService.findOrCreateDefault(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result.id()).isEqualTo(DEFAULT_FOLDER_ID);
            assertThat(result.isDefault()).isTrue();
            verify(folderRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 未分類フォルダ未生成なら lazy 生成する")
        void findOrCreateDefault_lazy生成() {
            // Given
            given(folderRepository.findByUserIdAndScopeTypeAndIsDefaultTrueAndDeletedAtIsNull(USER_ID, ScopeType.TEAM))
                    .willReturn(Optional.empty());
            given(folderRepository.save(any(MyScopeFolderEntity.class)))
                    .willAnswer(inv -> {
                        MyScopeFolderEntity entity = inv.getArgument(0);
                        return MyScopeFolderEntity.builder()
                                .id(DEFAULT_FOLDER_ID)
                                .userId(entity.getUserId())
                                .scopeType(entity.getScopeType())
                                .name(entity.getName())
                                .isDefault(entity.getIsDefault())
                                .sortOrder(entity.getSortOrder())
                                .build();
                    });
            given(itemRepository.findByFolderIdOrderBySortOrder(DEFAULT_FOLDER_ID)).willReturn(List.of());

            // When
            ScopeFolderResponse result = folderService.findOrCreateDefault(USER_ID, ScopeType.TEAM);

            // Then
            ArgumentCaptor<MyScopeFolderEntity> captor = ArgumentCaptor.forClass(MyScopeFolderEntity.class);
            verify(folderRepository).save(captor.capture());
            assertThat(captor.getValue().getIsDefault()).isTrue();
            assertThat(captor.getValue().getSortOrder()).isEqualTo(9999);
            assertThat(captor.getValue().getName()).isEqualTo("未分類");
            assertThat(result.isDefault()).isTrue();
        }
    }

    // ========================================
    // F15.3 追加: bulkAssign
    // ========================================

    @Nested
    @DisplayName("bulkAssign (F15.3)")
    class BulkAssign {

        @Test
        @DisplayName("正常系: 複数 scopeId を一括で割り当て成功件数が返る")
        void bulkAssign_正常系() {
            // Given
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            BulkAssignRequest req = new BulkAssignRequest(FOLDER_ID, List.of(SCOPE_ID, SCOPE_ID_2), ScopeType.TEAM);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(membershipRepository.existsActiveByUserAndScope(
                    eq(USER_ID), eq(com.mannschaft.app.membership.domain.ScopeType.TEAM), any()))
                    .willReturn(true);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(eq(USER_ID), eq(ScopeType.TEAM), any()))
                    .willReturn(Optional.empty());
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of());
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            BulkAssignResponse result = folderService.bulkAssign(USER_ID, req);

            // Then
            assertThat(result.assignedCount()).isEqualTo(2);
            assertThat(result.skippedCount()).isZero();
            assertThat(result.errors()).isEmpty();
            verify(itemRepository, times(2)).save(any());
        }

        @Test
        @DisplayName("F15.3 異常系: scope_type 不一致で SCOPE_FOLDER_TYPE_MISMATCH 例外")
        void bulkAssign_異常系_scope_type不一致() {
            // Given: フォルダは TEAM、リクエストは ORGANIZATION
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            BulkAssignRequest req = new BulkAssignRequest(FOLDER_ID, List.of(SCOPE_ID), ScopeType.ORGANIZATION);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));

            // When / Then
            assertThatThrownBy(() -> folderService.bulkAssign(USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH.getCode()));
            verify(itemRepository, never()).save(any());
        }

        @Test
        @DisplayName("F15.3 正常系: 一部失敗時は assignedCount / skippedCount が分かれて返る（存在漏洩防止）")
        void bulkAssign_一部失敗時の集計() {
            // Given: SCOPE_ID は所属、SCOPE_ID_2 は非所属
            MyScopeFolderEntity folder = createFolderEntity(FOLDER_ID, USER_ID, ScopeType.TEAM, "フォルダ", 0);
            BulkAssignRequest req = new BulkAssignRequest(FOLDER_ID, List.of(SCOPE_ID, SCOPE_ID_2), ScopeType.TEAM);

            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_ID, USER_ID))
                    .willReturn(Optional.of(folder));
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID))
                    .willReturn(true);
            given(membershipRepository.existsActiveByUserAndScope(
                    USER_ID, com.mannschaft.app.membership.domain.ScopeType.TEAM, SCOPE_ID_2))
                    .willReturn(false);
            given(itemRepository.findByUserAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.empty());
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_ID))
                    .willReturn(List.of());
            given(itemRepository.save(any(MyScopeFolderItemEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // When
            BulkAssignResponse result = folderService.bulkAssign(USER_ID, req);

            // Then: 1 件成功、1 件スキップ、エラーコードは集約（個別 scope_id は含めない）
            assertThat(result.assignedCount()).isEqualTo(1);
            assertThat(result.skippedCount()).isEqualTo(1);
            assertThat(result.errors())
                    .containsExactly(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER.getCode());
        }
    }

    // ========================================
    // F15.3 追加: handleMembershipEnded / handleScopeDeleted
    // ========================================

    @Nested
    @DisplayName("handleMembershipEnded / handleScopeDeleted (F15.3 イベント連動)")
    class HandleEventHooks {

        @Test
        @DisplayName("handleMembershipEnded: ユーザー × scope のアイテムを物理削除")
        void handleMembershipEnded_正常系_物理削除() {
            // Given
            MyScopeFolderItemEntity item1 = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findAllByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of(item1));

            // When
            folderService.handleMembershipEnded(USER_ID, ScopeType.TEAM, SCOPE_ID);

            // Then
            verify(itemRepository).deleteAll(List.of(item1));
        }

        @Test
        @DisplayName("handleMembershipEnded: 該当アイテムが無くてもエラーにならない")
        void handleMembershipEnded_アイテム無し_スキップ() {
            // Given
            given(itemRepository.findAllByUserAndScope(USER_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of());

            // When
            folderService.handleMembershipEnded(USER_ID, ScopeType.TEAM, SCOPE_ID);

            // Then
            verify(itemRepository, never()).deleteAll(any());
        }

        @Test
        @DisplayName("handleScopeDeleted: 全ユーザー分のアイテムを物理削除")
        void handleScopeDeleted_正常系() {
            // Given
            MyScopeFolderItemEntity item1 = createItemEntity(1L, FOLDER_ID, SCOPE_ID, 0);
            MyScopeFolderItemEntity item2 = createItemEntity(2L, OTHER_FOLDER_ID, SCOPE_ID, 0);
            given(itemRepository.findAllByScope(ScopeType.TEAM, SCOPE_ID))
                    .willReturn(List.of(item1, item2));

            // When
            folderService.handleScopeDeleted(ScopeType.TEAM, SCOPE_ID);

            // Then
            verify(itemRepository).deleteAll(List.of(item1, item2));
        }
    }
}
