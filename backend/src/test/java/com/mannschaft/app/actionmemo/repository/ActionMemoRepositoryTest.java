package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F02.5 Phase 3 {@link ActionMemoRepository} カテゴリフィルタ系クエリの検証。
 *
 * <p>既存 {@code ActionMemoIntegrationTest} の流儀に倣い、{@link SpringBootTest} +
 * Testcontainers (MySQL) + {@code @EnabledIf} で Docker 利用可能時のみ実行する。
 * H2/HSQLDB は MySQL の方言依存（@SQLRestriction の挙動など）と乖離するため使用しない。</p>
 *
 * <p>検証対象クエリ（設計書 §10.1 Phase 3）:</p>
 * <ul>
 *   <li>{@code findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull} — カテゴリ + 未投稿のみ</li>
 *   <li>{@code findByUserIdAndCategoryWithCursor} — カテゴリでカーソルページネーション</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@EnabledIf("com.mannschaft.app.actionmemo.repository.ActionMemoRepositoryTest#isDockerAvailable")
@DisplayName("ActionMemoRepository Phase 3 クエリテスト")
class ActionMemoRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @Autowired
    private ActionMemoRepository memoRepository;

    private static final Long USER_ID = 9_001L;
    private static final Long OTHER_USER_ID = 9_002L;
    private static final Long TEAM_ID = 7_777L;

    @BeforeEach
    void cleanUp() {
        // テストごとに本ユーザーのメモを物理削除して独立性を担保。
        // FK は entity 側 @JoinColumn 不在のため Hibernate 自動 DDL では生成されない。
        memoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(USER_ID)
                .forEach(memoRepository::delete);
        memoRepository.findByUserIdOrderByMemoDateDescCreatedAtDesc(OTHER_USER_ID)
                .forEach(memoRepository::delete);
    }

    @Test
    @DisplayName("findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull: カテゴリ + 未投稿のみ抽出")
    void findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull_filtersByCategoryAndUnposted() {
        LocalDate today = LocalDate.now();

        // WORK 未投稿（対象）
        ActionMemoEntity workUnposted = persist(USER_ID, today, "work A",
                ActionMemoCategory.WORK, null);
        // WORK 投稿済（除外: postedTeamId NOT NULL）
        persist(USER_ID, today, "work B (already posted)", ActionMemoCategory.WORK, TEAM_ID);
        // PRIVATE 未投稿（除外: カテゴリ違い）
        persist(USER_ID, today, "private memo", ActionMemoCategory.PRIVATE, null);
        // 別日の WORK 未投稿（除外: 日付違い）
        persist(USER_ID, today.minusDays(1), "yesterday work", ActionMemoCategory.WORK, null);
        // 他ユーザーの WORK 未投稿（除外: ユーザー違い）
        persist(OTHER_USER_ID, today, "other user work", ActionMemoCategory.WORK, null);

        List<ActionMemoEntity> result = memoRepository
                .findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
                        USER_ID, today, ActionMemoCategory.WORK);

        assertThat(result)
                .extracting(ActionMemoEntity::getId)
                .containsExactly(workUnposted.getId());
        assertThat(result.get(0).getContent()).isEqualTo("work A");
        assertThat(result.get(0).getPostedTeamId()).isNull();
        assertThat(result.get(0).getCategory()).isEqualTo(ActionMemoCategory.WORK);
    }

    @Test
    @DisplayName("findByUserIdAndCategoryWithCursor: カテゴリでカーソルページネーション（同カテゴリのみ降順返却）")
    void findByUserIdAndCategoryWithCursor_paginatesByCategory() {
        LocalDate today = LocalDate.now();

        // WORK を 3 件、PRIVATE を 2 件。各 PRIVATE は除外されるべき。
        ActionMemoEntity work1 = persist(USER_ID, today, "work-1", ActionMemoCategory.WORK, null);
        persist(USER_ID, today, "private-1", ActionMemoCategory.PRIVATE, null);
        ActionMemoEntity work2 = persist(USER_ID, today, "work-2", ActionMemoCategory.WORK, null);
        persist(USER_ID, today, "private-2", ActionMemoCategory.PRIVATE, null);
        ActionMemoEntity work3 = persist(USER_ID, today, "work-3", ActionMemoCategory.WORK, null);

        Pageable pageable = PageRequest.of(0, 10);

        // cursorId = null → 全 WORK を id 降順で取得
        List<ActionMemoEntity> all = memoRepository.findByUserIdAndCategoryWithCursor(
                USER_ID, ActionMemoCategory.WORK, null, pageable);
        assertThat(all)
                .extracting(ActionMemoEntity::getId)
                .containsExactly(work3.getId(), work2.getId(), work1.getId());
        assertThat(all)
                .extracting(ActionMemoEntity::getCategory)
                .containsOnly(ActionMemoCategory.WORK);

        // cursorId = work3.id → work3 より古い (id < cursor) のみ返る
        List<ActionMemoEntity> older = memoRepository.findByUserIdAndCategoryWithCursor(
                USER_ID, ActionMemoCategory.WORK, work3.getId(), pageable);
        assertThat(older)
                .extracting(ActionMemoEntity::getId)
                .containsExactly(work2.getId(), work1.getId());

        // ページサイズ 1 で先頭1件のみ
        List<ActionMemoEntity> firstOnly = memoRepository.findByUserIdAndCategoryWithCursor(
                USER_ID, ActionMemoCategory.WORK, null, PageRequest.of(0, 1));
        assertThat(firstOnly)
                .extracting(ActionMemoEntity::getId)
                .containsExactly(work3.getId());
    }

    /**
     * テスト用ヘルパ: 行動メモを永続化する。
     */
    private ActionMemoEntity persist(Long userId,
                                     LocalDate memoDate,
                                     String content,
                                     ActionMemoCategory category,
                                     Long postedTeamId) {
        ActionMemoEntity entity = ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .category(category)
                .postedTeamId(postedTeamId)
                .completesTodo(false)
                .build();
        return memoRepository.saveAndFlush(entity);
    }
}
