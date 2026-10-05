package com.mannschaft.app.timeline.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.TimelineRanchRewardPayload;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository;
import com.mannschaft.app.timeline.event.TimelineBookmarkAnonymizationEventListener;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

/** 実auth/源BeanとMySQLのnative・receiver・旧token境界。HTTPロール/全投稿経路の証拠ではない。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties="ranch.source.timeline.queue-capacity=0")
class TimelineRanchNativeTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private TimelinePostRepository posts;
    @Autowired private TimelineRanchTransportRepository rows;
    @Autowired private UserOperationGuard active;
    @Autowired private UserRewardDeliveryGuard delivery;
    @Autowired private TimelineRanchNativeWriter nativeWriter;
    @Autowired private TimelineRanchNativeOperationFacade operations;
    @Autowired private TimelineRanchTransportWriter transport;
    @Autowired private TimelineRanchOutboxDeliveryService outboxes;
    @Autowired private TimelineBookmarkAnonymizationEventListener purge;
    @Autowired private JdbcTemplate jdbc;
    private Long owner;
    private final List<Long> ownPosts=new ArrayList<>();
    @BeforeEach void fixture() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@timeline-transport.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner==null) return;
        rows.deleteForUser(owner);
        for(Long id:ownPosts) posts.deleteById(id);
        users.deleteById(owner);
    }
    @Test void sameContentConcurrentReceptionKeepsOneWinnerAndBothNativePosts() throws Exception {
        var first=publish("同じ本文");var second=publish("同じ本文");
        var executor=Executors.newFixedThreadPool(2);
        try {
            var one=executor.submit(() -> receive(first.capture()));
            var two=executor.submit(() -> receive(second.capture()));
            assertThat((one.get(10,TimeUnit.SECONDS)?1:0)+(two.get(10,TimeUnit.SECONDS)?1:0)).isEqualTo(1);
        } finally { executor.shutdownNow(); }
        assertThat(count("timeline_ranch_witnesses")).isEqualTo(1);
        assertThat(count("timeline_ranch_outboxes")).isEqualTo(1);
        assertThat(nativeCount()).isEqualTo(2);
    }
    @Test void receiverCollisionRollsBackWinnerButKeepsCommittedNativePost() {
        var first=publish("本文一");assertThat(receive(first.capture())).isTrue();var second=publish("本文二");
        var f=second.capture().payload();
        var collision=new TimelineRanchRewardPayload(first.capture().payload().eventId(),f.schemaVersion(),f.sourceType(),
                f.sourceIdType(),f.canonicalSourceId(),f.scopeType(),f.scopeIdType(),f.canonicalScopeId(),f.actorKind(),
                f.actorUserId(),f.originalAdminId(),f.subjectUserId(),f.recipientUserId(),f.occurredAt(),f.origin(),f.facts());
        assertThatThrownBy(() -> receive(new TimelineRanchCapture(collision,second.capture().fingerprint())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("timeline_ranch_witnesses")).isEqualTo(1);
        assertThat(count("timeline_ranch_outboxes")).isEqualTo(1);
        assertThat(nativeCount()).isEqualTo(2);
    }
    @Test void queueOffKeepsFacadeSuccessAndNativeMetadataInSameRow() {
        var saved=operations.create(request("キューOFF本文"),owner,owner,false).orElseThrow();ownPosts.add(saved.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE id=? AND is_ranch_origin_known=TRUE "
                +"AND ranch_qualified_at IS NOT NULL AND ranch_qualified_user_id=?",Integer.class,saved.getId(),owner)).isEqualTo(1);
        assertThat(count("timeline_ranch_outboxes")).isZero();
    }
    @Test void foreignPersonalScopeStillRejectsWithoutNativeOrTransportSideEffects() {
        assertThatThrownBy(() -> operations.create(request("範囲境界本文"),owner+1,owner,false))
                .isInstanceOfSatisfying(BusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.mannschaft.app.common.CommonErrorCode.COMMON_002));
        assertThat(nativeCount()).isZero();assertThat(count("timeline_ranch_outboxes")).isZero();
    }
    @Test void realSourcePurgeRejectsLateCaptureAndLeaseTokenWithoutDeletingPostBody() {
        var saved=publish("消去境界本文");assertThat(receive(saved.capture())).isTrue();
        var now=jdbc.queryForObject("SELECT MAX(next_attempt_at) FROM timeline_ranch_outboxes WHERE recipient_user_id=?",
                java.sql.Timestamp.class,owner).toInstant().plusSeconds(1);
        var lease=outboxes.lease(new SourceOutboxLeaseRequest(now,1,30,2)).getFirst();
        assertThat(purge.retryPurge(owner)).isTrue();assertThat(receive(saved.capture())).isFalse();
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.NOT_ENROLLED,null,0);
        assertThat(outboxes.acknowledge(new SourceOutboxAckRequest(lease.eventId(),lease.leaseToken(),now.plusSeconds(1),terminal))).isFalse();
        assertThat(count("timeline_ranch_witnesses")).isZero();assertThat(count("timeline_ranch_outboxes")).isZero();
        assertThat(nativeCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE id=? AND ranch_qualified_at IS NULL "
                +"AND ranch_qualified_user_id IS NULL AND is_ranch_origin_known=TRUE",Integer.class,saved.response().getId())).isEqualTo(1);
    }
    private CreatePostRequest request(String content) {
        return new CreatePostRequest(content,"PERSONAL",owner.toString(),"USER",null,null,null,null,null,null,null,null);
    }
    private TimelineRanchNativeWriter.Outcome publish(String content) {
        var outcome=active.withActiveUser(owner,() -> nativeWriter.create(request(content),owner,owner));
        ownPosts.add(outcome.response().getId());return outcome;
    }
    private boolean receive(TimelineRanchCapture capture) {
        return delivery.withLockedDeliveryUser(owner,state -> transport.accept(capture));
    }
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE recipient_user_id=?",Integer.class,owner);
    }
    private int nativeCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE user_id=?",Integer.class,owner); }
}
