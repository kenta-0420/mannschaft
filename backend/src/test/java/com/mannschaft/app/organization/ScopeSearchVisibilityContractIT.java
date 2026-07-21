package com.mannschaft.app.organization;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治 Wave6 追加戦 — スコープ検索（組織 / チーム）の可視性フィルタ契約テスト。
 *
 * <h3>敷設した仕様</h3>
 * <p>{@code GET /api/v1/organizations/search} と {@code GET /api/v1/teams/search} は、
 * <b>PUBLIC かつ未アーカイブ</b>のスコープのみを返す。論理削除済みは Entity の
 * {@code @SQLRestriction("deleted_at IS NULL")} が除外する。</p>
 *
 * <h3>粒度の設計根拠（なぜ「公開のみ」なのか）</h3>
 * <p>両 EP は閲覧者ごとの可視性ラダー解決（{@code ContentVisibilityChecker}）を通さない公開検索であり、
 * 未認証でも到達しうる。同一 Repository に既に存在する {@code TeamRepository#searchPublicTeams}
 * （{@code visibility = PUBLIC AND archivedAt IS NULL}）が「正しい書き方の見本」として並存しているため、
 * 独自実装をせずその流儀へ揃えた。「自分がメンバーである非公開スコープも返す」は
 * 閲覧者依存の解決が必要になり本 EP の設計から外れるため採らず、最も安全側に倒している。</p>
 *
 * <h3>正常系の固定</h3>
 * <p>絞りすぎて検索が空になる事故を防ぐため、<b>PUBLIC かつ未アーカイブのスコープが確実にヒットする</b>
 * ことを組織・チームの双方で固定している。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("スコープ検索の可視性フィルタ契約テスト（Wave6 追加戦）")
class ScopeSearchVisibilityContractIT extends AbstractMySqlIntegrationTest {

    /** 4 件を一意に拾うための検索キーワード（他テストのデータと衝突しない接頭辞）。 */
    private static final String KEYWORD = "W6SEARCHVIS";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        // --- 組織 4 パターン ---
        insertOrganization(KEYWORD + " 公開組織", "PUBLIC", false, false);
        insertOrganization(KEYWORD + " 非公開組織", "PRIVATE", false, false);
        insertOrganization(KEYWORD + " アーカイブ組織", "PUBLIC", true, false);
        insertOrganization(KEYWORD + " 削除済組織", "PUBLIC", false, true);

        // --- チーム 4 パターン ---
        insertTeam(KEYWORD + " 公開チーム", "PUBLIC", false, false);
        insertTeam(KEYWORD + " 非公開チーム", "MEMBERS_AND_ABOVE", false, false);
        insertTeam(KEYWORD + " アーカイブチーム", "PUBLIC", true, false);
        insertTeam(KEYWORD + " 削除済チーム", "PUBLIC", false, true);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. GET /api/v1/organizations/search
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. GET /organizations/search")
    class SearchOrganizations {

        @Test
        @DisplayName("正常系: PUBLIC かつ未アーカイブの組織のみが返る")
        void 公開組織のみ返る() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", contains(KEYWORD + " 公開組織")));
        }

        @Test
        @DisplayName("非公開（PRIVATE）組織は返らない")
        void 非公開組織は返らない() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " 非公開組織"))));
        }

        @Test
        @DisplayName("アーカイブ済み組織は返らない")
        void アーカイブ組織は返らない() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " アーカイブ組織"))));
        }

        @Test
        @DisplayName("論理削除済み組織は返らない")
        void 削除済組織は返らない() throws Exception {
            mockMvc.perform(get("/api/v1/organizations/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " 削除済組織"))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. GET /api/v1/teams/search
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. GET /teams/search")
    class SearchTeams {

        @Test
        @DisplayName("正常系: PUBLIC かつ未アーカイブのチームのみが返る")
        void 公開チームのみ返る() throws Exception {
            mockMvc.perform(get("/api/v1/teams/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", contains(KEYWORD + " 公開チーム")));
        }

        @Test
        @DisplayName("非公開（MEMBERS_AND_ABOVE）チームは返らない")
        void 非公開チームは返らない() throws Exception {
            mockMvc.perform(get("/api/v1/teams/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " 非公開チーム"))));
        }

        @Test
        @DisplayName("アーカイブ済みチームは返らない")
        void アーカイブチームは返らない() throws Exception {
            mockMvc.perform(get("/api/v1/teams/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " アーカイブチーム"))));
        }

        @Test
        @DisplayName("論理削除済みチームは返らない")
        void 削除済チームは返らない() throws Exception {
            mockMvc.perform(get("/api/v1/teams/search").param("keyword", KEYWORD))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].name", not(hasItem(KEYWORD + " 削除済チーム"))));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void insertOrganization(String name, String visibility, boolean archived, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, archived_at, deleted_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', :vis, 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                                + ":archivedAt, :deletedAt, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("vis", visibility)
                .setParameter("archivedAt", archived ? java.time.LocalDateTime.now() : null)
                .setParameter("deletedAt", deleted ? java.time.LocalDateTime.now() : null)
                .executeUpdate();
    }

    private void insertTeam(String name, String visibility, boolean archived, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "archived_at, deleted_at, created_at, updated_at) "
                                + "VALUES (:name, :vis, 1, 0, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), "
                                + ":archivedAt, :deletedAt, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("vis", visibility)
                .setParameter("archivedAt", archived ? java.time.LocalDateTime.now() : null)
                .setParameter("deletedAt", deleted ? java.time.LocalDateTime.now() : null)
                .executeUpdate();
    }
}
