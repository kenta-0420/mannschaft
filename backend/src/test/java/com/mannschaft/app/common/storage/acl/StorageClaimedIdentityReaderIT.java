package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** 実claim/同native TXのcurrent scalar lookup。HTTP・報酬・DB障害隔離の実測とは別。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class StorageClaimedIdentityReaderIT extends AbstractMySqlIntegrationTest {
    @Autowired private StorageAclService claims;
    @Autowired private StorageAclRepository rows;
    @Autowired private StorageClaimedIdentityReader identities;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private Long owner;
    private String key;
    private StorageAclScope scope;
    private final StorageAclContentReference parent=new StorageAclContentReference("TIMELINE_SCOPE","PERSONAL:1");
    private final StorageAclAttachmentBinding binding=new StorageAclAttachmentBinding("TIMELINE_POST_ATTACHMENT","1");

    @BeforeEach void fixture() {
        owner=users.saveAndFlush(UserEntity.builder().email(UUID.randomUUID()+"@identity.invalid")
                .lastName("検証").firstName("本人").displayName("検証").isSearchable(false)
                .locale("ja").timezone("UTC").status(UserEntity.UserStatus.ACTIVE).build()).getId();
        key="synthetic/"+UUID.randomUUID();scope=StorageAclScope.personal(owner);
        claims.registerPending(key,owner,scope,"image/png",Duration.ofMinutes(10),parent);
    }
    @AfterEach void cleanupOwnRows() {
        if(key!=null) rows.findByFileKey(key).ifPresent(rows::delete);
        if(owner!=null) users.deleteById(owner);
    }
    @Test void exactCurrentClaimReturnsStoredUploadUuidAndRejectsEveryForeignBoundary() {
        UUID expected=rows.findByFileKey(key).orElseThrow().getId();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            claims.claimPending(key,owner,scope,parent,binding);
            assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,binding)).contains(expected);
            assertThat(identities.currentClaimedIdentity(key,owner+1,scope,parent,binding)).isEmpty();
            assertThat(identities.currentClaimedIdentity(key,owner,StorageAclScope.personal(owner+1),parent,binding)).isEmpty();
            assertThat(identities.currentClaimedIdentity(key,owner,scope,
                    new StorageAclContentReference("TIMELINE_SCOPE","PERSONAL:2"),binding)).isEmpty();
            assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,
                    new StorageAclAttachmentBinding("TIMELINE_POST_ATTACHMENT","2"))).isEmpty();
        });
    }
    @Test void pendingOrNoNativeTransactionCannotProduceIdentity() {
        assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,binding)).isEmpty();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,binding)).isEmpty());
    }
    @Test void rollbackLeavesPendingUploadAndNoLaterClaimedIdentity() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            claims.claimPending(key,owner,scope,parent,binding);
            assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,binding)).isPresent();
            status.setRollbackOnly();
        });
        assertThat(rows.findByFileKey(key).orElseThrow().getStatus()).isEqualTo(StorageAclStatus.PENDING);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                assertThat(identities.currentClaimedIdentity(key,owner,scope,parent,binding)).isEmpty());
    }
}
