package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderDetailResponse;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFileQuotaService;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFolderQueryService} の単体テスト。
 *
 * <p>核心は「folderId からスコープを解決して自前で認可を当てる」漏洩防止の検証である。
 * 各受け入れ条件（AC-1〜AC-12）に 1:1 対応するテストを並べる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderQueryService 単体テスト")
class SharedFolderQueryServiceTest {

    @Mock
    private SharedFolderRepository folderRepository;

    @Mock
    private SharedFileRepository fileRepository;

    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private SharedFileQuotaService sharedFileQuotaService;

    @InjectMocks
    private SharedFolderQueryService service;

    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long FOLDER_ID = 100L;
    private static final Long TEAM_ID = 5L;
    private static final Long ORG_ID = 7L;

    private SharedFolderEntity personalFolder(Long ownerId) {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.PERSONAL).userId(ownerId)
                .name("個人フォルダ").createdBy(ownerId).build();
    }

    private SharedFolderEntity teamFolder() {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                .name("チームフォルダ").createdBy(USER_ID).build();
    }

    private SharedFolderEntity orgFolder() {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.ORGANIZATION).organizationId(ORG_ID)
                .name("組織フォルダ").createdBy(USER_ID).build();
    }

    @Nested
    @DisplayName("getFolderDetail — 認可と組み立て")
    class GetFolderDetail {

        @Test
        @DisplayName("AC-1: PERSONAL 本人で subfolders/files/breadcrumbs を組み立てて返す")
        void AC1_PERSONAL本人_詳細組み立て() {
            SharedFolderEntity folder = personalFolder(USER_ID);
            SharedFolderEntity sub = SharedFolderEntity.builder()
                    .id(101L).scopeType(FileScopeType.PERSONAL).userId(USER_ID)
                    .parentId(FOLDER_ID).name("サブ").createdBy(USER_ID).build();
            SharedFileEntity file = SharedFileEntity.builder()
                    .id(201L).folderId(FOLDER_ID).name("a.pdf").fileKey("k").fileSize(10L)
                    .contentType("application/pdf").createdBy(USER_ID).currentVersion(1).build();

            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(sub));
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(file));
            given(fileRepository.countByFolderId(101L)).willReturn(2L);
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(Map.of(USER_ID, "ユーザー10"));

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.id()).isEqualTo(FOLDER_ID);
            assertThat(result.scopeType()).isEqualTo("PERSONAL");
            assertThat(result.scopeId()).isEqualTo(String.valueOf(USER_ID));
            assertThat(result.subfolders()).hasSize(1);
            assertThat(result.files()).hasSize(1);
            assertThat(result.fileCount()).isEqualTo(1);
            assertThat(result.subfolderCount()).isEqualTo(1);
            assertThat(result.breadcrumbs()).extracting(FolderDetailResponse.BreadcrumbItem::id)
                    .containsExactly(FOLDER_ID);
            assertThat(result.createdBy().displayName()).isEqualTo("ユーザー10");
        }

        @Test
        @DisplayName("AC-10: 他人の PERSONAL フォルダは FOLDER_NOT_FOUND（404 で存在隠蔽）")
        void AC10_他人PERSONAL_NotFound() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-4: TEAM メンバーは checkMembership を通過し詳細が返る")
        void AC4_TEAMメンバー_正常() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.scopeType()).isEqualTo("TEAM");
            assertThat(result.scopeId()).isEqualTo(String.valueOf(TEAM_ID));
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("AC-8: TEAM 非メンバーは checkMembership が COMMON_002（403）を投げる")
        void AC8_TEAM非メンバー_403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-5: ORGANIZATION メンバーは checkMembership を通過し詳細が返る")
        void AC5_ORGメンバー_正常() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(orgFolder()));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.scopeId()).isEqualTo(String.valueOf(ORG_ID));
            verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("AC-9: ORGANIZATION 非メンバーは 403（COMMON_002）")
        void AC9_ORG非メンバー_403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(orgFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-11: TOURNAMENT フォルダは FolderScopeAccessGuard に委譲する")
        void AC11_TOURNAMENT_guard委譲() {
            SharedFolderEntity tournamentFolder = SharedFolderEntity.builder()
                    .id(FOLDER_ID).scopeType(FileScopeType.TOURNAMENT).organizationId(ORG_ID)
                    .scopeRefId(42L).name("大会フォルダ").createdBy(USER_ID).build();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.scopeId()).isEqualTo("42");
            verify(folderScopeAccessGuard).checkFolderViewByFolderId(FOLDER_ID, USER_ID);
        }

        @Test
        @DisplayName("AC-12: 存在しない folderId は FOLDER_NOT_FOUND")
        void AC12_存在しない_NotFound() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-6: 親→子→孫の breadcrumb 順序になり、削除済みの祖先は除外される")
        void AC6_breadcrumb順序と削除祖先除外() {
            // C(現在, parent=B) → B(parent=A) → A(parent=null) だが A は削除済み（findById 空）
            Long aId = 1L;
            Long bId = 2L;
            Long cId = FOLDER_ID;
            SharedFolderEntity c = SharedFolderEntity.builder()
                    .id(cId).scopeType(FileScopeType.PERSONAL).userId(USER_ID)
                    .parentId(bId).name("孫C").createdBy(USER_ID).build();
            SharedFolderEntity b = SharedFolderEntity.builder()
                    .id(bId).scopeType(FileScopeType.PERSONAL).userId(USER_ID)
                    .parentId(aId).name("子B").createdBy(USER_ID).build();

            given(folderRepository.findById(cId)).willReturn(Optional.of(c));
            given(folderRepository.findById(bId)).willReturn(Optional.of(b));
            given(folderRepository.findById(aId)).willReturn(Optional.empty()); // 削除済み祖先
            given(folderRepository.findByParentIdOrderByNameAsc(cId)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(cId)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(cId, USER_ID);

            assertThat(result.breadcrumbs())
                    .extracting(FolderDetailResponse.BreadcrumbItem::name)
                    .containsExactly("子B", "孫C"); // 削除済み A は欠落、順序はルート寄り→現在
        }

        @Test
        @DisplayName("AC-2: サブフォルダの fileCount は countByFolderId で解決される")
        void AC2_サブフォルダfileCount() {
            SharedFolderEntity folder = personalFolder(USER_ID);
            SharedFolderEntity sub = SharedFolderEntity.builder()
                    .id(101L).scopeType(FileScopeType.PERSONAL).userId(USER_ID)
                    .parentId(FOLDER_ID).name("サブ").createdBy(USER_ID).build();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(sub));
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.countByFolderId(101L)).willReturn(3L);
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.subfolders().get(0).fileCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-3: ファイルの versionCount は entity.currentVersion を反映する")
        void AC3_versionCount() {
            SharedFolderEntity folder = personalFolder(USER_ID);
            SharedFileEntity file = SharedFileEntity.builder()
                    .id(201L).folderId(FOLDER_ID).name("doc.xlsx").fileKey("k").fileSize(20L)
                    .contentType("application/vnd.ms-excel").createdBy(USER_ID).currentVersion(4).build();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(file));
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of(USER_ID, "u10"));

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            FolderDetailResponse.FileSummary fs = result.files().get(0);
            assertThat(fs.versionCount()).isEqualTo(4);
            assertThat(fs.fileName()).isEqualTo("doc.xlsx");
            assertThat(fs.mimeType()).isEqualTo("application/vnd.ms-excel");
            assertThat(fs.uploadedBy().displayName()).isEqualTo("u10");
        }
    }

    @Nested
    @DisplayName("listFolders — スコープ認可")
    class ListFolders {

        @Test
        @DisplayName("TEAM ルート一覧はメンバーシップ検証を通る")
        void TEAMルート一覧_メンバー検証() {
            SharedFolderEntity root = SharedFolderEntity.builder()
                    .id(101L).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("ルート").createdBy(USER_ID).build();
            given(folderRepository.findByTeamIdAndParentIdIsNullOrderByNameAsc(TEAM_ID))
                    .willReturn(List.of(root));
            given(fileRepository.countByFolderId(101L)).willReturn(0L);
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            List<FolderDetailResponse.FolderSummary> result =
                    service.listFolders("TEAM", String.valueOf(TEAM_ID), null, USER_ID);

            assertThat(result).hasSize(1);
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("TEAM 非メンバーは一覧で 403（COMMON_002）")
        void TEAM非メンバー_一覧403() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() ->
                    service.listFolders("TEAM", String.valueOf(TEAM_ID), null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("createFolder — スコープ認可")
    class CreateFolder {

        @Test
        @DisplayName("TEAM フォルダ作成はメンバーシップ検証を通し保存する")
        void TEAMフォルダ作成_メンバー検証() {
            CreateFolderRequest request =
                    new CreateFolderRequest("新規", null, null, "TEAM", String.valueOf(TEAM_ID), null, null);
            SharedFolderEntity saved = SharedFolderEntity.builder()
                    .id(101L).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .name("新規").createdBy(USER_ID).build();
            given(folderRepository.existsByParentIdAndName(null, "新規")).willReturn(false);
            given(folderRepository.save(any(SharedFolderEntity.class))).willReturn(saved);
            lenient().when(fileRepository.countByFolderId(anyLong())).thenReturn(0L);
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse.FolderSummary result =
                    service.createFolder(request, String.valueOf(TEAM_ID), USER_ID);

            assertThat(result.name()).isEqualTo("新規");
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(folderRepository).save(any(SharedFolderEntity.class));
        }

        @Test
        @DisplayName("TEAM 非メンバーは作成で 403（保存しない）")
        void TEAM非メンバー_作成403() {
            CreateFolderRequest request =
                    new CreateFolderRequest("新規", null, null, "TEAM", String.valueOf(TEAM_ID), null, null);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() ->
                    service.createFolder(request, String.valueOf(TEAM_ID), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    /**
     * download-url 発行（{@link com.mannschaft.app.filesharing.service.SharedFileService#presignDownload}）
     * から呼ばれるファイル単位の閲覧認可入口。スコープ別ポリシーを再利用できることを検証する。
     */
    @Nested
    @DisplayName("authorizeFolderViewById — ファイル単位ダウンロード認可の再利用入口")
    class AuthorizeFolderViewById {

        @Test
        @DisplayName("PERSONAL 本人は通過する（例外なし）")
        void PERSONAL本人_通過() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(USER_ID)));

            service.authorizeFolderViewById(FOLDER_ID, USER_ID);
            // 例外が出なければ OK（個人スコープでは外部サービス呼び出しなし）
        }

        @Test
        @DisplayName("PERSONAL 他人は 404（FOLDER_NOT_FOUND・存在隠蔽）")
        void PERSONAL他人_404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));

            assertThatThrownBy(() -> service.authorizeFolderViewById(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("TEAM メンバーは checkMembership を通して通過する")
        void TEAMメンバー_通過() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));

            service.authorizeFolderViewById(FOLDER_ID, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("TEAM 非メンバーは 403（COMMON_002）")
        void TEAM非メンバー_403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.authorizeFolderViewById(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("存在しないフォルダは 404（FOLDER_NOT_FOUND）")
        void フォルダ不存在_404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorizeFolderViewById(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }
    }

    /**
     * フォルダ削除（{@code DELETE /api/v1/files/folders/{id}}）の核心。
     *
     * <p>① スコープ別の<b>削除認可</b>（PERSONAL=本人のみ / TEAM・ORG=管理者(ADMIN/DEPUTY_ADMIN)限定・
     * 一般 MEMBER は 403 / 大会=編集認可）、② 部分木（自身＋サブフォルダ）の再帰カスケード soft-delete、
     * ③ ファイルごとの {@link SharedFileQuotaService#recordFileDeletion} による容量戻し、を検証する。</p>
     */
    @Nested
    @DisplayName("deleteFolder — 認可・カスケード・容量戻し")
    class DeleteFolder {

        private SharedFolderEntity sub(Long id, Long parentId) {
            return SharedFolderEntity.builder()
                    .id(id).scopeType(FileScopeType.PERSONAL).userId(USER_ID)
                    .parentId(parentId).name("サブ" + id).createdBy(USER_ID).build();
        }

        private SharedFileEntity file(Long id, Long folderId, long size) {
            return SharedFileEntity.builder()
                    .id(id).folderId(folderId).name("f" + id).fileKey("k" + id).fileSize(size)
                    .contentType("application/octet-stream").createdBy(USER_ID).currentVersion(1).build();
        }

        @Test
        @DisplayName("AC-FD-1: 本人の個人フォルダ(ファイル2＋サブ1)を削除→全 softDelete・recordFileDeletion がファイル数分")
        void ACFD1_本人個人フォルダ削除_カスケードと容量戻し() {
            SharedFolderEntity root = personalFolder(USER_ID);
            SharedFolderEntity subFolder = sub(101L, FOLDER_ID);
            SharedFileEntity f1 = file(201L, FOLDER_ID, 10L);
            SharedFileEntity f2 = file(202L, FOLDER_ID, 20L);

            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(root));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(subFolder));
            given(folderRepository.findByParentIdOrderByNameAsc(101L)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(f1, f2));
            given(fileRepository.findByFolderIdOrderByNameAsc(101L)).willReturn(List.of());

            service.deleteFolder(FOLDER_ID, USER_ID);

            // 全フォルダ・全ファイルが softDelete されている
            assertThat(root.getDeletedAt()).isNotNull();
            assertThat(subFolder.getDeletedAt()).isNotNull();
            assertThat(f1.getDeletedAt()).isNotNull();
            assertThat(f2.getDeletedAt()).isNotNull();
            verify(folderRepository, times(2)).save(any(SharedFolderEntity.class)); // root + sub
            verify(fileRepository, times(2)).save(any(SharedFileEntity.class));     // f1 + f2
            // 容量戻しはファイル数分・正しい fileSize で（root スコープ解決は recordFileDeletion 内部）
            verify(sharedFileQuotaService).recordFileDeletion(root, 201L, 10L, USER_ID);
            verify(sharedFileQuotaService).recordFileDeletion(root, 202L, 20L, USER_ID);
            verify(sharedFileQuotaService, times(2))
                    .recordFileDeletion(any(SharedFolderEntity.class), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-3: 他人の個人フォルダは 404（softDelete も recordFileDeletion も呼ばない）")
        void ACFD3_他人個人_404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));

            assertThatThrownBy(() -> service.deleteFolder(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));

            verify(folderRepository, never()).save(any());
            verify(fileRepository, never()).save(any());
            verify(sharedFileQuotaService, never())
                    .recordFileDeletion(any(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-4: 非所属チームフォルダは 403（checkAdminOrAbove が COMMON_002・何も削除しない）")
        void ACFD4_非所属チーム_403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.deleteFolder(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            verify(folderRepository, never()).save(any());
            verify(fileRepository, never()).save(any());
            verify(sharedFileQuotaService, never())
                    .recordFileDeletion(any(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-4b: 所属していても一般 MEMBER は TEAM フォルダを削除できず 403（管理者限定・何も削除しない）")
        void ACFD4b_一般MEMBER_403() {
            // 一般メンバーは isMember=true でも isAdminOrAbove=false → checkAdminOrAbove が COMMON_002。
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.deleteFolder(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));

            verify(folderRepository, never()).save(any());
            verify(fileRepository, never()).save(any());
            verify(sharedFileQuotaService, never())
                    .recordFileDeletion(any(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-5: 存在しない folderId は 404（何も削除しない）")
        void ACFD5_不存在_404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteFolder(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));

            verify(folderRepository, never()).save(any());
            verify(sharedFileQuotaService, never())
                    .recordFileDeletion(any(), anyLong(), anyLong(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-6: サブフォルダ配下のファイルも再帰的に softDelete・recordFileDeletion される")
        void ACFD6_再帰カスケード() {
            SharedFolderEntity root = personalFolder(USER_ID);
            SharedFolderEntity subFolder = sub(101L, FOLDER_ID);
            SharedFileEntity nested = file(301L, 101L, 30L); // サブフォルダ配下のファイル

            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(root));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(subFolder));
            given(folderRepository.findByParentIdOrderByNameAsc(101L)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(101L)).willReturn(List.of(nested));

            service.deleteFolder(FOLDER_ID, USER_ID);

            assertThat(nested.getDeletedAt()).isNotNull();
            assertThat(subFolder.getDeletedAt()).isNotNull();
            // 容量戻しはサブフォルダ自身のスコープで解決される（recordFileDeletion(subFolder, ...)）
            verify(sharedFileQuotaService).recordFileDeletion(subFolder, 301L, 30L, USER_ID);
        }

        @Test
        @DisplayName("AC-FD-8: TEAM の ADMIN は checkAdminOrAbove を通過して 204 削除可（checkMembership は使わない）")
        void ACFD8_ADMIN_削除可() {
            SharedFolderEntity root = teamFolder();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(root));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            // ADMIN は checkAdminOrAbove が素通り（void・doNothing）→ 削除が進む。

            service.deleteFolder(FOLDER_ID, USER_ID);

            assertThat(root.getDeletedAt()).isNotNull();
            // 削除権限の関門は checkAdminOrAbove（メンバーシップ判定では弱すぎる）。
            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("AC-FD-8: ORGANIZATION の DEPUTY_ADMIN(副長) も checkAdminOrAbove を通過して 204 削除可")
        void ACFD8_DEPUTY_ADMIN_削除可() {
            // checkAdminOrAbove は内部 ADMIN_ROLES={ADMIN,DEPUTY_ADMIN} で副長も許可する（実コードで確認済み）。
            // 副長許可は「checkAdminOrAbove が例外を投げない」ことで表現される（void・doNothing）。
            SharedFolderEntity root = orgFolder();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(root));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());

            service.deleteFolder(FOLDER_ID, USER_ID);

            assertThat(root.getDeletedAt()).isNotNull();
            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
        }
    }

    // ============================================================
    // B: 最低可視ロール（表示制御）
    // ============================================================

    private static final Long FILE_ID = 300L;

    private SharedFolderEntity teamFolder(FileVisibilityRole minRole) {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                .minVisibleRole(minRole)
                .name("チームフォルダ").createdBy(USER_ID).build();
    }

    private SharedFileEntity fileIn(SharedFolderEntity folder, FileVisibilityRole fileMinRole, boolean dlDisabled) {
        return SharedFileEntity.builder()
                .id(FILE_ID).folderId(folder.getId()).name("doc.pdf").fileKey("k").fileSize(10L)
                .contentType("application/pdf").createdBy(USER_ID).currentVersion(1)
                .minVisibleRole(fileMinRole).downloadDisabled(dlDisabled).build();
    }

    @Nested
    @DisplayName("B: 最低可視ロール — フォルダ詳細（getFolderDetail）")
    class MinVisibleRoleFolder {

        @Test
        @DisplayName("AC-B1: min=ADMINS_AND_ABOVE のチームフォルダを非管理者 MEMBER が閲覧→403(COMMON_002)")
        void ACB1_ADMINS_MEMBER_403() {
            given(folderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE)));
            // メンバーシップは通過（checkMembership は void・doNothing）だが ADMIN 未満で min role が弾く。
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B2: 同フォルダを ADMIN/DEPUTY_ADMIN(hasRoleOrAbove ADMIN=true) が閲覧→200")
        void ACB2_ADMINS_ADMIN_200() {
            given(folderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE)));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(true);
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.minVisibleRole()).isEqualTo(FileVisibilityRole.ADMINS_AND_ABOVE);
        }

        @Test
        @DisplayName("AC-B3: MEMBERS_AND_ABOVE を SUPPORTER(hasRoleOrAbove MEMBER=false) が閲覧→403")
        void ACB3_MEMBERS_SUPPORTER_403() {
            given(folderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE)));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B3: MEMBERS_AND_ABOVE を MEMBER(hasRoleOrAbove MEMBER=true) が閲覧→200")
        void ACB3_MEMBERS_MEMBER_200() {
            given(folderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE)));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            assertThatCode(() -> service.getFolderDetail(FOLDER_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B6: min=NULL（既存データ）は所属者全員可視（hasRoleOrAbove を呼ばず非回帰）")
        void ACB6_NULL_非回帰() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder(null)));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            service.getFolderDetail(FOLDER_ID, USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
            verify(accessControlService, never()).isSystemAdmin(anyLong());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は min role を貫通して閲覧できる（B 貫通）")
        void SYSTEM_ADMIN_貫通() {
            given(folderRepository.findById(FOLDER_ID))
                    .willReturn(Optional.of(teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE)));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            assertThatCode(() -> service.getFolderDetail(FOLDER_ID, USER_ID)).doesNotThrowAnyException();
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("B: 最低可視ロール — ファイル経路（authorizeFileViewById・ファイル値優先→フォルダ継承）")
    class MinVisibleRoleFile {

        @Test
        @DisplayName("AC-B4: フォルダ=MEMBERS_AND_ABOVE・ファイル=NULL→フォルダ継承で MEMBER 可視")
        void ACB4_ファイルNULL_フォルダ継承() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            assertThatCode(() -> service.authorizeFileViewById(FILE_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B5: ファイル=ADMINS_AND_ABOVE・フォルダ=NULL→ファイル優先で ADMIN のみ（非管理者は403）")
        void ACB5_ファイル優先_非管理者403() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, FileVisibilityRole.ADMINS_AND_ABOVE, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> service.authorizeFileViewById(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B5: ファイル=ADMINS_AND_ABOVE・フォルダ=NULL→ADMIN は可視")
        void ACB5_ファイル優先_ADMIN可視() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, FileVisibilityRole.ADMINS_AND_ABOVE, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(true);

            assertThatCode(() -> service.authorizeFileViewById(FILE_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B8: min role は authorizeDownload(DL URL 発行)にも効く（フォルダ ADMINS を非管理者→403）")
        void ACB8_DL認可にも効く() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> service.authorizeDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    // ============================================================
    // C: ダウンロード禁止フラグ（authorizeDownload）
    // ============================================================

    @Nested
    @DisplayName("C: ダウンロード禁止フラグ — authorizeDownload / 閲覧は通す")
    class DownloadDisabled {

        @Test
        @DisplayName("AC-C1: file.downloadDisabled=true→authorizeDownload が DOWNLOAD_DISABLED(403)")
        void ACC1_ファイル禁止_403() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, true);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.authorizeDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C2: DL 禁止でも閲覧（authorizeFileViewById）は通る")
        void ACC2_禁止でも閲覧可() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, true);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThatCode(() -> service.authorizeFileViewById(FILE_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-C3: フォルダ=true・ファイル未設定→配下 DL は 403（継承）")
        void ACC3_フォルダ禁止_継承403() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .downloadDisabled(true).name("f").createdBy(USER_ID).build();
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.authorizeDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C4: フォルダ=true・ファイル=false→それでも403（禁止は単調・ファイルで解除不可）")
        void ACC4_単調_ファイルfalseでも403() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .downloadDisabled(true).name("f").createdBy(USER_ID).build();
            SharedFileEntity file = fileIn(folder, null, false); // ファイル側 false
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThatThrownBy(() -> service.authorizeDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C5: 既定 false→従来どおり DL 可（非回帰）")
        void ACC5_既定false_DL可() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThatCode(() -> service.authorizeDownload(FILE_ID, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-C6: SYSTEM_ADMIN は DL 禁止を貫通して DL 可（B/C 貫通）")
        void ACC6_SYSTEM_ADMIN_貫通() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                    .downloadDisabled(true).name("f").createdBy(USER_ID).build();
            SharedFileEntity file = fileIn(folder, null, true);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> service.authorizeDownload(FILE_ID, USER_ID)).doesNotThrowAnyException();
        }
    }

    // ============================================================
    // B: 一覧経路の許可レベル解決（resolveVisibleFileLevels）
    //    フォルダより厳しいファイル個別 min role のメタ露出をクエリ段階で絞るための土台。
    // ============================================================

    @Nested
    @DisplayName("B: resolveVisibleFileLevels — 一覧の許可レベル集合解決")
    class ResolveVisibleFileLevels {

        @Test
        @DisplayName("AC-2相当: TEAM で ADMIN 相当（全レベル満たす）→ 3 レベル全部を返す")
        void ADMIN_全レベル() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(true);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactlyInAnyOrder(
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE,
                    FileVisibilityRole.MEMBERS_AND_ABOVE,
                    FileVisibilityRole.ADMINS_AND_ABOVE);
        }

        @Test
        @DisplayName("AC-1相当: TEAM で MEMBER 相当（ADMIN 未満）→ ADMINS_AND_ABOVE を含まない")
        void MEMBER_ADMINS除外() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactlyInAnyOrder(
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE, FileVisibilityRole.MEMBERS_AND_ABOVE);
            assertThat(result).doesNotContain(FileVisibilityRole.ADMINS_AND_ABOVE);
        }

        @Test
        @DisplayName("AC-3相当: TEAM で SUPPORTER 相当（MEMBER 未満）→ SUPPORTERS_AND_ABOVE のみ")
        void SUPPORTER_MEMBERS除外() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactly(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
        }

        @Test
        @DisplayName("どのレベルも満たさない→空集合（NULL ファイルのみ可視の合図）")
        void 満たさない_空集合() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("AC-5相当: SYSTEM_ADMIN は全許可（null＝フィルタ不要・hasRoleOrAbove を呼ばない）")
        void SYSTEM_ADMIN_null() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).isNull();
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("PERSONAL は全許可（null＝所有者のみ・authorizeView で担保・role 判定しない）")
        void PERSONAL_null() {
            SharedFolderEntity folder = personalFolder(USER_ID);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).isNull();
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("ORGANIZATION は organizationId/\"ORGANIZATION\" で判定する")
        void ORG_スコープ解決() {
            SharedFolderEntity folder = orgFolder();
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactlyInAnyOrder(
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE, FileVisibilityRole.MEMBERS_AND_ABOVE);
            verify(accessControlService).hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "ADMIN");
        }

        @Test
        @DisplayName("TOURNAMENT は主催組織 organizationId/\"ORGANIZATION\" ロールで判定する")
        void TOURNAMENT_主催組織で判定() {
            SharedFolderEntity folder = SharedFolderEntity.builder()
                    .id(FOLDER_ID).scopeType(FileScopeType.TOURNAMENT).organizationId(ORG_ID)
                    .scopeRefId(42L).name("大会フォルダ").createdBy(USER_ID).build();
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactly(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
        }

        @Test
        @DisplayName("folderId 受け口: フォルダを読み込んで同じ結果を返す")
        void folderId受け口() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder(null)));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = service.resolveVisibleFileLevels(FOLDER_ID, USER_ID);

            assertThat(result).containsExactly(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
        }
    }
}
