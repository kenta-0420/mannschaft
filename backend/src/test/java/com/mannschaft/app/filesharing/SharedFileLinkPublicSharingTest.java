package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.filesharing.dto.AccessLinkRequest;
import com.mannschaft.app.filesharing.dto.CreateLinkRequest;
import com.mannschaft.app.filesharing.dto.FileResponse;
import com.mannschaft.app.filesharing.dto.LinkResponse;
import com.mannschaft.app.filesharing.dto.SharedFileDownloadUrlResponse;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.service.FolderScopeAccessGuard;
import com.mannschaft.app.filesharing.service.SharedFileLinkService;
import com.mannschaft.app.filesharing.service.SharedFileService;
import com.mannschaft.app.filesharing.service.SharedFolderQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F05.5 PR-D 公開リンク共有のドメイン単体テスト（{@link SharedFileLinkService}）。
 *
 * <p>受け入れ条件 AC-D1〜D11 のサービス層を検証する:</p>
 * <ul>
 *   <li>公開アクセス検証順（token 404 → is_active 410 → expired 410 → password 403）と access_count 加算（D3/D4/D5/D6/D10）</li>
 *   <li>DL: download_allowed（false→403 LINK_DOWNLOAD_NOT_ALLOWED）＋ C: download_disabled 貫通（C 優先 AND・D7/D8）</li>
 *   <li>発行認可（ADMIN/DEPUTY 限定・一般 MEMBER は 403・D9）＋ 有効期限バリデーション（必須・最大30日・D11）</li>
 * </ul>
 *
 * <p>未認証クライアントで実際に叩く契約テストは {@code PublicFileLinkContractIT}（Testcontainers 起動時に実行）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileLinkService PR-D 公開リンク共有 単体テスト")
class SharedFileLinkPublicSharingTest {

    @Mock
    private SharedFileLinkRepository linkRepository;
    @Mock
    private SharedFileService fileService;
    @Mock
    private FileSharingMapper fileSharingMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private FolderScopeAccessGuard folderScopeAccessGuard;
    @Mock
    private SharedFolderQueryService folderQueryService;

    @InjectMocks
    private SharedFileLinkService service;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;

    private static SharedFileLinkEntity linkBuilder() {
        return SharedFileLinkEntity.builder()
                .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().plusDays(1))
                .accessCount(0).createdBy(USER_ID).build();
    }

    private static FileResponse fileResponse() {
        return new FileResponse(FILE_ID, 1L, "test.pdf", "key", 1024L, "application/pdf",
                null, USER_ID, 1, null, null, null, null);
    }

    // ========================================================================
    // accessLinkPublic（メタ）
    // ========================================================================

    @Nested
    @DisplayName("accessLinkPublic（未認証メタ取得）")
    class AccessLinkPublic {

        @Test
        @DisplayName("AC-D1/D2: 有効リンクで 200 相当（メタ返却）＋ フォルダ認可を通さない")
        void 有効リンク_メタ返却() {
            SharedFileLinkEntity link = linkBuilder();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));
            given(fileService.getFileForSharedLink(FILE_ID)).willReturn(fileResponse());

            FileResponse result = service.accessLinkPublic("tok", null);

            assertThat(result.getName()).isEqualTo("test.pdf");
        }

        @Test
        @DisplayName("AC-D10: アクセスで access_count がインクリメントされ保存される")
        void アクセスでカウント増加() {
            SharedFileLinkEntity link = linkBuilder();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));
            given(fileService.getFileForSharedLink(FILE_ID)).willReturn(fileResponse());

            service.accessLinkPublic("tok", null);

            assertThat(link.getAccessCount()).isEqualTo(1);
            verify(linkRepository).save(link);
        }

        @Test
        @DisplayName("AC-D5: 存在しないトークンは LINK_NOT_FOUND（→404・存在秘匿）")
        void トークン不在_404() {
            given(linkRepository.findByToken("nope")).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.accessLinkPublic("nope", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-D4: is_active=false は LINK_INACTIVE（→410）")
        void 手動失効_410() {
            SharedFileLinkEntity link = linkBuilder();
            link.deactivate();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));

            assertThatThrownBy(() -> service.accessLinkPublic("tok", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_INACTIVE));
        }

        @Test
        @DisplayName("AC-D3: 期限切れは LINK_EXPIRED（→410）")
        void 期限切れ_410() {
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().minusDays(1))
                    .accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));

            assertThatThrownBy(() -> service.accessLinkPublic("tok", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_EXPIRED));
        }

        @Test
        @DisplayName("AC-D6: パスワード誤りは LINK_PASSWORD_INVALID（→403）、正しければ 200 相当")
        void パスワード分岐() {
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().plusDays(1))
                    .passwordHash("hash").accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));
            given(passwordEncoder.matches("wrong", "hash")).willReturn(false);

            assertThatThrownBy(() -> service.accessLinkPublic("tok", new AccessLinkRequest("wrong")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_PASSWORD_INVALID));

            given(passwordEncoder.matches("right", "hash")).willReturn(true);
            given(fileService.getFileForSharedLink(FILE_ID)).willReturn(fileResponse());
            FileResponse ok = service.accessLinkPublic("tok", new AccessLinkRequest("right"));
            assertThat(ok.getName()).isEqualTo("test.pdf");
        }

        @Test
        @DisplayName("検証順: 失効は期限・パスワードより先に評価される（inactive 優先）")
        void 検証順_失効優先() {
            // 失効 かつ 期限切れ かつ パスワード付き → 最初に評価される LINK_INACTIVE を返す。
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().minusDays(1))
                    .passwordHash("hash").accessCount(0).createdBy(USER_ID).build();
            link.deactivate();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));

            assertThatThrownBy(() -> service.accessLinkPublic("tok", new AccessLinkRequest("x")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_INACTIVE));
        }
    }

    // ========================================================================
    // presignDownloadForLink（DL URL）
    // ========================================================================

    @Nested
    @DisplayName("presignDownloadForLink（未認証DL URL）")
    class PresignDownloadForLink {

        @Test
        @DisplayName("AC-D7: download_allowed=false は LINK_DOWNLOAD_NOT_ALLOWED（→403）・R2 を呼ばない")
        void DL未許可_403() {
            SharedFileLinkEntity link = linkBuilder(); // downloadAllowed 既定 false
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));

            assertThatThrownBy(() -> service.presignDownloadForLink("tok", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_DOWNLOAD_NOT_ALLOWED));
            verify(fileService, never()).presignDownloadForSharedLink(any());
        }

        @Test
        @DisplayName("AC-D8: download_allowed=true でも C:download_disabled=true なら DOWNLOAD_DISABLED（→403・C 優先）")
        void DL許可でもC禁止優先_403() {
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().plusDays(1))
                    .downloadAllowed(true).accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));
            willThrow(new BusinessException(FileSharingErrorCode.DOWNLOAD_DISABLED))
                    .given(fileService).presignDownloadForSharedLink(FILE_ID);

            assertThatThrownBy(() -> service.presignDownloadForLink("tok", null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.DOWNLOAD_DISABLED));
        }

        @Test
        @DisplayName("正常系: download_allowed=true かつ C 未禁止なら DL URL を発行・access_count 加算")
        void DL許可_URL発行() {
            SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("tok").expiresAt(LocalDateTime.now().plusDays(1))
                    .downloadAllowed(true).accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.findByToken("tok")).willReturn(Optional.of(link));
            given(fileService.presignDownloadForSharedLink(FILE_ID))
                    .willReturn(new SharedFileDownloadUrlResponse("https://r2/x", 900L));

            SharedFileDownloadUrlResponse result = service.presignDownloadForLink("tok", null);

            assertThat(result.downloadUrl()).isEqualTo("https://r2/x");
            assertThat(link.getAccessCount()).isEqualTo(1);
        }
    }

    // ========================================================================
    // createLink（発行認可・期限バリデーション）
    // ========================================================================

    @Nested
    @DisplayName("createLink（発行認可・期限バリデーション）")
    class CreateLink {

        @Test
        @DisplayName("AC-D9: 発行認可（ADMIN/DEPUTY 限定）が拒否すれば伝播（一般 MEMBER→403）")
        void 発行認可拒否_伝播() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(folderQueryService).authorizeLinkManageByFileId(FILE_ID, USER_ID);
            CreateLinkRequest request = new CreateLinkRequest(LocalDateTime.now().plusDays(7), null, false);

            assertThatThrownBy(() -> service.createLink(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("AC-D9: ADMIN/DEPUTY（認可通過）＋有効期限で作成される・download_allowed が反映される")
        void 認可通過_作成() {
            CreateLinkRequest request = new CreateLinkRequest(LocalDateTime.now().plusDays(7), null, true);
            SharedFileLinkEntity saved = SharedFileLinkEntity.builder()
                    .fileId(FILE_ID).token("gen").downloadAllowed(true).accessCount(0).createdBy(USER_ID).build();
            given(linkRepository.save(any())).willReturn(saved);
            given(fileSharingMapper.toLinkResponse(saved))
                    .willReturn(new LinkResponse(1L, FILE_ID, "gen", null, false, 0, null, USER_ID, null, true, true));

            LinkResponse result = service.createLink(FILE_ID, USER_ID, request);

            assertThat(result.isDownloadAllowed()).isTrue();
            verify(linkRepository).save(any());
        }

        @Test
        @DisplayName("AC-D11: expiresAt 未指定は LINK_EXPIRY_INVALID（→400）")
        void 期限未指定_400() {
            CreateLinkRequest request = new CreateLinkRequest(null, null, false);
            assertThatThrownBy(() -> service.createLink(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_EXPIRY_INVALID));
        }

        @Test
        @DisplayName("AC-D11: expiresAt が30日超は LINK_EXPIRY_INVALID（→400）")
        void 期限30日超_400() {
            CreateLinkRequest request = new CreateLinkRequest(LocalDateTime.now().plusDays(31), null, false);
            assertThatThrownBy(() -> service.createLink(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_EXPIRY_INVALID));
        }

        @Test
        @DisplayName("AC-D11: expiresAt が過去は LINK_EXPIRY_INVALID（→400）")
        void 期限過去_400() {
            CreateLinkRequest request = new CreateLinkRequest(LocalDateTime.now().minusMinutes(1), null, false);
            assertThatThrownBy(() -> service.createLink(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(FileSharingErrorCode.LINK_EXPIRY_INVALID));
        }
    }
}
