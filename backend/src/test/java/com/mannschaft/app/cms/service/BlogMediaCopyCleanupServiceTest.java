package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.media.BlogMediaScope;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** ロールバック後の再削除で、加算されていない使用量を減算しないことを検証する。 */
@ExtendWith(MockitoExtension.class)
class BlogMediaCopyCleanupServiceTest {
    @Mock private R2StorageService storageService;
    @Mock private BlogMediaR2DeleteRetryRepository retryRepository;
    @InjectMocks private BlogMediaCopyCleanupService service;

    @Test
    void 削除失敗は使用量減算なしの再試行として永続化する() {
        String key = "blog/TEAM/12/new-copy";
        doThrow(new IllegalStateException("unavailable")).when(storageService).delete(key);
        service.cleanup(key, new BlogMediaScope(StorageScopeType.TEAM, 12L));
        ArgumentCaptor<BlogMediaR2DeleteRetryEntity> retry = ArgumentCaptor.forClass(BlogMediaR2DeleteRetryEntity.class);
        verify(retryRepository).save(retry.capture());
        assertThat(retry.getValue().getObjectKey()).isEqualTo(key);
        assertThat(retry.getValue().getFileSize()).isZero();
        assertThat(retry.getValue().getScopeId()).isEqualTo("12");
    }
}
