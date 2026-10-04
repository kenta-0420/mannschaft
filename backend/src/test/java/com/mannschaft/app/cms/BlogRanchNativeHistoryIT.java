package com.mannschaft.app.cms;

import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

/** 実DBのnative履歴のみ検証する。単独Entity操作は本人資格や報酬配送の証拠にしない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogRanchNativeHistoryIT extends AbstractMySqlIntegrationTest {
    @Autowired private EntityManagerFactory factory;
    private static final LocalDateTime BASE=LocalDateTime.of(2026,10,3,12,0);

    @Test void publicationHistorySurvivesPersistenceContextAcrossTransactions() {
        EntityManager em=factory.createEntityManager();
        try {
            var post=draft(em);
            em.getTransaction().begin();
            post.publish(null,BASE);
            em.flush();
            assertThat(post.isRanchPublicationObserved()).isTrue();
            em.getTransaction().commit();
            em.getTransaction().begin();
            post.unpublish(BASE.plusHours(1));
            em.flush();
            em.getTransaction().commit();
            assertThat(post.isRanchPublicationObserved()).isTrue();
            em.getTransaction().begin();
            post.publish(null,BASE.plusHours(2));
            em.flush();
            em.getTransaction().commit();
            assertThat(post.isRanchPublicationObserved()).isTrue();
            assertThat(post.getFirstPublishedAt()).isNull();
            assertThat(post.getFirstPublishedAuthorUserId()).isNull();
        } finally { close(em); }
    }

    @Test void rolledBackPublicationDoesNotCreateCommittedHistory() {
        Long id;
        EntityManager em=factory.createEntityManager();
        try {
            var post=draft(em); id=post.getId();
            em.getTransaction().begin();
            post.publish(null,BASE);
            em.flush();
            assertThat(post.isRanchPublicationObserved()).isTrue();
            em.getTransaction().rollback();
        } finally { close(em); }
        em=factory.createEntityManager();
        try {
            em.getTransaction().begin();
            var stored=em.find(BlogPostEntity.class,id);
            assertThat(stored.getStatus()).isEqualTo(PostStatus.DRAFT);
            assertThat(stored.isRanchPublicationObserved()).isFalse();
            assertThat(stored.isPublicationHistoryKnown()).isTrue();
            em.getTransaction().commit();
        } finally { close(em); }
    }

    @Test void earlyFlushThenWithdrawalKeepsConservativeObservedHistory() {
        EntityManager em=factory.createEntityManager();
        try {
            var post=draft(em);
            em.getTransaction().begin();
            post.publish(null,BASE);
            em.flush();
            post.unpublish(BASE.plusHours(1));
            em.flush();
            em.getTransaction().commit();
            em.clear();
            em.getTransaction().begin();
            var stored=em.find(BlogPostEntity.class,post.getId());
            assertThat(stored.getStatus()).isEqualTo(PostStatus.DRAFT);
            assertThat(stored.isRanchPublicationObserved()).isTrue();
            assertThat(stored.getFirstPublishedAt()).isNull();
            em.getTransaction().commit();
        } finally { close(em); }
    }

    private void close(EntityManager em) {
        try { if(em.getTransaction().isActive()) em.getTransaction().rollback(); }
        finally { em.close(); }
    }
    private BlogPostEntity draft(EntityManager em) {
        em.getTransaction().begin();
        var post=BlogPostEntity.builder().authorId(1L).title("native history fixture")
                .slug("history-"+UUID.randomUUID()).body("synthetic native body").build();
        em.persist(post); em.flush();
        assertThat(post.isPublicationHistoryKnown()).isTrue();
        assertThat(post.isRanchPublicationHistorical()).isFalse();
        assertThat(post.isRanchPublicationObserved()).isFalse();
        em.getTransaction().commit();
        return post;
    }
}
