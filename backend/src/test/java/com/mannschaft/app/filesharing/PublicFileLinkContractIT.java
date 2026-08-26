package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F05.5 PR-D 公開ファイルリンクの<b>契約テスト</b>（本丸）。<b>認証ヘッダ無し</b>の MockMvc（実 Spring Security
 * フィルタチェーン）で公開経路を叩き、未認証で開ける範囲・エラーの HTTP セマンティクスを実 MySQL に対して検証する。
 *
 * <p>受け入れ条件 AC-D1〜D11 の未認証契約:</p>
 * <ul>
 *   <li>AC-D1: 有効リンクで<b>未ログイン</b>が access → 200</li>
 *   <li>AC-D2: 非会員（他チーム所属相当・ここでは TEAM フォルダに未所属の匿名）が access → 200（フォルダ認可を通さない）</li>
 *   <li>AC-D3: 期限切れ → 410（LINK_EXPIRED）</li>
 *   <li>AC-D4: is_active=false → 410（LINK_INACTIVE）</li>
 *   <li>AC-D5: 存在しない token → 404（LINK_NOT_FOUND・存在秘匿）</li>
 *   <li>AC-D6: password 誤り → 403（LINK_PASSWORD_INVALID）、正 → 200</li>
 *   <li>AC-D7: download_allowed=false で download-url → 403（LINK_DOWNLOAD_NOT_ALLOWED）</li>
 *   <li>AC-D8: download_allowed=true かつ download_disabled=true → 403（DOWNLOAD_DISABLED・C 優先）</li>
 *   <li>AC-D10: access で access_count がインクリメントされる（実 DB 読み直し）</li>
 *   <li>正常 DL: download_allowed=true かつ C 未禁止 → 200（DL URL）</li>
 * </ul>
 *
 * <p>{@code application-test.yml} は {@code ddl-auto=create} + {@code flyway.enabled=false} のため、
 * {@code is_active} / {@code download_allowed} 列は Entity の {@code columnDefinition} から生成される
 * （V136.001 マイグレーションは from-scratch Flyway 番人テスト側で検証される）。</p>
 */
@AutoConfigureMockMvc
@DisplayName("PublicFileLinkController 公開リンク 契約テスト (F05.5 PR-D)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PublicFileLinkContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SharedFolderRepository folderRepository;
    @Autowired
    private SharedFileRepository fileRepository;
    @Autowired
    private SharedFileLinkRepository linkRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    /** R2 は外部依存のため mock（DL URL 発行の成功パスで使用）。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    private Long fileId;
    private Long downloadDisabledFileId;

    @BeforeEach
    void setUp() {
        linkRepository.deleteAll();
        fileRepository.deleteAll();
        folderRepository.deleteAll();

        given(r2StorageService.generateDownloadUrl(anyString(), any(Duration.class)))
                .willReturn("https://r2.example.com/signed");

        // TEAM スコープのフォルダ（匿名/非会員は本来アクセス不可 → トークンが capability であることを示す）。
        SharedFolderEntity folder = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(9001L).name("契約テスト用").createdBy(1L).build());

        SharedFileEntity file = fileRepository.save(SharedFileEntity.builder()
                .folderId(folder.getId()).name("doc.pdf").fileKey("team/9001/doc.pdf")
                .fileSize(1024L).contentType("application/pdf").createdBy(1L).build());
        fileId = file.getId();

        SharedFileEntity ddFile = fileRepository.save(SharedFileEntity.builder()
                .folderId(folder.getId()).name("locked.pdf").fileKey("team/9001/locked.pdf")
                .fileSize(2048L).contentType("application/pdf").downloadDisabled(true).createdBy(1L).build());
        downloadDisabledFileId = ddFile.getId();
    }

    private String seedLink(Long targetFileId, LocalDateTime expiresAt, boolean active,
                            boolean downloadAllowed, String rawPassword) {
        SharedFileLinkEntity.SharedFileLinkEntityBuilder<?, ?> b = SharedFileLinkEntity.builder()
                .fileId(targetFileId).token(java.util.UUID.randomUUID().toString())
                .expiresAt(expiresAt).downloadAllowed(downloadAllowed).accessCount(0).createdBy(1L);
        if (rawPassword != null) {
            b.passwordHash(passwordEncoder.encode(rawPassword));
        }
        SharedFileLinkEntity saved = linkRepository.save(b.build());
        if (!active) {
            saved.deactivate();
            saved = linkRepository.save(saved);
        }
        return saved.getToken();
    }

    // ======================= access（メタ）=======================

    @Test
    @DisplayName("AC-D1: 有効リンクで未ログインが access → 200")
    void D1_未ログイン_access_200() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    @WithMockUser(username = "88888")
    @DisplayName("AC-D2: 非会員（TEAM 未所属のログイン済ユーザー）が access → 200（フォルダ認可を通さない）")
    void D2_非会員_access_200() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-D3: 期限切れ token → 410（LINK_EXPIRED）")
    void D3_期限切れ_410() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().minusDays(1), true, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_011"));
    }

    @Test
    @DisplayName("AC-D4: is_active=false → 410（LINK_INACTIVE）")
    void D4_失効_410() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), false, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_018"));
    }

    @Test
    @DisplayName("AC-D5: 存在しない token → 404（LINK_NOT_FOUND・存在秘匿）")
    void D5_存在しないトークン_404() throws Exception {
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", "no-such-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_007"));
    }

    @Test
    @DisplayName("AC-D6: password 誤り → 403、正 → 200")
    void D6_パスワード分岐() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, false, "secret");
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token)
                        .contentType("application/json").content("{\"password\":\"wrong\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_012"));
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token)
                        .contentType("application/json").content("{\"password\":\"secret\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-D10: access で access_count がインクリメントされる")
    void D10_アクセスカウント増加() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/access", token))
                .andExpect(status().isOk());
        SharedFileLinkEntity reloaded = linkRepository.findByToken(token).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getAccessCount()).isEqualTo(1);
    }

    // ======================= download-url =======================

    @Test
    @DisplayName("AC-D7: download_allowed=false で download-url → 403（LINK_DOWNLOAD_NOT_ALLOWED）")
    void D7_DL未許可_403() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, false, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/download-url", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_019"));
    }

    @Test
    @DisplayName("AC-D8: download_allowed=true かつ download_disabled=true → 403（DOWNLOAD_DISABLED・C 優先）")
    void D8_C禁止優先_403() throws Exception {
        String token = seedLink(downloadDisabledFileId, LocalDateTime.now().plusDays(1), true, true, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/download-url", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FILE_SHARING_017"));
    }

    @Test
    @DisplayName("正常 DL: download_allowed=true かつ C 未禁止 → 200（DL URL 発行）")
    void DL許可_200() throws Exception {
        String token = seedLink(fileId, LocalDateTime.now().plusDays(1), true, true, null);
        mockMvc.perform(post("/api/v1/public/file-links/{token}/download-url", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.downloadUrl").value("https://r2.example.com/signed"));
    }
}
