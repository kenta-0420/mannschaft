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
import com.mannschaft.app.filesharing.service.SharedFolderAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFolderQueryService} の単体テスト。
 *
 * <p>本サービスは folderId / fileId から<b>実体を解決し、その実体を
 * {@link SharedFolderAccessGuard} へ渡して認可を当てる</b>。本テストが固定するのは次の 3 点である。</p>
 * <ol>
 *   <li><b>委譲の正しさ</b> — ガードへ渡るフォルダ／ファイルが、リクエストのパラメータではなく
 *       <b>リポジトリから引いた実体</b>であること（実体由来スコープでの認可を保証する）。</li>
 *   <li><b>拒否時の無副作用</b> — ガードが拒否したとき、保存・容量戻しなどの副作用が一切起きないこと。</li>
 *   <li><b>サービス固有の組み立て</b> — パンくず・件数・再帰カスケード削除・容量戻し、および
 *       一覧と同一の最低可視ロール絞り込みの適用。</li>
 * </ol>
 *
 * <p>スコープ別の可否・最低可視ロールの境界・ダウンロード禁止フラグといった
 * <b>認可の判断そのもの</b>は {@code SharedFolderAccessGuardTest} が検証する。</p>
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

    /**
     * 認可判定の一元化先。本テストでは判定の中身ではなく
     * <b>どの実体・どの操作者でガードを呼んでいるか</b>（委譲の正しさ）を検証する。
     * 判定そのものの検証は {@code SharedFolderAccessGuardTest} が担う。
     */
    @Mock
    private SharedFolderAccessGuard folderAccessGuard;

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
    @DisplayName("getFolderDetail — ガードへの委譲と組み立て")
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
            // PERSONAL は所有者本人に束縛されるため、ガードは全許可（null）を返す契約である。
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(null);
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of(file));
            given(fileRepository.countByFolderId(101L)).willReturn(2L);
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(Map.of(USER_ID, "ユーザー10"));

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.id()).isEqualTo(FOLDER_ID);
            assertThat(result.subfolders()).hasSize(1);
            assertThat(result.files()).hasSize(1);
            // 認可はリクエストのパラメータではなく、リポジトリから引いたフォルダ実体で当てる。
            verify(folderAccessGuard).authorizeView(folder, USER_ID);
        }

        @Test
        @DisplayName("スコープの異なる各種フォルダでも、認可には常にリポジトリ由来の実体が渡る（BOLA 対策）")
        void 実体由来スコープでガードを呼ぶ() {
            SharedFolderEntity team = teamFolder();
            Set<FileVisibilityRole> levels = Set.of(FileVisibilityRole.SUPPORTERS_AND_ABOVE,
                    FileVisibilityRole.MEMBERS_AND_ABOVE);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(team));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            // TEAM スコープでは、ガードは操作者が満たすレベル集合を返す（一覧と同一の絞り込みに使う）。
            given(folderAccessGuard.resolveVisibleFileLevels(team, USER_ID)).willReturn(levels);
            given(fileRepository.findVisibleByFolderIdAndLevels(FOLDER_ID, levels)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());

            service.getFolderDetail(FOLDER_ID, USER_ID);

            ArgumentCaptor<SharedFolderEntity> captor = ArgumentCaptor.forClass(SharedFolderEntity.class);
            verify(folderAccessGuard).authorizeView(captor.capture(), eq(USER_ID));
            assertThat(captor.getValue()).isSameAs(team);
            assertThat(captor.getValue().getTeamId()).isEqualTo(TEAM_ID);
        }

        @Test
        @DisplayName("ガードが拒否したら詳細を組み立てず例外をそのまま伝える（エラーコードを潰さない）")
        void ガード拒否_伝播() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderAccessGuard).authorizeView(any(SharedFolderEntity.class), anyLong());

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));

            verify(folderRepository, never()).findByParentIdOrderByNameAsc(anyLong());
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong());
        }

        @Test
        @DisplayName("ガードが 403（COMMON_002）で拒否したときも同じくそのまま伝える")
        void ガード拒否_403伝播() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderAccessGuard).authorizeView(any(SharedFolderEntity.class), anyLong());

            assertThatThrownBy(() -> service.getFolderDetail(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
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
            // PERSONAL は所有者本人に束縛されるため、ガードは全許可（null）を返す契約である。
            given(folderAccessGuard.resolveVisibleFileLevels(c, USER_ID)).willReturn(null);
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
            // PERSONAL は所有者本人に束縛されるため、ガードは全許可（null）を返す契約である。
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(null);
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
            // PERSONAL は所有者本人に束縛されるため、ガードは全許可（null）を返す契約である。
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(null);
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

    /**
     * 詳細のファイル一覧は、一覧 API（{@code listFiles}）と<b>同一の最低可視ロール絞り込み</b>を
     * クエリ段階で適用する。両経路の可視範囲を一致させ、より厳しい最低可視ロールを持つファイルの
     * メタ情報が下位ロールの応答に載らないようにするための分岐を固定する。
     */
    @Nested
    @DisplayName("getFolderDetail — 最低可視ロールによるファイル絞り込み（一覧と同一）")
    class GetFolderDetailVisibleFiles {

        private void stubCommon(SharedFolderEntity folder) {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
        }

        @Test
        @DisplayName("全許可（null）→ 絞り込み無しのクエリを使う")
        void 全許可_絞り込み無し() {
            SharedFolderEntity folder = teamFolder();
            stubCommon(folder);
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(null);
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());

            service.getFolderDetail(FOLDER_ID, USER_ID);

            verify(fileRepository).findByFolderIdOrderByNameAsc(FOLDER_ID);
            verify(fileRepository, never()).findVisibleByFolderIdAndLevels(anyLong(), any());
            verify(fileRepository, never()).findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(anyLong());
        }

        @Test
        @DisplayName("どのレベルも満たさない（空集合）→ 最低可視ロール未設定のファイルのみを返す")
        void 空集合_NULLのみ() {
            SharedFolderEntity folder = teamFolder();
            stubCommon(folder);
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID))
                    .willReturn(Set.of());
            given(fileRepository.findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(FOLDER_ID))
                    .willReturn(List.of());

            service.getFolderDetail(FOLDER_ID, USER_ID);

            verify(fileRepository).findByFolderIdAndMinVisibleRoleIsNullOrderByNameAsc(FOLDER_ID);
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong());
        }

        @Test
        @DisplayName("満たすレベルがある→ そのレベル集合でクエリ段階から絞り込む（上位ロール専用ファイルは応答に載らない）")
        void 非空集合_レベルで絞る() {
            SharedFolderEntity folder = teamFolder();
            Set<FileVisibilityRole> levels = Set.of(FileVisibilityRole.SUPPORTERS_AND_ABOVE,
                    FileVisibilityRole.MEMBERS_AND_ABOVE);
            SharedFileEntity visible = SharedFileEntity.builder()
                    .id(201L).folderId(FOLDER_ID).name("member.pdf").fileKey("k1").fileSize(1L)
                    .contentType("application/pdf").createdBy(USER_ID).currentVersion(1)
                    .minVisibleRole(FileVisibilityRole.MEMBERS_AND_ABOVE).build();
            stubCommon(folder);
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(levels);
            given(fileRepository.findVisibleByFolderIdAndLevels(FOLDER_ID, levels))
                    .willReturn(List.of(visible));

            FolderDetailResponse result = service.getFolderDetail(FOLDER_ID, USER_ID);

            assertThat(result.files()).extracting(FolderDetailResponse.FileSummary::fileName)
                    .containsExactly("member.pdf");
            assertThat(result.fileCount()).isEqualTo(1);
            verify(fileRepository).findVisibleByFolderIdAndLevels(FOLDER_ID, levels);
            verify(fileRepository, never()).findByFolderIdOrderByNameAsc(anyLong());
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
     * ダウンロード URL 発行などの外部入口から再利用される認可エントリ。
     * fileId / folderId から<b>実体を解決してガードへ渡している</b>ことを固定する。
     */
    @Nested
    @DisplayName("認可エントリ — 実体を解決してガードへ委譲する")
    class AuthorizeFolderViewById {

        @Test
        @DisplayName("authorizeFolderViewById: folderId から引いたフォルダ実体でガードを呼ぶ")
        void folderId_実体で委譲() {
            SharedFolderEntity folder = teamFolder();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            service.authorizeFolderViewById(FOLDER_ID, USER_ID);

            verify(folderAccessGuard).authorizeView(folder, USER_ID);
        }

        @Test
        @DisplayName("authorizeFolderViewById: ガードの拒否をそのまま伝える")
        void folderId_拒否伝播() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderAccessGuard).authorizeView(any(SharedFolderEntity.class), anyLong());

            assertThatThrownBy(() -> service.authorizeFolderViewById(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("存在しないフォルダは 404（ガードを呼ばない）")
        void フォルダ不存在_404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorizeFolderViewById(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));

            verify(folderAccessGuard, never()).authorizeView(any(), anyLong());
        }

        @Test
        @DisplayName("authorizeFileViewById: fileId → file → folder を解決し、両実体でガードを呼ぶ")
        void fileId_実体で委譲() {
            SharedFolderEntity folder = teamFolder();
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            service.authorizeFileViewById(FILE_ID, USER_ID);

            verify(folderAccessGuard).authorizeFileView(folder, file, USER_ID);
        }

        @Test
        @DisplayName("authorizeFileViewById: 存在しないファイルは FILE_NOT_FOUND（ガードを呼ばない）")
        void ファイル不存在_404() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.authorizeFileViewById(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND));

            verify(folderAccessGuard, never()).authorizeFileView(any(), any(), anyLong());
        }

        @Test
        @DisplayName("authorizeDownload: ファイルとフォルダの実体でダウンロード認可を呼ぶ")
        void download_実体で委譲() {
            SharedFolderEntity folder = teamFolder();
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            service.authorizeDownload(FILE_ID, USER_ID);

            verify(folderAccessGuard).authorizeDownload(folder, file, USER_ID);
        }

        @Test
        @DisplayName("authorizeDownload: ガードの DOWNLOAD_DISABLED をそのまま伝える")
        void download_禁止伝播() {
            SharedFolderEntity folder = teamFolder();
            SharedFileEntity file = fileIn(folder, null, true);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));
            willThrow(new BusinessException(FileSharingErrorCode.DOWNLOAD_DISABLED))
                    .given(folderAccessGuard).authorizeDownload(any(), any(), anyLong());

            assertThatThrownBy(() -> service.authorizeDownload(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("authorizeLinkManageByFileId: 公開リンク管理は削除と同じ強い権限をフォルダ実体で要求する")
        void linkManage_実体で委譲() {
            SharedFolderEntity folder = teamFolder();
            SharedFileEntity file = fileIn(folder, null, false);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            service.authorizeLinkManageByFileId(FILE_ID, USER_ID);

            verify(folderAccessGuard).authorizeDelete(folder, USER_ID);
        }

        @Test
        @DisplayName("checkDownloadDisabledForSharedLink: 公開リンク経路でも禁止フラグ評価を通す")
        void sharedLink_禁止フラグ評価() {
            SharedFolderEntity folder = teamFolder();
            SharedFileEntity file = fileIn(folder, null, true);
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            service.checkDownloadDisabledForSharedLink(FILE_ID);

            verify(folderAccessGuard).requireDownloadEnabled(folder, file);
        }

        @Test
        @DisplayName("resolveVisibleFileLevels: フォルダ実体・folderId の双方でガードへ委譲する")
        void 可視レベル_委譲() {
            SharedFolderEntity folder = teamFolder();
            Set<FileVisibilityRole> levels = Set.of(FileVisibilityRole.MEMBERS_AND_ABOVE);
            given(folderAccessGuard.resolveVisibleFileLevels(folder, USER_ID)).willReturn(levels);
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(folder));

            assertThat(service.resolveVisibleFileLevels(folder, USER_ID)).isEqualTo(levels);
            assertThat(service.resolveVisibleFileLevels(FOLDER_ID, USER_ID)).isEqualTo(levels);
        }
    }

    /**
     * フォルダ削除（{@code DELETE /api/v1/files/folders/{id}}）。
     *
     * <p>削除認可はフォルダ実体を {@link SharedFolderAccessGuard#authorizeDelete} へ渡して当てる。
     * 本ブロックでは委譲の正しさ・拒否時の無副作用に加え、部分木の再帰カスケード soft-delete と
     * ファイルごとの容量戻しを検証する。</p>
     */
    @Nested
    @DisplayName("deleteFolder — 認可委譲・カスケード・容量戻し")
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
        @DisplayName("AC-FD-2: 削除認可はフォルダ実体で当てる（閲覧より強い authorizeDelete を呼ぶ）")
        void ACFD2_削除認可_実体で委譲() {
            SharedFolderEntity root = teamFolder();
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(root));
            given(folderRepository.findByParentIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());
            given(fileRepository.findByFolderIdOrderByNameAsc(FOLDER_ID)).willReturn(List.of());

            service.deleteFolder(FOLDER_ID, USER_ID);

            verify(folderAccessGuard).authorizeDelete(root, USER_ID);
            // 削除は閲覧より強い権限を要求する。閲覧認可で代用しない。
            verify(folderAccessGuard, never()).authorizeView(any(), anyLong());
        }

        @Test
        @DisplayName("AC-FD-3: ガードが 404 で拒否→ softDelete も recordFileDeletion も呼ばない")
        void ACFD3_拒否404_無副作用() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(personalFolder(OTHER_USER_ID)));
            willThrow(new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND))
                    .given(folderAccessGuard).authorizeDelete(any(SharedFolderEntity.class), anyLong());

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
        @DisplayName("AC-FD-4: ガードが 403（COMMON_002）で拒否→ 何も削除しない")
        void ACFD4_拒否403_無副作用() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderAccessGuard).authorizeDelete(any(SharedFolderEntity.class), anyLong());

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
    }
}
