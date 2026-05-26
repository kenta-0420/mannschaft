package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.ArchiveFolderTreeResponse;
import com.mannschaft.app.bulletin.dto.CreateArchiveFolderRequest;
import com.mannschaft.app.bulletin.dto.DeleteArchiveFolderResponse;
import com.mannschaft.app.bulletin.dto.UpdateArchiveFolderRequest;
import com.mannschaft.app.bulletin.entity.BulletinArchiveFolderEntity;
import com.mannschaft.app.bulletin.repository.BulletinArchiveFolderRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinArchiveFolderService} の単体テスト（設計書 F05.1 §5）。
 *
 * <p>循環参照防止・深さ超過・削除退避・scope 越境・上限 200・認可・ツリー取得を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinArchiveFolderService 単体テスト")
class BulletinArchiveFolderServiceTest {

    @Mock
    private BulletinArchiveFolderRepository folderRepository;

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinAccessGuard accessGuard;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private BulletinArchiveFolderService service;

    private static final ScopeType SCOPE = ScopeType.TEAM;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;

    private BulletinArchiveFolderEntity folder(UUID id, UUID parentId, int depth) {
        BulletinArchiveFolderEntity f = BulletinArchiveFolderEntity.builder()
                .scopeType(SCOPE)
                .scopeId(SCOPE_ID)
                .parentFolderId(parentId)
                .name("folder-" + id)
                .depth(depth)
                .displayOrder(0)
                .createdBy(USER_ID)
                .build();
        f.setId(id);
        return f;
    }

    // ========================================
    // ツリー取得 + childCount/threadCount
    // ========================================

    @Nested
    @DisplayName("getFolderTree")
    class GetFolderTree {

        @Test
        @DisplayName("ツリー取得_childCountとthreadCountが付与される")
        void ツリー取得_集計付与() {
            UUID rootId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            BulletinArchiveFolderEntity root = folder(rootId, null, 0);
            BulletinArchiveFolderEntity child = folder(childId, rootId, 1);
            given(folderRepository.findByScopeTypeAndScopeIdOrderByDisplayOrderAsc(SCOPE, SCOPE_ID))
                    .willReturn(List.of(root, child));
            given(threadRepository.countArchivedThreadsByFolder(SCOPE, SCOPE_ID))
                    .willReturn(List.of(new Object[]{rootId, 5L}, new Object[]{childId, 12L}));
            given(threadRepository.countByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderIdIsNull(SCOPE, SCOPE_ID))
                    .willReturn(8L);

            ArchiveFolderTreeResponse tree = service.getFolderTree(SCOPE, SCOPE_ID, USER_ID);

            assertThat(tree.getData()).hasSize(1);
            ArchiveFolderResponse rootNode = tree.getData().get(0);
            assertThat(rootNode.getId()).isEqualTo(rootId);
            assertThat(rootNode.getChildCount()).isEqualTo(1);
            assertThat(rootNode.getThreadCount()).isEqualTo(5);
            assertThat(rootNode.getChildren()).hasSize(1);
            assertThat(rootNode.getChildren().get(0).getThreadCount()).isEqualTo(12);
            assertThat(tree.getMeta().getUnfiledThreadCount()).isEqualTo(8L);
            assertThat(tree.getMeta().getTotalFolderCount()).isEqualTo(2L);
            assertThat(tree.getMeta().getMaxFolderCount()).isEqualTo(200);
        }

        @Test
        @DisplayName("ツリー取得_非メンバー403")
        void ツリー取得_非メンバー403() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(USER_ID, SCOPE, SCOPE_ID);
            assertThatThrownBy(() -> service.getFolderTree(SCOPE, SCOPE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ========================================
    // 作成: 深さ超過 / 上限200 / 権限
    // ========================================

    @Nested
    @DisplayName("createFolder")
    class CreateFolder {

        @Test
        @DisplayName("作成_ルート_正常_depth0")
        void 作成_ルート_正常() {
            CreateArchiveFolderRequest req = new CreateArchiveFolderRequest();
            req.setName("2025年度");
            given(folderRepository.countByScopeForUpdate(SCOPE, SCOPE_ID)).willReturn(0L);
            given(folderRepository.findMaxDisplayOrder(SCOPE, SCOPE_ID, null)).willReturn(-1);
            given(folderRepository.save(any())).willAnswer(inv -> {
                BulletinArchiveFolderEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            ArchiveFolderResponse res = service.createFolder(SCOPE, SCOPE_ID, USER_ID, req);

            assertThat(res.getDepth()).isZero();
            assertThat(res.getDisplayOrder()).isZero();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE, SCOPE_ID);
        }

        @Test
        @DisplayName("作成_深さ超過_400")
        void 作成_深さ超過_400() {
            UUID parentId = UUID.randomUUID();
            BulletinArchiveFolderEntity parent = folder(parentId, null, 4); // depth4 の子は depth5 で超過
            CreateArchiveFolderRequest req = new CreateArchiveFolderRequest();
            req.setName("深すぎ");
            req.setParentFolderId(parentId);
            given(folderRepository.findByIdForUpdate(parentId)).willReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.createFolder(SCOPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_DEPTH_EXCEEDED));
        }

        @Test
        @DisplayName("作成_上限200_409")
        void 作成_上限200_409() {
            CreateArchiveFolderRequest req = new CreateArchiveFolderRequest();
            req.setName("溢れ");
            given(folderRepository.countByScopeForUpdate(SCOPE, SCOPE_ID)).willReturn(200L);

            assertThatThrownBy(() -> service.createFolder(SCOPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_LIMIT_EXCEEDED));
        }

        @Test
        @DisplayName("作成_親がscope越境_409")
        void 作成_親scope越境_409() {
            UUID parentId = UUID.randomUUID();
            BulletinArchiveFolderEntity parent = BulletinArchiveFolderEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(999L)
                    .name("別scope").depth(0).displayOrder(0).build();
            parent.setId(parentId);
            CreateArchiveFolderRequest req = new CreateArchiveFolderRequest();
            req.setName("子");
            req.setParentFolderId(parentId);
            given(folderRepository.findByIdForUpdate(parentId)).willReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.createFolder(SCOPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH));
        }

        @Test
        @DisplayName("作成_非管理者_403")
        void 作成_非管理者_403() {
            CreateArchiveFolderRequest req = new CreateArchiveFolderRequest();
            req.setName("x");
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(USER_ID, SCOPE, SCOPE_ID);

            assertThatThrownBy(() -> service.createFolder(SCOPE, SCOPE_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class);
            verify(folderRepository, never()).save(any());
        }
    }

    // ========================================
    // 移動: 循環参照 / 深さ超過
    // ========================================

    @Nested
    @DisplayName("updateFolder（移動）")
    class MoveFolder {

        @Test
        @DisplayName("移動_自分自身へ_循環参照400")
        void 移動_自分自身_400() {
            UUID id = UUID.randomUUID();
            BulletinArchiveFolderEntity f = folder(id, null, 0);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID)).willReturn(List.of(f));

            UpdateArchiveFolderRequest req = new UpdateArchiveFolderRequest();
            req.setParentFolderId(id); // 自分自身を親に

            assertThatThrownBy(() -> service.updateFolder(SCOPE, SCOPE_ID, USER_ID, id, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_CYCLE));
        }

        @Test
        @DisplayName("移動_子孫へ_循環参照400")
        void 移動_子孫_400() {
            UUID rootId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            BulletinArchiveFolderEntity root = folder(rootId, null, 0);
            BulletinArchiveFolderEntity child = folder(childId, rootId, 1);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID))
                    .willReturn(List.of(root, child));

            UpdateArchiveFolderRequest req = new UpdateArchiveFolderRequest();
            req.setParentFolderId(childId); // root を 自分の子の下へ移動

            assertThatThrownBy(() -> service.updateFolder(SCOPE, SCOPE_ID, USER_ID, rootId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_CYCLE));
        }

        @Test
        @DisplayName("移動_サブツリー深さ超過_400")
        void 移動_深さ超過_400() {
            // 移動対象 A（サブツリー深さ 2 段: A→B→C, 相対最大深さ=2）を depth3 の親 P の下へ移動
            // → 新 depth 4 + 相対 2 = 6 > 4 で超過
            UUID pId = UUID.randomUUID();
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            UUID cId = UUID.randomUUID();
            BulletinArchiveFolderEntity p = folder(pId, null, 3);
            BulletinArchiveFolderEntity a = folder(aId, null, 0);
            BulletinArchiveFolderEntity b = folder(bId, aId, 1);
            BulletinArchiveFolderEntity c = folder(cId, bId, 2);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID))
                    .willReturn(List.of(p, a, b, c));

            UpdateArchiveFolderRequest req = new UpdateArchiveFolderRequest();
            req.setParentFolderId(pId);

            assertThatThrownBy(() -> service.updateFolder(SCOPE, SCOPE_ID, USER_ID, aId, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_DEPTH_EXCEEDED));
        }

        @Test
        @DisplayName("移動_正常_depth再計算される")
        void 移動_正常_depth再計算() {
            // A(root,0) と B(root,0) があり、A を B の下へ移動 → A.depth=1
            UUID aId = UUID.randomUUID();
            UUID bId = UUID.randomUUID();
            BulletinArchiveFolderEntity a = folder(aId, null, 0);
            BulletinArchiveFolderEntity b = folder(bId, null, 0);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID))
                    .willReturn(List.of(a, b));
            given(folderRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            UpdateArchiveFolderRequest req = new UpdateArchiveFolderRequest();
            req.setParentFolderId(bId);

            ArchiveFolderResponse res = service.updateFolder(SCOPE, SCOPE_ID, USER_ID, aId, req);

            assertThat(res.getParentId()).isEqualTo(bId);
            assertThat(res.getDepth()).isEqualTo(1);
        }
    }

    // ========================================
    // 削除: スレッドNULL化 + 子繰り上げ + depth再計算
    // ========================================

    @Nested
    @DisplayName("deleteFolder（退避）")
    class DeleteFolder {

        @Test
        @DisplayName("削除_スレッドNULL化と子繰り上げ_depth再計算")
        void 削除_退避() {
            // root(0) → mid(1) → leaf(2)。mid を削除すると leaf は root 直下(depth1)へ繰り上げ
            UUID rootId = UUID.randomUUID();
            UUID midId = UUID.randomUUID();
            UUID leafId = UUID.randomUUID();
            BulletinArchiveFolderEntity root = folder(rootId, null, 0);
            BulletinArchiveFolderEntity mid = folder(midId, rootId, 1);
            BulletinArchiveFolderEntity leaf = folder(leafId, midId, 2);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID))
                    .willReturn(List.of(root, mid, leaf));
            given(threadRepository.bulkClearArchiveFolderId(midId)).willReturn(5);
            given(folderRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            DeleteArchiveFolderResponse res = service.deleteFolder(SCOPE, SCOPE_ID, USER_ID, midId);

            assertThat(res.getMovedThreadCount()).isEqualTo(5);
            assertThat(res.getPromotedFolderCount()).isEqualTo(1);
            // leaf は root の子へ繰り上げ → depth1
            assertThat(leaf.getParentFolderId()).isEqualTo(rootId);
            assertThat(leaf.getDepth()).isEqualTo(1);
            assertThat(mid.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("削除_ルートフォルダ_子はルートへ繰り上げ")
        void 削除_ルート_子ルート化() {
            UUID rootId = UUID.randomUUID();
            UUID childId = UUID.randomUUID();
            BulletinArchiveFolderEntity root = folder(rootId, null, 0);
            BulletinArchiveFolderEntity child = folder(childId, rootId, 1);
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID))
                    .willReturn(List.of(root, child));
            given(threadRepository.bulkClearArchiveFolderId(rootId)).willReturn(0);
            given(folderRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            DeleteArchiveFolderResponse res = service.deleteFolder(SCOPE, SCOPE_ID, USER_ID, rootId);

            assertThat(res.getPromotedFolderCount()).isEqualTo(1);
            assertThat(child.getParentFolderId()).isNull();
            assertThat(child.getDepth()).isZero();
        }

        @Test
        @DisplayName("削除_不存在_404")
        void 削除_不存在_404() {
            UUID missing = UUID.randomUUID();
            given(folderRepository.findByScopeForUpdate(SCOPE, SCOPE_ID)).willReturn(List.of());

            assertThatThrownBy(() -> service.deleteFolder(SCOPE, SCOPE_ID, USER_ID, missing))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND));
        }
    }

    // ========================================
    // validateFolderInScope
    // ========================================

    @Nested
    @DisplayName("validateFolderInScope")
    class ValidateFolderInScope {

        @Test
        @DisplayName("scope一致_正常")
        void scope一致() {
            UUID id = UUID.randomUUID();
            BulletinArchiveFolderEntity f = folder(id, null, 0);
            given(folderRepository.findById(id)).willReturn(Optional.of(f));

            BulletinArchiveFolderEntity res = service.validateFolderInScope(SCOPE, SCOPE_ID, id);
            assertThat(res.getId()).isEqualTo(id);
        }

        @Test
        @DisplayName("不存在_404")
        void 不存在_404() {
            UUID id = UUID.randomUUID();
            given(folderRepository.findById(id)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.validateFolderInScope(SCOPE, SCOPE_ID, id))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("scope越境_409")
        void scope越境_409() {
            UUID id = UUID.randomUUID();
            BulletinArchiveFolderEntity f = BulletinArchiveFolderEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(999L)
                    .name("別").depth(0).displayOrder(0).build();
            f.setId(id);
            given(folderRepository.findById(id)).willReturn(Optional.of(f));
            assertThatThrownBy(() -> service.validateFolderInScope(SCOPE, SCOPE_ID, id))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.ARCHIVE_FOLDER_SCOPE_MISMATCH));
        }
    }
}
