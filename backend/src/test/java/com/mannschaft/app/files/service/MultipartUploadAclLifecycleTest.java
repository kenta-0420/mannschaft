package com.mannschaft.app.files.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.acl.MultipartContentTargetRegistry;
import com.mannschaft.app.common.storage.acl.StorageAclAttachmentBinding;
import com.mannschaft.app.common.storage.acl.StorageAclContentReference;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.files.dto.CompleteMultipartRequest;
import com.mannschaft.app.files.dto.PartUrlRequest;
import com.mannschaft.app.files.dto.StartMultipartUploadRequest;
import com.mannschaft.app.files.entity.MultipartUploadSessionEntity;
import com.mannschaft.app.files.repository.MultipartUploadSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** multipart の期限・claim・外部完了後の再試行に関する回帰試練。 */
@ExtendWith(MockitoExtension.class)
class MultipartUploadAclLifecycleTest {
    @Mock private R2StorageService storage;
    @Mock private MultipartUploadSessionRepository sessions;
    @Mock private StorageAclService acl;
    @Mock private MultipartUploadCleanupService cleanup;
    @Mock private MultipartContentTargetRegistry targets;
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-13T00:00:00Z"), ZoneOffset.UTC);
    private MultipartUploadService service;

    @BeforeEach
    void setUp() {
        service = new MultipartUploadService(storage, sessions, acl, cleanup, targets, clock);
    }

    @Test
    void 任意の子プレフィックスはR2開始前に拒否する() {
        var request = new StartMultipartUploadRequest(null, "v.mp4", "video/mp4",
                100L, 1, 5242880L, "blog/TEAM/999/");
        assertThatThrownBy(() -> service.startUpload(1L, request))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).createMultipartUpload(anyString(), anyString());
    }

    @Test
    void 期限切れ境界ではURL発行と完了を拒否する() {
        MultipartUploadSessionEntity expired = session()
                .toBuilder().expiresAt(LocalDateTime.now(clock)).build();
        when(sessions.findByUploadId("upload")).thenReturn(Optional.of(expired));
        when(sessions.findByUploadIdForUpdate("upload")).thenReturn(Optional.of(expired));
        assertThatThrownBy(() -> service.getPartUrls("upload", 1L,
                new PartUrlRequest("files/v.mp4", List.of(1))))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.completeUpload("upload", 1L, complete()))
                .isInstanceOf(ResponseStatusException.class);
        verify(storage, never()).createPresignedPartUrls(anyString(), anyString(), anyList(), any());
        verify(storage, never()).completeMultipartUpload(anyString(), anyString(), anyList());
    }

    @Test
    void 廃止前に作成された汎用セッションもURL発行と完了を拒否する() {
        MultipartUploadSessionEntity legacyGeneric = session();
        when(sessions.findByUploadId("upload")).thenReturn(Optional.of(legacyGeneric));
        when(sessions.findByUploadIdForUpdate("upload")).thenReturn(Optional.of(legacyGeneric));

        assertThatThrownBy(() -> service.getPartUrls("upload", 1L,
                new PartUrlRequest("files/v.mp4", List.of(1))))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(410);
        assertThatThrownBy(() -> service.completeUpload("upload", 1L, complete()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(exception -> ((ResponseStatusException) exception).getStatusCode().value())
                .isEqualTo(410);

        verify(storage, never()).createPresignedPartUrls(anyString(), anyString(), anyList(), any());
        verify(storage, never()).completeMultipartUpload(anyString(), anyString(), anyList());
    }

    @Test
    void 完了時に開始セッションへACLをclaimする() {
        when(sessions.findByUploadIdForUpdate("upload")).thenReturn(Optional.of(session()));
        service.completeUpload("upload", 1L, complete());
        verify(acl).claimPending("files/v.mp4", 1L, StorageAclScope.personal(1L),
                new StorageAclContentReference("MULTIPART_UPLOAD", "upload"),
                new StorageAclAttachmentBinding("MULTIPART_UPLOAD", "upload"));
        verify(storage).completeMultipartUpload(anyString(), anyString(), anyList());
    }

    @Test
    void claim失敗ではR2を完成させない() {
        when(sessions.findByUploadIdForUpdate("upload")).thenReturn(Optional.of(session()));
        doThrow(new IllegalStateException("claim failed")).when(acl)
                .claimPending(anyString(), any(), any(), any(), any());
        assertThatThrownBy(() -> service.completeUpload("upload", 1L, complete()))
                .isInstanceOf(IllegalStateException.class);
        verify(storage, never()).completeMultipartUpload(anyString(), anyString(), anyList());
        verify(sessions, never()).save(any());
    }

    @Test
    void R2完了後DB失敗の再試行は完成済みオブジェクトから復旧する() {
        when(sessions.findByUploadIdForUpdate("upload")).thenReturn(Optional.of(session()));
        when(storage.objectExists("files/v.mp4")).thenReturn(true);
        service.completeUpload("upload", 1L, complete());
        verify(storage, never()).completeMultipartUpload(anyString(), anyString(), anyList());
        verify(acl).claimPending(anyString(), any(), any(), any(), any());
        verify(sessions).save(any());
    }

    private MultipartUploadSessionEntity session() {
        return MultipartUploadSessionEntity.builder().uploadId("upload").r2Key("files/v.mp4")
                .feature("files").scopeType("PERSONAL").scopeId(1L).uploaderId(1L)
                .contentType("video/mp4").status("IN_PROGRESS")
                .expiresAt(LocalDateTime.now(clock).plusHours(1)).build();
    }

    private CompleteMultipartRequest complete() {
        return new CompleteMultipartRequest("files/v.mp4",
                List.of(new CompleteMultipartRequest.PartEtag(1, "etag")));
    }
}
