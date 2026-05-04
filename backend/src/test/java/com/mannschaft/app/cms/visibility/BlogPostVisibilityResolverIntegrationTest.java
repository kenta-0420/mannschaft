package com.mannschaft.app.cms.visibility;

import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase B BlogPost — 実 MySQL に対する Repository クエリ結合テスト。
 *
 * <p>{@link BlogPostRepository#findVisibilityProjectionsByIdIn} の SQL 1 本での
 * 射影取得と、{@code @SQLRestriction} 配下では引けない論理削除済み行を
 * 「JPQL 内の {@code deletedAt IS NULL}」で除外する挙動を実 DB で検証する。
 *
 * <p>{@link BlogPostVisibilityResolver} 自体は Spring DI 解決には依存しないため、
 * Repository の挙動を実 DB で確認すれば Resolver 全体の正しさは担保される。
 *
 * <p>セットアップ方式: A-3b の {@code MembershipBatchQueryServiceIntegrationTest}
 * と同様、{@code AbstractMySqlIntegrationTest} を継承して TestContext Cache を共有する。
 * BlogPost は JPA Entity の save で投入する（NOT NULL 多数のため native INSERT より楽）。
 */
@Transactional
@DisplayName("BlogPostVisibilityResolver 結合テスト (Repository 射影)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogPostVisibilityResolverIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BlogPostRepository blogPostRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long TEAM_ID = 4242L;
    private static final Long ORG_ID = 8484L;
    private static final Long AUTHOR_ID = 100L;

    private Long publishedTeamPostId;
    private Long privateOrgPostId;
    private Long deletedPostId;

    @BeforeEach
    void setUp() {
        // PUBLISHED + TEAM スコープ + MEMBERS_ONLY
        publishedTeamPostId = blogPostRepository.save(BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(AUTHOR_ID)
                .title("公開記事 (TEAM)")
                .slug("public-team-" + System.nanoTime())
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.MEMBERS_ONLY)
                .status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build()).getId();

        // PUBLISHED + ORGANIZATION スコープ + PRIVATE
        privateOrgPostId = blogPostRepository.save(BlogPostEntity.builder()
                .organizationId(ORG_ID)
                .authorId(AUTHOR_ID)
                .title("非公開記事 (ORG)")
                .slug("private-org-" + System.nanoTime())
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PRIVATE)
                .status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build()).getId();

        // DELETED 行 (deletedAt セット → @SQLRestriction で除外されるはず)
        BlogPostEntity deleted = blogPostRepository.save(BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(AUTHOR_ID)
                .title("削除済み")
                .slug("deleted-" + System.nanoTime())
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(Visibility.PUBLIC)
                .status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build());
        deletedPostId = deleted.getId();
        deleted.softDelete();
        blogPostRepository.save(deleted);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("findVisibilityProjectionsByIdIn — 実存 ID のみ Projection が返る")
    void findVisibilityProjectionsByIdIn_filtersDeleted() {
        List<BlogPostVisibilityProjection> result = blogPostRepository
                .findVisibilityProjectionsByIdIn(
                        Set.of(publishedTeamPostId, privateOrgPostId, deletedPostId, 999_999L));

        // 削除済み・実存しない ID は除外される
        assertThat(result).hasSize(2);
        assertThat(result).extracting(BlogPostVisibilityProjection::id)
                .containsExactlyInAnyOrder(publishedTeamPostId, privateOrgPostId);
    }

    @Test
    @DisplayName("findVisibilityProjectionsByIdIn — TEAM スコープが正しく射影される")
    void findVisibilityProjectionsByIdIn_teamScopeProjected() {
        List<BlogPostVisibilityProjection> result = blogPostRepository
                .findVisibilityProjectionsByIdIn(Set.of(publishedTeamPostId));

        assertThat(result).hasSize(1);
        BlogPostVisibilityProjection p = result.get(0);
        assertThat(p.scopeType()).isEqualTo("TEAM");
        assertThat(p.scopeId()).isEqualTo(TEAM_ID);
        assertThat(p.authorUserId()).isEqualTo(AUTHOR_ID);
        assertThat(p.visibility()).isEqualTo(Visibility.MEMBERS_ONLY);
        assertThat(p.status()).isEqualTo(PostStatus.PUBLISHED);
    }

    @Test
    @DisplayName("findVisibilityProjectionsByIdIn — ORGANIZATION スコープが正しく射影される")
    void findVisibilityProjectionsByIdIn_orgScopeProjected() {
        List<BlogPostVisibilityProjection> result = blogPostRepository
                .findVisibilityProjectionsByIdIn(Set.of(privateOrgPostId));

        assertThat(result).hasSize(1);
        BlogPostVisibilityProjection p = result.get(0);
        assertThat(p.scopeType()).isEqualTo("ORGANIZATION");
        assertThat(p.scopeId()).isEqualTo(ORG_ID);
        assertThat(p.visibility()).isEqualTo(Visibility.PRIVATE);
    }

}
