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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
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
                    new CreateFolderRequest("新規", null, null, "TEAM", String.valueOf(TEAM_ID));
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
                    new CreateFolderRequest("新規", null, null, "TEAM", String.valueOf(TEAM_ID));
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
}
