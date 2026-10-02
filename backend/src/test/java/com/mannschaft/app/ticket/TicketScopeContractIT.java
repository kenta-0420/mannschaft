package com.mannschaft.app.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 Wave5: ticket（F08.5 回数券）ドメイン API 契約テスト（試練）。
 *
 * <p>正本: {@code .claude/campaigns/2026-07-10-authz-idor-audit.md}（ticket 節）・
 * {@code TicketAccessGuard}。金型: {@code ParkingScopeContractIT}
 * （{@code @AutoConfigureMockMvc(addFilters=false)} + 実 MySQL・
 * 越境 403/404 はアプリ層例外として認可フィルタ無効でも検証できる）。</p>
 *
 * <p>本ドメインは Controller・Service ともに認可シグナルを持たず、JavaDoc 上「ADMIN」と称する
 * スタッフ操作が誰でも到達可能だった。加えて顧客面の詳細・領収書・QR が所有者照合を欠いていたため、
 * 以下 4 象限で契約を固定する:</p>
 * <ol>
 *   <li><b>非メンバー / 非 ADMIN のスタッフ操作</b> → 403（COMMON_002）</li>
 *   <li><b>他人の bookId（顧客面）</b> → 404（TICKET_002）で存在秘匿 ← 本丸</li>
 *   <li><b>他チームの id</b> → 404 で存在秘匿</li>
 *   <li><b>正当な ADMIN / 所有者</b> → 200（正当操作の非回帰）</li>
 * </ol>
 *
 * <p>ADMIN 役の被験者は {@code checkMembership}（memberships 表）と
 * {@code checkAdminOrAbove}（user_roles 表）の両方を満たすよう二重に seed する
 * （認可根治戦役 Wave0+1 で確立した既知の地雷）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ticket ドメイン API 契約テスト（認可根治 Wave5）")
class TicketScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** 領収書 PDF はテンプレート描画が本筋でないため差し替える（認可の契約のみを検証する）。 */
    @MockitoBean
    private PdfGeneratorService pdfGeneratorService;

    private Long teamAId;
    private Long teamBId;
    private Long adminAId;
    private Long adminBId;
    private Long memberAId;
    private Long customerAId;
    private Long otherCustomerAId;
    private Long outsiderId;

    private Long productAId;
    private Long bookAId;
    private Long bookInTeamBId;

    @BeforeEach
    void setUp() {
        insertRole("ADMIN", "管理者", 2, false);

        teamAId = insertTeam("TK契約テストチームA");
        teamBId = insertTeam("TK契約テストチームB");

        adminAId = insertUser("tk-contract-admin-a@example.com");
        adminBId = insertUser("tk-contract-admin-b@example.com");
        memberAId = insertUser("tk-contract-member-a@example.com");
        customerAId = insertUser("tk-contract-customer-a@example.com");
        otherCustomerAId = insertUser("tk-contract-other-customer-a@example.com");
        outsiderId = insertUser("tk-contract-outsider@example.com");

        // ADMIN 役は checkMembership(memberships) と checkAdminOrAbove(user_roles) の両方を満たす必要がある
        MembershipTestHelper.insertUserRole(em, adminAId, "ADMIN", teamAId, null);
        MembershipTestHelper.insertMembership(em, adminAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertUserRole(em, adminBId, "ADMIN", teamBId, null);
        MembershipTestHelper.insertMembership(em, adminBId, ScopeType.TEAM, teamBId, RoleKind.MEMBER);

        // memberA / customerA / otherCustomerA はチームAの一般メンバー（ADMIN権限なし）
        MembershipTestHelper.insertMembership(em, memberAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, customerAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);
        MembershipTestHelper.insertMembership(em, otherCustomerAId, ScopeType.TEAM, teamAId, RoleKind.MEMBER);

        // outsiderId はどちらのチームにも一切所属しない

        productAId = insertProduct(teamAId, "TK商品A", adminAId);
        Long productBId = insertProduct(teamBId, "TK商品B", adminBId);

        // customerA が所有するチームAのチケット（決済済み＝領収書取得可能）
        Long paymentAId = insertPayment(teamAId, customerAId, productAId);
        bookAId = insertBook(teamAId, productAId, customerAId, paymentAId);

        // チームBのチケット（越境参照の的）
        Long paymentBId = insertPayment(teamBId, adminBId, productBId);
        bookInTeamBId = insertBook(teamBId, productBId, adminBId, paymentBId);

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限①: スタッフ面は ADMIN 限定（非メンバー・非 ADMIN・他チーム ADMIN は 403）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("スタッフ面(ticket-books)の ADMIN 認可")
    class StaffBookOperations {

        @Test
        @DisplayName("非メンバーのチケット発行一覧は403（COMMON_002）")
        void 非メンバーの発行一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーのチケット発行一覧は403（全顧客の購入履歴・氏名の保護）")
        void 非ADMINメンバーの発行一覧は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINのチケット発行一覧は403（越境拒否）")
        void 他チームADMINの発行一覧は403() throws Exception {
            setAuthentication(adminBId); // チームBのADMINがチームAのURLを叩く

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの手動発行は403（@Valid を満たす body でもガードで弾く）")
        void 非ADMINメンバーの手動発行は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("productId", productAId);
            body.put("userId", customerAId);
            body.put("paymentMethod", "CASH");
            body.put("amount", 0);

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-books/issue", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーのチケット消化は403")
        void 非ADMINメンバーの消化は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("note", "契約テスト");

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-books/{id}/consume", teamAId, bookAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの返金は403")
        void 非ADMINメンバーの返金は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("refundType", "FULL");

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-books/{id}/refund", teamAId, bookAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの有効期限延長は403")
        void 非ADMINメンバーの延長は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("newExpiresAt", "2030-01-01T00:00:00");

            mockMvc.perform(patch("/api/v1/teams/{teamId}/ticket-books/{id}/extend", teamAId, bookAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーのQRスキャン消化は403")
        void 非ADMINメンバーのQR消化は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("qrPayload", "tkt_" + bookAId + "_otp_deadbeef1234");

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-books/consume-by-qr", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの一括消化は403")
        void 非ADMINメンバーの一括消化は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookId", bookAId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("consumptions", List.of(item));

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-books/bulk-consume", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの統計参照は403")
        void 非ADMINメンバーの統計は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/stats", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの売上CSVエクスポートは403（全顧客売上の流出防止）")
        void 非ADMINメンバーのCSVエクスポートは403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/stats/export", teamAId)
                            .param("format", "csv")
                            .param("period", "30d"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーのチケット詳細(スタッフ面)は403")
        void 非メンバーのスタッフ詳細は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/{id}", teamAId, bookAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限①: 商品 CRUD（作成・更新・削除は ADMIN、一覧はメンバー）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("商品(ticket-products)の認可")
    class ProductOperations {

        @Test
        @DisplayName("非ADMINメンバーの商品作成は403")
        void 非ADMINメンバーの商品作成は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-products", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(productBody("新商品"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの商品作成は403（越境拒否）")
        void 他チームADMINの商品作成は403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-products", teamAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(productBody("越境商品"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの商品更新は403")
        void 非ADMINメンバーの商品更新は403() throws Exception {
            setAuthentication(memberAId);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", "改名");

            mockMvc.perform(put("/api/v1/teams/{teamId}/ticket-products/{id}", teamAId, productAId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの商品削除は403")
        void 非ADMINメンバーの商品削除は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(delete("/api/v1/teams/{teamId}/ticket-products/{id}", teamAId, productAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非メンバーの商品一覧は403（他チームのカタログを見せない）")
        void 非メンバーの商品一覧は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-products", teamAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("一般メンバーの商品一覧(販売中のみ)は200（購入導線の非回帰）")
        void 一般メンバーの商品一覧は200() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-products", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("一般メンバーの includeInactive=true は403（販売停止中は運用情報）")
        void 一般メンバーの停止中込み一覧は403() throws Exception {
            setAuthentication(memberAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-products", teamAId)
                            .param("includeInactive", "true"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの includeInactive=true は200")
        void 正当ADMINの停止中込み一覧は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-products", teamAId)
                            .param("includeInactive", "true"))
                    .andExpect(status().isOk());
        }

        // ---- 認可根治 Wave5 追込: 購入(checkout)をカタログ閲覧と同粒度に揃えた分の番人 ----
        //
        // createCheckout は JavaDoc で「MEMBER / SUPPORTER」を宣言しながら認可の強制実装を持たず、
        // ログイン済みなら誰でも他チームの商品に対して Stripe Checkout Session と
        // PENDING の購入行を作成できた（商品一覧のほうが購入より厳しいという粒度逆転）。
        // requireTeamMember 追加により非メンバーはガードで 403 になる。
        //
        // 注: 正当メンバーの成功系（200）は Stripe API 実呼び出しを伴うため本 IT では検証しない
        //     （本クラスは Stripe をモックしていない）。ここではガードが効くこと＝
        //     Stripe 到達前に 403 で中断することのみを担保する。

        @Test
        @DisplayName("非メンバーの購入(checkout)は403（他チームの商品を勝手に購入させない）")
        void 非メンバーの購入は403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-products/{id}/checkout",
                            teamAId, productAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームメンバーの購入(checkout)は403（越境拒否）")
        void 他チームメンバーの購入は403() throws Exception {
            setAuthentication(adminBId); // チームB の ADMIN はチームA の非メンバー

            mockMvc.perform(post("/api/v1/teams/{teamId}/ticket-products/{id}/checkout",
                            teamAId, productAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        private Map<String, Object> productBody(String name) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            body.put("totalTickets", 10);
            body.put("price", 10000);
            return body;
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限②【本丸】: 顧客面は所有者限定。他人の bookId は 404 で存在秘匿
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("顧客面(my-tickets)の所有者照合")
    class CustomerOwnership {

        @Test
        @DisplayName("他人のチケット詳細は404（TICKET_002。決済額・購入者名の漏洩防止）")
        void 他人のチケット詳細は404() throws Exception {
            setAuthentication(otherCustomerAId); // 同じチームAのメンバーだが所有者ではない

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}", teamAId, bookAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }

        @Test
        @DisplayName("他人の領収書は404（TICKET_002）")
        void 他人の領収書は404() throws Exception {
            setAuthentication(otherCustomerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}/receipt", teamAId, bookAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }

        @Test
        @DisplayName("他人のQRコードは404（TICKET_002。QRは消化に使える権利証）")
        void 他人のQRは404() throws Exception {
            setAuthentication(otherCustomerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}/qr", teamAId, bookAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }

        @Test
        @DisplayName("ADMINであっても顧客面エンドポイントで他人のチケットは引けない（顧客面は所有者限定）")
        void ADMINでも顧客面で他人のチケットは404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}", teamAId, bookAId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }

        @Test
        @DisplayName("所有者本人のチケット詳細は200（正当操作の非回帰）")
        void 所有者本人の詳細は200() throws Exception {
            setAuthentication(customerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}", teamAId, bookAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(bookAId));
        }

        @Test
        @DisplayName("所有者本人の領収書は200")
        void 所有者本人の領収書は200() throws Exception {
            setAuthentication(customerAId);
            when(pdfGeneratorService.generateFromTemplate(anyString(), any())).thenReturn(new byte[]{1, 2, 3});

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}/receipt", teamAId, bookAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("所有者本人のQRコードは200")
        void 所有者本人のQRは200() throws Exception {
            setAuthentication(customerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}/qr", teamAId, bookAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.qrPayload").exists());
        }

        @Test
        @DisplayName("自分のチケット一覧は非ADMINでも200（自己スコープで自足）")
        void 自分のチケット一覧は200() throws Exception {
            setAuthentication(customerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets", teamAId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限③: 他チームの id は 404 で存在秘匿
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("チーム束縛(越境 id は 404)")
    class CrossTeamBinding {

        @Test
        @DisplayName("顧客面で他チームのbookIdを自チームURLに混ぜると404")
        void 顧客面の他チームbookIdは404() throws Exception {
            setAuthentication(customerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/my-tickets/{bookId}", teamAId, bookInTeamBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }

        @Test
        @DisplayName("スタッフ面で他チームのbookIdを自チームURLに混ぜると404（正当ADMINでも越境不可）")
        void スタッフ面の他チームbookIdは404() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/{id}", teamAId, bookInTeamBId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TICKET_002"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限①④: 顧客チケットサマリ（二重BOLA: teamId・userId）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("顧客チケットサマリ(ticket-summary)の認可")
    class TicketSummary {

        @Test
        @DisplayName("非メンバーの顧客サマリ参照は403（任意ユーザーの氏名・残数の開示防止）")
        void 非メンバーのサマリは403() throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/users/{userId}/ticket-summary", teamAId, customerAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("非ADMINメンバーの顧客サマリ参照は403（他人の残数を覗けない）")
        void 非ADMINメンバーのサマリは403() throws Exception {
            setAuthentication(otherCustomerAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/users/{userId}/ticket-summary", teamAId, customerAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("他チームADMINの顧客サマリ参照は403（越境拒否）")
        void 他チームADMINのサマリは403() throws Exception {
            setAuthentication(adminBId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/users/{userId}/ticket-summary", teamAId, customerAId))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.error.code").value("COMMON_002"));
        }

        @Test
        @DisplayName("正当ADMINの顧客サマリ参照は200（カルテ連携の非回帰）")
        void 正当ADMINのサマリは200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/users/{userId}/ticket-summary", teamAId, customerAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(customerAId));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 象限④: 正当 ADMIN のスタッフ操作は 200（過剰ガードの非回帰）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("正当ADMINのスタッフ操作")
    class LegitimateAdmin {

        @Test
        @DisplayName("正当ADMINのチケット発行一覧は200")
        void 正当ADMINの発行一覧は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINのチケット詳細(スタッフ面)は200（他人のチケットでも閲覧可）")
        void 正当ADMINのスタッフ詳細は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/{id}", teamAId, bookAId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.id").value(bookAId));
        }

        @Test
        @DisplayName("正当ADMINの統計参照は200")
        void 正当ADMINの統計は200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/stats", teamAId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("正当ADMINの売上CSVエクスポートは200")
        void 正当ADMINのCSVエクスポートは200() throws Exception {
            setAuthentication(adminAId);

            mockMvc.perform(get("/api/v1/teams/{teamId}/ticket-books/stats/export", teamAId)
                            .param("format", "csv")
                            .param("period", "30d"))
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
                                + "VALUES (:email, 'TKContract', 'テスト', 'TK契約テスト', 'ACTIVE', "
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

    /**
     * 回数券商品を1件投入する。Entity の {@code @Column(nullable = false)} 列を全て埋める
     * （test profile は ddl-auto=create で Entity 由来のスキーマになるため）。
     */
    private Long insertProduct(Long teamId, String name, Long createdBy) {
        em.createNativeQuery(
                        "INSERT INTO ticket_products (team_id, name, total_tickets, price, tax_rate, "
                                + "is_online_purchasable, is_active, sort_order, created_by, created_at, updated_at) "
                                + "VALUES (:teamId, :name, 10, 10000, 10.00, 1, 1, 0, :createdBy, NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("name", name)
                .setParameter("createdBy", createdBy)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM ticket_products WHERE team_id = :teamId AND name = :name")
                .setParameter("teamId", teamId)
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    /** 決済済み（PAID・現地決済）の決済レコードを1件投入する。領収書取得の前提。 */
    private Long insertPayment(Long teamId, Long userId, Long productId) {
        em.createNativeQuery(
                        "INSERT INTO ticket_payments (team_id, user_id, product_id, payment_method, amount, "
                                + "status, paid_at, created_at, updated_at) "
                                + "VALUES (:teamId, :userId, :productId, 'CASH', 10000, 'PAID', NOW(), NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("userId", userId)
                .setParameter("productId", productId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }

    /** ACTIVE な回数券を1件投入する。 */
    private Long insertBook(Long teamId, Long productId, Long userId, Long paymentId) {
        em.createNativeQuery(
                        "INSERT INTO ticket_books (team_id, product_id, user_id, total_tickets, used_tickets, "
                                + "status, purchased_at, payment_id, created_at, updated_at) "
                                + "VALUES (:teamId, :productId, :userId, 10, 0, 'ACTIVE', NOW(), :paymentId, "
                                + "NOW(), NOW())")
                .setParameter("teamId", teamId)
                .setParameter("productId", productId)
                .setParameter("userId", userId)
                .setParameter("paymentId", paymentId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
    }
}
