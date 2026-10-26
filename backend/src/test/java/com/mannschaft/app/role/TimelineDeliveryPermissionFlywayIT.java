package com.mannschaft.app.role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.14 V190 が timeline 配信用権限と feature flag を正しく初期投入することを検証する。
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none"
})
@ActiveProfiles("test")
@Testcontainers
@Transactional
@EnabledIf("com.mannschaft.app.role.TimelineDeliveryPermissionFlywayIT#isDockerAvailable")
@DisplayName("F09.14 timeline 配信権限 Flyway 検証")
class TimelineDeliveryPermissionFlywayIT {

    private static final String SEND_PAID_TIMELINE = "SEND_PAID_TIMELINE";
    private static final String VIEW_TIMELINE_COST = "VIEW_TIMELINE_COST";
    private static final String FLAG = "F09_14_TIMELINE_PAID_DELIVERY_ENABLED";

    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_f0914_permission_flyway")
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

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("敏感権限2件は catalog に存在し ADMIN default のみ、DEPUTY 行はない")
    void sensitivePermissionsAreRegisteredForAdminOnly() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT p.name, r.name, rp.is_default "
                                + "FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE p.name IN (:names) ORDER BY p.name, r.name")
                .setParameter("names", List.of(SEND_PAID_TIMELINE, VIEW_TIMELINE_COST))
                .getResultList();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat((String) row[1]).isEqualTo("ADMIN");
            assertThat(toBool(row[2])).isTrue();
        });

        @SuppressWarnings("unchecked")
        List<Object> permissions = em.createNativeQuery(
                        "SELECT name FROM permissions WHERE name IN (:names) ORDER BY name")
                .setParameter("names", List.of(SEND_PAID_TIMELINE, VIEW_TIMELINE_COST))
                .getResultList();
        assertThat(permissions).containsExactly(SEND_PAID_TIMELINE, VIEW_TIMELINE_COST);

        @SuppressWarnings("unchecked")
        List<Object> deputyRows = em.createNativeQuery(
                        "SELECT rp.id FROM role_permissions rp "
                                + "JOIN roles r ON r.id = rp.role_id "
                                + "JOIN permissions p ON p.id = rp.permission_id "
                                + "WHERE r.name = 'DEPUTY_ADMIN' AND p.name IN (:names)")
                .setParameter("names", List.of(SEND_PAID_TIMELINE, VIEW_TIMELINE_COST))
                .getResultList();
        assertThat(deputyRows).isEmpty();
    }

    @Test
    @DisplayName("有料配信 feature flag は既定 OFF")
    void paidDeliveryFeatureFlagDefaultsOff() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT flag_key, is_enabled FROM feature_flags WHERE flag_key = :flag")
                .setParameter("flag", FLAG)
                .getResultList();
        assertThat(rows).hasSize(1);
        assertThat((String) rows.get(0)[0]).isEqualTo(FLAG);
        assertThat(toBool(rows.get(0)[1])).isFalse();
    }

    private static boolean toBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        throw new IllegalStateException("Unexpected boolean value: " + value);
    }
}
