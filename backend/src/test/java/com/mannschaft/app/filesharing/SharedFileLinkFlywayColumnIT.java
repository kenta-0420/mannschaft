package com.mannschaft.app.filesharing;

import com.mannschaft.app.filesharing.entity.SharedFileLinkEntity;
import com.mannschaft.app.filesharing.repository.SharedFileLinkRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SharedFileLinkEntity} の {@code is_active} / {@code download_allowed} 列名マッピングを
 * <b>実 Flyway スキーマ</b>に対して検証する再発防止テスト。
 *
 * <h2>なぜ専用クラスが必要か</h2>
 * <p>通常の統合テスト基底クラス {@code AbstractMySqlIntegrationTest} は
 * {@code @ActiveProfiles("test")} により {@code spring.flyway.enabled=false} /
 * {@code spring.jpa.hibernate.ddl-auto=create} で動作する。
 * {@code ddl-auto=create} は Entity アノテーションに基づいてテーブルを生成するため、
 * {@code @Column(name=...)} の有無にかかわらず「Hibernate が生成した列名」で
 * 自己整合してしまい、Flyway 実列名との不一致を検出できない（既存の
 * {@code PublicFileLinkContractIT} がこの落とし穴に該当する）。</p>
 *
 * <p>本クラスは {@code @SpringBootTest(properties=...)} でクラス単位に
 * {@code spring.flyway.enabled=true} / {@code spring.jpa.hibernate.ddl-auto=none} を
 * 上書きし、実 Flyway スキーマ（V5.026 + V136.001）を適用した MySQL に対してテストを実行する。</p>
 *
 * <h2>検証する列名マッピング</h2>
 * <ul>
 *   <li>{@code active} フィールド → {@code @Column(name="is_active")} → DB 列 {@code is_active}（V136.001）</li>
 *   <li>{@code downloadAllowed} フィールド → Hibernate camelCase 変換 → DB 列 {@code download_allowed}（V136.001）</li>
 * </ul>
 *
 * <h2>根治した重大バグ（実機E2E発見・2026-07-02）</h2>
 * <p>{@code @Column(name="is_active")} が欠落していると、Hibernate は {@code active} という列名で
 * INSERT/SELECT を発行する。しかし Flyway V136.001 が追加する列名は {@code is_active} のため、
 * 実 DB（Flyway 適用済み）で {@code Unknown column 'shared_file_links.active'} が発生し、
 * 公開リンクの作成・アクセス・DL が全件 500 エラーになる。</p>
 *
 * <p>既存の {@code PublicFileLinkContractIT} は {@code ddl-auto=create} を使うため、
 * Entity から {@code active} 列を生成してしまい、この不一致を CI では検出できなかった。</p>
 *
 * <h2>ネイティブ SQL による列名の直接検証</h2>
 * <p>JPA 往復（save → findByToken）だけでは「JPA が誤った列名でクエリを発行し、
 * その同じ誤列名で読み込む」という両側ミスマッチを見逃す余地がある。
 * そのため本テストでは {@link EntityManager#createNativeQuery} による
 * {@code SELECT is_active, download_allowed FROM shared_file_links} で
 * DB の実列名を直接指定し、JPA が正しい列名を使っていることを確認する。</p>
 *
 * <h2>@Column(name) を外すと何が起きるか</h2>
 * <p>{@code active} フィールドから {@code @Column(name="is_active")} を外すと、
 * Hibernate は {@code active} という誤列名で INSERT を発行し、
 * {@code Unknown column 'active' in 'field list'} の
 * {@link org.springframework.dao.DataIntegrityViolationException} が発生して本テストが FAIL する。
 * これにより再発を恒久的に検知する。</p>
 *
 * <h2>ApplicationContext 分離について</h2>
 * <p>本クラスは {@code @SpringBootTest(properties=...)} でプロパティを上書きするため、
 * {@code AbstractMySqlIntegrationTest} とは別の ApplicationContext が生成される（TestContext Cache 分裂）。
 * OOM 回避のため本クラスを不必要に増やさないこと。
 * 手本: {@code ProxyInputConsentS3KeyFlywaySchemaTest}。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} によりスキップされる。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
// test プロファイル（Redis 無効化等の試験設定）を読み込んだ上で、上記 properties が
// flyway/ddl-auto のみを上書きする。欠落すると default プロファイルで context が組まれ CI 起動不能。
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.filesharing.SharedFileLinkFlywayColumnIT#isDockerAvailable")
@DisplayName("SharedFileLink is_active/download_allowed 列名マッピング再発防止テスト（Flyway 実スキーマ）")
class SharedFileLinkFlywayColumnIT {

    /**
     * Flyway スキーマ適用用 MySQL コンテナ。
     * AbstractMySqlIntegrationTest と同じ tmpfs 設定で WSL2 VHD 遅延を回避する。
     * {@code --log_bin_trust_function_creators=1} は FlywayFromScratchMigrationTest と同じ理由
     * （TRIGGER 作成権限）で必要。
     */
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_filesharing_flyway")
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
    private SharedFileLinkRepository linkRepository;

    @PersistenceContext
    private EntityManager em;

    /**
     * {@code is_active} / {@code download_allowed} 列名の JPA 往復確認と、
     * ネイティブ SQL による DB 実列名の直接検証。
     *
     * <p>テスト手順:</p>
     * <ol>
     *   <li>FK チェックを一時無効化（列名マッピング検証が目的のため）</li>
     *   <li>{@code active=false, downloadAllowed=true} の SharedFileLinkEntity を保存・flush</li>
     *   <li>1 次キャッシュをクリアして DB から再ロード（findByToken）</li>
     *   <li>JPA 往復で値が正しく取得できることを確認</li>
     *   <li>ネイティブ SQL で {@code is_active, download_allowed} の実列名を直接指定して取得・値確認</li>
     * </ol>
     *
     * <p>{@code @Column(name="is_active")} を外すと Hibernate が {@code active} 列に INSERT しようとし、
     * {@code Unknown column 'shared_file_links.active' in 'field list'} で本テストが FAIL する。</p>
     */
    @Test
    @DisplayName("is_active/download_allowed 列名が Flyway 実スキーマと一致する — JPA 往復 + ネイティブ SQL 列名直接確認")
    void isActiveAndDownloadAllowedColumnsMatchFlywaySchema() {
        // Flyway 実スキーマには shared_files, shared_folders への FK が存在する。
        // 本テストの対象は「列名マッピング」であり FK 整合は対象外のため、セッション限定で
        // FK チェックを無効化する（最小INSERT で前提行を組むのは NOT NULL 列が多く brittle。
        // ProxyInputConsentS3KeyFlywaySchemaTest と同じアプローチ）。
        em.createNativeQuery("SET FOREIGN_KEY_CHECKS = 0").executeUpdate();

        // given: is_active=false かつ downloadAllowed=true のリンクを作成
        // (active=false は deactivate()、downloadAllowed=true は builder で設定)
        String token = UUID.randomUUID().toString();
        SharedFileLinkEntity link = SharedFileLinkEntity.builder()
                .fileId(99999L)  // FK チェック無効のため存在しない file_id でよい
                .token(token)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .downloadAllowed(true)
                .accessCount(0)
                .createdBy(1L)
                .build();
        // active は @Builder.Default true だが deactivate() で false にする
        link.deactivate();  // active = false（is_active = false に対応）

        linkRepository.save(link);
        em.flush();
        em.clear();  // 1次キャッシュをクリアして DB から再ロードさせる

        // when: JPA 往復確認（findByToken）
        Optional<SharedFileLinkEntity> loaded = linkRepository.findByToken(token);

        // then-1: JPA 往復で is_active=false が正しく取得できること
        // (@Column(name="is_active") が欠落していると INSERT 段階で SQLSyntaxErrorException)
        assertThat(loaded)
                .as("保存した SharedFileLinkEntity が findByToken で取得できること").isPresent();
        assertThat(loaded.get().isInactiveOrExpired())
                .as("deactivate() 後に isInactiveOrExpired() が true になること（is_active=false）")
                .isTrue();
        assertThat(loaded.get().isDownloadAllowed())
                .as("downloadAllowed=true が JPA 往復で正しく取得できること（download_allowed 列名一致確認）")
                .isTrue();

        // and: ネイティブ SQL で DB の実列名（Flyway DDL 定義: V136.001）を直接指定して取得できること
        // → JPA が正しい列名でクエリを発行していることを確認（両側ミスマッチの見逃しを防ぐ）
        //   is_active / download_allowed の列名で SELECT できれば、Flyway スキーマが正しいことも確認できる
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT is_active, download_allowed FROM shared_file_links WHERE token = :token")
                .setParameter("token", token)
                .getResultList();

        assertThat(rows)
                .as("ネイティブクエリ（is_active, download_allowed 列名指定）で 1 件取得できること").hasSize(1);
        Object[] row = rows.get(0);
        // is_active=false を確認（MySQL の BOOLEAN は 0/1 で返る）
        assertThat(((Number) row[0]).intValue())
                .as("ネイティブ SELECT is_active の値が 0（false）であること")
                .isEqualTo(0);
        // download_allowed=true を確認
        assertThat(((Number) row[1]).intValue())
                .as("ネイティブ SELECT download_allowed の値が 1（true）であること")
                .isEqualTo(1);
    }
}
