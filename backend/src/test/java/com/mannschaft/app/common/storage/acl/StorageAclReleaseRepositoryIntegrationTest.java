package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実 MySQL で解放の条件、冪等性、transaction 境界を固定する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class StorageAclReleaseRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final StorageAclAttachmentBinding BINDING =
            new StorageAclAttachmentBinding("FORM_SUBMISSION_VALUE", "301");

    @Autowired private StorageAclRepository repository;
    @Autowired private StorageAclService service;
    @Autowired private StorageAccessService accessService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void 同じ親の別添付を残して対象のみ解放し同じtransactionで再送できる() {
        String fileKey = "integration/acl-release-" + System.nanoTime();
        String sibling = fileKey + "-sibling";
        tx().executeWithoutResult(status -> {
            repository.saveAndFlush(claimed(fileKey));
            repository.saveAndFlush(claimed(sibling).toBuilder().attachmentBindingKey("302").build());
        });

        tx().executeWithoutResult(status -> {
            // 先に管理済み entity と snapshot を作っても、解放後の再送は古い CLAIMED を参照しない。
            repository.findByFileKey(fileKey).orElseThrow();
            service.releaseClaimed(fileKey, BINDING);
            service.releaseClaimed(fileKey, BINDING);
        });

        assertThat(readStatus(fileKey)).isEqualTo(StorageAclStatus.REVOKED);
        assertThat(readStatus(sibling)).isEqualTo(StorageAclStatus.CLAIMED);
        assertThatThrownBy(() -> accessService.generateDownloadUrl(fileKey, StorageAclScope.team(9002L),
                new StorageAclContentReference("FORM_SUBMISSION", "200"), BINDING, java.time.Duration.ofMinutes(5)))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
        assertThatThrownBy(() -> service.claimPending(fileKey, 9001L, StorageAclScope.team(9002L),
                new StorageAclContentReference("FORM_SUBMISSION", "200"), BINDING))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_CLAIM_CONFLICT);
    }

    @Test
    void fileKeyと束縛の大小文字を含む不一致は解放しない() {
        String fileKey = "integration/acl-release-case-" + System.nanoTime();
        tx().executeWithoutResult(status -> repository.saveAndFlush(claimed(fileKey)));

        assertRejected(fileKey.toUpperCase(java.util.Locale.ROOT), BINDING);
        assertRejected(fileKey, new StorageAclAttachmentBinding("form_submission_value", "301"));
        assertRejected(fileKey, new StorageAclAttachmentBinding("FORM_SUBMISSION_VALUE", "302"));

        assertThat(readStatus(fileKey)).isEqualTo(StorageAclStatus.CLAIMED);
    }

    @Test
    void PENDINGとCONTENT_BOUND以外は解放しない() {
        String pendingKey = "integration/acl-release-pending-" + System.nanoTime();
        String otherModeKey = pendingKey + "-mode";
        StorageAclMode otherMode = java.util.Arrays.stream(StorageAclMode.values())
                .filter(mode -> mode != StorageAclMode.CONTENT_BOUND).findFirst().orElseThrow();
        tx().executeWithoutResult(status -> {
            repository.saveAndFlush(claimed(pendingKey).toBuilder().status(StorageAclStatus.PENDING).build());
            repository.saveAndFlush(claimed(otherModeKey).toBuilder().aclMode(otherMode).build());
        });

        assertRejected(pendingKey, BINDING);
        assertRejected(otherModeKey, BINDING);
        assertThat(readStatus(pendingKey)).isEqualTo(StorageAclStatus.PENDING);
        assertThat(readStatus(otherModeKey)).isEqualTo(StorageAclStatus.CLAIMED);
    }

    @Test
    void 添付更新がrollbackすると解放もrollbackする() {
        String fileKey = "integration/acl-release-rollback-" + System.nanoTime();
        tx().executeWithoutResult(status -> repository.saveAndFlush(claimed(fileKey)));

        tx().executeWithoutResult(status -> {
            service.releaseClaimed(fileKey, BINDING);
            status.setRollbackOnly();
        });

        assertThat(readStatus(fileKey)).isEqualTo(StorageAclStatus.CLAIMED);
    }

    private void assertRejected(String fileKey, StorageAclAttachmentBinding binding) {
        assertThatThrownBy(() -> service.releaseClaimed(fileKey, binding))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
    }

    private StorageAclStatus readStatus(String key) {
        return tx().execute(status -> repository.findByFileKey(key).orElseThrow().getStatus());
    }

    private StorageAclEntity claimed(String fileKey) {
        return StorageAclEntity.builder().fileKey(fileKey).ownerId(9001L)
                .scopeType(StorageAclScopeType.TEAM).scopeKey("9002")
                .aclMode(StorageAclMode.CONTENT_BOUND).contentType("image/png")
                .parentContentReferenceType("FORM_SUBMISSION").parentContentReferenceKey("200")
                .attachmentBindingType(BINDING.type()).attachmentBindingKey(BINDING.key())
                .status(StorageAclStatus.CLAIMED).expiresAt(Instant.now().plusSeconds(900)).build();
    }

    private TransactionTemplate tx() {
        TransactionTemplate result = new TransactionTemplate(transactionManager);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return result;
    }
}
