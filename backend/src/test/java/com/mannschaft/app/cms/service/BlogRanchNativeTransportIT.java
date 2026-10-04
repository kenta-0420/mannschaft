package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.cms.dto.BlogRanchRewardPayload;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogRanchTransportRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実Bean/専用MySQLの本体commitと別transport原子性を検証する。HTTP/filter試験ではない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties="ranch.source.blog.queue-capacity=0")
class BlogRanchNativeTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private BlogPostRepository posts;
    @Autowired private BlogMediaUploadRepository media;
    @Autowired private UserOperationGuard active;
    @Autowired private UserRewardDeliveryGuard delivery;
    @Autowired private BlogRanchNativeWriter nativeWriter;
    @Autowired private BlogRanchNativeOperationFacade operations;
    @Autowired private BlogRanchTransportWriter transport;
    @Autowired private BlogRanchTransportRepository transportRows;
    @Autowired private JdbcTemplate jdbc;
    private Long owner;
    private final List<Long> ownPosts=new ArrayList<>();
    private final List<Long> ownMedia=new ArrayList<>();

    @BeforeEach void fixture() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@blog-transport.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner==null) return;
        transportRows.deleteForUser(owner);
        for(Long id:ownMedia) media.deleteById(id);
        for(Long id:ownPosts) posts.deleteById(id);
        users.deleteById(owner);
    }

    @Test void concurrentSameDigestDifferentPostsKeepsOneWinnerAndBothNativeCommits() throws Exception {
        var first=publish("同じ本文");
        var second=publish("同じ本文");
        assertThat(first.capture().fingerprint().digest()).isEqualTo(second.capture().fingerprint().digest());
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        var executor=Executors.newFixedThreadPool(2);
        try {
            var one=executor.submit(() -> { ready.countDown();if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture開始待機失敗");return receive(first.capture()); });
            var two=executor.submit(() -> { ready.countDown();if(!start.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("fixture開始待機失敗");return receive(second.capture()); });
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue();start.countDown();
            int accepted=(one.get(15,TimeUnit.SECONDS)?1:0)+(two.get(15,TimeUnit.SECONDS)?1:0);
            assertThat(accepted).isEqualTo(1);
        } finally { start.countDown();executor.shutdownNow(); }
        assertThat(count("blog_ranch_witnesses")).isEqualTo(1);
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        assertThat(publishedCount()).isEqualTo(2);
    }

    @Test void earlierOccurredAtArrivingLaterDoesNotReplaceDurableWinner() {
        var earlier=publish("順序比較本文");
        var later=publish("順序比較本文");
        assertThat(receive(later.capture())).isTrue();
        assertThat(receive(earlier.capture())).isFalse();
        byte[] saved=jdbc.queryForObject("SELECT canonical_source_id FROM blog_ranch_witnesses WHERE recipient_user_id=?",byte[].class,owner);
        assertThat(new String(saved,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo(later.capture().payload().canonicalSourceId());
        assertThat(count("blog_ranch_witnesses")).isEqualTo(1);
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        assertThat(publishedCount()).isEqualTo(2);
    }

    @Test void outboxFailureRollsBackWinnerButPreservesSecondNativePublication() {
        var first=publish("本文一");
        assertThat(receive(first.capture())).isTrue();
        var second=publish("本文二");
        var fact=second.capture().payload();
        // receiverの原子rollbackを強制する技術event PK衝突fixture。HTTP資格の証拠には流用しない。
        var collision=new BlogRanchRewardPayload(first.capture().payload().eventId(),fact.schemaVersion(),fact.sourceType(),
                fact.sourceIdType(),fact.canonicalSourceId(),fact.scopeType(),fact.scopeIdType(),fact.canonicalScopeId(),
                fact.actorKind(),fact.actorUserId(),fact.originalAdminId(),fact.subjectUserId(),fact.recipientUserId(),
                fact.occurredAt(),fact.origin(),fact.facts());
        assertThatThrownBy(() -> receive(new BlogRanchCapture(collision,second.capture().fingerprint())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("blog_ranch_witnesses")).isEqualTo(1);
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        assertThat(publishedCount()).isEqualTo(2);
    }

    @Test void disabledOptionalQueueKeepsNativeAckAndOneAtomicFirstMetadataSave() {
        Long id=draft("キューOFF本文");
        assertThat(operations.changeStatus(id,owner,new PublishRequest("PUBLISHED",null,null),false)).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND status='PUBLISHED' "
                +"AND first_published_at IS NOT NULL AND first_published_author_user_id=? "
                +"AND is_ranch_publication_observed=TRUE",Integer.class,id,owner)).isEqualTo(1);
        assertThat(count("blog_ranch_witnesses")).isZero();
        assertThat(count("blog_ranch_outboxes")).isZero();
    }

    @Test void unavailableOldContentKeySuppressesNewRewardButKeepsNativeSuccess() {
        var first=publish("旧鍵証跡fixture");
        assertThat(receive(first.capture())).isTrue();
        byte[] oldKey=jdbc.queryForObject("SELECT content_key_id FROM blog_ranch_witnesses WHERE recipient_user_id=?",byte[].class,owner);
        oldKey[0]=(byte)(oldKey[0]^1);
        jdbc.update("UPDATE blog_ranch_witnesses SET content_key_id=? WHERE recipient_user_id=?",oldKey,owner);
        var second=publish("新しい本文fixture");
        assertThat(receive(second.capture())).isFalse();
        assertThat(count("blog_ranch_witnesses")).isEqualTo(1);
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        assertThat(publishedCount()).isEqualTo(2);
    }

    @Test void unusedReadyUploadDoesNotChangePublishedContentWinner() {
        var first=publish("添付未使用の同じ本文");
        Long secondId=draft("添付未使用の同じ本文");
        var unused=media.saveAndFlush(BlogMediaUploadEntity.builder().blogPostId(secondId).uploaderId(owner)
                .scopeType("PERSONAL").scopeId(owner).s3Key("blog/PERSONAL/"+owner+"/"+UUID.randomUUID()+".png")
                .fileSize(100L).contentType("image/png").build());
        ownMedia.add(unused.getId());
        var second=active.withActiveUser(owner,() -> nativeWriter.changeStatus(secondId,owner,new PublishRequest("PUBLISHED",null,null)));
        assertThat(second.capture()).isNotNull();
        assertThat(second.capture().fingerprint().digest()).isEqualTo(first.capture().fingerprint().digest());
        assertThat(receive(first.capture())).isTrue();
        assertThat(receive(second.capture())).isFalse();
        assertThat(count("blog_ranch_witnesses")).isEqualTo(1);
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        assertThat(publishedCount()).isEqualTo(2);
    }
    private Long draft(String body) {
        Long id=posts.saveAndFlush(BlogPostEntity.builder().authorId(owner).userId(owner)
                .title("同一比較タイトル").slug("transport-"+UUID.randomUUID()).body(body).build()).getId();
        ownPosts.add(id);return id;
    }
    private BlogRanchNativeWriter.Outcome publish(String body) {
        Long id=draft(body);
        var outcome=active.withActiveUser(owner,() -> nativeWriter.changeStatus(id,owner,new PublishRequest("PUBLISHED",null,null)));
        assertThat(outcome.capture()).isNotNull();return outcome;
    }
    private boolean receive(BlogRanchCapture capture) {
        return delivery.withLockedDeliveryUser(owner,state -> transport.accept(capture));
    }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE recipient_user_id=?",Integer.class,owner); }
    private int publishedCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE author_id=? AND status='PUBLISHED'",Integer.class,owner); }
}
