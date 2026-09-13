package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.acl.MultipartContentTargetRegistry;
import com.mannschaft.app.common.storage.acl.StorageAclRepository;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.acl.StorageAclStatus;
import com.mannschaft.app.files.dto.CompleteMultipartRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.repository.MultipartUploadSessionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 実MySQLでmultipart完了とACL claimのrollback・再試行を検証する。R2通信だけを模擬する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MultipartUploadTransactionIT extends AbstractMySqlIntegrationTest {
    @Autowired private MultipartUploadSessionRepository sessions;
    @Autowired private StorageAclRepository acls;
    @Autowired private StorageAclService aclService;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void R2完了後のDBrollbackではPENDINGが残り再試行でCLAIMEDへ復旧する() {
        R2StorageService storage = mock(R2StorageService.class);
        String uploadId = UUID.randomUUID().toString();
        when(storage.createMultipartUpload(anyString(), anyString())).thenReturn(uploadId);
        var service = new MultipartUploadService(storage, sessions, aclService,
                mock(MultipartUploadCleanupService.class), mock(MultipartContentTargetRegistry.class), Clock.systemUTC());
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var started = tx.execute(status -> service.startUpload(9001L,
                new StartMultipartUploadRequest(null, "video.mp4", "video/mp4", 100L, 1, 5242880L, "files/")));
        var complete = new CompleteMultipartRequest(started.getFileKey(),
                List.of(new CompleteMultipartRequest.PartEtag(1, "etag")));
        try {
            tx.executeWithoutResult(status -> {
                service.completeUpload(uploadId, 9001L, complete);
                // R2は取り消せないが、DBの最終保存/commit失敗はこのrollbackと同じ状態を残す。
                status.setRollbackOnly();
            });
            assertThat(acls.findByFileKey(started.getFileKey())).get()
                    .extracting(acl -> acl.getStatus()).isEqualTo(StorageAclStatus.PENDING);
            assertThat(sessions.findByUploadId(uploadId)).get()
                    .extracting(session -> session.getStatus()).isEqualTo("IN_PROGRESS");

            when(storage.objectExists(started.getFileKey())).thenReturn(true);
            tx.executeWithoutResult(status -> service.completeUpload(uploadId, 9001L, complete));
            assertThat(acls.findByFileKey(started.getFileKey())).get()
                    .extracting(acl -> acl.getStatus()).isEqualTo(StorageAclStatus.CLAIMED);
            assertThat(sessions.findByUploadId(uploadId)).get()
                    .extracting(session -> session.getStatus()).isEqualTo("COMPLETED");
            verify(storage, times(1)).completeMultipartUpload(anyString(), anyString(), anyList());
        } finally {
            tx.executeWithoutResult(status -> {
                sessions.findByUploadId(uploadId).ifPresent(sessions::delete);
                acls.findByFileKey(started.getFileKey()).ifPresent(acls::delete);
            });
        }
    }
}
