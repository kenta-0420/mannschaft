package com.mannschaft.app.auth.entity;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.9 年齢確認・保護者同意機能:
 * {@link ParentalConsentLinkEntity} および {@link ParentalConsentLinkRepository} の結合テスト。
 *
 * <p>Testcontainers（MySQL 8.0）を使用し、Flyway マイグレーションを実行した
 * 実際のテーブル構造に対してリポジトリ操作を検証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ParentalConsentLinkEntity 結合テスト")
class ParentalConsentLinkEntityTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ParentalConsentLinkRepository repository;

    /**
     * テスト用エンティティを組み立てるヘルパー。
     *
     * @param childUserId 子ユーザー ID
     * @param parentEmail 保護者メールアドレス
     * @param tokenHash   トークンハッシュ（一意にすること）
     * @return 保存用エンティティ（PENDING, 1時間後期限）
     */
    private ParentalConsentLinkEntity buildLink(Long childUserId, String parentEmail, String tokenHash) {
        return ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentEmail(parentEmail)
                .tokenHash(tokenHash)
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }

    // -------------------------------------------------------------------
    // findByChildUserId
    // -------------------------------------------------------------------

    @Test
    @DisplayName("findByChildUserId_returnsLinks: childUserId で同意リンクを検索できる")
    void findByChildUserId_returnsLinks() {
        // given
        repository.saveAndFlush(buildLink(101L, "parent1@example.com", "hash-101-a"));
        repository.saveAndFlush(buildLink(101L, "parent2@example.com", "hash-101-b"));
        repository.saveAndFlush(buildLink(999L, "other@example.com",   "hash-999-a")); // 別ユーザー

        // when
        List<ParentalConsentLinkEntity> results = repository.findByChildUserId(101L);

        // then: childUserId=101 の 2 件だけ返る
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.getChildUserId().equals(101L));
    }

    // -------------------------------------------------------------------
    // findByTokenHash
    // -------------------------------------------------------------------

    @Test
    @DisplayName("findByTokenHash_returnsLink: tokenHash でピンポイントに検索できる")
    void findByTokenHash_returnsLink() {
        // given
        String targetHash = "unique-token-hash-abc123";
        repository.saveAndFlush(buildLink(200L, "parent@example.com", targetHash));
        repository.saveAndFlush(buildLink(200L, "other@example.com", "other-hash-xyz"));

        // when
        Optional<ParentalConsentLinkEntity> result = repository.findByTokenHash(targetHash);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getTokenHash()).isEqualTo(targetHash);
        assertThat(result.get().getChildUserId()).isEqualTo(200L);
    }

    // -------------------------------------------------------------------
    // countByChildUserIdAndStatus
    // -------------------------------------------------------------------

    @Test
    @DisplayName("countByChildUserIdAndStatus_countsPending: PENDING 件数が正確にカウントされる")
    void countByChildUserIdAndStatus_countsPending() {
        // given: childUserId=300 に PENDING 2件・APPROVED 1件を保存
        repository.saveAndFlush(buildLink(300L, "a@example.com", "hash-300-a"));
        repository.saveAndFlush(buildLink(300L, "b@example.com", "hash-300-b"));
        ParentalConsentLinkEntity link3 = repository.saveAndFlush(
                buildLink(300L, "c@example.com", "hash-300-c"));

        // APPROVED に変更して再保存
        link3.approve(50L);
        repository.saveAndFlush(link3);

        // when
        long pendingCount  = repository.countByChildUserIdAndStatus(300L, ParentalConsentLinkStatus.PENDING);
        long approvedCount = repository.countByChildUserIdAndStatus(300L, ParentalConsentLinkStatus.APPROVED);

        // then
        assertThat(pendingCount).isEqualTo(2);
        assertThat(approvedCount).isEqualTo(1);
    }

    // -------------------------------------------------------------------
    // findByStatusAndExpiresAtBefore
    // -------------------------------------------------------------------

    @Test
    @DisplayName("findByStatusAndExpiresAtBefore_returnsExpired: 期限切れ PENDING リンクを取得できる")
    void findByStatusAndExpiresAtBefore_returnsExpired() {
        // given: 既に期限切れのリンクと有効なリンク
        ParentalConsentLinkEntity expired = ParentalConsentLinkEntity.builder()
                .childUserId(400L)
                .parentEmail("expired@example.com")
                .tokenHash("hash-expired-001")
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusDays(1)) // 昨日期限切れ
                .build();

        ParentalConsentLinkEntity valid = ParentalConsentLinkEntity.builder()
                .childUserId(400L)
                .parentEmail("valid@example.com")
                .tokenHash("hash-valid-001")
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().plusHours(1)) // まだ有効
                .build();

        repository.saveAndFlush(expired);
        repository.saveAndFlush(valid);

        // when: 現在時刻より前に期限切れになった PENDING を取得
        List<ParentalConsentLinkEntity> results = repository.findByStatusAndExpiresAtBefore(
                ParentalConsentLinkStatus.PENDING, LocalDateTime.now());

        // then: 期限切れの 1 件だけ返る
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTokenHash()).isEqualTo("hash-expired-001");
    }

    // -------------------------------------------------------------------
    // approve ビジネスメソッド
    // -------------------------------------------------------------------

    @Test
    @DisplayName("approve_setsStatusAndParentUserId: approve() が status/parentUserId/approvedAt を正しく設定する")
    void approve_setsStatusAndParentUserId() {
        // given
        ParentalConsentLinkEntity link = repository.saveAndFlush(
                buildLink(500L, "parent@example.com", "hash-500-a"));
        assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.PENDING);

        // when
        link.approve(99L);
        repository.saveAndFlush(link);

        // then: 再取得して確認
        ParentalConsentLinkEntity reloaded = repository.findById(link.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
        assertThat(reloaded.getParentUserId()).isEqualTo(99L);
        assertThat(reloaded.getApprovedAt()).isNotNull();
    }
}
