package com.mannschaft.app.template;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * モジュールカタログ Wave3（{@code V157.__amend_module_descriptions_wave3.sql}）の
 * description 追補内容を検証する契約 IT（試練 / red 先行）。
 *
 * <h2>なぜ {@code AbstractMySqlIntegrationTest}（@SpringBootTest）を使わないのか</h2>
 * <p>本プロジェクトの通常統合テスト環境（{@code src/test/resources/application-test.yml}）は
 * {@code spring.jpa.hibernate.ddl-auto=create} + {@code spring.flyway.enabled=false} で動作する。
 * すなわちテスト DB スキーマは Entity から生成され、<b>Flyway マイグレーション（本 UPDATE 含む）は
 * 一切実行されない</b>。したがって注入 Repository で追補後の description を検証しようとしても
 * 元の description のままであり、常に赤（誤検知）になる
 * （メモリ {@code feedback_test_profile_ddl_create_skips_flyway_seed} の罠）。</p>
 *
 * <p>V157 を実際に適用して検証するには、{@code FlywayFromScratchMigrationTest} と同じく
 * Spring コンテキストを起動せず Testcontainers の実 MySQL に対して {@link Flyway} を
 * Java API で直接実行し、適用後の行を JDBC で検証するのが唯一の正しい方法である。
 * 本テストはその金型（{@code ModuleCatalogWave2SeedIT}）に倣う。</p>
 *
 * <h2>red 先行性</h2>
 * <p>V157 を適用しなければ、下記6モジュールの description には追記トークンが一切含まれず、
 * 下記アサーションはすべて赤になる。V157 適用後に緑化する（試練→出陣）。
 * なお本 Wave3 は新規 seed 行を追加しない（既存6モジュールの UPDATE のみ）。</p>
 *
 * <h2>検証内容（軍議 受け入れ条件）</h2>
 * <p>既存6モジュール（timeline / member_intro / payment / voting / safety_check / knowledge_base）の
 * description が、吸収された B群機能（digest/skill/receipt/proxyvote/residencestatus/faq）を示す
 * 追記トークンを含むこと。</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.mannschaft.app.template.ModuleCatalogWave3DescriptionIT#isDockerAvailable")
@DisplayName("モジュールカタログ Wave3 description 追補 契約IT（試練・V157）")
class ModuleCatalogWave3DescriptionIT {

    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_wave3desc")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
            // FlywayFromScratchMigrationTest と同条件（CREATE TRIGGER を含むマイグレーションのため）。
            .withCommand("--log_bin_trust_function_creators=1");

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    void migrate() {
        MYSQL.start();
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .outOfOrder(false)
                .load()
                .migrate();
    }

    @AfterAll
    void stop() {
        MYSQL.stop();
    }

    private Connection conn() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private String descriptionOf(String slug) throws Exception {
        try (Connection c = conn();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT description FROM module_definitions WHERE slug = ?")) {
            ps.setString(1, slug);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("slug=%s の行が存在すること", slug).isTrue();
                return rs.getString("description");
            }
        }
    }

    @Nested
    @DisplayName("既存6モジュールの description 追補")
    class DescriptionAmendment {

        @Test
        @DisplayName("timeline の description に「ダイジェスト生成を含む」が追記されている")
        void timelineにダイジェスト追記() throws Exception {
            assertThat(descriptionOf("timeline")).contains("ダイジェスト生成を含む");
        }

        @Test
        @DisplayName("member_intro の description に「スキル紹介を含む」が追記されている")
        void member_introにスキル紹介追記() throws Exception {
            assertThat(descriptionOf("member_intro")).contains("スキル紹介を含む");
        }

        @Test
        @DisplayName("payment の description に「領収書発行を含む」が追記されている")
        void paymentに領収書追記() throws Exception {
            assertThat(descriptionOf("payment")).contains("領収書発行を含む");
        }

        @Test
        @DisplayName("voting の description に「代理投票・委任状を含む」が追記されている")
        void votingに代理投票追記() throws Exception {
            assertThat(descriptionOf("voting")).contains("代理投票・委任状を含む");
        }

        @Test
        @DisplayName("safety_check の description に「平時の居住状況確認」が追記されている")
        void safety_checkに平時居住状況追記() throws Exception {
            assertThat(descriptionOf("safety_check")).contains("平時の居住状況確認");
        }

        @Test
        @DisplayName("knowledge_base の description に「FAQ」が追記されている")
        void knowledge_baseにFAQ追記() throws Exception {
            assertThat(descriptionOf("knowledge_base")).contains("FAQ");
        }
    }
}
