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
            users.deleteById(actor);
        }
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
}
