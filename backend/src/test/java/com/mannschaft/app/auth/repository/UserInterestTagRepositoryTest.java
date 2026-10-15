package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 AdSegmentEvaluator Phase A — {@link UserInterestTagRepository} 結合テスト。
 *
 * <p>特に {@link UserInterestTagRepository#findUserIdsByTagHashIn} の動作を検証する。
 * user_interest_tags テーブルへの保存・削除・INTEREST_TAG セグメント検索の基本動作を確認する。</p>
 *
 * <p><b>ユーザーフィクスチャについて</b>: {@code findUserIdsByTagHashIn}/{@code countUserIdsByTagHashIn}
 * は {@link UserEntity} と結合し {@code deletedAt IS NULL AND status = ACTIVE} のユーザーのみを返す
 * よう修正された（配信対象クエリの絞り込み統一）。そのため本テストは架空の ID ではなく実際に
 * {@link UserRepository} で永続化したユーザーの ID を使用する。ACTIVE 以外のステータス・
 * 論理削除済みユーザーを除外することを確認するケースも追加している。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("UserInterestTagRepository 結合テスト")
class UserInterestTagRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserInterestTagRepository repository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private Long USER_ID_1;
    private Long USER_ID_2;

    private Long persistUser(UserEntity.UserStatus status) {
        long n = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder()
                .email("interest-tag-" + n + "@example.com")
                .lastName("山田")
                .firstName("太郎")
                .displayName("user" + n)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(status)
                .isSearchable(true)
                .build();
        return userRepository.saveAndFlush(user).getId();
    }

    @BeforeEach
    void setUp() {
        USER_ID_1 = persistUser(UserEntity.UserStatus.ACTIVE);
        USER_ID_2 = persistUser(UserEntity.UserStatus.ACTIVE);

        // テストデータ投入
        repository.saveAndFlush(UserInterestTagEntity.create(
                USER_ID_1, "sports", encryptionService.hmac("sports")));
        repository.saveAndFlush(UserInterestTagEntity.create(
                USER_ID_1, "cooking", encryptionService.hmac("cooking")));
        repository.saveAndFlush(UserInterestTagEntity.create(
                USER_ID_2, "travel", encryptionService.hmac("travel")));
        repository.saveAndFlush(UserInterestTagEntity.create(
                USER_ID_2, "sports", encryptionService.hmac("sports")));
    }

    @Test
    @DisplayName("findByUserId: 指定ユーザーのタグ一覧を取得できる")
    void shouldFindByUserId() {
        List<UserInterestTagEntity> tags = repository.findByUserId(USER_ID_1);
        assertThat(tags).hasSize(2);
        assertThat(tags).extracting(UserInterestTagEntity::getTag)
                .containsExactlyInAnyOrder("sports", "cooking");
    }

    @Test
    @DisplayName("findUserIdsByTagHashIn: 単一タグで複数ユーザーを取得できる")
    void shouldFindUserIdsByTagHashIn_singleTag() {
        String sportsHash = encryptionService.hmac("sports");
        List<Long> userIds = repository.findUserIdsByTagHashIn(List.of(sportsHash));

        assertThat(userIds).hasSize(2);
        assertThat(userIds).containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
    }

    @Test
    @DisplayName("findUserIdsByTagHashIn: 複数タグで OR 検索できる（DISTINCT 適用）")
    void shouldFindUserIdsByTagHashIn_multiTags_distinct() {
        String sportsHash = encryptionService.hmac("sports");
        String cookingHash = encryptionService.hmac("cooking");
        // USER_ID_1 は sports と cooking 両方持つが DISTINCT で 1 件
        List<Long> userIds = repository.findUserIdsByTagHashIn(List.of(sportsHash, cookingHash));

        assertThat(userIds).hasSize(2);
        assertThat(userIds).containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
    }

    @Test
    @DisplayName("findUserIdsByTagHashIn: 一致なしのハッシュは空リストを返す")
    void shouldReturnEmptyWhenNoMatch() {
        String unknownHash = encryptionService.hmac("unknown_tag_xyz");
        List<Long> userIds = repository.findUserIdsByTagHashIn(List.of(unknownHash));

        assertThat(userIds).isEmpty();
    }

    @Test
    @DisplayName("deleteByUserId: 指定ユーザーのタグをすべて削除できる")
    void shouldDeleteByUserId() {
        repository.deleteByUserId(USER_ID_1);
        repository.flush();

        List<UserInterestTagEntity> remaining = repository.findByUserId(USER_ID_1);
        assertThat(remaining).isEmpty();

        // USER_ID_2 のタグは影響なし
        List<UserInterestTagEntity> user2Tags = repository.findByUserId(USER_ID_2);
        assertThat(user2Tags).hasSize(2);
    }

    @Test
    @DisplayName("findUserIdsByTagHashIn: status が ACTIVE 以外のユーザーは除外され、find/count 件数が一致する")
    void shouldExcludeNonActiveUser() {
        Long frozenUserId = persistUser(UserEntity.UserStatus.FROZEN);
        repository.saveAndFlush(UserInterestTagEntity.create(
                frozenUserId, "sports", encryptionService.hmac("sports")));

        String sportsHash = encryptionService.hmac("sports");
        List<Long> found = repository.findUserIdsByTagHashIn(List.of(sportsHash));
        long count = repository.countUserIdsByTagHashIn(List.of(sportsHash));

        assertThat(found).containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
        assertThat(found).doesNotContain(frozenUserId);
        assertThat(count).isEqualTo(found.size());
    }

    @Test
    @DisplayName("findUserIdsByTagHashIn: 論理削除済みユーザーは除外され、find/count 件数が一致する")
    void shouldExcludeDeletedUser() {
        Long deletedUserId = persistUser(UserEntity.UserStatus.ACTIVE);
        repository.saveAndFlush(UserInterestTagEntity.create(
                deletedUserId, "sports", encryptionService.hmac("sports")));
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), deletedUserId);

        String sportsHash = encryptionService.hmac("sports");
        List<Long> found = repository.findUserIdsByTagHashIn(List.of(sportsHash));
        long count = repository.countUserIdsByTagHashIn(List.of(sportsHash));

        assertThat(found).containsExactlyInAnyOrder(USER_ID_1, USER_ID_2);
        assertThat(found).doesNotContain(deletedUserId);
        assertThat(count).isEqualTo(found.size());
    }
}
