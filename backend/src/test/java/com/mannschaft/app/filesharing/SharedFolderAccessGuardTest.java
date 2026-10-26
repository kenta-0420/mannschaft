package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFolderAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFolderAccessGuard} の単体テスト。
 *
 * <p>ファイル共有ドメインの認可判定を一元的に検証する。判定材料は取得済みエンティティが保持する
 * スコープのみであり、リクエスト申告値は用いない。検証する層は次の 3 段である。</p>
 * <ol>
 *   <li>基本認可（PERSONAL 所有者 / TEAM・ORGANIZATION メンバーシップ / 大会系はガード委譲）</li>
 *   <li>B: 最低可視ロール（ファイル値優先 → フォルダ継承。SYSTEM_ADMIN は貫通）</li>
 *   <li>C: ダウンロード禁止フラグ（フォルダ OR ファイルの単調な AND 評価）</li>
 * </ol>
 * <p>加えて、削除・公開リンク管理の強い権限（管理者限定）、親フォルダの接ぎ木封鎖
 * （{@code requireParentWithinScope}）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFolderAccessGuard 単体テスト")
class SharedFolderAccessGuardTest {

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;

    @InjectMocks
    private SharedFolderAccessGuard guard;

    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long FOLDER_ID = 100L;
    private static final Long FILE_ID = 300L;
    private static final Long TEAM_ID = 5L;
    private static final Long ORG_ID = 7L;
    private static final Long TOURNAMENT_REF_ID = 42L;

    private SharedFolderEntity personalFolder(Long ownerId) {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.PERSONAL).userId(ownerId)
                .name("個人フォルダ").createdBy(ownerId).build();
    }

    private SharedFolderEntity teamFolder() {
        return teamFolder(null);
    }

    private SharedFolderEntity teamFolder(FileVisibilityRole minRole) {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                .minVisibleRole(minRole)
                .name("チームフォルダ").createdBy(USER_ID).build();
    }

    private SharedFolderEntity orgFolder() {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.ORGANIZATION).organizationId(ORG_ID)
                .name("組織フォルダ").createdBy(USER_ID).build();
    }

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.TOURNAMENT).organizationId(ORG_ID)
                .scopeRefId(TOURNAMENT_REF_ID).name("大会フォルダ").createdBy(USER_ID).build();
    }

    private SharedFolderEntity dlDisabledTeamFolder() {
        return SharedFolderEntity.builder()
                .id(FOLDER_ID).scopeType(FileScopeType.TEAM).teamId(TEAM_ID)
                .downloadDisabled(true).name("f").createdBy(USER_ID).build();
    }

    private SharedFileEntity fileIn(SharedFolderEntity folder, FileVisibilityRole fileMinRole, boolean dlDisabled) {
        return SharedFileEntity.builder()
                .id(FILE_ID).folderId(folder.getId()).name("doc.pdf").fileKey("k").fileSize(10L)
                .contentType("application/pdf").createdBy(USER_ID).currentVersion(1)
                .minVisibleRole(fileMinRole).downloadDisabled(dlDisabled).build();
    }

    /**
     * 基本認可（{@code authorizeView} / {@code authorizeBaseView}）。
     * ファイル単位 DL URL 発行から再利用される入口でもあり、スコープ別ポリシーが等しく適用される。
     */
    @Nested
    @DisplayName("authorizeView — スコープ別の基本認可（DL 認可の再利用入口）")
    class AuthorizeFolderViewById {

        @Test
        @DisplayName("PERSONAL 本人は通過する（例外なし）")
        void PERSONAL本人_通過() {
            guard.authorizeView(personalFolder(USER_ID), USER_ID);
            // 例外が出なければ OK（個人スコープでは外部サービス呼び出しなし）
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("PERSONAL 他人は 404（FOLDER_NOT_FOUND・存在隠蔽）")
        void PERSONAL他人_404() {
            SharedFolderEntity folder = personalFolder(OTHER_USER_ID);

            assertThatThrownBy(() -> guard.authorizeView(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("TEAM メンバーは checkMembership を通して通過する")
        void TEAMメンバー_通過() {
            guard.authorizeView(teamFolder(), USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("TEAM 非メンバーは 403（COMMON_002）")
        void TEAM非メンバー_403() {
            SharedFolderEntity folder = teamFolder();
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> guard.authorizeView(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("ORGANIZATION は organizationId/\"ORGANIZATION\" でメンバーシップを検証する")
        void ORGANIZATION_メンバーシップ検証() {
            guard.authorizeView(orgFolder(), USER_ID);

            verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("TOURNAMENT は FolderScopeAccessGuard へ委譲する")
        void TOURNAMENT_guard委譲() {
            guard.authorizeView(tournamentFolder(), USER_ID);

            verify(folderScopeAccessGuard).checkFolderViewByFolderId(FOLDER_ID, USER_ID);
        }

        @Test
        @DisplayName("authorizeBaseView は最低可視ロールを評価しない（基本認可のみ）")
        void authorizeBaseView_minRole評価なし() {
            guard.authorizeBaseView(teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE), USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }
    }

    /**
     * 削除・公開リンク管理（{@code authorizeDelete}）の認可。
     *
     * <p>閲覧より強い権限を要求する。PERSONAL は本人のみ（他人は 404・存在秘匿）、
     * TEAM / ORGANIZATION は管理者（ADMIN / DEPUTY_ADMIN）限定で一般 MEMBER は 403、
     * 大会系は編集認可へ委譲する。</p>
     */
    @Nested
    @DisplayName("authorizeDelete — 削除・公開リンク管理の強い認可")
    class DeleteFolder {

        @Test
        @DisplayName("AC-FD-3: 他人の個人フォルダは 404")
        void ACFD3_他人個人_404() {
            SharedFolderEntity folder = personalFolder(OTHER_USER_ID);

            assertThatThrownBy(() -> guard.authorizeDelete(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-FD-4: 非所属チームフォルダは 403（checkAdminOrAbove が COMMON_002）")
        void ACFD4_非所属チーム_403() {
            SharedFolderEntity folder = teamFolder();
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> guard.authorizeDelete(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-FD-4b: 所属していても一般 MEMBER は TEAM フォルダを削除できず 403（管理者限定）")
        void ACFD4b_一般MEMBER_403() {
            // 一般メンバーは isMember=true でも isAdminOrAbove=false → checkAdminOrAbove が COMMON_002。
            SharedFolderEntity folder = teamFolder();
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> guard.authorizeDelete(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-FD-8: TEAM の ADMIN は checkAdminOrAbove を通過して削除可（checkMembership は使わない）")
        void ACFD8_ADMIN_削除可() {
            // ADMIN は checkAdminOrAbove が素通り（void・doNothing）→ 認可が通る。
            assertThatCode(() -> guard.authorizeDelete(teamFolder(), USER_ID)).doesNotThrowAnyException();

            // 削除権限の関門は checkAdminOrAbove（メンバーシップ判定では弱すぎる）。
            verify(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("AC-FD-8: ORGANIZATION の DEPUTY_ADMIN(副長) も checkAdminOrAbove を通過して削除可")
        void ACFD8_DEPUTY_ADMIN_削除可() {
            // checkAdminOrAbove は内部 ADMIN_ROLES={ADMIN,DEPUTY_ADMIN} で副長も許可する。
            // 副長許可は「checkAdminOrAbove が例外を投げない」ことで表現される（void・doNothing）。
            assertThatCode(() -> guard.authorizeDelete(orgFolder(), USER_ID)).doesNotThrowAnyException();

            verify(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("大会スコープは FolderScopeAccessGuard の編集認可へ委譲する")
        void 大会スコープ_編集認可へ委譲() {
            guard.authorizeDelete(tournamentFolder(), USER_ID);

            verify(folderScopeAccessGuard).checkFolderPostByFolderId(FOLDER_ID, USER_ID);
        }
    }

    // ============================================================
    // B: 最低可視ロール（表示制御）
    // ============================================================

    @Nested
    @DisplayName("B: 最低可視ロール — フォルダ経路（authorizeView）")
    class MinVisibleRoleFolder {

        @Test
        @DisplayName("AC-B1: min=ADMINS_AND_ABOVE のチームフォルダを非管理者 MEMBER が閲覧→403(COMMON_002)")
        void ACB1_ADMINS_MEMBER_403() {
            // メンバーシップは通過（checkMembership は void・doNothing）だが ADMIN 未満で min role が弾く。
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> guard.authorizeView(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B2: 同フォルダを ADMIN/DEPUTY_ADMIN(hasRoleOrAbove ADMIN=true) が閲覧→通過")
        void ACB2_ADMINS_ADMIN_200() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(true);

            assertThatCode(() -> guard.authorizeView(folder, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B3: MEMBERS_AND_ABOVE を SUPPORTER(hasRoleOrAbove MEMBER=false) が閲覧→403")
        void ACB3_MEMBERS_SUPPORTER_403() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> guard.authorizeView(folder, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B3: MEMBERS_AND_ABOVE を MEMBER(hasRoleOrAbove MEMBER=true) が閲覧→通過")
        void ACB3_MEMBERS_MEMBER_200() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            assertThatCode(() -> guard.authorizeView(folder, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B6: min=NULL（既存データ）は所属者全員可視（hasRoleOrAbove を呼ばず非回帰）")
        void ACB6_NULL_非回帰() {
            guard.authorizeView(teamFolder(null), USER_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
            verify(accessControlService, never()).isSystemAdmin(anyLong());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は min role を貫通して閲覧できる（B 貫通）")
        void SYSTEM_ADMIN_貫通() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> guard.authorizeView(folder, USER_ID)).doesNotThrowAnyException();
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("B: 最低可視ロール — ファイル経路（authorizeFileView・ファイル値優先→フォルダ継承）")
    class MinVisibleRoleFile {

        @Test
        @DisplayName("AC-B4: フォルダ=MEMBERS_AND_ABOVE・ファイル=NULL→フォルダ継承で MEMBER 可視")
        void ACB4_ファイルNULL_フォルダ継承() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, null, false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(true);

            assertThatCode(() -> guard.authorizeFileView(folder, file, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B5: ファイル=ADMINS_AND_ABOVE・フォルダ=NULL→ファイル優先で ADMIN のみ（非管理者は403）")
        void ACB5_ファイル優先_非管理者403() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, FileVisibilityRole.ADMINS_AND_ABOVE, false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> guard.authorizeFileView(folder, file, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("AC-B5: ファイル=ADMINS_AND_ABOVE・フォルダ=NULL→ADMIN は可視")
        void ACB5_ファイル優先_ADMIN可視() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, FileVisibilityRole.ADMINS_AND_ABOVE, false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(true);

            assertThatCode(() -> guard.authorizeFileView(folder, file, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-B8: min role は authorizeDownload(DL URL 発行)にも効く（フォルダ ADMINS を非管理者→403）")
        void ACB8_DL認可にも効く() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.ADMINS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, null, false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            assertThatThrownBy(() -> guard.authorizeDownload(folder, file, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    /**
     * 実効最低可視ロールの解決規約（ファイル値優先 → フォルダ継承 → 両方 NULL は判定スキップ）。
     */
    @Nested
    @DisplayName("effectiveMinRole — ファイル値優先・フォルダ継承")
    class EffectiveMinRole {

        @Test
        @DisplayName("ファイル値があればファイル値を採る（フォルダ値より優先）")
        void ファイル値優先() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, FileVisibilityRole.ADMINS_AND_ABOVE, false);

            assertThat(guard.effectiveMinRole(file, folder)).isEqualTo(FileVisibilityRole.ADMINS_AND_ABOVE);
        }

        @Test
        @DisplayName("ファイル値が NULL ならフォルダ値を継承する")
        void フォルダ継承() {
            SharedFolderEntity folder = teamFolder(FileVisibilityRole.MEMBERS_AND_ABOVE);
            SharedFileEntity file = fileIn(folder, null, false);

            assertThat(guard.effectiveMinRole(file, folder)).isEqualTo(FileVisibilityRole.MEMBERS_AND_ABOVE);
        }

        @Test
        @DisplayName("両方 NULL なら NULL（所属者全員可視＝判定スキップ）")
        void 両方NULL() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, false);

            assertThat(guard.effectiveMinRole(file, folder)).isNull();
        }
    }

    // ============================================================
    // C: ダウンロード禁止フラグ（authorizeDownload / requireDownloadEnabled）
    // ============================================================

    @Nested
    @DisplayName("C: ダウンロード禁止フラグ — authorizeDownload / 閲覧は通す")
    class DownloadDisabled {

        @Test
        @DisplayName("AC-C1: file.downloadDisabled=true→authorizeDownload が DOWNLOAD_DISABLED(403)")
        void ACC1_ファイル禁止_403() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, true);

            assertThatThrownBy(() -> guard.authorizeDownload(folder, file, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C2: DL 禁止でも閲覧（authorizeFileView）は通る")
        void ACC2_禁止でも閲覧可() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, true);

            assertThatCode(() -> guard.authorizeFileView(folder, file, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-C3: フォルダ=true・ファイル未設定→配下 DL は 403（継承）")
        void ACC3_フォルダ禁止_継承403() {
            SharedFolderEntity folder = dlDisabledTeamFolder();
            SharedFileEntity file = fileIn(folder, null, false);

            assertThatThrownBy(() -> guard.authorizeDownload(folder, file, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C4: フォルダ=true・ファイル=false→それでも403（禁止は単調・ファイルで解除不可）")
        void ACC4_単調_ファイルfalseでも403() {
            SharedFolderEntity folder = dlDisabledTeamFolder();
            SharedFileEntity file = fileIn(folder, null, false); // ファイル側 false

            assertThatThrownBy(() -> guard.authorizeDownload(folder, file, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("AC-C5: 既定 false→従来どおり DL 可（非回帰）")
        void ACC5_既定false_DL可() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, false);

            assertThatCode(() -> guard.authorizeDownload(folder, file, USER_ID)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-C6: SYSTEM_ADMIN は DL 禁止を貫通して DL 可（B/C 貫通）")
        void ACC6_SYSTEM_ADMIN_貫通() {
            SharedFolderEntity folder = dlDisabledTeamFolder();
            SharedFileEntity file = fileIn(folder, null, true);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            assertThatCode(() -> guard.authorizeDownload(folder, file, USER_ID)).doesNotThrowAnyException();
        }
    }

    /**
     * 公開リンク経路の貫通防御。トークンは capability でありスコープ認可は通さないが、
     * ダウンロード禁止フラグは公開リンクでも必ず評価され、AND 評価が保たれる。
     */
    @Nested
    @DisplayName("C: requireDownloadEnabled — 公開リンク経路でも禁止が貫通する")
    class RequireDownloadEnabled {

        @Test
        @DisplayName("フォルダ側 true → DOWNLOAD_DISABLED（公開リンクでも DL 不可）")
        void フォルダ側true_403() {
            SharedFolderEntity folder = dlDisabledTeamFolder();
            SharedFileEntity file = fileIn(folder, null, false);

            assertThatThrownBy(() -> guard.requireDownloadEnabled(folder, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("ファイル側 true → DOWNLOAD_DISABLED（公開リンクでも DL 不可）")
        void ファイル側true_403() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, true);

            assertThatThrownBy(() -> guard.requireDownloadEnabled(folder, file))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("両方 false → 通過（スコープ認可は評価しない）")
        void 両方false_通過() {
            SharedFolderEntity folder = teamFolder(null);
            SharedFileEntity file = fileIn(folder, null, false);

            assertThatCode(() -> guard.requireDownloadEnabled(folder, file)).doesNotThrowAnyException();
            verify(accessControlService, never()).checkMembership(anyLong(), anyLong(), any());
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

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

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

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

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

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactly(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
        }

        @Test
        @DisplayName("どのレベルも満たさない→空集合（NULL ファイルのみ可視の合図）")
        void 満たさない_空集合() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("AC-5相当: SYSTEM_ADMIN は全許可（null＝フィルタ不要・hasRoleOrAbove を呼ばない）")
        void SYSTEM_ADMIN_null() {
            SharedFolderEntity folder = teamFolder(null);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).isNull();
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("PERSONAL は全許可（null＝所有者のみ・authorizeView で担保・role 判定しない）")
        void PERSONAL_null() {
            SharedFolderEntity folder = personalFolder(USER_ID);

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

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

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactlyInAnyOrder(
                    FileVisibilityRole.SUPPORTERS_AND_ABOVE, FileVisibilityRole.MEMBERS_AND_ABOVE);
            verify(accessControlService).hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "ADMIN");
        }

        @Test
        @DisplayName("TOURNAMENT は主催組織 organizationId/\"ORGANIZATION\" ロールで判定する")
        void TOURNAMENT_主催組織で判定() {
            SharedFolderEntity folder = tournamentFolder();
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "ADMIN")).willReturn(false);

            Set<FileVisibilityRole> result = guard.resolveVisibleFileLevels(folder, USER_ID);

            assertThat(result).containsExactly(FileVisibilityRole.SUPPORTERS_AND_ABOVE);
        }
    }

    /**
     * 親フォルダの接ぎ木封鎖。他スコープの folderId を親に指定する経路を 404 で塞ぎ、
     * 他スコープにおける folderId の存在有無も漏らさない。
     */
    @Nested
    @DisplayName("requireParentWithinScope — 親フォルダの接ぎ木封鎖")
    class RequireParentWithinScope {

        @Test
        @DisplayName("スコープ種別が違う親は 404（FOLDER_NOT_FOUND）")
        void スコープ種別違い_404() {
            SharedFolderEntity parent = orgFolder();

            assertThatThrownBy(() -> guard.requireParentWithinScope(parent, FileScopeType.TEAM, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("同種でもスコープ ID が違う親は 404（FOLDER_NOT_FOUND）")
        void スコープID違い_404() {
            SharedFolderEntity parent = teamFolder();

            assertThatThrownBy(() -> guard.requireParentWithinScope(parent, FileScopeType.TEAM, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("スコープ種別・スコープ ID が一致する親は通過する")
        void 一致_通過() {
            SharedFolderEntity parent = teamFolder();

            assertThatCode(() -> guard.requireParentWithinScope(parent, FileScopeType.TEAM, TEAM_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ORGANIZATION は organizationId で一致判定する")
        void ORGANIZATION_organizationIdで一致() {
            SharedFolderEntity parent = orgFolder();

            assertThatCode(() -> guard.requireParentWithinScope(parent, FileScopeType.ORGANIZATION, ORG_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PERSONAL は他人所有の親を 404 で弾く（expectedScopeId=操作者 userId）")
        void PERSONAL_他人所有の親_404() {
            SharedFolderEntity parent = personalFolder(OTHER_USER_ID);

            assertThatThrownBy(() -> guard.requireParentWithinScope(parent, FileScopeType.PERSONAL, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND));
        }

        @Test
        @DisplayName("PERSONAL は本人所有の親なら通過する")
        void PERSONAL_本人所有の親_通過() {
            SharedFolderEntity parent = personalFolder(USER_ID);

            assertThatCode(() -> guard.requireParentWithinScope(parent, FileScopeType.PERSONAL, USER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("大会系は scopeRefId で一致判定する")
        void 大会系_scopeRefIdで一致() {
            SharedFolderEntity parent = tournamentFolder();

            assertThatCode(() ->
                    guard.requireParentWithinScope(parent, FileScopeType.TOURNAMENT, TOURNAMENT_REF_ID))
                    .doesNotThrowAnyException();
        }
    }
}
