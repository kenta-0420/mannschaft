package com.mannschaft.app.receipt;

import com.jayway.jsonpath.JsonPath;
import com.mannschaft.app.receipt.entity.ReceiptEntity;
import com.mannschaft.app.receipt.repository.ReceiptRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.4 発行者情報のスナップショット契約テスト（試練・実装前 red）。
 *
 * <p>正本は {@code docs/features/F08.4_receipt.md} §9.4「発行者情報のスナップショット」
 * AC-3 / AC-4 / AC-16 / AC-17。</p>
 *
 * <h2>何を守るテストか</h2>
 * <p>領収書は法的文書であり、発行時点の発行者情報（適格請求書発行事業者フラグ・
 * インボイス登録番号）を {@code receipts} 行へ<b>スナップショット</b>として複製する。
 * 発行者設定をあとから変更しても、<b>すでに発行済みの領収書の内容は変わってはならない</b>。
 * 本クラスはこの不変性を、設計書の指定どおり <b>PDF ではなく {@code receipts} 行の
 * {@code is_qualified_invoice} / {@code invoice_registration_number} 列</b>に対して検証する。</p>
 *
 * <h2>本クラスが red である理由</h2>
 * <p>AC の文言は「ADMIN が<b>保存し</b>、その後に領収書を発行する<b>と</b>」であり、
 * 発行者設定の保存は実 API を通す。その更新系が {@code PUT}（フル置換）のままで
 * {@code PATCH}（差分更新）ではないため、保存の時点で 405 になり全ケースが赤くなる。
 * また AC-16 / AC-17 の「設定だけを後から変える」手順は 1 項目だけの差分更新であり、
 * {@code @NotBlank issuerName} / {@code @NotNull isQualifiedInvoicer} が残っている限り
 * 400 になる。
 * スナップショットの複製ロジック自体（{@code ReceiptService#createReceipt}）は既に存在するため、
 * PATCH 化と DTO 是正が済めばそのまま緑になる見込みである。
 * <b>逆に言えば、それらが済んだ後にこのクラスが赤いままなら、それはスナップショットが
 * 壊れているという本物の欠陥である。</b></p>
 *
 * <h2>フィクスチャ方針</h2>
 * <p>{@code member_payments} には依存しない。{@code CreateReceiptRequest#memberPaymentId} は
 * 任意であり、{@code recipientName} を直接指定する「手動入力モード」（設計書 §8）で
 * 発行できるため、最小限のフィクスチャ（チーム・ADMIN ロール・発行者設定）だけで
 * 発行フローを一気通貫で通せる。発行時に PDF 生成は走らないので外部境界のスタブも不要である。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("F08.4 発行者情報スナップショット契約テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReceiptIssuerSettingsSnapshotIT extends AbstractMySqlIntegrationTest {

    private static final String SETTINGS_PATH = "/api/v1/admin/receipt-settings";
    private static final String RECEIPTS_PATH = "/api/v1/admin/receipts";

    private static final Long ADMIN_A = 920142001L;

    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleRepository userRoleRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private ReceiptRepository receiptRepository;

    /** 発行者設定が未作成のチーム（各テストが PATCH の UPSERT で作る）。 */
    private Long teamId;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_A);

        Long adminRoleId = ensureRole("ADMIN", 2);
        ensureRole("SYSTEM_ADMIN", 1);
        ensureRole("DEPUTY_ADMIN", 3);
        ensureRole("MEMBER", 4);
        ensureRole("SUPPORTER", 5);
        ensureRole("GUEST", 6);

        teamId = teamRepository.save(TeamEntity.builder()
                .slug("receipt-snapshot-" + SLUG_SEQ.incrementAndGet())
                .name("領収書スナップショットテストチーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build()).getId();

        userRoleRepository.save(UserRoleEntity.builder()
                .userId(ADMIN_A).roleId(adminRoleId).teamId(teamId).build());

        em.flush();
        em.clear();
    }

    private Long ensureRole(String name, int priority) {
        return roleRepository.findByName(name)
                .map(RoleEntity::getId)
                .orElseGet(() -> roleRepository.save(RoleEntity.builder()
                        .name(name)
                        .displayName(name)
                        .priority(priority)
                        .isSystem("SYSTEM_ADMIN".equals(name))
                        .build()).getId());
    }

    /** 発行者設定を実 API（差分更新）で保存する。 */
    private void saveSettings(String bodyJson) throws Exception {
        mockMvc.perform(patch(SETTINGS_PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyJson))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
    }

    /** 領収書を実 API で発行し、発行された receipts 行の ID を返す。 */
    private Long issueReceipt(String description) throws Exception {
        String body = "{\"recipientName\":\"受領者太郎\",\"description\":\"" + description
                + "\",\"amount\":10000,\"taxRate\":10.00,\"paymentMethodLabel\":\"現金\"}";

        String response = mockMvc.perform(post(RECEIPTS_PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        em.flush();
        em.clear();

        Number id = JsonPath.read(response, "$.data.id");
        return id.longValue();
    }

    /** receipts 行を DB から読み直す（1次キャッシュ越しの偽 green を避ける）。 */
    private ReceiptEntity reloadReceipt(Long receiptId) {
        em.flush();
        em.clear();
        return receiptRepository.findById(receiptId).orElseThrow();
    }

    // ───────────────────────────── AC-3 ─────────────────────────────

    @Test
    @WithMockUser(username = "920142001")
    @DisplayName("AC-3: 適格ON・T1234567890123 で保存した後に発行すると、receipts 行に同じ登録番号が複製される")
    void ac3_issueAfterEnablingQualifiedInvoicer_snapshotsRegistrationNumber() throws Exception {
        saveSettings("{\"issuerName\":\"適格な発行者\",\"isQualifiedInvoicer\":true,"
                + "\"invoiceRegistrationNumber\":\"T1234567890123\"}");

        Long receiptId = issueReceipt("会議費として");

        ReceiptEntity receipt = reloadReceipt(receiptId);
        assertThat(receipt.getIsQualifiedInvoice())
                .as("適格請求書として発行されること（PDF の注記はこの値から導出される）")
                .isTrue();
        assertThat(receipt.getInvoiceRegistrationNumber())
                .as("発行時点の登録番号が receipts 行へ複製されること")
                .isEqualTo("T1234567890123");
        assertThat(receipt.getIssuerName()).isEqualTo("適格な発行者");
    }

    // ───────────────────────────── AC-4 ─────────────────────────────

    @Test
    @WithMockUser(username = "920142001")
    @DisplayName("AC-4: 適格OFF で保存した後に発行すると、登録番号スナップショットが空で非適格事業者扱いになる")
    void ac4_issueAfterDisablingQualifiedInvoicer_snapshotHasNoRegistrationNumber() throws Exception {
        saveSettings("{\"issuerName\":\"非適格の発行者\",\"isQualifiedInvoicer\":false}");

        Long receiptId = issueReceipt("月会費として");

        ReceiptEntity receipt = reloadReceipt(receiptId);
        assertThat(receipt.getIsQualifiedInvoice())
                .as("非適格事業者として発行されること（PDF の注記切り替えはこの値から導出される）")
                .isFalse();
        assertThat(receipt.getInvoiceRegistrationNumber())
                .as("非適格なら登録番号は複製されないこと")
                .isNull();
    }

    // ───────────────────────────── AC-16 ─────────────────────────────

    @Test
    @WithMockUser(username = "920142001")
    @DisplayName("AC-16: 発行後に登録番号を変更しても、先に発行した receipts 行の登録番号は変わらない")
    void ac16_changingRegistrationNumberAfterIssuance_doesNotAlterIssuedReceipt() throws Exception {
        saveSettings("{\"issuerName\":\"発行者\",\"isQualifiedInvoicer\":true,"
                + "\"invoiceRegistrationNumber\":\"T1111111111111\"}");
        Long firstReceiptId = issueReceipt("変更前に発行");

        // 設定だけを後から変更する（1 項目のみの差分更新）。
        saveSettings("{\"invoiceRegistrationNumber\":\"T2222222222222\"}");

        assertThat(reloadReceipt(firstReceiptId).getInvoiceRegistrationNumber())
                .as("既発行の領収書は法令上変更できない。設定変更に追随してはならない")
                .isEqualTo("T1111111111111");

        // 変更後に発行した分だけが新しい番号になる。
        Long secondReceiptId = issueReceipt("変更後に発行");
        assertThat(reloadReceipt(secondReceiptId).getInvoiceRegistrationNumber())
                .isEqualTo("T2222222222222");
    }

    // ───────────────────────────── AC-17 ─────────────────────────────

    @Test
    @WithMockUser(username = "920142001")
    @DisplayName("AC-17: 適格ONで発行済みの領収書は、設定をOFFに変えてもスナップショットが変わらない")
    void ac17_disablingQualifiedAfterIssuance_doesNotAlterIssuedReceipt() throws Exception {
        saveSettings("{\"issuerName\":\"発行者\",\"isQualifiedInvoicer\":true,"
                + "\"invoiceRegistrationNumber\":\"T3333333333333\"}");
        Long issuedWhileQualified = issueReceipt("適格中に発行");

        // ON → OFF。登録番号も明示クリアする（§9.2 トグル挙動 5）。
        saveSettings("{\"isQualifiedInvoicer\":false,\"invoiceRegistrationNumber\":\"\"}");

        ReceiptEntity before = reloadReceipt(issuedWhileQualified);
        assertThat(before.getIsQualifiedInvoice())
                .as("既発行分の適格フラグは変わらないこと")
                .isTrue();
        assertThat(before.getInvoiceRegistrationNumber())
                .as("既発行分の登録番号は変わらないこと")
                .isEqualTo("T3333333333333");

        // 以後の発行分のみ非適格になる。
        ReceiptEntity after = reloadReceipt(issueReceipt("非適格化後に発行"));
        assertThat(after.getIsQualifiedInvoice()).isFalse();
        assertThat(after.getInvoiceRegistrationNumber()).isNull();
    }
}
