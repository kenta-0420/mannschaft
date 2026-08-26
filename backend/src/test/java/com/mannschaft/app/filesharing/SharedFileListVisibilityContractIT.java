package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.filesharing.repository.SharedFolderRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F05.5 一覧経路のファイル個別「最低可視ロール」絞り込みの<b>契約テスト</b>（情報漏洩根治の本丸）。
 *
 * <p>穴: {@code GET /api/v1/files?folderId=...} はフォルダ単位の閲覧認可は通していたが、
 * フォルダより厳しいファイル個別 {@code min_visible_role}（例: 公開フォルダ内の 1 ファイルだけ
 * {@code ADMINS_AND_ABOVE}）でフィルタしておらず、下位ロールの一覧にファイル名・メタが露出していた。
 * 本テストは実 MySQL に対して MockMvc で一覧 EP を叩き、隠しファイルが一覧に出ないこと・
 * ページング総件数/総ページ数が絞り込み後の値で整合すること（取得後 Java フィルタではないこと）を検証する。</p>
 *
 * <p>クロスドメイン（user / team ロール解決）の {@link AccessControlService} は {@code @MockitoBean} で
 * ロールを模擬する（Flyway 無効の {@code ddl-auto=create} では roles/user_roles/memberships が未シードのため）。
 * これにより「実 JPQL による絞り込み＋ページング整合」を実 DB で検証しつつ、ロール解決だけを隔離する。</p>
 *
 * <p>受け入れ条件:</p>
 * <ul>
 *   <li>AC-1: MEMBER は ADMINS_AND_ABOVE ファイルが一覧に出ない（NULL / MEMBERS は出る）</li>
 *   <li>AC-2: ADMIN は全ファイルが出る</li>
 *   <li>AC-3: SUPPORTER は MEMBERS_AND_ABOVE ファイルが出ない（NULL / SUPPORTERS は出る）</li>
 *   <li>AC-4: min_visible_role=NULL のファイルは常に出る（フォルダ継承・非回帰）</li>
 *   <li>AC-5: SYSTEM_ADMIN は全ファイルが出る</li>
 *   <li>AC-6: 絞り込み後の総件数(total)・総ページ数(totalPages)が整合（1 ページに収まらないケース）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@DisplayName("SharedFile 一覧 ファイル個別最低可視ロール 契約テスト (F05.5)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SharedFileListVisibilityContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SharedFolderRepository folderRepository;
    @Autowired
    private SharedFileRepository fileRepository;

    /** クロスドメインのロール解決は user/team ドメイン依存。ロールを模擬するため mock 化する。 */
    @MockitoBean
    private AccessControlService accessControlService;

    private static final Long TEAM_ID = 9500L;
    private static final long MEMBER_ID = 70001L;
    private static final long ADMIN_ID = 70002L;
    private static final long SUPPORTER_ID = 70003L;
    private static final long SYS_ADMIN_ID = 70004L;

    private Long folderId;

    @BeforeEach
    void setUp() {
        fileRepository.deleteAll();
        folderRepository.deleteAll();

        // 公開（min role 無し）TEAM フォルダ。フォルダ認可自体は checkMembership の mock で通す。
        SharedFolderEntity folder = folderRepository.save(SharedFolderEntity.builder()
                .scopeType(FileScopeType.TEAM).teamId(TEAM_ID).name("公開フォルダ").createdBy(1L).build());
        folderId = folder.getId();

        // name ASC 昇順: a_null < b_null < c_members < d_admins < e_null
        saveFile("a_null.pdf", null);
        saveFile("b_null.pdf", null);
        saveFile("c_members.pdf", FileVisibilityRole.MEMBERS_AND_ABOVE);
        saveFile("d_admins.pdf", FileVisibilityRole.ADMINS_AND_ABOVE);
        saveFile("e_null.pdf", null);
    }

    private void saveFile(String name, FileVisibilityRole minRole) {
        fileRepository.save(SharedFileEntity.builder()
                .folderId(folderId).name(name).fileKey("team/9500/" + name)
                .fileSize(1024L).contentType("application/pdf").minVisibleRole(minRole).createdBy(1L).build());
    }

    /** hasRoleOrAbove を段階的に許可する（requiredRoleName 単位で満たすか）。 */
    private void grant(long userId, boolean supporter, boolean member, boolean admin) {
        given(accessControlService.hasRoleOrAbove(eq(userId), eq(TEAM_ID), eq("TEAM"), eq("SUPPORTER")))
                .willReturn(supporter);
        given(accessControlService.hasRoleOrAbove(eq(userId), eq(TEAM_ID), eq("TEAM"), eq("MEMBER")))
                .willReturn(member);
        given(accessControlService.hasRoleOrAbove(eq(userId), eq(TEAM_ID), eq("TEAM"), eq("ADMIN")))
                .willReturn(admin);
    }

    @Test
    @WithMockUser(username = "70001")
    @DisplayName("AC-1/AC-4/AC-6: MEMBER は ADMINS ファイルが出ず、NULL/MEMBERS は出る（total=4・totalPages=2）")
    void AC1_MEMBER_ADMINS隠蔽_ページング整合() throws Exception {
        grant(MEMBER_ID, true, true, false); // SUPPORTER/MEMBER 満たす・ADMIN 満たさない

        // size=2 の 1 ページ目。可視は a_null,b_null,c_members,e_null の 4 件 → total=4/totalPages=2。
        mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(folderId))
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(4))          // ADMINS の 1 件を除いた総件数
                .andExpect(jsonPath("$.meta.totalPages").value(2))     // 4 件 / size2 = 2 ページ（取得後フィルタなら 5/3 になる）
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("a_null.pdf"))
                .andExpect(jsonPath("$.data[1].name").value("b_null.pdf"));

        // 2 ページ目: c_members, e_null（d_admins は絞り込みで欠落）
        mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(folderId))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("c_members.pdf"))
                .andExpect(jsonPath("$.data[1].name").value("e_null.pdf"));
    }

    @Test
    @WithMockUser(username = "70002")
    @DisplayName("AC-2: ADMIN は全ファイル（ADMINS 含む 5 件）が一覧に出る")
    void AC2_ADMIN_全件() throws Exception {
        grant(ADMIN_ID, true, true, true);

        mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(folderId))
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(5))
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[3].name").value("d_admins.pdf"));
    }

    @Test
    @WithMockUser(username = "70003")
    @DisplayName("AC-3/AC-4: SUPPORTER は MEMBERS/ADMINS が出ず NULL のみ（3 件）")
    void AC3_SUPPORTER_MEMBERS隠蔽() throws Exception {
        grant(SUPPORTER_ID, true, false, false); // SUPPORTER のみ満たす

        // SUPPORTERS_AND_ABOVE レベルのファイルは無いので、可視は NULL の 3 件（a/b/e）のみ。
        mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(folderId))
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].name").value("a_null.pdf"))
                .andExpect(jsonPath("$.data[1].name").value("b_null.pdf"))
                .andExpect(jsonPath("$.data[2].name").value("e_null.pdf"));
    }

    @Test
    @WithMockUser(username = "70004")
    @DisplayName("AC-5: SYSTEM_ADMIN は B を貫通し全ファイル（5 件）が出る")
    void AC5_SYSTEM_ADMIN_全件() throws Exception {
        given(accessControlService.isSystemAdmin(SYS_ADMIN_ID)).willReturn(true);

        mockMvc.perform(get("/api/v1/files").param("folderId", String.valueOf(folderId))
                        .param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(5))
                .andExpect(jsonPath("$.data.length()").value(5));
    }
}
