package com.mannschaft.app.receipt;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F08.12 §3.1「発行の重複防止」— 生成列 + UNIQUE が<strong>実在すること</strong>を
 * 実 MySQL 上で直接観測する試練（red）。
 *
 * <p>「重複しない」だけでは観測できないため、設計書は
 * <b>DB 制約が働いたことを直接観測する AC</b> を分けて置いている。本クラスはそれに対応する。
 * ArgumentCaptor やアプリ層の {@code exists} 検査では TOCTOU を塞げないため、
 * ここでは<strong>生の SQL で実際に 2 行目を INSERT して 1062 を受ける</strong>ことを検証する。
 *
 * <p>対応する受け入れ条件: AC-04 / AC-78 / AC-82 / AC-83 / AC-84 / AC-85 / AC-86。
 *
 * <h2>実装者への注意（試練から出陣への申し送り）</h2>
 * <p>統合テストのスキーマは {@code application-test.yml} で
 * <b>{@code spring.jpa.hibernate.ddl-auto: create} かつ {@code flyway.enabled: false}</b> である。
 * すなわち<strong>スキーマは Flyway ではなく Entity から生成される</strong>。
 * したがって Flyway マイグレーションを書くだけでは本クラスは緑にならない。
 * {@code ReceiptEntity} 側にも生成列と UNIQUE を宣言する必要がある。例:
 * <pre>{@code
 * @Table(name = "receipts", uniqueConstraints = @UniqueConstraint(
 *         name = "uq_r_active_platform_source", columnNames = "active_platform_source_key"))
 * ...
 * @Column(name = "active_platform_source_key", insertable = false, updatable = false,
 *         columnDefinition = "VARCHAR(110) GENERATED ALWAYS AS (...) STORED")
 * private String activePlatformSourceKey;
 * }</pre>
 * 本番 DDL（Flyway）と Entity の宣言が食い違うと、CI は緑なのに本番で重複が通る、という
 * 最悪の偽陰性になる。両者が一致していることを必ず確認すること。
 */
@Transactional
@DisplayName("F08.12 運営領収書の重複防止（生成列 + UNIQUE）統合テスト（試練・実装前 red）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PlatformReceiptDuplicateGuardIT extends AbstractMySqlIntegrationTest {

    private static final Long ADMIN_USER = 920812001L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager em;

    @BeforeEach
    void setUp() {
        MembershipTestHelper.insertActiveUser(em, ADMIN_USER);
        em.flush();
        em.clear();
        jdbcTemplate.update("DELETE FROM receipts WHERE issued_by = ?", ADMIN_USER);
    }

    /**
     * 領収書行を生 SQL で INSERT する。生成列は指定しない（DB が計算する）。
     *
     * @return 生成された領収書 ID
     */
    private Long insertReceipt(String scopeType, long scopeId, String receiptNumber,
                               String sourceType, String sourceRef, boolean voided) {
        jdbcTemplate.update("""
                INSERT INTO receipts
                  (scope_type, scope_id, status, receipt_number, recipient_name, issuer_name,
                   is_qualified_invoice, description, amount, tax_rate, tax_amount, amount_excl_tax,
                   payment_date, issued_at, issued_by, encryption_key_version,
                   source_type, source_ref, voided_at, voided_by, created_at, updated_at)
                VALUES (?, ?, 'ISSUED', ?, ?, ?, 0, ?, 11000, 10.00, 1000, 10000,
                        CURRENT_DATE, NOW(), ?, 1, ?, ?, ?, ?, NOW(), NOW())
                """,
                scopeType, scopeId, receiptNumber, "宛名テスト", "運営事務局", "広告掲載料として",
                ADMIN_USER, sourceType, sourceRef,
                voided ? java.sql.Timestamp.from(java.time.Instant.now()) : null,
                voided ? ADMIN_USER : null);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    @Test
    @DisplayName("AC-78: 有効な PLATFORM 領収書と同一 (source_type, source_ref) の直接 INSERT が 1062 で拒否される")
    void ac78_duplicateActivePlatformSourceIsRejectedByUniqueKey() {
        insertReceipt("PLATFORM", 0L, "PR-2026-09-00001", "AD_INVOICE", "12345", false);

        assertThatThrownBy(() ->
                insertReceipt("PLATFORM", 0L, "PR-2026-09-00002", "AD_INVOICE", "12345", false))
                .as("uq_r_active_platform_source が実在しなければ 2 通目が静かに作られ、"
                        + "金銭の証憑が二重に出る事故になる")
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_r_active_platform_source");
    }

    @Test
    @DisplayName("AC-85: 有効な PLATFORM 領収書を void すると生成列が NULL に再計算される")
    void ac85_voidingRecalculatesGeneratedColumnToNull() {
        Long id = insertReceipt("PLATFORM", 0L, "PR-2026-09-00010", "AD_INVOICE", "22222", false);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_platform_source_key FROM receipts WHERE id = ?", String.class, id))
                .as("有効な PLATFORM 行はキーが値を持つ")
                .isNotNull();

        jdbcTemplate.update("UPDATE receipts SET voided_at = NOW(), voided_by = ? WHERE id = ?",
                ADMIN_USER, id);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT active_platform_source_key FROM receipts WHERE id = ?", String.class, id))
                .as("STORED 生成列が UPDATE 時に再評価され NULL になること")
                .isNull();
    }

    @Test
    @DisplayName("AC-82 / AC-83: void 後は同一 source で何通でも再発行できる")
    void ac82and83_reissueAfterVoidSucceedsRepeatedly() {
        Long first = insertReceipt("PLATFORM", 0L, "PR-2026-09-00020", "AD_INVOICE", "33333", false);
        jdbcTemplate.update("UPDATE receipts SET voided_at = NOW(), voided_by = ? WHERE id = ?",
                ADMIN_USER, first);

        Long second = assertReissueSucceeds("PR-2026-09-00021", "33333");
        jdbcTemplate.update("UPDATE receipts SET voided_at = NOW(), voided_by = ? WHERE id = ?",
                ADMIN_USER, second);

        assertReissueSucceeds("PR-2026-09-00022", "33333");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM receipts WHERE source_type = 'AD_INVOICE' AND source_ref = '33333'",
                Integer.class))
                .as("無効な行は何通でも並ぶ")
                .isEqualTo(3);
    }

    private Long assertReissueSucceeds(String receiptNumber, String sourceRef) {
        final Long[] holder = new Long[1];
        assertThatCode(() ->
                holder[0] = insertReceipt("PLATFORM", 0L, receiptNumber, "AD_INVOICE", sourceRef, false))
                .as("無効化された行はキーが NULL のため制約に掛からない")
                .doesNotThrowAnyException();
        return holder[0];
    }

    @Test
    @DisplayName("AC-84: 団体（TEAM）は同一 source で 2 通発行できる（F08.4 の分割領収書を壊さない）")
    void ac84_teamScopeIsUnaffectedByThePlatformUniqueKey() {
        assertThatCode(() -> {
            insertReceipt("TEAM", 1L, "R-2026-09-00001", "MEMBER_PAYMENT", "44444", false);
            insertReceipt("TEAM", 1L, "R-2026-09-00002", "MEMBER_PAYMENT", "44444", false);
        }).as("生成列は scope_type='PLATFORM' 以外では常に NULL でなければならない")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-04: PLATFORM の領収書は receipt_number が異なれば 2 件入る（uq_r_receipt_number は 3 列複合）")
    void ac04_platformReceiptsWithDistinctNumbersCoexist() {
        assertThatCode(() -> {
            insertReceipt("PLATFORM", 0L, "PR-2026-09-00030", "AD_INVOICE", "55551", false);
            insertReceipt("PLATFORM", 0L, "PR-2026-09-00031", "AD_INVOICE", "55552", false);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-78(スキーマ): 生成列 active_platform_source_key と UNIQUE 索引が実在する")
    void ac78_schemaObjectsExist() {
        List<String> generated = jdbcTemplate.queryForList("""
                SELECT extra FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'receipts'
                   AND column_name = 'active_platform_source_key'
                """, String.class);
        assertThat(generated)
                .as("生成列が存在すること")
                .hasSize(1);
        assertThat(generated.get(0))
                .as("STORED GENERATED であること（VIRTUAL では索引の再計算挙動が変わる）")
                .containsIgnoringCase("STORED GENERATED");

        Integer unique = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'receipts'
                   AND index_name = 'uq_r_active_platform_source'
                   AND non_unique = 0
                """, Integer.class);
        assertThat(unique).as("UNIQUE 索引が実在すること").isEqualTo(1);

        Integer sourceIdx = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                 WHERE table_schema = DATABASE()
                   AND table_name = 'receipts'
                   AND index_name = 'idx_r_source'
                """, Integer.class);
        assertThat(sourceIdx).as("AC-46: idx_r_source (source_type, source_ref) が実在すること")
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("AC-86: source_type / source_ref が NULL の PLATFORM 領収書は作れない")
    void ac86_platformReceiptRequiresSource() {
        assertThatThrownBy(() ->
                insertReceipt("PLATFORM", 0L, "PR-2026-09-00040", null, null, false))
                .as("取引先検索の穴を塞ぐため、運営領収書は source を必ず持つ")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
