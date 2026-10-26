package com.mannschaft.app.scopefolder.repository;

import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F15.2 {@link MyScopeFolderRepository} および {@link MyScopeFolderItemRepository} の統合テスト。
 *
 * <p>MySQL Testcontainers を使って実際の DB に対してクエリを検証する。</p>
 *
 * <p>注意: {@code @SpringBootTest} / {@code @Testcontainers} / {@code @ActiveProfiles} は
 * 親クラス {@link AbstractMySqlIntegrationTest} で既に宣言済みのため、再宣言しないこと。</p>
 */
@Transactional
@DisplayName("MyScopeFolderRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MyScopeFolderRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MyScopeFolderRepository folderRepository;

    @Autowired
    private MyScopeFolderItemRepository itemRepository;

    @PersistenceContext
    private EntityManager em;

    // ────────────────────────────────────────────
    // テスト用定数
    // ────────────────────────────────────────────

    private static final Long USER_A = 1001L;
    private static final Long USER_B = 1002L;

    // ────────────────────────────────────────────
    // ヘルパー
    // ────────────────────────────────────────────

    /**
     * フォルダを永続化して 1 次キャッシュをクリアする。
     *
     * <p>flush + clear により Repository のクエリが DB を素通りで評価できるようにする。</p>
     */
    private MyScopeFolderEntity persistFolder(Long userId, ScopeType scopeType, String name, int sortOrder) {
        MyScopeFolderEntity entity = MyScopeFolderEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .name(name)
                .color("#FF0000")
                .sortOrder(sortOrder)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    /**
     * フォルダアイテムを永続化して 1 次キャッシュをクリアする。
     */
    private MyScopeFolderItemEntity persistItem(Long folderId, Long scopeId, int sortOrder) {
        MyScopeFolderItemEntity entity = MyScopeFolderItemEntity.builder()
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(sortOrder)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    // ════════════════════════════════════════════════
    // MyScopeFolderRepository
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("MyScopeFolderRepository")
    class FolderRepositoryTests {

        @Test
        @DisplayName("保存したフォルダを findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder で取得できる")
        void フォルダ保存_一覧取得できる() {
            persistFolder(USER_A, ScopeType.TEAM, "フォルダ1", 0);
            persistFolder(USER_A, ScopeType.TEAM, "フォルダ2", 1);

            List<MyScopeFolderEntity> result =
                    folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_A, ScopeType.TEAM);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getName()).isEqualTo("フォルダ1");
            assertThat(result.get(0).getSortOrder()).isEqualTo(0);
            assertThat(result.get(1).getName()).isEqualTo("フォルダ2");
            assertThat(result.get(1).getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("IDOR確認: findByIdAndUserIdAndDeletedAtIsNull — 他ユーザーのIDで検索すると空が返る")
        void findByIdAndUserId_他ユーザーIDで空が返る_IDOR確認() {
            MyScopeFolderEntity saved = persistFolder(USER_A, ScopeType.TEAM, "ユーザーAのフォルダ", 0);

            // USER_B で USER_A のフォルダを検索 → 空であること（IDOR防止）
            Optional<MyScopeFolderEntity> result =
                    folderRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), USER_B);

            assertThat(result)
                    .as("他ユーザーのフォルダは見えてはならない（IDOR防止）")
                    .isEmpty();
        }

        @Test
        @DisplayName("findByIdAndUserIdAndDeletedAtIsNull — 自分のIDなら取得できる")
        void findByIdAndUserId_自分のIDなら取得できる() {
            MyScopeFolderEntity saved = persistFolder(USER_A, ScopeType.TEAM, "自分のフォルダ", 0);

            Optional<MyScopeFolderEntity> result =
                    folderRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), USER_A);

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("自分のフォルダ");
        }

        @Test
        @DisplayName("existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull — 同名フォルダが存在するとtrueを返す")
        void existsByName_同名フォルダ存在でtrue() {
            persistFolder(USER_A, ScopeType.TEAM, "チームA", 0);

            boolean exists = folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(
                    USER_A, ScopeType.TEAM, "チームA");

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull — 異なる名前ならfalseを返す")
        void existsByName_異なる名前でfalse() {
            persistFolder(USER_A, ScopeType.TEAM, "チームA", 0);

            boolean exists = folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(
                    USER_A, ScopeType.TEAM, "チームB");

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("論理削除後は findByUserIdAndScopeTypeAndDeletedAtIsNull* に引っかからない")
        void 論理削除後は一覧に出ない() {
            MyScopeFolderEntity saved = persistFolder(USER_A, ScopeType.TEAM, "削除対象", 0);

            // 論理削除を実行
            MyScopeFolderEntity managed = em.find(MyScopeFolderEntity.class, saved.getId());
            managed.softDelete();
            em.flush();
            em.clear();

            // findByUserIdAndScopeType... では見えない
            List<MyScopeFolderEntity> list =
                    folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_A, ScopeType.TEAM);
            assertThat(list).isEmpty();

            // existsByName でも見えない
            boolean exists = folderRepository.existsByUserIdAndScopeTypeAndNameAndDeletedAtIsNull(
                    USER_A, ScopeType.TEAM, "削除対象");
            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("論理削除後は findByIdAndUserIdAndDeletedAtIsNull で見えない")
        void 論理削除後はfindByIdでも見えない() {
            MyScopeFolderEntity saved = persistFolder(USER_A, ScopeType.TEAM, "削除対象", 0);

            // 論理削除を実行
            MyScopeFolderEntity managed = em.find(MyScopeFolderEntity.class, saved.getId());
            managed.softDelete();
            em.flush();
            em.clear();

            Optional<MyScopeFolderEntity> result =
                    folderRepository.findByIdAndUserIdAndDeletedAtIsNull(saved.getId(), USER_A);
            assertThat(result).isEmpty();
        }
    }

    // ════════════════════════════════════════════════
    // MyScopeFolderItemRepository
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("MyScopeFolderItemRepository")
    class ItemRepositoryTests {

        @Test
        @DisplayName("アイテム保存 → findByFolderIdOrderBySortOrder でsortOrder昇順で取得できる")
        void アイテム保存_sortOrder昇順で取得できる() {
            MyScopeFolderEntity folder = persistFolder(USER_A, ScopeType.TEAM, "フォルダ", 0);
            Long folderId = folder.getId();

            persistItem(folderId, 200L, 1);
            persistItem(folderId, 100L, 0);

            List<MyScopeFolderItemEntity> items = itemRepository.findByFolderIdOrderBySortOrder(folderId);

            assertThat(items).hasSize(2);
            assertThat(items.get(0).getScopeId()).isEqualTo(100L);
            assertThat(items.get(0).getSortOrder()).isEqualTo(0);
            assertThat(items.get(1).getScopeId()).isEqualTo(200L);
            assertThat(items.get(1).getSortOrder()).isEqualTo(1);
        }

        @Test
        @DisplayName("existsByFolderIdAndScopeId — 存在するアイテムでtrue")
        void existsByFolderIdAndScopeId_存在するアイテムでtrue() {
            MyScopeFolderEntity folder = persistFolder(USER_A, ScopeType.TEAM, "フォルダ", 0);
            Long folderId = folder.getId();
            Long scopeId = 100L;

            persistItem(folderId, scopeId, 0);

            boolean exists = itemRepository.existsByFolderIdAndScopeId(folderId, scopeId);

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("existsByFolderIdAndScopeId — 存在しないアイテムでfalse")
        void existsByFolderIdAndScopeId_存在しないアイテムでfalse() {
            MyScopeFolderEntity folder = persistFolder(USER_A, ScopeType.TEAM, "フォルダ", 0);
            Long folderId = folder.getId();

            // アイテムを追加していない状態で確認
            boolean exists = itemRepository.existsByFolderIdAndScopeId(folderId, 999L);

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("findByFolderIdIn — 複数フォルダのアイテムを一括取得できる")
        void findByFolderIdIn_複数フォルダのアイテムを一括取得() {
            MyScopeFolderEntity folderA = persistFolder(USER_A, ScopeType.TEAM, "フォルダA", 0);
            MyScopeFolderEntity folderB = persistFolder(USER_A, ScopeType.TEAM, "フォルダB", 1);

            persistItem(folderA.getId(), 100L, 0);
            persistItem(folderB.getId(), 200L, 0);

            List<MyScopeFolderItemEntity> items =
                    itemRepository.findByFolderIdIn(List.of(folderA.getId(), folderB.getId()));

            assertThat(items).hasSize(2);
            assertThat(items.stream().map(MyScopeFolderItemEntity::getScopeId))
                    .containsExactlyInAnyOrder(100L, 200L);
        }
    }
}
