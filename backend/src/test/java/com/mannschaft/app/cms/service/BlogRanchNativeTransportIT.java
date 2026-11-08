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
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAckRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxDeferRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxFailureRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxLeaseRequest;
import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.SourceOutboxAdminService;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogRanchTransportRepository;
import com.mannschaft.app.cms.repository.BlogRanchOutboxAdminRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import com.mannschaft.app.cms.event.UserBlogSettingsPurgeEventListener;
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
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 実Bean/専用MySQLの本体commitと別transport原子性を検証する。
 * AC45の追加だけは実HTTPで未参加管理者の保存とowner非作成を固定する回帰防止柵である。
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties="ranch.source.blog.queue-capacity=0")
@Isolated
class BlogRanchNativeTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private BlogPostRepository posts;
    @Autowired private BlogMediaUploadRepository media;
    @Autowired private BlogRanchOutboxDeliveryService outboxDelivery;
    @Autowired private BlogRanchOutboxAdminService outboxAdmin;
    @Autowired private java.time.Clock clock;
    @Autowired private UserBlogSettingsPurgeEventListener cmsPurge;
    @Autowired private UserOperationGuard active;
    @Autowired private UserRewardDeliveryGuard delivery;
    @Autowired private BlogRanchNativeWriter nativeWriter;
    @Autowired private BlogRanchNativeOperationFacade operations;
    @Autowired private BlogRanchTransportWriter transport;
    @Autowired private BlogRanchTransportRepository transportRows;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private RanchOwnerRepository ranchOwners;
    @Autowired private BlogRanchOutboxAdminRepository adminCommands;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager em;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    private Long owner;
    private final List<Long> ownPosts=new ArrayList<>();
    private final List<Long> ownMedia=new ArrayList<>();
    private final List<Long> ownActors=new ArrayList<>();

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
        for(Long actor:ownActors) {
            jdbc.update("DELETE FROM blog_ranch_admin_commands WHERE actor_user_id=?",actor);
            jdbc.update("DELETE FROM user_roles WHERE user_id=?",actor);
            users.deleteById(actor);
        }
    }

    @Test void cmsCommitSurvivesAuthRollback() {
        Long id=draft("認証側rollbackから独立する公開");
        var failure=new IllegalStateException("auth rollback after cms commit");
        assertThatThrownBy(() -> delivery.withLockedDeliveryUsers(List.of(owner),states -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            nativeWriter.changeStatus(id,owner,new PublishRequest("PUBLISHED",null,null));
            throw failure;
        })).isSameAs(failure);
        assertThat(posts.findById(id).orElseThrow().getStatus())
                .isEqualTo(com.mannschaft.app.cms.PostStatus.PUBLISHED);
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
    @Test void deferRestoresOnlyCurrentLeaseAndFailureConsumesFiniteBudget() {
        var nativeResult=publish("配送保留と障害の本文fixture");
        assertThat(receive(nativeResult.capture())).isTrue();
        var now=deliveryNow();
        var first=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,2)).getFirst();
        assertThat(first.attemptCount()).isEqualTo(1);
        var defer=new SourceOutboxDeferRequest(first.eventId(),first.leaseToken(),now.plusSeconds(1),now.plusSeconds(5),10);
        assertThat(outboxDelivery.defer(defer)).isTrue();
        assertThat(outboxDelivery.defer(defer)).isFalse();
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(first.eventId(),first.leaseToken(),now.plusSeconds(2),terminal))).isFalse();
        makeRetryDue(first.eventId());
        var second=outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(5),10,30,2)).getFirst();
        assertThat(second.attemptCount()).isEqualTo(1);
        assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
        assertThat(outboxDelivery.retry(new SourceOutboxFailureRequest(second.eventId(),second.leaseToken(),now.plusSeconds(6),2,1,1,"CONSUMER_FAILED"))).isTrue();
        makeRetryDue(second.eventId());
        var third=outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(7),10,30,2)).getFirst();
        assertThat(third.attemptCount()).isEqualTo(2);
        assertThat(outboxDelivery.retry(new SourceOutboxFailureRequest(third.eventId(),third.leaseToken(),now.plusSeconds(8),2,1,1,"CONSUMER_FAILED"))).isTrue();
        assertThat(status(third.eventId())).isEqualTo("DEAD_LETTER");
        assertThat(outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(100),10,30,2))).isEmpty();
    }

    @Test void expiredCrashIsBoundedButCurrentLeaseIsNotStolen() {
        var nativeResult=publish("期限切れcrash本文fixture");
        assertThat(receive(nativeResult.capture())).isTrue();
        var now=deliveryNow();
        var first=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,1)).getFirst();
        assertThat(outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(29),10,30,1))).isEmpty();
        assertThat(status(first.eventId())).isEqualTo("LEASED");
        expireOwnLease(first.eventId(),first.leaseToken());
        assertThat(outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(30),10,30,1))).isEmpty();
        assertThat(status(first.eventId())).isEqualTo("DEAD_LETTER");
        assertThat(publishedCount()).isEqualTo(1);
    }

    @Test void poisonPayloadDoesNotStopOtherLeasesAndAckCannotReviveRemovedRow() {
        var invalid=publish("壊れた配送fixture本文");
        var valid=publish("正常配送fixture本文");
        assertThat(receive(invalid.capture())).isTrue();assertThat(receive(valid.capture())).isTrue();
        var invalidId=invalid.capture().payload().eventId();
        jdbc.update("UPDATE blog_ranch_outboxes SET payload_json='null' WHERE id=?",idBytes(invalidId));
        var now=deliveryNow();
        var leased=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,2));
        assertThat(leased).hasSize(1);assertThat(status(invalidId)).isEqualTo("DEAD_LETTER");
        var event=leased.getFirst();
        jdbc.update("DELETE FROM blog_ranch_outboxes WHERE id=?",idBytes(event.eventId()));
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(event.eventId(),event.leaseToken(),now.plusSeconds(1),terminal))).isFalse();
        assertThat(outboxDelivery.defer(new SourceOutboxDeferRequest(event.eventId(),event.leaseToken(),now.plusSeconds(1),now.plusSeconds(2),10))).isFalse();
        assertThat(count("blog_ranch_outboxes")).isEqualTo(1);
        // 手動削除fixtureはlate tokenのno-recreateだけ。実purge接続の証拠には流用しない。
        assertThat(publishedCount()).isEqualTo(2);
    }
    private java.time.Instant deliveryNow() { return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,index) -> rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant()); }
    /** 自己イベントだけのDB条件を作り、Java時計の仮想前進で期限判定を代用しない。 */
    private void makeRetryDue(UUID event) {
        assertThat(jdbc.update("UPDATE blog_ranch_outboxes SET next_attempt_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=? AND status='RETRY'",idBytes(event))).isEqualTo(1);
    }
    private void expireOwnLease(UUID event,UUID token) {
        assertThat(jdbc.update("UPDATE blog_ranch_outboxes SET lease_expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6)) WHERE id=? AND status='LEASED' AND lease_token=?",idBytes(event),idBytes(token))).isEqualTo(1);
    }
    private String status(UUID event) { return jdbc.queryForObject("SELECT status FROM blog_ranch_outboxes WHERE id=?",String.class,idBytes(event)); }
    private static byte[] idBytes(UUID event) { return java.nio.ByteBuffer.allocate(16).putLong(event.getMostSignificantBits()).putLong(event.getLeastSignificantBits()).array(); }
    @Test void adminRetrySavedAckSurvivesRemovedEventAndRejectsChangedBody() {
        // source-own命令の原子性だけ。HTTP SYSTEM_ADMIN認可は入口側の別試験。
        var nativeResult=publish("管理再試行fixture本文");assertThat(receive(nativeResult.capture())).isTrue();
        var event=nativeResult.capture().payload().eventId();var key=UUID.randomUUID();var now=deliveryNow();
        var reason=new SourceOutboxAdminRetryRequest("OPERATOR_RETRY");
        var ack=active.withActiveUser(owner,() -> outboxAdmin.retry(owner,event,key,reason,now));
        assertThat(ack.disposition()).isEqualTo(SourceOutboxAdminRetryAck.Disposition.RETRY_SCHEDULED);
        assertThat(status(event)).isEqualTo("RETRY");
        jdbc.update("DELETE FROM blog_ranch_outboxes WHERE id=?",idBytes(event));
        SourceOutboxAdminRetryAck replayAck=active.withActiveUser(owner,() -> outboxAdmin.retry(owner,event,key,reason,now.plusSeconds(20)));
        assertThat(replayAck).isEqualTo(ack);
        assertThatThrownBy(() -> active.withActiveUser(owner,() -> outboxAdmin.retry(owner,event,key,
                new SourceOutboxAdminRetryRequest("OTHER_REASON"),now.plusSeconds(20))))
                .isInstanceOf(BusinessException.class).extracting("errorCode.code").isEqualTo("SOURCEOUTBOX_003");
        assertThat(count("blog_ranch_outboxes")).isZero();
    }

    @Test
    void 未参加の現役管理者が別本人の配送をHTTP再予約しても個人ownerを作らない() throws Exception {
        var transaction = new TransactionTemplate(transactionManager);
        Long administrator = transaction.execute(ignored -> {
            Long actor = users.saveAndFlush(BlogRanchAdminTestFixture.user()).getId();
            ownActors.add(actor);
            MembershipTestHelper.insertUserRole(em, actor, "SYSTEM_ADMIN", null, null);
            return actor;
        });
        assertThat(administrator).isNotNull().isNotEqualTo(owner);
        assertThat(ranchOwners.findByUserId(administrator)).isEmpty();
        var nativeResult = publish("未参加管理者の再処理対象fixture本文");
        assertThat(receive(nativeResult.capture())).isTrue();
        UUID event = nativeResult.capture().payload().eventId();
        UUID key = UUID.randomUUID();
        var reason = new SourceOutboxAdminRetryRequest("OPERATOR_RETRY");

        var result = mvc.perform(post("/api/v1/system-admin/ranch/outboxes/BLOG_FIRST_PUBLISH/{eventId}/retry", event)
                        .with(user(administrator.toString()).roles("SYSTEM_ADMIN"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsBytes(reason)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var body = mapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
        SourceOutboxAdminRetryAck ack = mapper.treeToValue(body, SourceOutboxAdminRetryAck.class);
        assertThat(ack.eventId()).isEqualTo(event);
        assertThat(ack.sourceType()).isEqualTo(com.mannschaft.app.ranch.reward.RanchRewardSourceType.BLOG_FIRST_PUBLISH);
        assertThat(ack.disposition()).isEqualTo(SourceOutboxAdminRetryAck.Disposition.RETRY_SCHEDULED);
        assertThat(ack.completedAt()).isNotNull();
        var saved = adminCommands.command(administrator, key);
        assertThat(saved).isNotNull();
        assertThat(saved.hash()).hasSize(32);
        SourceOutboxAdminRetryAck storedAck = mapper.readValue(saved.result(), SourceOutboxAdminRetryAck.class);
        assertThat(storedAck).isEqualTo(ack);
        assertThat(status(event)).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_ranch_admin_commands WHERE actor_user_id=? AND idempotency_key=? AND id=?",
                Long.class, administrator, idBytes(key), idBytes(ack.commandId()))).isEqualTo(1L);
        assertThat(ranchOwners.findByUserId(administrator)).isEmpty();
    }

    @Test void adminRetryDoesNotStealCurrentLeaseAndAckedEventRemainsTerminal() {
        var nativeResult=publish("管理処理中fixture本文");assertThat(receive(nativeResult.capture())).isTrue();
        var now=deliveryNow();var leased=outboxDelivery.lease(new SourceOutboxLeaseRequest(now,10,30,2)).getFirst();
        var reason=new SourceOutboxAdminRetryRequest("OPERATOR_RETRY");var key=UUID.randomUUID();
        assertThatThrownBy(() -> active.withActiveUser(owner,() -> outboxAdmin.retry(owner,leased.eventId(),key,reason,now.plusSeconds(1))))
                .isInstanceOf(BusinessException.class).extracting("errorCode.code").isEqualTo("SOURCEOUTBOX_004");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_ranch_admin_commands WHERE actor_user_id=?",Long.class,owner)).isZero();
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(leased.eventId(),leased.leaseToken(),now.plusSeconds(2),terminal))).isTrue();
        var ack=active.withActiveUser(owner,() -> outboxAdmin.retry(owner,leased.eventId(),key,reason,now.plusSeconds(3)));
        assertThat(ack.disposition()).isEqualTo(SourceOutboxAdminRetryAck.Disposition.ALREADY_TERMINAL);
        assertThat(status(leased.eventId())).isEqualTo("ACKED");
    }

    @Test void partialProviderSetIsUnavailableInsteadOfFourHealthyZeros() {
        var nativeResult=publish("管理health本文fixture");assertThat(receive(nativeResult.capture())).isTrue();
        assertThat(Long.parseLong(outboxAdmin.health(deliveryNow()).pendingCount())).isGreaterThanOrEqualTo(1);
        var partial=new SourceOutboxAdminService(List.of(outboxAdmin),clock);
        assertThatThrownBy(partial::health).isInstanceOf(BusinessException.class)
                .extracting("errorCode.code").isEqualTo("SOURCEOUTBOX_001");
    }
    @Test void realCmsPurgeSuppressesLateCaptureAndTokensButKeepsOtherActorTechnicalAck() {
        var nativeResult=publish("消去対象の本文fixture");assertThat(receive(nativeResult.capture())).isTrue();
        var event=nativeResult.capture().payload().eventId();var now=deliveryNow();
        Long actor=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@source-admin-fixture.invalid")
                .lastName("運営").firstName("検証").displayName("運営検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        ownActors.add(actor);var key=UUID.randomUUID();var reason=new SourceOutboxAdminRetryRequest("OPERATOR_RETRY");
        var ack=active.withActiveUser(actor,() -> outboxAdmin.retry(actor,event,key,reason,now));
        var leased=outboxDelivery.lease(new SourceOutboxLeaseRequest(now.plusSeconds(1),10,30,2)).getFirst();
        assertThat(cmsPurge.retryPurge(owner)).isTrue();
        assertThat(count("blog_ranch_outboxes")).isZero();assertThat(count("blog_ranch_witnesses")).isZero();
        assertThat(receive(nativeResult.capture())).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM blog_posts WHERE id=? AND status='PUBLISHED' "
                +"AND is_ranch_publication_observed=TRUE AND first_published_at IS NULL AND first_published_author_user_id IS NULL",
                Integer.class,ownPosts.getFirst())).isEqualTo(1);
        var terminal=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThat(outboxDelivery.acknowledge(new SourceOutboxAckRequest(event,leased.leaseToken(),now.plusSeconds(2),terminal))).isFalse();
        assertThat(outboxDelivery.defer(new SourceOutboxDeferRequest(event,leased.leaseToken(),now.plusSeconds(2),now.plusSeconds(3),10))).isFalse();
        SourceOutboxAdminRetryAck replay=active.withActiveUser(actor,() -> outboxAdmin.retry(actor,event,key,reason,now.plusSeconds(3)));
        assertThat(replay).isEqualTo(ack);assertThat(count("blog_ranch_outboxes")).isZero();
        String saved=jdbc.queryForObject("SELECT result_json FROM blog_ranch_admin_commands WHERE actor_user_id=?",String.class,actor);
        assertThat(saved).doesNotContain("消去対象の本文fixture","recipientUserId","payload","bodyHash","contentDigest");
        // 実CMS retryPurgeとtoken/captureを使う源消去証拠。全AccountPurge/管理HTTP認可/並行競合は別途未証明。
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
    @Autowired private BlogContentFingerprintService utcFingerprints;
    @Autowired private BlogRanchCaptureTelemetry utcTelemetry;
    /** native JPAで確定した不変事実を接続別zoneでも保存・読取する。共有pool/設定は変更しない。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"UTC","Pacific/Honolulu"})
    void nativeEventAndLeaseRoundTripIgnoreJdbcSessionZone(String zone) throws Exception {
        var saved=publish("時刻境界本文");assertThat(saved.capture()).isNotNull();var capture=saved.capture();var fact=capture.payload();
        var config=new com.zaxxer.hikari.HikariConfig();
        // own Testcontainers資格だけを使い、出力しない。
        config.setJdbcUrl(MYSQL.getJdbcUrl());config.setUsername(MYSQL.getUsername());config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(2);config.setMinimumIdle(0);
        config.addDataSourceProperty("connectionTimeZone",zone);
        config.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
        config.addDataSourceProperty("preserveInstants","true");
        try(var probe=new com.zaxxer.hikari.HikariDataSource(config)) {
            var probeJdbc=new JdbcTemplate(probe);
            var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(probe));
            var receiver=new BlogRanchTransportWriter(new BlogRanchTransportRepository(probeJdbc),probeJdbc,utcFingerprints,mapper,java.time.Clock.systemUTC(),utcTelemetry);
            Boolean accepted=tx.execute(status->receiver.accept(capture));
            assertThat(accepted).as("実native事実の耐久受付 zone=%s",zone).isTrue();
            var repository=new com.mannschaft.app.cms.repository.BlogRanchOutboxRepository(probeJdbc);
            tx.executeWithoutResult(status -> {
                java.time.Instant dbNow=probeJdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant());
                var candidate=repository.candidates(dbNow,100).stream().filter(row->row.eventId().equals(fact.eventId())).findFirst().orElseThrow();
                assertThat(candidate.occurredAt()).as("保存payloadと実readerの不変Instant").isEqualTo(fact.occurredAt());
                UUID token=com.mannschaft.app.common.UuidV7.generate();
                java.util.Optional<java.time.Instant> expiry=repository.lease(fact.eventId(),token,dbNow.plusSeconds(30),dbNow);
                assertThat(expiry).isPresent();
                byte[] event=java.nio.ByteBuffer.allocate(16).putLong(fact.eventId().getMostSignificantBits()).putLong(fact.eventId().getLeastSignificantBits()).array();
                byte[] leaseToken=java.nio.ByteBuffer.allocate(16).putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array();
                java.time.Instant stored=probeJdbc.queryForObject("SELECT lease_expires_at FROM blog_ranch_outboxes WHERE id=? AND lease_token=? AND status='LEASED'",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant(),event,leaseToken);
                assertThat(expiry.orElseThrow()).as("公表期限は同じ源TXの実DB期限").isEqualTo(stored);
            });
        }
    }

    /** CMP-261016-0104: 実公開済み別記事の同一UTC週dedupを、生成時TZが異なる接続間で検査する。 */
    @Test void 同一UTC週の同タイトル本文別記事は生成時既定TZが異なる接続でも二重受付されない() throws Exception {
        var first = publish("同一週cached TZ比較用の人工本文");
        var second = publish("同一週cached TZ比較用の人工本文");
        assertThat(first.capture().payload().canonicalSourceId()).isNotEqualTo(second.capture().payload().canonicalSourceId());
        assertThat(first.capture().payload().eventId()).isNotEqualTo(second.capture().payload().eventId());
        assertThat(first.capture().fingerprint()).isEqualTo(second.capture().fingerprint());
        var monday = first.capture().payload().occurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        assertThat(first.capture().fingerprint().week()).isEqualTo(monday);
        var originalZone = java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            try (var utcProbe = cachedDefaultZoneProbe("UTC")) {
                java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
                try (var jstProbe = cachedDefaultZoneProbe("Asia/Tokyo")) {
                    String driverA = assertCachedDefaultZone(utcProbe, "UTC");
                    String driverB = assertCachedDefaultZone(jstProbe, "Asia/Tokyo");
                    var utcJdbc = new JdbcTemplate(utcProbe);
                    var jstJdbc = new JdbcTemplate(jstProbe);
                    assertThat(java.util.TimeZone.getDefault().toZoneId()).as("受付時の実JVM既定TZ")
                            .isEqualTo(java.time.ZoneId.of("Asia/Tokyo"));
                    assertThat(utcJdbc.queryForObject("SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())", String.class))
                            .as("UTC生成接続の実DB offset").isEqualTo("00:00:00");
                    assertThat(jstJdbc.queryForObject("SELECT TIMEDIFF(NOW(), UTC_TIMESTAMP())", String.class))
                            .as("JST生成接続の実DB offset").isEqualTo("00:00:00");
                    String driverClass = driverA.equals("9.6.0") && driverB.equals("9.6.0") ? "9.6.0" : "OTHER_VERSION";
                    System.out.println("Native週DATE条件: driver=" + driverClass
                            + " JVM=Asia/Tokyo connectionTimeZone=UTC driverDefaultA=UTC driverDefaultB=Asia/Tokyo DBoffset=UTC");
                    var utcTx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(utcProbe));
                    var jstTx = new TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jstProbe));
                    var utcReceiver = new BlogRanchTransportWriter(new BlogRanchTransportRepository(utcJdbc),
                            utcJdbc, utcFingerprints, mapper, java.time.Clock.systemUTC(), utcTelemetry);
                    var jstReceiver = new BlogRanchTransportWriter(new BlogRanchTransportRepository(jstJdbc),
                            jstJdbc, utcFingerprints, mapper, java.time.Clock.systemUTC(), utcTelemetry);
                    Boolean firstAccepted = utcTx.execute(status -> utcReceiver.accept(first.capture()));
                    Boolean secondAccepted = jstTx.execute(status -> jstReceiver.accept(second.capture()));
                    var softly = new org.assertj.core.api.SoftAssertions();
                    softly.assertThat(firstAccepted).as("UTC生成接続の先着受付").isTrue();
                    softly.assertThat(secondAccepted).as("同一UTC週の再受付").isFalse();
                    softly.assertThat(publishedCount()).as("本体の保存件数").isEqualTo(2);
                    softly.assertThat(count("blog_ranch_witnesses")).as("同一週のwitness件数").isEqualTo(1);
                    softly.assertThat(count("blog_ranch_outboxes")).as("同一週のoutbox件数").isEqualTo(1);
                    softly.assertThat(jdbc.queryForList("SELECT CAST(content_week AS CHAR) FROM blog_ranch_witnesses "
                            + "WHERE recipient_user_id=?", String.class, owner)).as("実MySQL DATEのUTC月曜")
                            .containsExactly(monday.toString());
                    softly.assertAll();
                }
            }
        } finally {
            java.util.TimeZone.setDefault(originalZone);
        }
    }

    /** 共有poolを触らず、生成時のConnector/J既定TZだけが異なる専有接続を保持する。 */
    private com.zaxxer.hikari.HikariDataSource cachedDefaultZoneProbe(String generatedZone) throws Exception {
        var config = new com.zaxxer.hikari.HikariConfig();
        config.setJdbcUrl(MYSQL.getJdbcUrl());
        config.setUsername(MYSQL.getUsername());
        config.setPassword(MYSQL.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        config.addDataSourceProperty("serverTimezone", "UTC");
        config.addDataSourceProperty("cacheDefaultTimeZone", "true");
        var probe = new com.zaxxer.hikari.HikariDataSource(config);
        try {
            assertCachedDefaultZone(probe, generatedZone);
            return probe;
        } catch (Exception | AssertionError failure) {
            probe.close();
            throw failure;
        }
    }

    /** runtimeOnlyの実driverを読み、JVMだけ切り替えて再現したつもりになることを防ぐ。 */
    private String assertCachedDefaultZone(com.zaxxer.hikari.HikariDataSource probe, String expectedZone) throws Exception {
        try (var connection = probe.getConnection()) {
            String driverVersion = connection.getMetaData().getDriverVersion();
            Class<?> connectionType = Class.forName("com.mysql.cj.jdbc.JdbcConnection");
            Object driver = connection.unwrap(connectionType);
            Object properties = connectionType.getMethod("getPropertySet").invoke(driver);
            Class<?> propertySet = Class.forName("com.mysql.cj.conf.PropertySet");
            Class<?> runtimeProperty = Class.forName("com.mysql.cj.conf.RuntimeProperty");
            Object connectionZone = propertySet.getMethod("getStringProperty", String.class)
                    .invoke(properties, "connectionTimeZone");
            Object cached = propertySet.getMethod("getBooleanProperty", String.class)
                    .invoke(properties, "cacheDefaultTimeZone");
            assertThat(runtimeProperty.getMethod("getValue").invoke(connectionZone)).isEqualTo("UTC");
            assertThat(runtimeProperty.getMethod("getValue").invoke(cached)).isEqualTo(Boolean.TRUE);
            Object session = connectionType.getMethod("getSession").invoke(driver);
            Object server = Class.forName("com.mysql.cj.Session").getMethod("getServerSession").invoke(session);
            java.util.TimeZone cachedZone = (java.util.TimeZone) Class.forName("com.mysql.cj.protocol.ServerSession")
                    .getMethod("getDefaultTimeZone").invoke(server);
            assertThat(cachedZone.toZoneId()).as("接続生成時の実cached default TZ")
                    .isEqualTo(java.time.ZoneId.of(expectedZone));
            return java.util.regex.Pattern.compile("(?<![0-9.])9\\.6\\.0(?![0-9.])").matcher(driverVersion).find()
                    ? "9.6.0" : "OTHER_VERSION";
        }
    }
}
