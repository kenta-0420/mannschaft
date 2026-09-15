package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.common.storage.acl.StorageAclService;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 複製時の独立 ACL と途中失敗の外部オブジェクト補償を検証する。 */
@ExtendWith(MockitoExtension.class)
class BlogMediaCopyServiceTest {
    private static final String KEY = "blog/TEAM/12/source.mp4";
    @Mock private BlogBodyMediaResolver bodyResolver;
    @Mock private BlogMediaUploadRepository mediaRepository;
    @Mock private StorageAccessService accessService;
    @Mock private StorageAclService aclService;
    @Mock private R2StorageService storageService;
    @Mock private StorageQuotaService quotaService;
    @Mock private BlogMediaCopyCleanupService cleanupService;
    @InjectMocks private BlogMediaCopyService service;
    private BlogPostEntity original;
    private BlogPostEntity copy;
    private BlogMediaUploadEntity media;

    @BeforeEach
    void setUp() {
        original = BlogPostEntity.builder().id(100L).teamId(12L).body("<video src=\"" + KEY + "\">").build();
        copy = BlogPostEntity.builder().id(101L).teamId(12L).body(original.getBody()).build();
        media = BlogMediaUploadEntity.builder().id(7L).blogPostId(100L).uploaderId(1L)
                .scopeType("TEAM").scopeId(12L).s3Key(KEY).mediaType("VIDEO")
                .contentType("video/mp4").fileSize(50L).processingStatus("READY").build();
        when(bodyResolver.extractR2Keys(original.getBody())).thenReturn(List.of(KEY));
        when(mediaRepository.findForPostBinding(anyCollection())).thenReturn(List.of(media));
    }

    private void successfulSave() {
        when(mediaRepository.save(any())).thenAnswer(invocation ->
                ((BlogMediaUploadEntity) invocation.getArgument(0)).toBuilder().id(8L).build());
    }

    @Test
    void 複製元とは異なるキーと台帳とbindingを発行する() {
        successfulSave();
        when(bodyResolver.replaceKeys(eq(original.getBody()), anyMap())).thenAnswer(invocation ->
                original.getBody().replace(KEY, ((java.util.Map<String, String>) invocation.getArgument(1)).get(KEY)));
        service.copyMedia(original, copy, 1L);
        ArgumentCaptor<BlogMediaUploadEntity> saved = ArgumentCaptor.forClass(BlogMediaUploadEntity.class);
        verify(mediaRepository).save(saved.capture());
        var destination = saved.getValue();
        assertThat(destination.getS3Key()).startsWith("blog/TEAM/12/").isNotEqualTo(KEY);
        assertThat(destination.getBlogPostId()).isEqualTo(101L);
        assertThat(copy.getBody()).contains(destination.getS3Key()).doesNotContain(KEY);
        verify(storageService).copyObject(KEY, destination.getS3Key());
        var target = BlogMediaAclService.targetOf(destination.toBuilder().id(8L).build());
        verify(aclService).claimPending(destination.getS3Key(), 1L, target.scope(), target.parent(), target.binding());
        verifyNoInteractions(cleanupService);
    }

    @Test
    void 別記事の台帳を複製元に使わせない() {
        when(mediaRepository.findForPostBinding(anyCollection())).thenReturn(List.of(media.toBuilder().blogPostId(99L).build()));
        assertThatThrownBy(() -> service.copyMedia(original, copy, 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storageService, aclService, quotaService);
    }

    @Test
    void 未claimの複製元は外部コピー前に拒否する() {
        when(accessService.generateDownloadUrl(anyString(), any(), any(), any(), any()))
                .thenThrow(new BusinessException(StorageErrorCode.ACL_NOT_FOUND));
        assertThatThrownBy(() -> service.copyMedia(original, copy, 1L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storageService, aclService, quotaService);
    }

    @Test
    void クォータ拒否ではコピーも新規台帳保存も行わない() {
        doThrow(new IllegalStateException("quota denied")).when(quotaService).checkQuota(any(), eq(12L), eq(50L));
        assertThatThrownBy(() -> service.copyMedia(original, copy, 1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(storageService, aclService);
        verify(mediaRepository, never()).save(any());
    }

    @Test
    void コピーの曖昧な失敗でも新しいキーだけを補償削除する() {
        successfulSave();
        doThrow(new IllegalStateException("copy failed")).when(storageService).copyObject(eq(KEY), anyString());
        assertThatThrownBy(() -> service.copyMedia(original, copy, 1L)).isInstanceOf(IllegalStateException.class);
        verify(cleanupService).cleanup(argThat(key -> key.startsWith("blog/TEAM/12/") && !key.equals(KEY)), any());
        verify(aclService, never()).claimPending(anyString(), anyLong(), any(), any(), any());
    }

    @Test
    void 完了後のDBロールバックでも複製先のみを削除する() {
        successfulSave();
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.copyMedia(original, copy, 1L);
            verifyNoInteractions(cleanupService);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                    sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(cleanupService).cleanup(argThat(key -> !KEY.equals(key)), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
