package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.common.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 AdSegmentEvaluator Phase A — {@link UserInterestTagRepository} 結合テスト。
 *
 * <p>特に {@link UserInterestTagRepository#findUserIdsByTagHashIn} の動作を検証する。
 * user_interest_tags テーブルへの保存・削除・INTEREST_TAG セグメント検索の基本動作を確認する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("UserInterestTagRepository 結合テスト")
class UserInterestTagRepositoryTest {

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

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private UserInterestTagRepository repository;

    @Autowired
    private EncryptionService encryptionService;

    private static final Long USER_ID_1 = 1001L;
    private static final Long USER_ID_2 = 1002L;

    @BeforeEach
    void setUp() {
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
}
