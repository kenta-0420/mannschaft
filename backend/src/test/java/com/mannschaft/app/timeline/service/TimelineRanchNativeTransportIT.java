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
import org.junit.jupiter.api.parallel.Isolated;
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
@Isolated
class TimelineRanchNativeTransportIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactionManager;
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

    /** 消去前に捕捉済みのtaskも、後着時のauth markerによりtransportを新規作成しない。 */
    @Test void PURGING後に解放した実queueはwitnessとoutboxを再作成しない() throws Exception {
        var saved = publish("後着queueの人工本文");
        assertThat(saved.capture()).isNotNull();
        assertThat(receive(saved.capture())).isTrue();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        // 共有contextの受付停止値は変更せず、専有の本番queueだけを有限capacity1で作る。
        var queue = new TimelineRanchCaptureQueue(delivery, transport, utcTelemetry, 1);
        var executor = (java.util.concurrent.ThreadPoolExecutor)
                org.springframework.test.util.ReflectionTestUtils.getField(queue, "executor");
        assertThat(executor).isNotNull();
        try {
            executor.execute(() -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("専有queueの待機期限切れ");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("専有queueの待機中断", error);
                }
            });
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            queue.offer(saved.capture());
            assertThat(executor.getQueue()).hasSize(1);
            var tx = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                var current = users.findByIdForUpdateIncludingDeleted(owner).orElseThrow();
                current.requestDeletion();
            });
            tx.executeWithoutResult(status -> assertThat(users.markPurgeStarted(owner)).isEqualTo(1));
            com.mannschaft.app.auth.dto.DeliveryUserState.Lifecycle currentLifecycle =
                    delivery.withLockedDeliveryUser(owner, state -> state.lifecycle());
            assertThat(currentLifecycle).isEqualTo(com.mannschaft.app.auth.dto.DeliveryUserState.Lifecycle.PURGING);
            assertThat(purge.retryPurge(owner)).isTrue();
            assertThat(count("timeline_ranch_witnesses")).isZero();
            assertThat(count("timeline_ranch_outboxes")).isZero();
            release.countDown();
            queue.close();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.getCompletedTaskCount()).isEqualTo(2);
            assertThat(count("timeline_ranch_witnesses")).isZero();
            assertThat(count("timeline_ranch_outboxes")).isZero();
            assertThat(nativeCount()).isEqualTo(1);
        } finally {
            release.countDown();
            queue.close();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }


    /** CMP-261016-0104: 実native別投稿の同一UTC週dedupを、生成時TZが異なる接続間で検査する。 */
    @Test void 同一UTC週の同本文別投稿は生成時既定TZが異なる接続でも二重受付されない() throws Exception {
        var first = publish("同一週cached TZ比較用の人工本文");
        var second = publish("同一週cached TZ比較用の人工本文");
        assertThat(first.capture()).isNotNull();
        assertThat(second.capture()).isNotNull();
        assertThat(first.response().getId()).isNotEqualTo(second.response().getId());
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
                    var utcTx = new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(utcProbe));
                    var jstTx = new org.springframework.transaction.support.TransactionTemplate(
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(jstProbe));
                    var utcReceiver = new TimelineRanchTransportWriter(new TimelineRanchTransportRepository(utcJdbc),
                            utcJdbc, fingerprints, mapper, java.time.Clock.systemUTC(), utcTelemetry);
                    var jstReceiver = new TimelineRanchTransportWriter(new TimelineRanchTransportRepository(jstJdbc),
                            jstJdbc, fingerprints, mapper, java.time.Clock.systemUTC(), utcTelemetry);
                    Boolean firstAccepted = utcTx.execute(status -> utcReceiver.accept(first.capture()));
                    Boolean secondAccepted = jstTx.execute(status -> jstReceiver.accept(second.capture()));
                    var softly = new org.assertj.core.api.SoftAssertions();
                    softly.assertThat(firstAccepted).as("UTC生成接続の先着受付").isTrue();
                    softly.assertThat(secondAccepted).as("同一UTC週の再受付").isFalse();
                    softly.assertThat(nativeCount()).as("本体の保存件数").isEqualTo(2);
                    softly.assertThat(count("timeline_ranch_witnesses")).as("同一週のwitness件数").isEqualTo(1);
                    softly.assertThat(count("timeline_ranch_outboxes")).as("同一週のoutbox件数").isEqualTo(1);
                    softly.assertThat(jdbc.queryForList("SELECT CAST(content_week AS CHAR) FROM timeline_ranch_witnesses "
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
