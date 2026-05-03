package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProxyInputConsentRepository} の結合テスト。
 * カスタム @Query メソッド（findValidConsent・existsActiveConsent）を検証する。
 *
 * <p>Testcontainers で MySQL 8.0 を起動し、Spring Boot のフルコンテキストで実行する。
 * OOM 対策として ActionMemoIntegrationTest と同一の ApplicationContext を再利用できるよう
 * 同じ設定パターンを採用している。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("ProxyInputConsentRepository 結合テスト（カスタムクエリ）")
class ProxyInputConsentRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // Redis 関連 Bean をモック化（OOM 対策 — ActionMemoIntegrationTest と同パターン）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ProxyInputConsentRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SUBJECT_USER_ID = 100L;
    private static final Long PROXY_USER_ID = 200L;
    private static final Long ORG_ID = 10L;

    /**
     * 承認済み・有効期限内・revoke なしのアクティブ同意書を永続化して返す。
     */
    private ProxyInputConsentEntity persistActiveConsent(Long subjectUserId, Long proxyUserId) {
        ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                subjectUserId, proxyUserId, ORG_ID,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                null, null, null,
                LocalDate.now().minusDays(1),
                LocalDate.now().plusMonths(6));
        consent.approve(999L);
        em.persist(consent);
        em.flush();
        return consent;
    }

    @Nested
    @DisplayName("findValidConsent — 有効同意書の検索")
    class FindValidConsent {

        @Test
        @DisplayName("承認済み・有効期限内・revoke なし → 取得できる")
        void shouldReturnConsentWhenActive() {
            ProxyInputConsentEntity saved = persistActiveConsent(SUBJECT_USER_ID, PROXY_USER_ID);

            Optional<ProxyInputConsentEntity> result = repository.findValidConsent(
                    saved.getId(), PROXY_USER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getSubjectUserId()).isEqualTo(SUBJECT_USER_ID);
        }

        @Test
        @DisplayName("未承認（approvedAt = null）→ empty")
        void shouldReturnEmptyWhenNotApproved() {
            ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                    SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null, null, null,
                    LocalDate.now().minusDays(1), LocalDate.now().plusMonths(6));
            em.persist(consent);
            em.flush();

            Optional<ProxyInputConsentEntity> result = repository.findValidConsent(
                    consent.getId(), PROXY_USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("有効期限切れ（effectiveUntil < 今日）→ empty")
        void shouldReturnEmptyWhenExpired() {
            ProxyInputConsentEntity consent = ProxyInputConsentEntity.create(
                    SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null, null, null,
                    LocalDate.now().minusMonths(2),
                    LocalDate.now().minusDays(1));
            consent.approve(999L);
            em.persist(consent);
            em.flush();

            Optional<ProxyInputConsentEntity> result = repository.findValidConsent(
                    consent.getId(), PROXY_USER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("revokedAt あり → empty")
        void shouldReturnEmptyWhenRevoked() {
            ProxyInputConsentEntity consent = persistActiveConsent(SUBJECT_USER_ID, PROXY_USER_ID);
            consent.revoke(ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT, null, "本人希望");
            em.flush();

            Optional<ProxyInputConsentEntity> result = repository.findValidConsent(
                    consent.getId(), PROXY_USER_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("existsActiveConsent — 重複チェック")
    class ExistsActiveConsent {

        @Test
        @DisplayName("有効な同意書が存在する → true")
        void shouldReturnTrueWhenActiveConsentExists() {
            ProxyInputConsentEntity consent = persistActiveConsent(SUBJECT_USER_ID, PROXY_USER_ID);

            boolean result = repository.existsActiveConsent(
                    SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID, consent.getEffectiveFrom());

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("有効な同意書が存在しない → false")
        void shouldReturnFalseWhenNoActiveConsent() {
            boolean result = repository.existsActiveConsent(
                    SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID, LocalDate.now());

            assertThat(result).isFalse();
        }
    }
}
