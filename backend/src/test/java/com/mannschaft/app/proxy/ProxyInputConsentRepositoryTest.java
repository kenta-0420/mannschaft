package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProxyInputConsentRepository} の結合テスト。
 * カスタム @Query メソッド（findValidConsent・existsActiveConsent）を検証する。
 *
 * <p>実行には Docker + MySQL 8.0 が必要（Testcontainers）。
 * CI 環境では自動的に起動する。ローカルでの単独実行は ./gradlew test --tests "*.ProxyInputConsentRepositoryTest"。</p>
 */
@DataJpaTest
@ActiveProfiles("test")
@DisplayName("ProxyInputConsentRepository 結合テスト（カスタムクエリ）")
class ProxyInputConsentRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ProxyInputConsentRepository repository;

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
        return em.persistAndFlush(consent);
    }

    @Nested
    @DisplayName("findValidConsent — 有効同意書の検索")
    class FindValidConsent {

        @Test
        @DisplayName("承認済み・有効期限内・revoke なし → 取得できる")
        void shouldReturnConsentWhenActive() {
            persistActiveConsent(SUBJECT_USER_ID, PROXY_USER_ID);

            Optional<ProxyInputConsentEntity> result = repository.findValidConsent(
                    getLastInsertedId(), PROXY_USER_ID);

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
            em.persistAndFlush(consent);

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
            em.persistAndFlush(consent);

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

    /**
     * TestEntityManager 経由で最後に永続化したエンティティの ID を取得するヘルパー。
     * より堅牢なテストでは戻り値の ID を直接使うこと。
     */
    private Long getLastInsertedId() {
        return (Long) em.getEntityManager()
                .createQuery("SELECT MAX(c.id) FROM ProxyInputConsentEntity c")
                .getSingleResult();
    }
}
