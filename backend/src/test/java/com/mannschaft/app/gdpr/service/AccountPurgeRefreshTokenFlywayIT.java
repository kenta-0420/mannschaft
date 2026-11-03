package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.dto.RequestWithdrawalRequest;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.service.UserService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * CMP-260822-1243: 失効済み refresh_tokens を持つ退会ユーザーの強匿名化が、<b>Flyway の実スキーマ</b>で
 * commit 後まで完走することを、実際の {@link AccountPurgeService#purgeExpiredAccounts()} で確かめる。
 *
 * <p>通常の IT は {@code ddl-auto=create} で Entity からスキーマを作り、{@code refresh_tokens.user_id} は
 * 単なる Long のため users への FK 自体が存在せず、欠陥（V1.003 の {@code fk_refresh_tokens_user} が
 * ON DELETE なしで users を参照し、失効済み行が残ると users 物理削除が FK 違反になる）を再現できない。
 * V256 が FK を ON DELETE CASCADE へ張り替えたことは、本 IT が実スキーマ上の purge 完走で証明する。</p>
 *
 * <p><b>専用クラスにした理由</b>: {@code FlywayFromScratchMigrationTest} は Spring コンテキストを起動しない
 * 素の JDBC 試験であり、{@link AccountPurgeService}（多数の依存を持つ Bean）を呼べない。
 * 実 Bean を通すには Flyway 有効の {@code @SpringBootTest} が要るため、先例
 * {@code PriceRevisionCancelFlywayIT} と同じ構成で専用コンテナを持つ。FK の形（CASCADE であること）自体の
 * 静的検査は {@code FlywayFromScratchMigrationTest} に置く。</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@EnabledIf("com.mannschaft.app.gdpr.service.AccountPurgeRefreshTokenFlywayIT#isDockerAvailable")
@DisplayName("強匿名化（Flyway 実スキーマ）: 失効済み refresh_tokens を持つ退会者が commit 後まで完走する")
class AccountPurgeRefreshTokenFlywayIT {

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_account_purge_refresh_token_flyway")
            .withUsername("test")
            .withPassword("test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--log_bin_trust_function_creators=1");

    static {
        if (isDockerAvailable()) {
            MYSQL.start();
        }
    }

    @MockitoBean
    private StringRedisTemplate redisTemplate;

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

    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private UserService userService;
    @Autowired private AccountPurgeService accountPurgeService;
    @PersistenceContext private EntityManager entityManager;

    @Test
    @DisplayName("失効済みと未失効の refresh_token を持つ退会者を実purgeで物理削除でき、別ユーザーのトークンは残る")
    void purgeCompletesWithRevokedRefreshTokensAndKeepsOtherUser() {
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        Long target = createUser("本人");
        Long other = createUser("別ユーザー");
        try {
            insertToken(target, "a", true);
            insertToken(target, "b", false);
            insertToken(other, "c", true);
            insertToken(other, "d", false);
            // 退会受付は本人の全トークンを失効させる（失効済み行が残る欠陥の入口）。
            userService.requestWithdrawal(target, new RequestWithdrawalRequest(null));
            assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = " + target + " AND revoked_at IS NULL"))
                    .as("退会受付後は本人の未失効トークンが無い").isZero();
            assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = " + target))
                    .as("失効済みの行は残っている").isEqualTo(2);
            transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                    "UPDATE users SET deleted_at = DATE_SUB(NOW(), INTERVAL 31 DAY) WHERE id = :id")
                    .setParameter("id", target).executeUpdate());

            accountPurgeService.purgeExpiredAccounts();

            await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
                assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + target))
                        .as("commit 後に退会者の users 行が物理削除されている").isZero();
                assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = " + target))
                        .as("退会者の refresh_tokens は0件").isZero();
                assertThat(count("SELECT COUNT(*) FROM users WHERE id = " + other))
                        .as("別ユーザーは残る").isEqualTo(1);
                assertThat(count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = " + other))
                        .as("別ユーザーの refresh_tokens は残る").isEqualTo(2);
            });
        } finally {
            transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                    "DELETE FROM users WHERE id IN (" + target + "," + other + ")").executeUpdate());
        }
    }

    private Long createUser(String name) {
        return transactionTemplate.execute(tx -> {
            UserEntity user = UserEntity.builder()
                    .email("cmp1243-fw-" + System.nanoTime() + "@example.com")
                    .lastName("試練").firstName(name).displayName(name)
                    .status(UserEntity.UserStatus.ACTIVE).locale("ja").timezone("Asia/Tokyo")
                    .isSearchable(true).build();
            entityManager.persist(user);
            entityManager.flush();
            return user.getId();
        });
    }

    private void insertToken(Long userId, String suffix, boolean revoked) {
        transactionTemplate.executeWithoutResult(tx -> entityManager.createNativeQuery(
                "INSERT INTO refresh_tokens (user_id,token_hash,remember_me,jti,expires_at,revoked_at,created_at)"
                        + " VALUES (" + userId + ",'cmp1243-fw-" + userId + suffix + "',0,'cmp1243-fw-" + userId + suffix
                        + "',DATE_ADD(NOW(), INTERVAL 7 DAY)," + (revoked ? "NOW()" : "NULL") + ",NOW())")
                .executeUpdate());
    }

    private long count(String sql) {
        return transactionTemplate.execute(tx -> ((Number) entityManager.createNativeQuery(sql)
                .getSingleResult()).longValue());
    }
}
