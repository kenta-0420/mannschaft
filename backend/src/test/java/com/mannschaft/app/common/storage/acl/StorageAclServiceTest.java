package com.mannschaft.app.common.storage.acl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class StorageAclServiceTest {

    @Mock
    private StorageAclRepository repository;

    @Test
    void presignキーをPENDING_CONTENT_BOUNDとして登録する() {
        StorageAclService service = new StorageAclService(repository, Clock.systemUTC());
        given(repository.findByFileKey("workflow/key")).willReturn(java.util.Optional.empty());
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.registerPending("workflow/key", 7L, "TEAM", 3L, "video/mp4", Duration.ofMinutes(15),
                "WORKFLOW_REQUEST", 11L);

        ArgumentCaptor<StorageAclEntity> captor = ArgumentCaptor.forClass(StorageAclEntity.class);
        verify(repository).save(captor.capture());
        StorageAclEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(StorageAclStatus.PENDING);
        assertThat(saved.getAclMode()).isEqualTo(StorageAclMode.CONTENT_BOUND);
        assertThat(saved.getOwnerId()).isEqualTo(7L);
        assertThat(saved.getScopeType()).isEqualTo("TEAM");
        assertThat(saved.getReferenceId()).isEqualTo(11L);
    }

    @Test
    void 重複キーは登録しない() {
        StorageAclService service = new StorageAclService(repository, Clock.systemUTC());
        given(repository.findByFileKey("duplicate")).willReturn(java.util.Optional.of(
                StorageAclEntity.builder().fileKey("duplicate").build()));

        assertThatThrownBy(() -> service.registerPending("duplicate", 7L, "TEAM", 3L, "image/png",
                Duration.ofMinutes(1), null, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 期限が正でない場合は拒否する() {
        StorageAclService service = new StorageAclService(repository, Clock.systemUTC());

        assertThatThrownBy(() -> service.registerPending("key", 7L, "TEAM", 3L, "image/png",
                Duration.ZERO, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
