package com.mannschaft.app.receipt;

import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可漏れ監査 第2波（PII・金銭）: 領収書マイページ 3 エンドポイントの API 契約テスト。
 *
 * <p><b>対象 EP</b></p>
 * <ul>
 *   <li>{@code GET /api/v1/my/receipts} — 自分宛の領収書一覧</li>
 *   <li>{@code GET /api/v1/my/receipts/annual-summary} — 年間サマリー</li>
 *   <li>{@code GET /api/v1/my/receipts/{id}/pdf} — 自分宛の領収書 PDF</li>
 * </ul>
 *
 * <p><b>保証する内容</b>: 領収書には宛名・住所・金額といった個人情報が載る。3 EP のいずれも
 * 宛先（{@code recipient_user_id}）が認証主体である領収書だけを対象とし、
 * 他人宛の領収書 ID を指定しても不存在と区別せず {@code RECEIPT_002}（404）で秘匿する。
 * 一覧・年間サマリーは他人宛の領収書を 1 件も混入させず、金額も合算しない。</p>
 *
 * <p><b>PDF 生成の扱い</b>: PDF 本体の生成（Thymeleaf ＋ Flying Saucer ＋ 埋め込みフォント）は
 * 本テストの検証対象外とし、宛先不一致が<b>PDF 生成へ到達する前に</b>遮断されることを固定する。
 * 一覧・年間サマリーで宛先本人の領収書が正しく見えることを併せて張り、正常系の回帰も担保する。</p>
 *
 * <p><b>未認証（401）経路について</b>: {@code addFilters = false} のため未認証リクエストの経路は存在しない。
 * 未認証の遮断は {@code SecurityConfig} の {@code anyRequest().authenticated()} が担保する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("領収書マイページ 自己スコープ API 契約テスト（認可根治 第2波）")
class ReceiptMyScopeContractIT extends AbstractMySqlIntegrationTest {

    /** 領収書が見つからない（404・宛先不一致も同一応答で秘匿）。 */
    private static final String RECEIPT_NOT_FOUND = "RECEIPT_002";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReceiptRepository receiptRepository;

    @PersistenceContext
    private EntityManager em;

    /** 領収書の宛先本人。 */
    private Long recipientId;
    /** 宛先ではない第三者（越境してはならない）。 */
    private Long outsiderId;

    private Long teamId;
    private Long receiptId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("領収書認可テスト チーム " + System.nanoTime());
        recipientId = insertUser("receipt-recipient@example.com");
        outsiderId = insertUser("receipt-outsider@example.com");

        receiptId = insertReceipt(recipientId);

        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("自分宛の領収書一覧には他人宛の領収書が混入しない")
    void 領収書一覧は宛先本人に閉じる() throws Exception {
        setAuthentication(recipientId);
        mockMvc.perform(get("/api/v1/my/receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(receiptId));

        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/my/receipts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("スコープを指定した一覧でも他人宛の領収書は見えない")
    void スコープ指定でも宛先本人に閉じる() throws Exception {
        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/my/receipts")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        setAuthentication(recipientId);
        mockMvc.perform(get("/api/v1/my/receipts")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    @DisplayName("年間サマリーは他人宛の領収書の金額を合算しない")
    void 年間サマリーは宛先本人に閉じる() throws Exception {
        int year = Year.now().getValue();

        setAuthentication(recipientId);
        mockMvc.perform(get("/api/v1/my/receipts/annual-summary").param("year", String.valueOf(year)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(1));

        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/my/receipts/annual-summary").param("year", String.valueOf(year)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("他人宛の領収書 PDF はダウンロードできない（404 で秘匿・PDF 生成へ到達しない）")
    void 他人宛の領収書PDFはダウンロードできない() throws Exception {
        setAuthentication(outsiderId);
        mockMvc.perform(get("/api/v1/my/receipts/{id}/pdf", receiptId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(RECEIPT_NOT_FOUND));
    }

    @Test
    @DisplayName("存在しない領収書 ID も同じ 404 で応答する（宛先不一致と区別させない）")
    void 不在の領収書も同じ404() throws Exception {
        setAuthentication(recipientId);
        mockMvc.perform(get("/api/v1/my/receipts/{id}/pdf", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value(RECEIPT_NOT_FOUND));
    }

    // ═════════════════════════════════════════════════════════════════════
    // seed ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** 宛先本人の領収書を 1 件作る。宛名・金額は明らかにテスト用の値を使う。 */
    private Long insertReceipt(Long ownerUserId) {
        ReceiptEntity receipt = ReceiptEntity.builder()
                .scopeType(ReceiptScopeType.TEAM)
                .scopeId(teamId)
                .receiptNumber("TEST-RCPT-0001")
                .recipientUserId(ownerUserId)
                .recipientName("領収書契約テスト 宛名")
                .issuerName("領収書契約テスト 発行元")
                .description("契約テスト用の領収書")
                .amount(new BigDecimal("1100"))
                .taxRate(new BigDecimal("10.00"))
                .taxAmount(new BigDecimal("100"))
                .amountExclTax(new BigDecimal("1000"))
                .paymentDate(LocalDate.now())
                .issuedAt(LocalDateTime.now())
                .issuedBy(ownerUserId)
                .build();
        return receiptRepository.save(receipt).getId();
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
                                + "VALUES (:email, '領収書契約', 'テスト', '領収書契約テスト', 'ACTIVE', "
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
}
