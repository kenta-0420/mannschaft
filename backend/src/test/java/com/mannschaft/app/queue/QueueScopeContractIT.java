package com.mannschaft.app.queue;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5: queue（F03.7 順番待ち管理）ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}。
 * 金型: {@code ParkingScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL。
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>検証する 3 象限:</p>
 * <ol>
 *   <li><b>非メンバー → 403</b>（COMMON_002）: {@code QueueAccessGuard#requireScopeMember} /
 *       {@code requireScopeAdmin} が URL パスの scope に対する所属を検証する。</li>
 *   <li><b>他 scope の ID → 404</b>（QUEUE_001/002/003/008）: カテゴリ / カウンター / チケット / QRコードは
 *       entity 由来 scope と URL パスの {@code teamId} を突合し、越境 ID を存在秘匿する。</li>
 *   <li><b>正当メンバーの read = 200 / 正当 ADMIN の管理操作 = 200（201・204）</b>: 正当操作の温存。</li>
 * </ol>
 *
 * <p><b>ORGANIZATION スコープについて</b>: {@link QueueScopeType#ORGANIZATION} は enum に定義されているが、
 * queue ドメインの Controller は 6 本すべて {@code /api/v1/teams/{teamId}/queue/**} であり、
 * 組織スコープの HTTP 入口は現時点で存在しない（main 全体で {@code QueueScopeType.ORGANIZATION} の
 * 参照が 0 件）。したがって ORG 版 IT は対象 EP が無いため作成しない。
 * 組織スコープの Controller を新設する際は、本 IT と同型の ORG 版番人テストを必ず併せて作ること。</p>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("queue ドメイン API 契約テスト（認可根治 Wave5）")
class QueueScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long outsiderId;

    private Long categoryAId;
    private Long categoryBId;
    private Long counterAId;
    private Long counterBId;
    private Long ticketAId;
    private Long ticketBId;
    private Long qrCodeAId;
    private Long qrCodeBId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, false);

        teamAId = insertTeam("QU契約テストチームA");
        teamBId = insertTeam("QU契約テストチームB");

        adminAId = insertUser("qu-contract-admin-a@example.com");
        adminBId = insertUser("qu-contract-admin-b@example.com");
        memberAId = insertUser("qu-contract-member-a@example.com");
        outsiderId = insertUser("qu-contract-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        categoryAId = insertCategory(teamAId, "QU受付A");
        categoryBId = insertCategory(teamBId, "QU受付B");
        counterAId = insertCounter(categoryAId, "QU窓口A");
        counterBId = insertCounter(categoryBId, "QU窓口B");
        ticketAId = insertTicket(categoryAId, counterAId, "A001", memberAId, 1);
        ticketBId = insertTicket(categoryBId, counterBId, "B001", adminBId, 1);
        qrCodeAId = insertQrCode(categoryAId, "qu-token-a");
        qrCodeBId = insertQrCode(categoryBId, "qu-token-b");

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // カテゴリ（QueueCategoryController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カテゴリ(categories)")
    class Categories {

        @Test
        @DisplayName("非メンバーのカテゴリ一覧取得は403（COMMON_002）")
        void 非メンバーのカテゴリ一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/categories", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINのカテゴリ一覧取得は403（越境拒否）")
        void 他チームADMINのカテゴリ一覧は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/categories", teamAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("正当メンバーのカテゴリ一覧取得は200")
        void 正当メンバーのカテゴリ一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/categories", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーのカテゴリ作成は403（変更系はrequireScopeAdmin）")
        void 非ADMINメンバーのカテゴリ作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/categories", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryBody("新規カテゴリ"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINはカテゴリを作成できる（201）")
        void 正当ADMINはカテゴリを作成できる() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/categories", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryBody("新規カテゴリ"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームのカテゴリIDへの更新は404で存在秘匿（QUEUE_001）")
        void 他チームカテゴリの更新は404() throws Exception {
            setAuthentication(adminAId); // チームAのADMINがチームBのカテゴリIDを指定

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/categories/{id}", teamAId, categoryBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(categoryBody("乗っ取り"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_001"));
        }

        @Test
        @DisplayName("他チームのカテゴリIDの詳細取得は404で存在秘匿（QUEUE_001）")
        void 他チームカテゴリの詳細は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/categories/{id}", teamAId, categoryBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_001"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // カウンター（QueueCounterController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カウンター(counters)")
    class Counters {

        @Test
        @DisplayName("非メンバーのカウンター一覧取得は403")
        void 非メンバーのカウンター一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .param("categoryId", String.valueOf(categoryAId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーのカウンター一覧取得は200")
        void 正当メンバーのカウンター一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .param("categoryId", String.valueOf(categoryAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他チームのカテゴリIDでのカウンター一覧は404で存在秘匿（QUEUE_001）")
        void 他チームカテゴリのカウンター一覧は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .param("categoryId", String.valueOf(categoryBId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_001"));
        }

        @Test
        @DisplayName("他チームのカウンターID詳細取得は404で存在秘匿（QUEUE_002）")
        void 他チームカウンターの詳細は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters/{id}", teamAId, counterBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_002"));
        }

        @Test
        @DisplayName("正当メンバーのカウンター詳細取得は200")
        void 正当メンバーのカウンター詳細は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters/{id}", teamAId, counterAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーのカウンター作成は403")
        void 非ADMINメンバーのカウンター作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(counterBody(categoryAId, "新規窓口"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINはカウンターを作成できる（201）")
        void 正当ADMINはカウンターを作成できる() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(counterBody(categoryAId, "新規窓口"))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームのカテゴリ配下へのカウンター作成は404で存在秘匿（QUEUE_001）")
        void 他チームカテゴリ配下へのカウンター作成は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(counterBody(categoryBId, "越境窓口"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_001"));
        }

        @Test
        @DisplayName("他チームのカウンター更新は404で存在秘匿（QUEUE_002）")
        void 他チームカウンターの更新は404() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "乗っ取り窓口");

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/counters/{id}", teamAId, counterBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // チケット（QueueTicketController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チケット(tickets)")
    class Tickets {

        @Test
        @DisplayName("非メンバーのチケット詳細取得は403")
        void 非メンバーのチケット詳細は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーのチケット詳細取得は200")
        void 正当メンバーのチケット詳細は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他チームのチケットID詳細取得は404で存在秘匿（QUEUE_003）")
        void 他チームチケットの詳細は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_003"));
        }

        @Test
        @DisplayName("非ADMINメンバーのチケット操作は403（管理操作はrequireScopeAdmin）")
        void 非ADMINメンバーのチケット操作は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/tickets/{id}/action", teamAId, ticketAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(actionBody("CALL"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINはチケットを操作できる（200）")
        void 正当ADMINはチケットを操作できる() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/tickets/{id}/action", teamAId, ticketAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(actionBody("CALL"))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("CALLED"));
        }

        @Test
        @DisplayName("他チームのチケットへの管理操作は404で存在秘匿（QUEUE_003）")
        void 他チームチケットへの管理操作は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/tickets/{id}/action", teamAId, ticketBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(actionBody("CALL"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_003"));
        }

        @Test
        @DisplayName("非ADMINメンバーの全チケット一覧取得は403（管理者用API）")
        void 非ADMINメンバーの全チケット一覧は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters/{id}/tickets/all", teamAId, counterAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの全チケット一覧取得は200")
        void 正当ADMINの全チケット一覧は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters/{id}/tickets/all", teamAId, counterAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーの次チケット呼び出しは403")
        void 非ADMINメンバーの呼び出しは403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters/{id}/tickets/call-next",
                            teamAId, counterAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームのカウンターへの呼び出しは404で存在秘匿（QUEUE_002）")
        void 他チームカウンターへの呼び出しは404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters/{id}/tickets/call-next",
                            teamAId, counterBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_002"));
        }

        @Test
        @DisplayName("非メンバーの待ちチケット一覧取得は403")
        void 非メンバーの待ちチケット一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/counters/{id}/tickets", teamAId, counterAId))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("他チームのカテゴリのチケット一覧は404で存在秘匿（QUEUE_001）")
        void 他チームカテゴリのチケット一覧は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/categories/{id}/tickets", teamAId, categoryBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_001"));
        }

        @Test
        @DisplayName("非メンバーのチケット発行は403")
        void 非メンバーのチケット発行は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters/{id}/tickets", teamAId, counterAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームのカウンターへのチケット発行は404で存在秘匿（QUEUE_002）")
        void 他チームカウンターへの発行は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/counters/{id}/tickets", teamAId, counterBId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_002"));
        }

        @Test
        @DisplayName("自分のチケットは取消できる（204）")
        void 本人はチケットを取消できる() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他人のチケット取消は404で存在秘匿（cancelMyTicketの本人性欠落を入口で根治）")
        void 他人のチケット取消は404() throws Exception {
            // adminA はチームAのADMINだが、ticketA の所有者は memberA。
            // 自己取消 API は本人のみが対象であり、他人のチケットは存在秘匿する
            // （管理者による取消は PATCH /tickets/{id}/action の CANCEL を用いる）。
            setAuthentication(adminAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_003"));
        }

        @Test
        @DisplayName("他チームのチケット取消は404で存在秘匿（QUEUE_003）")
        void 他チームチケットの取消は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/queue/tickets/{id}", teamAId, ticketBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_003"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // QRコード（QueueQrCodeController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QRコード(qr-codes)")
    class QrCodes {

        @Test
        @DisplayName("非ADMINメンバーのQRコード一覧取得は403（運用操作はrequireScopeAdmin）")
        void 非ADMINメンバーのQRコード一覧は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/qr-codes", teamAId)
                            .param("categoryId", String.valueOf(categoryAId)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINのQRコード一覧取得は200")
        void 正当ADMINのQRコード一覧は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/qr-codes", teamAId)
                            .param("categoryId", String.valueOf(categoryAId)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("他チームのカテゴリ指定のQRコード一覧は404で存在秘匿（QUEUE_008）")
        void 他チームカテゴリのQRコード一覧は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/qr-codes", teamAId)
                            .param("categoryId", String.valueOf(categoryBId)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_008"));
        }

        @Test
        @DisplayName("正当ADMINはQRコードを発行できる（201）")
        void 正当ADMINはQRコードを発行できる() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("categoryId", categoryAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/qr-codes", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("他チームのカテゴリを指すQRコード発行は404で存在秘匿（QUEUE_008）")
        void 他チームカテゴリのQRコード発行は404() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("categoryId", categoryBId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/queue/qr-codes", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_008"));
        }

        @Test
        @DisplayName("他チームのQRコード無効化は404で存在秘匿（QUEUE_008）")
        void 他チームQRコードの無効化は404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/queue/qr-codes/{id}", teamAId, qrCodeBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_008"));
        }

        @Test
        @DisplayName("正当ADMINは自チームのQRコードを無効化できる（204）")
        void 正当ADMINはQRコードを無効化できる() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/queue/qr-codes/{id}", teamAId, qrCodeAId))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("他チームのQRトークン照合は404で存在秘匿（QUEUE_008）")
        void 他チームQRトークンの照合は404() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/qr-codes/token/{token}", teamAId, "qu-token-b"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QUEUE_008"));
        }

        @Test
        @DisplayName("正当メンバーの自チームQRトークン照合は200")
        void 正当メンバーのQRトークン照合は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/qr-codes/token/{token}", teamAId, "qu-token-a"))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 設定・ステータス（QueueSettingsController / QueueStatusController）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("設定(settings)・ステータス(status)")
    class SettingsAndStatus {

        @Test
        @DisplayName("非メンバーの設定取得は403")
        void 非メンバーの設定取得は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/settings", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーの設定取得は200")
        void 正当メンバーの設定取得は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/settings", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非ADMINメンバーの設定更新は403")
        void 非ADMINメンバーの設定更新は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("allowGuestQueue", false);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINは設定を更新できる（200）")
        void 正当ADMINは設定を更新できる() throws Exception {
            setAuthentication(adminAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("allowGuestQueue", false);

            mockMvc.perform(patch("/api/v1/teams/{teamId}/queue/settings", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("非メンバーのキューステータス取得は403")
        void 非メンバーのステータス取得は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/status", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当メンバーのキューステータス取得は200")
        void 正当メンバーのステータス取得は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/queue/status", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** カテゴリ作成/更新の有効なボディ（name は @NotBlank。@Valid はガードより先に走るため必須項目を充足させる）。 */
    private Map<String, Object> categoryBody(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        return body;
    }

    /** カウンター作成の有効なボディ（categoryId は @NotNull・name は @NotBlank）。 */
    private Map<String, Object> counterBody(Long categoryId, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("categoryId", categoryId);
        body.put("name", name);
        return body;
    }

    private Map<String, Object> actionBody(String action) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        return body;
    }

    private void insertRole(String name, String displayName, int priority, boolean isSystem) {
        // 冪等化: roles はグローバル参照テーブルのため、既存なら再利用し二重INSERTしない
        // （同一 name の重複INSERTは roles の UNIQUE 制約違反になる。CI shard 再編成で
        // 同一 JVM 内の同居テストが変わり得るため、盲目的 INSERT は禁止）。
        Number existingRoleCount = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (existingRoleCount.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .setParameter("sys", isSystem ? 1 : 0)
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
                                + "VALUES (:email, 'QUContract', 'テスト', 'QU契約テスト', 'ACTIVE', "
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
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    // test プロファイルは ddl-auto=create のため、queue_* のスキーマは Entity から生成される。
    // @Builder.Default は DB DEFAULT を作らないため、@Column(nullable = false) の列は native INSERT で明示指定が必須。

    private Long insertCategory(Long scopeId, String name) {
        em.createNativeQuery(
                        "INSERT INTO queue_categories (scope_type, scope_id, name, queue_mode, "
                                + "max_queue_size, display_order, created_at, updated_at) "
                                + "VALUES ('TEAM', :scopeId, :name, 'INDIVIDUAL', 50, 0, NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM queue_categories").getSingleResult()).longValue();
    }

    private Long insertCounter(Long categoryId, String name) {
        em.createNativeQuery(
                        "INSERT INTO queue_counters (category_id, name, accept_mode, avg_service_minutes, "
                                + "avg_service_minutes_manual, max_queue_size, is_active, is_accepting, "
                                + "display_order, created_at, updated_at) "
                                + "VALUES (:categoryId, :name, 'BOTH', 10, 0, 50, 1, 1, 0, NOW(), NOW())")
                .setParameter("categoryId", categoryId)
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM queue_counters").getSingleResult()).longValue();
    }

    private Long insertTicket(Long categoryId, Long counterId, String ticketNumber, Long userId, int position) {
        em.createNativeQuery(
                        "INSERT INTO queue_tickets (category_id, counter_id, ticket_number, user_id, "
                                + "party_size, source, status, position, hold_used, issued_date, "
                                + "created_at, updated_at) "
                                + "VALUES (:categoryId, :counterId, :ticketNumber, :userId, "
                                + "1, 'ONLINE', 'WAITING', :position, 0, CURDATE(), NOW(), NOW())")
                .setParameter("categoryId", categoryId)
                .setParameter("counterId", counterId)
                .setParameter("ticketNumber", ticketNumber)
                .setParameter("userId", userId)
                .setParameter("position", position)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM queue_tickets").getSingleResult()).longValue();
    }

    private Long insertQrCode(Long categoryId, String qrToken) {
        em.createNativeQuery(
                        "INSERT INTO queue_qr_codes (category_id, qr_token, is_active, created_at, updated_at) "
                                + "VALUES (:categoryId, :qrToken, 1, NOW(), NOW())")
                .setParameter("categoryId", categoryId)
                .setParameter("qrToken", qrToken)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM queue_qr_codes").getSingleResult()).longValue();
    }
}
