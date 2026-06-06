package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProxyInputConsentEntity} の S3 キーフィールド列名マッピングを
 * <b>実 Flyway スキーマ</b>に対して検証する再発防止テスト。
 *
 * <h2>なぜ専用クラスが必要か</h2>
 * <p>通常の統合テスト基底クラス {@code AbstractMySqlIntegrationTest} は
 * {@code @ActiveProfiles("test")} により {@code spring.flyway.enabled=false} /
 * {@code spring.jpa.hibernate.ddl-auto=create} で動作する。
 * {@code ddl-auto=create} は Entity アノテーションに基づいてテーブルを生成するため、
 * {@code @Column(name=...)} の有無にかかわらず「Hibernate が生成した列名」で
 * 自己整合してしまい、Flyway 実列名との不一致を検出できない。</p>
 *
 * <p>本クラスは {@code @SpringBootTest(properties=...)} でクラス単位に
 * {@code spring.flyway.enabled=true} / {@code spring.jpa.hibernate.ddl-auto=none} を
 * 上書きし、実 Flyway スキーマ（{@code V18.010__create_proxy_input_consents_table.sql}）を
 * 適用した MySQL に対してテストを実行する。</p>
 *
 * <h2>ネイティブ SQL による列名の直接検証</h2>
 * <p>JPA 往復（em.persist → em.find）だけでは「JPA が誤った列名でクエリを発行し、
 * その同じ誤列名で読み込む」という両側ミスマッチを見逃す余地がある。
 * そのため本テストでは {@link jakarta.persistence.EntityManager#createNativeQuery} による
 * {@code SELECT scanned_document_s3_key, guardian_certificate_s3_key FROM proxy_input_consents}
 * で DB の実列名を直接指定し、JPA が正しい列名を使っていることを確認する。</p>
 *
 * <h2>@Column(name) を外すと何が起きるか</h2>
 * <p>{@code scannedDocumentS3Key} から {@code @Column(name="scanned_document_s3_key")} を外すと、
 * Hibernate は {@code scanned_documents3key} という誤列名でクエリを発行し、
 * {@code Unknown column 'xxx.scanned_documents3key'} の {@link org.springframework.dao.DataIntegrityViolationException}
 * が発生して本テストが FAIL する。これにより再発を恒久的に検知する。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 *
 * <h2>ApplicationContext 分離について</h2>
 * <p>本クラスは {@code @SpringBootTest(properties=...)} でプロパティを上書きするため、
 * {@code AbstractMySqlIntegrationTest} とは別の ApplicationContext が生成される。
 * OOM 回避のため本クラスを不必要に増やさないこと。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.proxy.ProxyInputConsentS3KeyFlywaySchemaTest#isDockerAvailable")
@DisplayName("ProxyInputConsent S3Key 列名マッピング再発防止テスト（Flyway 実スキーマ）")
class ProxyInputConsentS3KeyFlywaySchemaTest {

    /**
     * Flyway スキーマ適用用 MySQL コンテナ。
     * AbstractMySqlIntegrationTest と同じ tmpfs 設定で WSL2 VHD 遅延を回避する。
     * {@code --log_bin_trust_function_creators=1} は FlywayFromScratchMigrationTest と同じ理由
     * （TRIGGER 作成権限）で必要。
     */
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_s3key_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    /** Redis は外部依存のためモック化。 */
    @MockitoBean
    org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private ProxyInputConsentRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SUBJECT_USER_ID = 100L;
    private static final Long PROXY_USER_ID = 200L;
    private static final Long ORG_ID = 10L;

    /**
     * S3 キー列名の JPA 往復確認と、ネイティブ SQL による DB 実列名の直接検証。
     *
     * <p>テスト手順:</p>
     * <ol>
     *   <li>S3 キーを指定して {@code ProxyInputConsentEntity} を永続化・flush</li>
     *   <li>1 次キャッシュをクリアして JPA 往復確認（em.find）</li>
     *   <li>ネイティブ SQL（{@code SELECT scanned_document_s3_key, guardian_certificate_s3_key}）で
     *       DB の実列名を直接指定して値を取得し、JPA が正しい列名を使っていることを確認</li>
     * </ol>
     *
     * <p>{@code @Column(name="scanned_document_s3_key")} を外すと Hibernate が
     * {@code scanned_documents3key} でクエリを発行し、INSERT 段階で
     * {@link org.springframework.dao.DataIntegrityViolationException} が発生してテストが FAIL する。</p>
     */
    @Test
    @DisplayName("S3Key 列名が Flyway 実スキーマと一致する — JPA 往復 + ネイティブ SQL 列名直接確認")
    void s3KeyColumnsMatchFlywaySchema() {
        // given: S3 キーを持つ同意書を作成
        String scannedKey = "orgs/10/proxy-consents/scan-flyway-test.pdf";
        String guardianKey = "orgs/10/proxy-consents/guardian-flyway-test.pdf";

        ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID,
                ProxyInputConsentEntity.ConsentMethod.GUARDIAN_BY_COURT,
                scannedKey, guardianKey, null,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusMonths(6));
        consent.approve(999L);
        em.persist(consent);
        em.flush();
        em.clear(); // 1次キャッシュをクリアして DB から再ロードさせる

        // when: JPA 往復確認（em.find）
        ProxyInputConsentEntity loaded = em.find(ProxyInputConsentEntity.class, consent.getId());

        // then: JPA 往復で S3 キーが正しく取得できること
        assertThat(loaded)
                .as("保存した同意書が取得できること").isNotNull();
        assertThat(loaded.getScannedDocumentS3Key())
                .as("scannedDocumentS3Key が JPA 往復で正しく取得できること（@Column name 不一致なら INSERT 段階で SQLSyntaxErrorException）")
                .isEqualTo(scannedKey);
        assertThat(loaded.getGuardianCertificateS3Key())
                .as("guardianCertificateS3Key が JPA 往復で正しく取得できること（@Column name 不一致なら INSERT 段階で SQLSyntaxErrorException）")
                .isEqualTo(guardianKey);

        // and: ネイティブ SQL で DB の実列名（Flyway DDL 定義）を直接指定して取得できること
        // → JPA が正しい列名でクエリを発行していることを確認（両側ミスマッチの見逃しを防ぐ）
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT scanned_document_s3_key, guardian_certificate_s3_key"
                        + " FROM proxy_input_consents WHERE id = :id")
                .setParameter("id", consent.getId())
                .getResultList();

        assertThat(rows).as("ネイティブクエリで 1 件取得できること").hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0])
                .as("ネイティブ SELECT scanned_document_s3_key の値が一致すること")
                .isEqualTo(scannedKey);
        assertThat(row[1])
                .as("ネイティブ SELECT guardian_certificate_s3_key の値が一致すること")
                .isEqualTo(guardianKey);
    }
}
