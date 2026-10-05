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
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Autowired private com.mannschaft.app.common.storage.acl.StorageAclService storageClaims;
    @Autowired private com.mannschaft.app.common.storage.acl.StorageAclRepository storageRows;
    @Autowired private TimelineContentFingerprintService fingerprints;
    private Long owner;
    private final List<Long> ownPosts=new ArrayList<>();
    private final List<String> ownUploadKeys=new ArrayList<>();
    @BeforeEach void fixture() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@timeline-transport.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner==null) return;
        rows.deleteForUser(owner);
        for(Long id:ownPosts) {
            jdbc.update("DELETE FROM timeline_post_attachments WHERE timeline_post_id=?",id);
            posts.deleteById(id);
        }
        for(String key:ownUploadKeys) storageRows.findByFileKey(key).ifPresent(storageRows::delete);
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
    @Test void imageCaptureUsesClaimedUploadUuidInsteadOfNewAttachmentRowId() throws Exception {
        String key="synthetic/"+UUID.randomUUID();ownUploadKeys.add(key);
        var scope=com.mannschaft.app.common.storage.acl.StorageAclScope.personal(owner);
        var parent=new com.mannschaft.app.common.storage.acl.StorageAclContentReference("TIMELINE_SCOPE","PERSONAL:"+owner);
        storageClaims.registerPending(key,owner,scope,"image/png",java.time.Duration.ofMinutes(10),parent);
        UUID upload=storageRows.findByFileKey(key).orElseThrow().getId();
        var attachment=mapper.readValue("{\"attachmentType\":\"IMAGE\",\"fileKey\":\""+key+"\"}",
                com.mannschaft.app.timeline.dto.CreateAttachmentRequest.class);
        var request=new CreatePostRequest("添付本文","PERSONAL",owner.toString(),"USER",
                null,null,null,null,null,List.of(attachment),null,null);
        var saved=active.withActiveUser(owner,() -> nativeWriter.create(request,owner,owner));
        ownPosts.add(saved.response().getId());
        assertThat(saved.capture()).isNotNull();
        var expected=fingerprints.fingerprint(owner,saved.capture().payload().occurredAt(),"添付本文",
                List.of(new com.mannschaft.app.timeline.dto.TimelineContentFingerprint.AttachmentRef("UUID",upload.toString())));
        assertThat(expected.equals(saved.capture().fingerprint())).as("私有比較は永続upload UUIDを使う").isTrue();
        assertThat(storageRows.findByFileKey(key).orElseThrow().getStatus())
                .isEqualTo(com.mannschaft.app.common.storage.acl.StorageAclStatus.CLAIMED);
        assertThat(receive(saved.capture())).isTrue();
        String payload=jdbc.queryForObject("SELECT payload_json FROM timeline_ranch_outboxes WHERE recipient_user_id=?",
                String.class,owner);
        assertThat(payload.contains(key)||payload.contains(upload.toString())||payload.contains("添付本文"))
                .as("原文・upload identityは配送payloadへ複製しない").isFalse();
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
        var now=jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",
                (rs,index) -> rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant());
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
    @Autowired private TimelineRanchCaptureTelemetry utcTelemetry;
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
            var receiver=new TimelineRanchTransportWriter(new TimelineRanchTransportRepository(probeJdbc),probeJdbc,fingerprints,mapper,java.time.Clock.systemUTC(),utcTelemetry);
            Boolean accepted=tx.execute(status->receiver.accept(capture));
            assertThat(accepted).as("実native事実の耐久受付 zone=%s",zone).isTrue();
            var repository=new com.mannschaft.app.timeline.repository.TimelineRanchOutboxRepository(probeJdbc);
            tx.executeWithoutResult(status -> {
                java.time.Instant dbNow=probeJdbc.queryForObject("SELECT UTC_TIMESTAMP(6)",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant());
                var candidate=repository.candidates(dbNow,100).stream().filter(row->row.eventId().equals(fact.eventId())).findFirst().orElseThrow();
                assertThat(candidate.occurredAt()).as("保存payloadと実readerの不変Instant").isEqualTo(fact.occurredAt());
                UUID token=com.mannschaft.app.common.UuidV7.generate();
                java.util.Optional<java.time.Instant> expiry=repository.lease(fact.eventId(),token,dbNow.plusSeconds(30),dbNow);
                assertThat(expiry).isPresent();
                byte[] event=java.nio.ByteBuffer.allocate(16).putLong(fact.eventId().getMostSignificantBits()).putLong(fact.eventId().getLeastSignificantBits()).array();
                byte[] leaseToken=java.nio.ByteBuffer.allocate(16).putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array();
                java.time.Instant stored=probeJdbc.queryForObject("SELECT lease_expires_at FROM timeline_ranch_outboxes WHERE id=? AND lease_token=? AND status='LEASED'",(rs,index)->rs.getTimestamp(1,com.mannschaft.app.common.jdbc.JdbcUtcCalendar.fresh()).toInstant(),event,leaseToken);
                assertThat(expiry.orElseThrow()).as("公表期限は同じ源TXの実DB期限").isEqualTo(stored);
            });
        }
    }
}
