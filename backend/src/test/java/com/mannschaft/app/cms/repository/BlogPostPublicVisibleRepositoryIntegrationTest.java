package com.mannschaft.app.cms.repository;

import com.mannschaft.app.cms.PostPriority;
import com.mannschaft.app.cms.PostStatus;
import com.mannschaft.app.cms.PostType;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("公開ブログ取得のpublicVisible条件（実MySQL）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogPostPublicVisibleRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(1_990_000L);

    @Autowired
    private BlogPostRepository repository;

    @Test
    @DisplayName("チーム公開一覧と詳細はpublicVisible=falseを除外する")
    void teamQueriesExcludePublicVisibleFalse() {
        Long teamId = SEQ.incrementAndGet();
        BlogPostEntity visible = savePost(teamId, null, true);
        BlogPostEntity hidden = savePost(teamId, null, false);
        savePost(teamId, null, true, Visibility.PRIVATE);

        assertThat(repository.findPublicPostsByTeamId(teamId, PageRequest.of(0, 20)))
                .extracting(BlogPostEntity::getId)
                .containsExactly(visible.getId());
        assertThat(repository.findPublicPostByTeamIdAndId(teamId, visible.getId())).isPresent();
        assertThat(repository.findPublicPostByTeamIdAndId(teamId, hidden.getId())).isEmpty();
        assertThat(repository.findAllPublicPostsByTeam(List.of(teamId)))
                .extracting(BlogPostEntity::getId)
                .containsExactly(visible.getId());
        assertThat(repository.findMaxCreatedAtByTeamIdIn(List.of(teamId)))
                .singleElement()
                .satisfies(row -> assertThat((LocalDateTime) row[1])
                        .isCloseTo(visible.getCreatedAt(), within(1, ChronoUnit.MICROS)));
    }

    @Test
    @DisplayName("組織公開一覧と詳細はpublicVisible=falseを除外する")
    void organizationQueriesExcludePublicVisibleFalse() {
        Long organizationId = SEQ.incrementAndGet();
        BlogPostEntity visible = savePost(null, organizationId, true);
        BlogPostEntity hidden = savePost(null, organizationId, false);
        savePost(null, organizationId, true, Visibility.PRIVATE);

        assertThat(repository.findPublicPostsByOrganizationId(
                organizationId, PageRequest.of(0, 20)))
                .extracting(BlogPostEntity::getId)
                .containsExactly(visible.getId());
        assertThat(repository.findPublicPostByOrganizationIdAndId(
                organizationId, visible.getId())).isPresent();
        assertThat(repository.findPublicPostByOrganizationIdAndId(
                organizationId, hidden.getId())).isEmpty();
        assertThat(repository.findAllPublicPostsByOrganization(List.of(organizationId)))
                .extracting(BlogPostEntity::getId)
                .containsExactly(visible.getId());
        assertThat(repository.findMaxCreatedAtByOrganizationIdIn(List.of(organizationId)))
                .singleElement()
                .satisfies(row -> assertThat((LocalDateTime) row[1])
                        .isCloseTo(visible.getCreatedAt(), within(1, ChronoUnit.MICROS)));
    }

    private BlogPostEntity savePost(Long teamId, Long organizationId, boolean publicVisible) {
        return savePost(teamId, organizationId, publicVisible, Visibility.PUBLIC);
    }

    private BlogPostEntity savePost(Long teamId, Long organizationId, boolean publicVisible,
                                    Visibility visibility) {
        long sequence = SEQ.incrementAndGet();
        return repository.saveAndFlush(BlogPostEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .authorId(sequence)
                .title("公開設定統合テスト")
                .slug("public-visible-integration-" + sequence)
                .body("本文")
                .postType(PostType.BLOG)
                .visibility(visibility)
                .priority(PostPriority.NORMAL)
                .status(PostStatus.PUBLISHED)
                .publishedAt(LocalDateTime.now().minusMinutes(1))
                .readingTimeMinutes((short) 1)
                .publicVisible(publicVisible)
                .build());
    }
}
