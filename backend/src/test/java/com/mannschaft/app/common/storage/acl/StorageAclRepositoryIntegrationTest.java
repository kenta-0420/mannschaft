package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLでACL台帳のINSERT/commitとキー検索を確認する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class StorageAclRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private StorageAclRepository repository;

    @Test
    void pendingAclをINSERTしてcommit後に取得できる() {
        StorageAclEntity saved = repository.saveAndFlush(StorageAclEntity.builder()
                .fileKey("integration/storage-acl-" + System.nanoTime())
                .ownerId(9001L)
                .scopeType("TEAM")
                .scopeId(9002L)
                .aclMode(StorageAclMode.CONTENT_BOUND)
                .contentType("video/mp4")
                .referenceType("WORKFLOW_REQUEST")
                .referenceId(9003L)
                .status(StorageAclStatus.PENDING)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .build());

        assertThat(repository.findByFileKey(saved.getFileKey())).get()
                .extracting(StorageAclEntity::getStatus)
                .isEqualTo(StorageAclStatus.PENDING);
    }
}
