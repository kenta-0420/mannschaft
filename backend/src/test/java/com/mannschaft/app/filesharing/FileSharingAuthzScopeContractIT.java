package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.entity.SharedFileStarEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFileStarRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F05.5 ファイル共有ドメインの認可契約テスト。
 *
 * <p>本 IT が固定する保証（対象 11 エンドポイント）:</p>
 * <ul>
 *   <li>{@code FileLinkController#listLinks} — 公開リンク一覧はスコープの管理者
 *       （ADMIN / DEPUTY_ADMIN）または個人フォルダ所有者本人のみが到達する。</li>
 *   <li>{@code FileLinkController#createLink} — 公開リンク発行は同上の権限に限定される。</li>
 *   <li>{@code FileLinkController#deleteLink} — 公開リンク削除は同上の権限に限定される。</li>
 *   <li>{@code FileLinkController#accessLink} — トークン自体が capability であり、
 *       不在トークンは 404、失効・期限切れは 410、パスワード付きリンクの未入力は 403 とする。</li>
 *   <li>{@code FileStarController#removeStar} — スターは (fileId, 認証主体) で解決され、
 *       他ユーザーのスターには到達しない。</li>
 *   <li>{@code FileStarController#listMyStars} — 検索条件が認証主体のみで、
 *       他ユーザーのスターは混入しない。</li>
 *   <li>{@code PersonalFolderController#listRootFolders} — 個人ルートフォルダ一覧は
 *       認証主体の PERSONAL スコープに束縛され、他ユーザーの個人フォルダは混入しない。</li>
 *   <li>{@code PersonalFolderController#createFolder} — 他ユーザーの個人フォルダを
 *       {@code parentId} に指定した接ぎ木は 404（存在秘匿）で拒否される。</li>
 *   <li>{@code SharedFileController#listFiles} — フォルダ実体由来のスコープで閲覧認可を強制し、
 *       さらにファイル個別の最低可視ロールをクエリ段階で絞り込む。</li>
 *   <li>{@code SharedFileController#getFile} — ファイル → フォルダを解決して閲覧認可＋
 *       最低可視ロール（ファイル値優先 → フォルダ継承）を当てる。</li>
 *   <li>{@code SharedFolderController#getFolderDetail} — フォルダ実体由来のスコープで閲覧認可を強制し、
 *       応答の {@code files} には最低可視ロールを満たすファイルのみを載せる（一覧経路と可視範囲が一致する）。</li>
 * </ul>
 *
 * <p>スコープ別の存在秘匿ポリシー: PERSONAL は所有者以外に 404（{@code FILE_SHARING_001} /
 * {@code FILE_SHARING_002}）、TEAM / ORGANIZATION は非メンバー・権限不足に 403（{@code COMMON_002}）。</p>
 *
 * <p>金型: {@code TodoPersonalScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} ＋
 * 実 MySQL ＋ 手動 SecurityContext ＋ {@code @EnabledIf isDockerAvailable}）。
 * コントローラが {@code SecurityUtils#getCurrentUserId} を呼ぶ EP では、未認証は
 * {@code COMMON_000} → 401 となる。</p>
 *
 * <p><b>テスト環境の前提</b>: {@code test} プロファイルはスキーマを Entity 定義から生成し Flyway を通さないため、
 * マスタデータである {@code storage_plans} のデフォルトプランは本テストが自前で用意する
 * （{@link #ensureDefaultStoragePlans()}。値は本番シード {@code V9.069} と同一）。
 * 認可通過後に走る署名 URL 発行はオブジェクトストレージへの外部依存であるため
 * {@link R2StorageService} をモックに差し替え、認可判定の成否だけがステータスに現れるようにする。
 * この 2 点は {@code ChatAuthzScopeContractIT} と同一に揃えてあり、両契約 IT が同じ
 * ApplicationContext を共有する（TestContext Cache の分裂を増やさない）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F05.5 ファイル共有 認可契約テスト")
class FileSharingAuthzScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SharedFolderRepository folderRepository;

    @Autowired
    private SharedFileRepository fileRepository;

    @Autowired
    private SharedFileStarRepository starRepository;

    @Autowired
    private SharedFileLinkRepository linkRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StoragePlanRepository storagePlanRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * オブジェクトストレージへの署名 URL 発行はテスト環境の外にあるため、決定的な値を返すモックに差し替える。
     * 認可は署名 URL 発行より前段で完結しており、本モックは認可判定に一切関与しない。
     *
     * <p>インターフェース型 {@code @MockitoBean StorageService} ではなく具象型
     * {@code R2StorageService}（bean 名 {@code r2StorageService}）で置換する。
     * インターフェース型で置換すると bean が {@code StorageService$MockitoMock} 型にすり替わり、
     * 同一 context 内で具象 {@code R2StorageService} を注入する消費者
     * （{@code StoragePathMigrationBatchService} 等）の DI が型不一致となって
     * ApplicationContext の起動そのものが失敗する。具象型でモックすれば
     * インターフェース消費者・具象型消費者の双方を満たす
     * （{@code BudgetFlatWriteScopeContractIT} / {@code CirculationExportScopeContractIT} と同一方針）。</p>
     */
    @MockitoBean
    private R2StorageService storageService;

    @PersistenceContext
    private EntityManager em;

    /** 個人フォルダ・個人ファイルの所有者。 */
    private Long personalOwnerId;
    /** チームの管理者（memberships MEMBER ＋ user_roles ADMIN）。 */
    private Long teamAdminId;
    /** チームの一般メンバー（memberships MEMBER のみ）。 */
    private Long teamMemberId;
    /** どのスコープにも所属しない無関係な他ユーザー。 */
    private Long outsiderId;

    private Long teamId;

    private Long personalFolderId;
    private Long teamFolderId;

    private Long personalFileId;
    private Long teamOpenFileId;
    private Long teamAdminOnlyFileId;

    /** 最低可視ロールが管理者限定のファイル名（一覧・詳細に現れないことの照合に使う）。 */
    private String teamAdminOnlyFileName;
    /** 所属者全員可視のファイル名。 */
    private String teamOpenFileName;

    private Long personalLinkId;
    private String personalLinkToken;
    private Long teamLinkId;
    private String inactiveLinkToken;
    private String expiredLinkToken;
    private String passwordLinkToken;

    @BeforeEach
    void setUp() {
        ensureDefaultStoragePlans();
        stubStorageService();

        // roles は priority を用いたロール強弱判定（hasRoleOrAbove）の基準になるため、
        // 本番 Flyway シード（V2.014）と同じ priority で確実に用意する。
        ensureRole("SYSTEM_ADMIN", "システム管理者", 1);
        ensureRole("ADMIN", "管理者", 2);
        ensureRole("DEPUTY_ADMIN", "副管理者", 3);
        ensureRole("MEMBER", "メンバー", 4);
        ensureRole("SUPPORTER", "サポーター", 5);

        String uniq = Long.toString(System.nanoTime(), 36);
        teamId = insertTeam("FSAUTHZ チーム", "fs-" + uniq);

        personalOwnerId = insertUser("fsauthz-owner-" + uniq + "@example.com");
        teamAdminId = insertUser("fsauthz-admin-" + uniq + "@example.com");
        teamMemberId = insertUser("fsauthz-member-" + uniq + "@example.com");
        outsiderId = insertUser("fsauthz-outsider-" + uniq + "@example.com");

        // ADMIN は memberships（所属）と user_roles（権限ロール）の両方が必要。
        MembershipTestHelper.insertMembership(em, teamAdminId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, teamAdminId, "ADMIN", teamId, null);
        MembershipTestHelper.insertMembership(em, teamMemberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);
        // outsider・personalOwner はどのスコープにも所属させない。

        personalFolderId = saveFolder(SharedFolderEntity.builder()
                .scopeType(FileScopeType.PERSONAL)
                .userId(personalOwnerId)
                .name("FSAUTHZ 個人フォルダ " + uniq)
                .createdBy(personalOwnerId));
        teamFolderId = saveFolder(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM)
                .teamId(teamId)
                .name("FSAUTHZ チームフォルダ " + uniq)
                .createdBy(teamAdminId));

        teamOpenFileName = "FSAUTHZ 全員可視ファイル " + uniq;
        teamAdminOnlyFileName = "FSAUTHZ 管理者限定ファイル " + uniq;

        personalFileId = saveFile(personalFolderId, "FSAUTHZ 個人ファイル " + uniq, personalOwnerId, null);
        teamOpenFileId = saveFile(teamFolderId, teamOpenFileName, teamAdminId, null);
        teamAdminOnlyFileId = saveFile(
                teamFolderId, teamAdminOnlyFileName, teamAdminId, FileVisibilityRole.ADMINS_AND_ABOVE);

        personalLinkToken = UUID.randomUUID().toString();
        personalLinkId = saveLink(personalFileId, personalLinkToken,
                LocalDateTime.now().plusDays(7), null, true, personalOwnerId);

        String teamLinkToken = UUID.randomUUID().toString();
        teamLinkId = saveLink(teamOpenFileId, teamLinkToken,
                LocalDateTime.now().plusDays(7), null, true, teamAdminId);

        inactiveLinkToken = UUID.randomUUID().toString();
        saveLink(teamOpenFileId, inactiveLinkToken,
                LocalDateTime.now().plusDays(7), null, false, teamAdminId);

        expiredLinkToken = UUID.randomUUID().toString();
        saveLink(teamOpenFileId, expiredLinkToken,
                LocalDateTime.now().minusDays(1), null, true, teamAdminId);

        passwordLinkToken = UUID.randomUUID().toString();
        saveLink(teamOpenFileId, passwordLinkToken,
                LocalDateTime.now().plusDays(7), passwordEncoder.encode("Passw0rd!"), true, teamAdminId);

        starRepository.save(SharedFileStarEntity.builder()
                .fileId(teamOpenFileId)
                .userId(teamMemberId)
                .build());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. FileLinkController#listLinks（公開リンク一覧・管理者/所有者限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. FileLinkController#listLinks の公開リンク管理認可を固定する")
    class ListLinks {

        @Test
        @DisplayName("FileLinkController#listLinks: 未認証はチームファイルの公開リンク一覧に到達しない（403）")
        void 未認証は到達しない() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/files/{fileId}/links", teamOpenFileId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("FileLinkController#listLinks: 一般 MEMBER は403")
        void 一般メンバーは403() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files/{fileId}/links", teamOpenFileId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("FileLinkController#listLinks: 無関係な他ユーザーが他人の個人ファイルを指定→404秘匿")
        void 他ユーザーの個人ファイルは404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/{fileId}/links", personalFileId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系 FileLinkController#listLinks: チーム管理者は200")
        void チーム管理者は200() throws Exception {
            setAuth(teamAdminId);
            mockMvc.perform(get("/api/v1/files/{fileId}/links", teamOpenFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("正常系 FileLinkController#listLinks: 個人フォルダ所有者本人は200")
        void 個人フォルダ所有者は200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(get("/api/v1/files/{fileId}/links", personalFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isArray());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. FileLinkController#createLink（公開リンク発行・管理者/所有者限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. FileLinkController#createLink の公開リンク発行認可を固定する")
    class CreateLink {

        @Test
        @DisplayName("FileLinkController#createLink: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/files/{fileId}/links", teamOpenFileId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(linkRequestBody()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("FileLinkController#createLink: 一般 MEMBER は403（リンクは発行されない）")
        void 一般メンバーは403() throws Exception {
            int before = linkRepository.findByFileIdOrderByCreatedAtDesc(teamOpenFileId).size();
            setAuth(teamMemberId);
            mockMvc.perform(post("/api/v1/files/{fileId}/links", teamOpenFileId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(linkRequestBody()))
                    .andExpect(status().isForbidden());

            assertThat(linkRepository.findByFileIdOrderByCreatedAtDesc(teamOpenFileId)).hasSize(before);
        }

        @Test
        @DisplayName("FileLinkController#createLink: 無関係な他ユーザーが他人の個人ファイルへ発行→404秘匿")
        void 他ユーザーの個人ファイルは404秘匿() throws Exception {
            int before = linkRepository.findByFileIdOrderByCreatedAtDesc(personalFileId).size();
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/files/{fileId}/links", personalFileId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(linkRequestBody()))
                    .andExpect(status().isNotFound());

            assertThat(linkRepository.findByFileIdOrderByCreatedAtDesc(personalFileId)).hasSize(before);
        }

        @Test
        @DisplayName("正常系 FileLinkController#createLink: チーム管理者は201")
        void チーム管理者は201() throws Exception {
            setAuth(teamAdminId);
            mockMvc.perform(post("/api/v1/files/{fileId}/links", teamOpenFileId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(linkRequestBody()))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. FileLinkController#deleteLink（公開リンク削除・管理者/所有者限定）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. FileLinkController#deleteLink の公開リンク削除認可を固定する")
    class DeleteLink {

        @Test
        @DisplayName("FileLinkController#deleteLink: 未認証はチームファイルの公開リンクを削除できない（403・リンクは残る）")
        void 未認証は削除できない() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/files/{fileId}/links/{linkId}", teamOpenFileId, teamLinkId))
                    .andExpect(status().isForbidden());

            assertThat(linkRepository.findById(teamLinkId)).isPresent();
        }

        @Test
        @DisplayName("FileLinkController#deleteLink: 一般 MEMBER は403（リンクは残る）")
        void 一般メンバーは403() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(delete("/api/v1/files/{fileId}/links/{linkId}", teamOpenFileId, teamLinkId))
                    .andExpect(status().isForbidden());

            assertThat(linkRepository.findById(teamLinkId)).isPresent();
        }

        @Test
        @DisplayName("FileLinkController#deleteLink: 無関係な他ユーザーは他人の個人ファイルのリンクを削除できない（404秘匿）")
        void 他ユーザーは404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(delete("/api/v1/files/{fileId}/links/{linkId}", personalFileId, personalLinkId))
                    .andExpect(status().isNotFound());

            assertThat(linkRepository.findById(personalLinkId)).isPresent();
        }

        @Test
        @DisplayName("正常系 FileLinkController#deleteLink: 個人フォルダ所有者本人は204で削除できる")
        void 個人フォルダ所有者は204() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(delete("/api/v1/files/{fileId}/links/{linkId}", personalFileId, personalLinkId))
                    .andExpect(status().isNoContent());

            assertThat(linkRepository.findById(personalLinkId)).isEmpty();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. FileLinkController#accessLink（トークンが capability）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. FileLinkController#accessLink のトークン検証順を固定する")
    class AccessLink {

        @Test
        @DisplayName("FileLinkController#accessLink: 存在しないトークンは404（総当りに存在を漏らさない）")
        void 不在トークンは404() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", UUID.randomUUID().toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("FileLinkController#accessLink: 無効化されたリンクは410")
        void 無効化リンクは410() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", inactiveLinkToken))
                    .andExpect(status().isGone());
        }

        @Test
        @DisplayName("FileLinkController#accessLink: 期限切れリンクは410")
        void 期限切れリンクは410() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", expiredLinkToken))
                    .andExpect(status().isGone());
        }

        @Test
        @DisplayName("FileLinkController#accessLink: パスワード付きリンクにパスワード無しは403")
        void パスワード無しは403() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", passwordLinkToken))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("FileLinkController#accessLink: パスワード不一致は403")
        void パスワード不一致は403() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", passwordLinkToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"WrongPassword!\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 FileLinkController#accessLink: 有効なトークンはスコープ非所属でもファイルメタを返す（capability）")
        void 有効トークンは200() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/shared-links/{token}/access", personalLinkToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(personalFileId.intValue()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. FileStarController#removeStar（自分のスターのみ到達）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. FileStarController#removeStar の自己スコープ性を固定する")
    class RemoveStar {

        @Test
        @DisplayName("FileStarController#removeStar: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(delete("/api/v1/files/{fileId}/stars", teamOpenFileId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("FileStarController#removeStar: 他ユーザーのスターは削除されない（自分のスターにしか到達しない）")
        void 他ユーザーのスターは消えない() throws Exception {
            setAuth(teamAdminId);
            mockMvc.perform(delete("/api/v1/files/{fileId}/stars", teamOpenFileId))
                    .andExpect(status().is4xxClientError());

            assertThat(starRepository.existsByFileIdAndUserId(teamOpenFileId, teamMemberId)).isTrue();
        }

        @Test
        @DisplayName("正常系 FileStarController#removeStar: 本人は204で自分のスターを外せる")
        void 本人は204() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(delete("/api/v1/files/{fileId}/stars", teamOpenFileId))
                    .andExpect(status().isNoContent());

            assertThat(starRepository.existsByFileIdAndUserId(teamOpenFileId, teamMemberId)).isFalse();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. FileStarController#listMyStars（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. FileStarController#listMyStars の自己スコープ性を固定する")
    class ListMyStars {

        @Test
        @DisplayName("FileStarController#listMyStars: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/files/{fileId}/stars/me", teamOpenFileId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("FileStarController#listMyStars: 他ユーザーのスターは混入しない")
        void 他ユーザーのスターは混入しない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/{fileId}/stars/me", teamOpenFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].fileId", not(hasItem(teamOpenFileId.intValue()))));
        }

        @Test
        @DisplayName("正常系 FileStarController#listMyStars: 本人のスターが返る")
        void 本人のスターが返る() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files/{fileId}/stars/me", teamOpenFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].fileId", hasItem(teamOpenFileId.intValue())))
                    .andExpect(jsonPath("$.data[*].userId", not(hasItem(personalOwnerId.intValue()))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 7. PersonalFolderController#listRootFolders（自己スコープ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("7. PersonalFolderController#listRootFolders の自己スコープ性を固定する")
    class ListRootFolders {

        @Test
        @DisplayName("PersonalFolderController#listRootFolders: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/folders"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PersonalFolderController#listRootFolders: 他ユーザーの個人フォルダは混入しない")
        void 他ユーザーの個人フォルダは混入しない() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/me/folders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(personalFolderId.intValue()))));
        }

        @Test
        @DisplayName("正常系 PersonalFolderController#listRootFolders: 所有者本人の個人フォルダが返る")
        void 所有者は200() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(get("/api/v1/me/folders"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(personalFolderId.intValue())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 8. PersonalFolderController#createFolder（他人の個人フォルダへの接ぎ木封鎖）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("8. PersonalFolderController#createFolder の接ぎ木封鎖を固定する")
    class CreatePersonalFolder {

        @Test
        @DisplayName("PersonalFolderController#createFolder: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(personalFolderBody("FSAUTHZ 未認証フォルダ", null)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("PersonalFolderController#createFolder: 他ユーザーの個人フォルダを parentId に指定→404秘匿（作られない）")
        void 他人の個人フォルダへの接ぎ木は404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(personalFolderBody("FSAUTHZ 接ぎ木フォルダ", personalFolderId)))
                    .andExpect(status().isNotFound());

            assertThat(folderRepository.findByParentIdOrderByNameAsc(personalFolderId)).isEmpty();
        }

        @Test
        @DisplayName("正常系 PersonalFolderController#createFolder: 所有者本人は自分の個人フォルダ配下に201で作成できる")
        void 所有者は201() throws Exception {
            setAuth(personalOwnerId);
            mockMvc.perform(post("/api/v1/me/folders")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(personalFolderBody("FSAUTHZ 自分の子フォルダ", personalFolderId)))
                    .andExpect(status().isCreated());

            assertThat(folderRepository.findByParentIdOrderByNameAsc(personalFolderId)).hasSize(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 9. SharedFileController#listFiles（スコープ認可＋最低可視ロール絞り込み）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("9. SharedFileController#listFiles のスコープ認可と最低可視ロール絞り込みを固定する")
    class ListFiles {

        @Test
        @DisplayName("SharedFileController#listFiles: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(teamFolderId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("SharedFileController#listFiles: 他ユーザーの個人フォルダは404秘匿")
        void 他人の個人フォルダは404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(personalFolderId)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SharedFileController#listFiles: 非メンバーのチームフォルダは403")
        void 非メンバーのチームフォルダは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(teamFolderId)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SharedFileController#listFiles: 一般 MEMBER には管理者限定ファイルが現れない")
        void 一般メンバーには管理者限定ファイルが現れない() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(teamFolderId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", hasItem(teamOpenFileName)))
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(teamAdminOnlyFileName))));
        }

        @Test
        @DisplayName("正常系 SharedFileController#listFiles: チーム管理者には管理者限定ファイルも返る")
        void チーム管理者には両方返る() throws Exception {
            setAuth(teamAdminId);
            mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(teamFolderId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", hasItem(teamOpenFileName)))
                    .andExpect(jsonPath("$.data[*].name", hasItem(teamAdminOnlyFileName)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 10. SharedFileController#getFile（ファイル単位の閲覧認可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("10. SharedFileController#getFile のファイル単位閲覧認可を固定する")
    class GetFile {

        @Test
        @DisplayName("SharedFileController#getFile: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/files/{fileId}", teamOpenFileId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("SharedFileController#getFile: 他ユーザーの個人ファイルは404秘匿")
        void 他人の個人ファイルは404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/{fileId}", personalFileId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SharedFileController#getFile: 非メンバーのチームファイルは403")
        void 非メンバーのチームファイルは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/{fileId}", teamOpenFileId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SharedFileController#getFile: 一般 MEMBER は管理者限定ファイルの詳細に到達しない（403）")
        void 一般メンバーは管理者限定ファイルに到達しない() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files/{fileId}", teamAdminOnlyFileId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正常系 SharedFileController#getFile: 一般 MEMBER は全員可視ファイルを取得できる")
        void 一般メンバーは全員可視ファイルを取得できる() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files/{fileId}", teamOpenFileId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.name").value(teamOpenFileName));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 11. SharedFolderController#getFolderDetail（詳細と一覧で可視範囲が一致する）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("11. SharedFolderController#getFolderDetail の閲覧認可と応答本文の可視範囲を固定する")
    class GetFolderDetail {

        @Test
        @DisplayName("SharedFolderController#getFolderDetail: 未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/files/folders/{folderId}", teamFolderId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("SharedFolderController#getFolderDetail: 他ユーザーの個人フォルダは404秘匿")
        void 他人の個人フォルダは404秘匿() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/folders/{folderId}", personalFolderId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("SharedFolderController#getFolderDetail: 非メンバーのチームフォルダは403")
        void 非メンバーのチームフォルダは403() throws Exception {
            setAuth(outsiderId);
            mockMvc.perform(get("/api/v1/files/folders/{folderId}", teamFolderId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("SharedFolderController#getFolderDetail: 一般 MEMBER の応答 files に管理者限定ファイルは載らない")
        void 一般メンバーの応答に管理者限定ファイルは載らない() throws Exception {
            setAuth(teamMemberId);
            mockMvc.perform(get("/api/v1/files/folders/{folderId}", teamFolderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.files[*].fileName", hasItem(teamOpenFileName)))
                    .andExpect(jsonPath("$.data.files[*].fileName", not(hasItem(teamAdminOnlyFileName))));
        }

        @Test
        @DisplayName("正常系 SharedFolderController#getFolderDetail: チーム管理者の応答 files には管理者限定ファイルも載る")
        void チーム管理者の応答には両方載る() throws Exception {
            setAuth(teamAdminId);
            mockMvc.perform(get("/api/v1/files/folders/{folderId}", teamFolderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.files[*].fileName", hasItem(teamOpenFileName)))
                    .andExpect(jsonPath("$.data.files[*].fileName", hasItem(teamAdminOnlyFileName)));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー（金型 TodoPersonalScopeContractIT より写経）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * {@code storage_plans} のスコープ別デフォルトプランを用意する（値は本番シード {@code V9.069} と同一）。
     *
     * <p>ストレージサブスクリプションの自動払い出しは {@code REQUIRES_NEW} の独立トランザクションで走り、
     * テストメソッドのトランザクション内の未コミット行を参照できない。そのため本 seed も
     * {@code REQUIRES_NEW} でコミットして、払い出しから確実に見えるようにする。
     * 既存行があるときは作らないため、テスト間で重複しない。</p>
     */
    private void ensureDefaultStoragePlans() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> {
            ensureDefaultStoragePlan("ORGANIZATION", "フリー（組織）", 53_687_091_200L);
            ensureDefaultStoragePlan("TEAM", "フリー（チーム）", 5_368_709_120L);
            ensureDefaultStoragePlan("PERSONAL", "フリー（個人）", 1_073_741_824L);
        });
    }

    private void ensureDefaultStoragePlan(String scopeLevel, String name, long includedBytes) {
        if (storagePlanRepository
                .findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(scopeLevel).isPresent()) {
            return;
        }
        storagePlanRepository.save(StoragePlanEntity.builder()
                .name(name)
                .scopeLevel(scopeLevel)
                .includedBytes(includedBytes)
                .maxBytes(includedBytes)
                .priceMonthly(BigDecimal.ZERO)
                .isDefault(true)
                .sortOrder((short) 1)
                .build());
    }

    /** 署名 URL 発行が決定的な値を返すようにする（認可通過後に外部依存でステータスが揺れないようにする）。 */
    private void stubStorageService() {
        given(storageService.generateUploadUrl(any(), any(), any()))
                .willAnswer(invocation -> new PresignedUploadResult(
                        "https://storage.test.invalid/upload/" + invocation.getArgument(0),
                        invocation.getArgument(0), 900L));
        given(storageService.generateDownloadUrl(any(), any()))
                .willAnswer(invocation ->
                        "https://storage.test.invalid/download/" + invocation.getArgument(0));
    }

    /** 公開リンク発行リクエスト本文（有効期限は必須・最大30日先）。 */
    private String linkRequestBody() {
        return "{\"expiresAt\":\"" + LocalDateTime.now().plusDays(7).withNano(0)
                + "\",\"downloadAllowed\":false}";
    }

    /** 個人フォルダ作成リクエスト本文。 */
    private String personalFolderBody(String name, Long parentId) {
        return "{\"name\":\"" + name + "\",\"scopeType\":\"PERSONAL\""
                + (parentId == null ? "" : ",\"parentId\":" + parentId) + "}";
    }

    private Long saveFolder(SharedFolderEntity.SharedFolderEntityBuilder<?, ?> builder) {
        return folderRepository.save(builder.build()).getId();
    }

    private Long saveFile(Long folderId, String name, Long createdBy, FileVisibilityRole minVisibleRole) {
        SharedFileEntity.SharedFileEntityBuilder<?, ?> builder = SharedFileEntity.builder()
                .folderId(folderId)
                .name(name)
                .fileKey("files/test/" + UUID.randomUUID() + ".txt")
                .fileSize(1024L)
                .contentType("text/plain")
                .createdBy(createdBy);
        // @Builder.Default を無効化しないよう、最低可視ロールは指定があるときだけ設定する。
        if (minVisibleRole != null) {
            builder.minVisibleRole(minVisibleRole);
        }
        return fileRepository.save(builder.build()).getId();
    }

    private Long saveLink(Long fileId, String token, LocalDateTime expiresAt,
                          String passwordHash, boolean active, Long createdBy) {
        SharedFileLinkEntity.SharedFileLinkEntityBuilder<?, ?> builder = SharedFileLinkEntity.builder()
                .fileId(fileId)
                .token(token)
                .expiresAt(expiresAt)
                .active(active)
                .createdBy(createdBy);
        if (passwordHash != null) {
            builder.passwordHash(passwordHash);
        }
        return linkRepository.save(builder.build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** roles を name で引き、priority を本番シード（V2.014）と同じ値に揃える。 */
    private void ensureRole(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            em.createNativeQuery("UPDATE roles SET priority = :priority WHERE name = :name")
                    .setParameter("priority", priority)
                    .setParameter("name", name)
                    .executeUpdate();
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'FSAUTHZ', 'テスト', 'FSAUTHZ テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name, String slug) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, :slug, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
