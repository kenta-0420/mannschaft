package com.mannschaft.app.family;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.family.entity.CoinTossResultEntity;
import com.mannschaft.app.family.entity.DutyRotationEntity;
import com.mannschaft.app.family.entity.ShoppingListEntity;
import com.mannschaft.app.family.entity.ShoppingListItemEntity;
import com.mannschaft.app.family.entity.TeamAnniversaryEntity;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.CoinTossResultRepository;
import com.mannschaft.app.family.repository.DutyRotationRepository;
import com.mannschaft.app.family.repository.ShoppingListItemRepository;
import com.mannschaft.app.family.repository.ShoppingListRepository;
import com.mannschaft.app.family.repository.TeamAnniversaryRepository;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave2 トランシェ2C: family ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（family 節）・
 * {@code AccessControlService}（{@code checkMembership}/{@code checkAdminOrAbove}）。
 * 金型: {@code ParkingScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>担当スコープ（teamId 系の全 EP ＋ CareOverride）:</p>
 * <ul>
 *   <li>記念日/コイントス/お買い物リスト（家族ユーティリティ）: 全操作=checkMembership。
 *       ID 指定操作は entity 由来 teamId とパス teamId の不一致を 404 で存在秘匿</li>
 *   <li>当番ローテーション作成/更新/削除・プレゼンスアイコン設定・ロール呼称設定・
 *       プレゼンス統計（ADMIN用 EP）: checkAdminOrAbove。閲覧=checkMembership</li>
 *   <li>プレゼンス送信/閲覧・壁紙一覧: checkMembership</li>
 *   <li>TeamCareOverride（児童 PII・後見系の最機密）: ケアリンク当事者
 *       （careRecipient または watcher）のみ操作可（FAMILY_030=403）。
 *       存在しない careLinkId は FAMILY_025=404 で存在秘匿</li>
 * </ul>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("family ドメイン API 契約テスト（認可根治 Wave2 トランシェ2C）")
class FamilyScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TeamAnniversaryRepository anniversaryRepository;

    @Autowired
    private DutyRotationRepository dutyRotationRepository;

    @Autowired
    private CoinTossResultRepository coinTossResultRepository;

    @Autowired
    private ShoppingListRepository shoppingListRepository;

    @Autowired
    private ShoppingListItemRepository shoppingListItemRepository;

    @Autowired
    private UserCareLinkRepository careLinkRepository;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long memberBId;
    private Long outsiderId;

    // ケアリンク当事者（childA=ケア対象者 / watcherA=見守り者）
    private Long childAId;
    private Long watcherAId;
    private Long careLinkAId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("ADMIN", "管理者", 2);

        teamAId = insertTeam("FAM契約テストチームA");
        teamBId = insertTeam("FAM契約テストチームB");

        adminAId = insertUser("fam-contract-admin-a@example.com");
        adminBId = insertUser("fam-contract-admin-b@example.com");
        memberAId = insertUser("fam-contract-member-a@example.com");
        memberBId = insertUser("fam-contract-member-b@example.com");
        outsiderId = insertUser("fam-contract-outsider@example.com");
        childAId = insertUser("fam-contract-child-a@example.com");
        watcherAId = insertUser("fam-contract-watcher-a@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // 一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, memberBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // childA は teamA のメンバー（ケア対象者）。watcherA はチーム非所属の見守り者（保護者）
        MembershipTestHelper.insertMembership(em, childAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // ACTIVE なケアリンク（childA ← watcherA）
        UserCareLinkEntity link = careLinkRepository.save(UserCareLinkEntity.builder()
                .careRecipientUserId(childAId)
                .watcherUserId(watcherAId)
                .careCategory(CareCategory.MINOR)
                .relationship(CareRelationship.PARENT)
                .status(CareLinkStatus.ACTIVE)
                .invitedBy(CareLinkInvitedBy.CARE_RECIPIENT)
                .createdBy(childAId)
                .build());
        careLinkAId = link.getId();

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 記念日（AnniversaryService: 全メンバー可・BOLA 404 是正）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("記念日(anniversaries)")
    class Anniversaries {

        @Test
        @DisplayName("非メンバーの記念日一覧取得は403（COMMON_002）")
        void 非メンバーの記念日一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/anniversaries", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの記念日一覧取得は403（越境拒否）")
        void 他チームADMINの記念日一覧は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/anniversaries", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーの記念日作成は201")
        void 正当メンバーの記念日作成は201() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "結婚記念日");
            body.put("date", LocalDate.now().plusDays(10).toString());

            mockMvc.perform(post("/api/v1/teams/{teamId}/anniversaries", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("他チームの記念日IDを自チームURLで更新すると404（BOLA是正: entity由来teamId検証）")
        void 他チームの記念日の越境更新は404() throws Exception {
            Long anniversaryId = insertAnniversary(teamAId, "チームAの記念日", adminAId);

            // チームBのADMINが、自分のチームBのURLに「teamAの記念日ID」を指定して更新を試みる
            setAuthentication(adminBId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "乗っ取り更新");
            body.put("date", LocalDate.now().plusDays(5).toString());

            mockMvc.perform(put("/api/v1/teams/{teamId}/anniversaries/{id}", teamBId, anniversaryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_018"));
        }

        @Test
        @DisplayName("正当メンバーの記念日更新は200")
        void 正当メンバーの記念日更新は200() throws Exception {
            Long anniversaryId = insertAnniversary(teamAId, "更新対象の記念日", adminAId);

            setAuthentication(memberAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "更新後の記念日");
            body.put("date", LocalDate.now().plusDays(7).toString());

            mockMvc.perform(put("/api/v1/teams/{teamId}/anniversaries/{id}", teamAId, anniversaryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // コイントス（CoinTossService）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("コイントス(coin-toss)")
    class CoinToss {

        @Test
        @DisplayName("非メンバーのコイントス実行は403")
        void 非メンバーのコイントスは403() throws Exception {
            setAuthentication(outsiderId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", "COIN");

            mockMvc.perform(post("/api/v1/teams/{teamId}/coin-toss", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームのコイントス結果IDを自チームURLで共有すると404（BOLA是正）")
        void 他チームのコイントスの越境共有は404() throws Exception {
            Long tossId = insertCoinToss(teamAId, memberAId);

            setAuthentication(adminBId);
            mockMvc.perform(post("/api/v1/teams/{teamId}/coin-toss/{id}/share", teamBId, tossId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_008"));
        }

        @Test
        @DisplayName("正当メンバーのコイントス履歴取得は200")
        void 正当メンバーの履歴は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/coin-toss/history", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのコイントス履歴取得は403")
        void 非メンバーの履歴は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/coin-toss/history", teamAId))
                    .andExpect(status().isForbidden());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 当番ローテーション（DutyRotationService: 変更系=ADMIN用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("当番ローテーション(duties)")
    class Duties {

        @Test
        @DisplayName("非メンバーの当番一覧取得は403")
        void 非メンバーの当番一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/duties", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーの当番作成は403（変更系はcheckAdminOrAbove）")
        void 非ADMINメンバーの当番作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/duties", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dutyBody("ゴミ出し"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの当番作成は201")
        void 正当ADMINの当番作成は201() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/duties", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(dutyBody("お風呂掃除"))))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("他チームの当番IDを自チームURLで削除すると404（BOLA是正: entity由来teamId検証）")
        void 他チームの当番の越境削除は404() throws Exception {
            Long dutyId = insertDuty(teamAId, "チームAの当番", adminAId);

            setAuthentication(adminBId);
            mockMvc.perform(delete("/api/v1/teams/{teamId}/duties/{id}", teamBId, dutyId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_016"));
        }

        private Map<String, Object> dutyBody(String name) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("dutyName", name);
            body.put("rotationType", "DAILY");
            body.put("memberOrder", List.of(memberAId));
            body.put("startDate", LocalDate.now().toString());
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // プレゼンス（PresenceService: 送信/閲覧=メンバー・統計=ADMIN用）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("プレゼンス(presence)")
    class Presence {

        @Test
        @DisplayName("非メンバーの帰ったよ通知は403")
        void 非メンバーの帰宅通知は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/presence/home", teamAId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの帰ったよ通知は201")
        void 正当メンバーの帰宅通知は201() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/presence/home", teamAId)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("非メンバーのステータス一覧取得は403（在宅状況PIIの越境防止）")
        void 非メンバーのステータス一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/presence/status", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームメンバーの履歴取得は403（越境拒否）")
        void 他チームメンバーの履歴は403() throws Exception {
            setAuthentication(memberBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/presence/history", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーの統計取得は403（ADMIN用EP）")
        void 非ADMINメンバーの統計は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/presence/stats", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの統計取得は200")
        void 正当ADMINの統計は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/presence/stats", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // プレゼンスアイコン・ロール呼称・壁紙（設定系=ADMIN用/閲覧=メンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("アイコン(icons)・ロール呼称(role-aliases)・壁紙(wallpapers)")
    class IconsAliasesWallpapers {

        @Test
        @DisplayName("非ADMINメンバーのアイコン設定は403")
        void 非ADMINメンバーのアイコン設定は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = Map.of("icons",
                    List.of(Map.of("eventType", "HOME", "icon", "🏠")));

            mockMvc.perform(put("/api/v1/teams/{teamId}/presence/icons", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのアイコン設定は200")
        void 正当ADMINのアイコン設定は200() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = Map.of("icons",
                    List.of(Map.of("eventType", "HOME", "icon", "🏠")));

            mockMvc.perform(put("/api/v1/teams/{teamId}/presence/icons", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのアイコン一覧取得は403")
        void 非メンバーのアイコン一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/presence/icons", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("非ADMINメンバーのロール呼称設定は403")
        void 非ADMINメンバーのロール呼称設定は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = Map.of("aliases",
                    List.of(Map.of("roleName", "ADMIN", "displayAlias", "お父さん")));

            mockMvc.perform(put("/api/v1/teams/{teamId}/role-aliases", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当ADMINのロール呼称設定は200")
        void 正当ADMINのロール呼称設定は200() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = Map.of("aliases",
                    List.of(Map.of("roleName", "ADMIN", "displayAlias", "お父さん")));

            mockMvc.perform(put("/api/v1/teams/{teamId}/role-aliases", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーの壁紙一覧取得は403")
        void 非メンバーの壁紙一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/wallpapers", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーの壁紙一覧取得は200")
        void 正当メンバーの壁紙一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/wallpapers", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // お買い物リスト（ShoppingListService: 全メンバー可・二重ID BOLA是正）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("お買い物リスト(shopping-lists)")
    class ShoppingLists {

        @Test
        @DisplayName("非メンバーのリスト一覧取得は403")
        void 非メンバーのリスト一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/shopping-lists", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーのリスト作成は201")
        void 正当メンバーのリスト作成は201() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = Map.of("name", "食料品");

            mockMvc.perform(post("/api/v1/teams/{teamId}/shopping-lists", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("他チームのリストIDを自チームURLでアイテム一覧取得すると404（BOLA是正: entity由来teamId検証）")
        void 他チームのリストの越境アイテム閲覧は404() throws Exception {
            Long listId = insertShoppingList(teamAId, "チームAのリスト", memberAId);

            setAuthentication(memberBId);
            mockMvc.perform(get("/api/v1/teams/{teamId}/shopping-lists/{id}/items", teamBId, listId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_011"));
        }

        @Test
        @DisplayName("別リストのitemIdを指定した更新は404（listId↔itemId 紐付け検証）")
        void 別リストのアイテムの更新は404() throws Exception {
            Long listA1 = insertShoppingList(teamAId, "リストA1", memberAId);
            Long listA2 = insertShoppingList(teamAId, "リストA2", memberAId);
            Long itemOfA1 = insertShoppingItem(listA1, "牛乳", memberAId);

            // listA2 のパスで listA1 のアイテムIDを指定する
            setAuthentication(memberAId);
            Map<String, Object> body = Map.of("name", "豆乳");

            mockMvc.perform(put("/api/v1/teams/{teamId}/shopping-lists/{id}/items/{itemId}",
                            teamAId, listA2, itemOfA1)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_013"));
        }

        @Test
        @DisplayName("正当メンバーのアイテム追加は201")
        void 正当メンバーのアイテム追加は201() throws Exception {
            Long listId = insertShoppingList(teamAId, "追加テストリスト", memberAId);

            setAuthentication(memberAId);
            Map<String, Object> body = Map.of("name", "牛乳");

            mockMvc.perform(post("/api/v1/teams/{teamId}/shopping-lists/{id}/items", teamAId, listId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // TeamCareOverride（児童PII・後見系の最機密: 当事者のみ）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チームケア通知上書き(care-overrides)")
    class CareOverrides {

        @Test
        @DisplayName("ケアリンク非当事者のチームメンバーが上書き設定をupsertすると403（他人の子どもの監視設定の越境操作防止）")
        void 非当事者の上書きupsertは403() throws Exception {
            setAuthentication(memberAId); // teamA のメンバーだがケアリンクの当事者ではない

            mockMvc.perform(put("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, careLinkAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(overrideBody())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_030"));
        }

        @Test
        @DisplayName("他チームADMINが上書き設定を閲覧すると403（当事者以外は閲覧不可）")
        void 非当事者ADMINの上書き閲覧は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamBId, careLinkAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("存在しないcareLinkIdのupsertは404（存在秘匿）")
        void 存在しないケアリンクのupsertは404() throws Exception {
            setAuthentication(watcherAId);

            mockMvc.perform(put("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, 999999999L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(overrideBody())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("FAMILY_025"));
        }

        @Test
        @DisplayName("当事者（見守り者）のupsertは200")
        void 当事者のupsertは200() throws Exception {
            setAuthentication(watcherAId);

            mockMvc.perform(put("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, careLinkAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(overrideBody())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.careLinkId").value(careLinkAId));
        }

        @Test
        @DisplayName("当事者（ケア対象者本人）の閲覧は200")
        void 当事者本人の閲覧は200() throws Exception {
            setAuthentication(childAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, careLinkAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非当事者の上書き設定削除は403")
        void 非当事者の上書き削除は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, careLinkAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("当事者の上書き設定削除は204")
        void 当事者の上書き削除は204() throws Exception {
            setAuthentication(watcherAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/care-overrides/{careLinkId}",
                            teamAId, careLinkAId))
                    .andExpect(status().isNoContent());
        }

        private Map<String, Object> overrideBody() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("notifyOnRsvp", false);
            body.put("disabled", false);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /**
     * roles はグローバル参照テーブルのため deleteAll せず、name で引く idempotent seed を行う。
     * （MembershipTestHelper の自動補完は priority=99 で入れてしまい、membership 由来の
     * MEMBER より弱くなって isAdminOrAbove が false になるため、ADMIN は priority=2 で先に入れる）
     */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
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
                                + "VALUES (:email, 'FAMContract', 'テスト', 'FAM契約テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('famc-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long insertAnniversary(Long teamId, String name, Long createdBy) {
        TeamAnniversaryEntity saved = anniversaryRepository.save(TeamAnniversaryEntity.builder()
                .teamId(teamId).name(name).date(LocalDate.now().plusDays(3))
                .repeatAnnually(true).notifyDaysBefore(1).createdBy(createdBy)
                .build());
        return saved.getId();
    }

    private Long insertDuty(Long teamId, String dutyName, Long createdBy) {
        DutyRotationEntity saved = dutyRotationRepository.save(DutyRotationEntity.builder()
                .teamId(teamId).dutyName(dutyName).rotationType(RotationType.DAILY)
                .memberOrder("[" + createdBy + "]").startDate(LocalDate.now())
                .isEnabled(true).createdBy(createdBy)
                .build());
        return saved.getId();
    }

    private Long insertCoinToss(Long teamId, Long userId) {
        CoinTossResultEntity saved = coinTossResultRepository.save(CoinTossResultEntity.builder()
                .teamId(teamId).userId(userId).mode(CoinTossMode.COIN)
                .options("[\"表\",\"裏\"]").resultIndex(0)
                .build());
        return saved.getId();
    }

    private Long insertShoppingList(Long teamId, String name, Long createdBy) {
        ShoppingListEntity saved = shoppingListRepository.save(ShoppingListEntity.builder()
                .teamId(teamId).name(name).isTemplate(false)
                .status(ShoppingListStatus.ACTIVE).createdBy(createdBy)
                .build());
        return saved.getId();
    }

    private Long insertShoppingItem(Long listId, String name, Long createdBy) {
        ShoppingListItemEntity saved = shoppingListItemRepository.save(ShoppingListItemEntity.builder()
                .listId(listId).name(name).createdBy(createdBy)
                .build());
        return saved.getId();
    }
}
