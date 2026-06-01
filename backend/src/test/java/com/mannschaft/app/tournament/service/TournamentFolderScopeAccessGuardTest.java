package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.tournament.ContactSpaceKind;
import com.mannschaft.app.tournament.ContactSpaceScopeType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TournamentFolderScopeAccessGuard} の単体テスト（F08.7.1 / 04 §3 / §5）。
 *
 * <p>検分指摘の情報漏洩根治の核心テスト。{@code /api/v1/files**} の読み取り／書き込み経路が
 * 大会フォルダに対して連絡スペース認可（{@code checkView}/{@code checkPost}）を必ず通すこと、
 * 大会以外（TEAM/ORG/PERSONAL）スコープでは no-op であること、フォルダ／ファイル不在で
 * 404（IDOR 対策）になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFolderScopeAccessGuard 単体テスト")
class TournamentFolderScopeAccessGuardTest {

    @Mock
    private SharedFolderRepository folderRepository;
    @Mock
    private SharedFileRepository fileRepository;
    @Mock
    private TournamentContactAccessService accessService;

    @InjectMocks
    private TournamentFolderScopeAccessGuard guard;

    private static final Long FOLDER_ID = 1L;
    private static final Long FILE_ID = 2L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long USER_ID = 10L;

    private SharedFolderEntity tournamentFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT)
                .organizationId(5L)
                .scopeRefId(TOURNAMENT_ID)
                .name("大会要項")
                .build();
    }

    private SharedFolderEntity divisionFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TOURNAMENT_DIVISION)
                .organizationId(5L)
                .scopeRefId(DIVISION_ID)
                .name("規約")
                .build();
    }

    private SharedFolderEntity teamFolder() {
        return SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(7L)
                .name("チームフォルダ")
                .build();
    }

    // ========================================================================
    // 大会フォルダ — 閲覧認可委譲
    // ========================================================================

    @Nested
    @DisplayName("大会フォルダの閲覧認可")
    class TournamentFolderView {

        @Test
        @DisplayName("非メンバーが非公開大会フォルダのファイル一覧を叩くと連絡スペース認可で 403")
        void 非メンバー閲覧で403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN))
                    .given(accessService).checkView(
                            eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                            eq(ContactSpaceKind.BULLETIN), eq(USER_ID));

            assertThatThrownBy(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.CONTACT_SPACE_VIEW_FORBIDDEN);
        }

        @Test
        @DisplayName("未ログイン（userId=null）でも閲覧認可へ委譲する（公開判定は連絡スペース側）")
        void 未ログインは連絡スペース認可へ委譲() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            guard.checkFolderViewByFolderId(FOLDER_ID, null);

            verify(accessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), isNull());
        }

        @Test
        @DisplayName("メンバーは閲覧認可を通過する（例外なし）")
        void メンバーは通過() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            // accessService.checkView は何もしない（許可）

            assertThatCode(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
        }

        @Test
        @DisplayName("ファイルID経由でもファイル→フォルダ→大会スコープと辿って閲覧認可へ委譲する")
        void ファイルID経由で閲覧認可委譲() {
            SharedFileEntity file = SharedFileEntity.builder()
                    .folderId(FOLDER_ID).name("a.pdf").fileKey("k").fileSize(1L).contentType("application/pdf").build();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(divisionFolder()));

            guard.checkFolderViewByFileId(FILE_ID, USER_ID);

            verify(accessService).checkView(
                    eq(ContactSpaceScopeType.TOURNAMENT_DIVISION), eq(DIVISION_ID),
                    eq(ContactSpaceKind.BULLETIN), eq(USER_ID));
        }
    }

    // ========================================================================
    // 大会フォルダ — 投稿認可委譲
    // ========================================================================

    @Nested
    @DisplayName("大会フォルダの投稿認可")
    class TournamentFolderPost {

        @Test
        @DisplayName("一般メンバーが大会フォルダにアップロードしようとすると投稿認可で 403")
        void 一般メンバー投稿で403() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));
            willThrow(new BusinessException(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN))
                    .given(accessService).checkPost(
                            eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID), eq(USER_ID));

            assertThatThrownBy(() -> guard.checkFolderPostByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TournamentErrorCode.CONTACT_SPACE_POST_FORBIDDEN);
        }

        @Test
        @DisplayName("ファイルID経由の投稿認可も委譲する")
        void ファイルID経由で投稿認可委譲() {
            SharedFileEntity file = SharedFileEntity.builder()
                    .folderId(FOLDER_ID).name("a.pdf").fileKey("k").fileSize(1L).contentType("application/pdf").build();
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.of(file));
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(tournamentFolder()));

            guard.checkFolderPostByFileId(FILE_ID, USER_ID);

            verify(accessService).checkPost(
                    eq(ContactSpaceScopeType.TOURNAMENT), eq(TOURNAMENT_ID), eq(USER_ID));
        }
    }

    // ========================================================================
    // 大会以外スコープ — no-op
    // ========================================================================

    @Nested
    @DisplayName("大会以外スコープは no-op")
    class NonTournamentScope {

        @Test
        @DisplayName("TEAM フォルダの閲覧では連絡スペース認可を一切呼ばない（既存挙動維持）")
        void TEAM閲覧はnoop() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));

            assertThatCode(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessService, never()).checkView(any(), any(), any(), any());
        }

        @Test
        @DisplayName("TEAM フォルダの投稿では連絡スペース認可を一切呼ばない（既存挙動維持）")
        void TEAM投稿はnoop() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.of(teamFolder()));

            assertThatCode(() -> guard.checkFolderPostByFolderId(FOLDER_ID, USER_ID))
                    .doesNotThrowAnyException();
            verify(accessService, never()).checkPost(any(), any(), any());
        }
    }

    // ========================================================================
    // IDOR / 不在 — 404
    // ========================================================================

    @Nested
    @DisplayName("不在・IDOR は 404")
    class NotFound {

        @Test
        @DisplayName("存在しないフォルダ ID は 404（FOLDER_NOT_FOUND）")
        void フォルダ不在404() {
            given(folderRepository.findById(FOLDER_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkFolderViewByFolderId(FOLDER_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FOLDER_NOT_FOUND);
            verify(accessService, never()).checkView(any(), any(), any(), any());
        }

        @Test
        @DisplayName("存在しないファイル ID は 404（FILE_NOT_FOUND）")
        void ファイル不在404() {
            given(fileRepository.findById(FILE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> guard.checkFolderViewByFileId(FILE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FileSharingErrorCode.FILE_NOT_FOUND);
            verify(accessService, never()).checkView(any(), any(), any(), any());
        }
    }
}
