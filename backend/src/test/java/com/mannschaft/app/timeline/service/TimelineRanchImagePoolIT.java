package com.mannschaft.app.timeline.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.auth.service.UserRewardDeliveryGuard;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclRepository;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.dto.CreateAttachmentRequest;
import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.repository.TimelineRanchTransportRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** 専用P2 contextで、通常quota初期化後の正サイズIMAGEを実Beanで検証する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@TestPropertySource(properties={"spring.datasource.hikari.maximum-pool-size=2",
        "spring.datasource.hikari.minimum-idle=0", "app.datasource.replica.enabled=false",
        "ranch.source.timeline.queue-capacity=0"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class TimelineRanchImagePoolIT extends AbstractMySqlIntegrationTest {
    @Autowired UserRepository users;
    @Autowired TimelinePostRepository posts;
    @Autowired TimelineRanchTransportRepository rows;
    @Autowired UserOperationGuard active;
    @Autowired UserRewardDeliveryGuard delivery;
    @Autowired TimelineRanchNativeWriter writer;
    @Autowired TimelineRanchTransportWriter transport;
    @Autowired StorageAclService claims;
    @Autowired StorageAclRepository acls;
    @Autowired StorageQuotaService quota;
    @Autowired StoragePlanRepository plans;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    private Long owner;
    private Long ownPlan;
    private String uploadKey;
    private final List<Long> ownPosts=new ArrayList<>();

    @BeforeEach void fixture() {
        owner=null;ownPlan=null;ownPosts.clear();
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource)dataSource).getMaximumPoolSize()).isEqualTo(2);
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@image-pool.invalid")
                .lastName("検証").firstName("本人").displayName("検証").locale("ja").timezone("UTC")
                .isSearchable(false).status(UserEntity.UserStatus.ACTIVE).build()).getId();
        if(plans.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL").isEmpty()) {
            ownPlan=plans.saveAndFlush(StoragePlanEntity.builder().name("画像P2専用検証")
                    .scopeLevel("PERSONAL").includedBytes(1048576L).priceMonthly(BigDecimal.ZERO)
                    .isDefault(true).sortOrder((short)0).build()).getId();
        }
        uploadKey="synthetic/image-pool/"+UUID.randomUUID();
    }
    @AfterEach void cleanupOwnRows() {
        if(owner==null) return;
        rows.deleteForUser(owner);
        // 保存が成功した直後のassert失敗でも、自分の投稿だけを回収する。
        ownPosts.addAll(jdbc.queryForList("SELECT id FROM timeline_posts WHERE user_id=?",Long.class,owner));
        for(Long id:ownPosts.stream().distinct().toList()) {
            jdbc.update("DELETE FROM timeline_post_attachments WHERE timeline_post_id=?",id);
            posts.deleteById(id);
        }
        acls.findByFileKey(uploadKey).ifPresent(acls::delete);
        jdbc.update("DELETE FROM storage_usage_logs WHERE subscription_id IN (SELECT id FROM storage_subscriptions WHERE scope_type='PERSONAL' AND scope_id=?)",owner);
        jdbc.update("DELETE FROM storage_subscriptions WHERE scope_type='PERSONAL' AND scope_id=?",owner);
        users.deleteById(owner);
        if(ownPlan!=null) plans.deleteById(ownPlan);
    }
    @Test void presignQuotaInitializationKeepsPositiveImageSaveAndCaptureWithinTwoConnections() throws Exception {
        // 実presignのquota段を再現する。外部R2署名やHTTP認可を検証したとは扱わない。
        quota.checkQuota(StorageScopeType.PERSONAL,owner,0L);
        assertThat(subscriptionCount()).isEqualTo(1);
        registerPending();
        assertSaveAndCapture();
    }
    private void registerPending() {
        claims.registerPending(uploadKey,owner,StorageAclScope.personal(owner),"image/png",Duration.ofMinutes(10),
                new StorageAclContentReference("TIMELINE_SCOPE","PERSONAL:"+owner));
    }
    private void assertSaveAndCapture() throws Exception {
        CreateAttachmentRequest attachment=mapper.readValue("{\"attachmentType\":\"IMAGE\",\"fileKey\":\""
                +uploadKey+"\",\"fileSize\":1}",CreateAttachmentRequest.class);
        var request=new CreatePostRequest("画像P2検証本文","PERSONAL",owner.toString(),"USER",
                null,null,null,null,null,List.of(attachment),null,null);
        var saved=active.withActiveUser(owner,()->writer.create(request,owner,owner));
        ownPosts.add(saved.response().getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_posts WHERE user_id=?",Integer.class,owner)).isEqualTo(1);
        assertThat(subscriptionCount()).isEqualTo(1);
        assertThat(saved.capture()).isNotNull();
        boolean accepted=delivery.withLockedDeliveryUser(owner,state->transport.accept(saved.capture()));
        assertThat(accepted).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM timeline_ranch_outboxes WHERE recipient_user_id=?",Integer.class,owner)).isEqualTo(1);
    }
    private int subscriptionCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM storage_subscriptions WHERE scope_type='PERSONAL' AND scope_id=?",Integer.class,owner);
    }
}