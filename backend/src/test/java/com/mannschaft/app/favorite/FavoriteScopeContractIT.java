package com.mannschaft.app.favorite;

import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F02.9 お気に入り EP の認可契約テスト（認可根治戦役 第1波・個人領域 ロットC）。
 *
 * <p>本 IT が固定する保証（{@code FavoriteAccessGuard}）:</p>
 * <ul>
 *   <li><b>登録対象の閲覧可否</b>: チームは F00 共通可視性ラダーで閲覧できる対象のみ登録できる。
 *       閲覧できないチーム（{@code MEMBERS_AND_ABOVE} の非メンバー等）は 404（{@code FAV_003}）で
 *       存在を秘匿し、チーム名・アイコンを返さない。</li>
 *   <li><b>お気に入り行の所有</b>: 参照・削除は登録した本人のみ（他者所有は {@code FAV_004} / 403）。</li>
 *   <li><b>一覧の自己スコープ</b>: 他ユーザーのお気に入りが混入しないこと。</li>
 *   <li><b>正常系の非回帰</b>: 公開チームの登録・本人の参照/削除は従来どおり成功すること。</li>
 *   <li><b>未認証</b>: 401。</li>
 * </ul>
 *
 * <p>レート制限フィルタ（{@code FavoriteRateLimitFilter}）は
 * {@code @AutoConfigureMockMvc(addFilters = false)} により本 IT では適用されない。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("お気に入り 認可契約テスト（第1波 ロットC）")
class FavoriteScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserFavoriteRepository favoriteRepository;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;
    private Long attackerId;
    private Long memberId;

    private Long publicTeamId;
    private Long privateTeamId;

    private UUID ownerFavoriteId;

    @BeforeEach
    void setUp() {
        publicTeamId = insertTeam("FAVAUTHZ 公開チーム", "PUBLIC");
        privateTeamId = insertTeam("FAVAUTHZ 会員限定チーム", "MEMBERS_AND_ABOVE");

        ownerId = insertUser("fav-authz-owner@example.com");
        attackerId = insertUser("fav-authz-attacker@example.com");
        memberId = insertUser("fav-authz-member@example.com");

        // memberId のみ会員限定チームのメンバー。owner / attacker はどこにも所属させない。
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, privateTeamId, RoleKind.MEMBER);

        ownerFavoriteId = saveFavorite(ownerId, FavoriteEntityType.TEAM, publicTeamId.toString());
        saveFavorite(attackerId, FavoriteEntityType.TEAM, publicTeamId.toString());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. 登録対象の閲覧可否（F00 可視性ラダー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. お気に入り登録（対象の閲覧可否）")
    class AddFavorite {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody("TEAM", publicTeamId)))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("閲覧できない会員限定チームの登録→404秘匿（行も作られない）")
        void 非メンバーの会員限定チーム登録は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody("TEAM", privateTeamId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAV_003"));

            assertThat(favoriteRepository.findByUserIdAndEntityTypeAndEntityId(
                    attackerId, FavoriteEntityType.TEAM, privateTeamId.toString())).isEmpty();
        }

        @Test
        @DisplayName("正常系: 会員限定チームでもメンバーなら登録できる（201）")
        void メンバーの会員限定チーム登録は201() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody("TEAM", privateTeamId)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.available").value(true));
        }

        @Test
        @DisplayName("正常系: 公開チームは非メンバーでも登録できる（201）")
        void 公開チームの登録は201() throws Exception {
            setAuth(memberId);
            mockMvc.perform(post("/api/v1/me/favorites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(addBody("TEAM", publicTeamId)))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. お気に入り行の所有（参照・削除）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. お気に入り参照/削除（登録者本人限定）")
    class OwnFavorite {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/favorites/{id}", ownerFavoriteId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーの参照→403")
        void 他ユーザーの参照は403() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/favorites/{id}", ownerFavoriteId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FAV_004"));
        }

        @Test
        @DisplayName("正常系: 登録者本人の参照は200")
        void 本人の参照は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/favorites/{id}", ownerFavoriteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(ownerFavoriteId.toString()));
        }

        @Test
        @DisplayName("無関係な他ユーザーの削除→403（削除も成立しない）")
        void 他ユーザーの削除は403() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/me/favorites/{id}", ownerFavoriteId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FAV_004"));

            assertThat(favoriteRepository.findById(ownerFavoriteId)).isPresent();
        }

        @Test
        @DisplayName("正常系: 登録者本人の削除は204")
        void 本人の削除は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/me/favorites/{id}", ownerFavoriteId))
                    .andExpect(status().isNoContent());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. 一覧の自己スコープ
    // ═════════════════════════════════════════════════════════════════════

    /** FavoriteController#listFavorites の自己スコープ性を固定する。 */
    @Nested
    @DisplayName("3. お気に入り一覧（自己スコープ）")
    class ListFavorites {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/favorites"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのお気に入りは混入しない")
        void 他ユーザーのお気に入りは混入しない() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/favorites"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id",
                            not(hasItem(ownerFavoriteId.toString()))));
        }

        @Test
        @DisplayName("正常系: 自分のお気に入りは返る")
        void 自分のお気に入りは返る() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/favorites"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(ownerFavoriteId.toString())));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String addBody(String entityType, Long entityId) {
        return "{\"entityType\":\"" + entityType + "\",\"entityId\":\"" + entityId + "\"}";
    }

    private UUID saveFavorite(Long userId, FavoriteEntityType entityType, String entityId) {
        UserFavoriteEntity e = new UserFavoriteEntity();
        e.setUserId(userId);
        e.setEntityType(entityType);
        e.setEntityId(entityId);
        e.setDisplayOrder((short) 0);
        return favoriteRepository.save(e).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'FAVAUTHZ', 'テスト', 'FAVAUTHZ テスト', 'ACTIVE', "
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

    private Long insertTeam(String name, String visibility) {
        String uniqueName = name + " " + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, :visibility, 1, 0, 0, "
                                + "CONCAT('fav-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", uniqueName)
                .setParameter("visibility", visibility)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", uniqueName)
                .getSingleResult()).longValue();
    }
}
