package com.mannschaft.app.receipt;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F08.12 §5.1 前提整備③「{@code pdf_storage_key} が一度も書かれない」および
 * §9「原本保存」の試練（red）。
 *
 * <p><strong>設計段階で摘出した実バグ:</strong>
 * {@code ReceiptEntity#updatePdfStorageKey()} の呼び出し元は<b>コード中に 0 件</b>である。
 * その結果、API が返す {@code pdfStatus} は
 * {@code receipt.getPdfStorageKey() != null ? "READY" : "GENERATING"} という導出であるため
 * <b>永久に {@code GENERATING} を返し続け</b>、PDF は取得のたびに再生成される。
 * 電子帳簿保存法の原本保存（同一の原本を返し続けること）が成立していない。
 *
 * <p>対応する受け入れ条件: AC-32 / AC-38 / AC-39。
 *
 * <p>外部境界（S3/R2）はローカルでは MinIO 相当のスタブに落ちるため、ここでは
 * <b>ストレージキーが実際に永続化されたか</b>と <b>API が導出ではなく列を返すか</b>を
 * 観測点に採る（設計書 AC-32 の文言どおり）。
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("F08.12 領収書 PDF 原本保存の契約テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReceiptPdfArchiveContractIT extends AbstractMySqlIntegrationTest {

    private static final String RECEIPTS_PATH = "/api/v1/admin/receipts";
    private static final String SETTINGS_PATH = "/api/v1/admin/receipt-settings";

    private static final Long ADMIN_USER = 920812201L;
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired private MockMvc mockMvc;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleRepository userRoleRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext private EntityManager em;

    private Long teamId;

    @BeforeEach
    void setUp() throws Exception {
        MembershipTestHelper.insertActiveUser(em, ADMIN_USER);
        Long adminRoleId = ensureRole("ADMIN", 2);

        teamId = teamRepository.save(TeamEntity.builder()
                .slug("receipt-pdf-archive-" + SLUG_SEQ.incrementAndGet())
                .name("領収書原本保存テストチーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(true)
                .build()).getId();

        userRoleRepository.save(UserRoleEntity.builder()
                .userId(ADMIN_USER).roleId(adminRoleId).teamId(teamId).build());

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

    private void saveIssuerSettings() throws Exception {
        mockMvc.perform(patch(SETTINGS_PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"issuerName\":\"テスト発行者\",\"isQualifiedInvoicer\":false}"))
                .andExpect(status().isOk());
        em.flush();
        em.clear();
    }

    private Long issueReceipt() throws Exception {
        String body = "{\"recipientName\":\"受領者太郎\",\"description\":\"広告掲載料として\","
                + "\"amount\":11000,\"taxRate\":10.00,\"paymentMethodLabel\":\"クレジットカード\"}";
        String json = mockMvc.perform(post(RECEIPTS_PATH)
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        em.flush();
        em.clear();
        return ((Number) JsonPath.read(json, "$.data.id")).longValue();
    }

    @Test
    @WithMockUser(username = "920812201")
    @DisplayName("AC-38 / AC-39: PDF を取得すると pdf_storage_key が永続化され、2 回目は再生成されない")
    void ac38and39_pdfStorageKeyIsPersistedAndReused() throws Exception {
        saveIssuerSettings();
        Long receiptId = issueReceipt();

        byte[] first = mockMvc.perform(get(RECEIPTS_PATH + "/" + receiptId + "/pdf")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        em.flush();
        em.clear();

        String storageKey = jdbcTemplate.queryForObject(
                "SELECT pdf_storage_key FROM receipts WHERE id = ?", String.class, receiptId);
        assertThat(storageKey)
                .as("updatePdfStorageKey() の呼び出し元が 0 件のため、現状は永久に NULL のままである")
                .isNotBlank();

        byte[] second = mockMvc.perform(get(RECEIPTS_PATH + "/" + receiptId + "/pdf")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(second)
                .as("原本保存の要件上、2 回目は保存済みの原本をそのまま返さねばならない"
                        + "（再生成すると生成時刻等でバイト列が変わりうる）")
                .isEqualTo(first);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT pdf_storage_key FROM receipts WHERE id = ?", String.class, receiptId))
                .as("2 回目の取得でキーが張り替わらないこと")
                .isEqualTo(storageKey);
    }

    @Test
    @WithMockUser(username = "920812201")
    @DisplayName("AC-32: API の pdfStatus は導出ではなく pdf_status 列を返す")
    void ac32_pdfStatusComesFromPersistedColumn() throws Exception {
        saveIssuerSettings();
        Long receiptId = issueReceipt();

        // pdf_status 列そのものが実在すること（導出をやめた証跡）
        Integer columnCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'receipts'
                   AND column_name = 'pdf_status'
                """, Integer.class);
        assertThat(columnCount)
                .as("現状 pdfStatus は pdf_storage_key の null 判定から導出されており、"
                        + "FAILED を表現できない")
                .isEqualTo(1);

        mockMvc.perform(get(RECEIPTS_PATH + "/" + receiptId + "/pdf")
                        .param("scopeType", "TEAM")
                        .param("scopeId", String.valueOf(teamId)))
                .andExpect(status().isOk());
        em.flush();
        em.clear();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT pdf_status FROM receipts WHERE id = ?", String.class, receiptId))
                .as("原本保存が成功したら READY が永続化されること")
                .isEqualTo("READY");
    }
}
