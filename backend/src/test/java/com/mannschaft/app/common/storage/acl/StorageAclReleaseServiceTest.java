package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** 添付単位の解放と再送の契約。SQL の状態条件は実 MySQL テストで検証する。 */
@ExtendWith(MockitoExtension.class)
class StorageAclReleaseServiceTest {
    private static final StorageAclAttachmentBinding BINDING =
            new StorageAclAttachmentBinding("FORM_SUBMISSION_VALUE", "301");

    @Mock
    private StorageAclRepository repository;

    @Test
    void 一度目の解放は条件付き更新だけで完了する() {
        given(repository.releaseClaimed("forms/key", BINDING.type(), BINDING.key())).willReturn(1);

        new StorageAclService(repository, Clock.systemUTC()).releaseClaimed("forms/key", BINDING);

        verify(repository).releaseClaimed("forms/key", BINDING.type(), BINDING.key());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void 同一束縛の解放再送は冪等に成功する() {
        given(repository.findReleasedFileKey("forms/key", BINDING.type(), BINDING.key()))
                .willReturn(Optional.of("forms/key"));

        new StorageAclService(repository, Clock.systemUTC()).releaseClaimed("forms/key", BINDING);

        verify(repository).releaseClaimed("forms/key", BINDING.type(), BINDING.key());
    }

    @Test
    void 不在や束縛不一致や未claimは404で秘匿する() {
        assertThatThrownBy(() -> new StorageAclService(repository, Clock.systemUTC())
                .releaseClaimed("forms/key", BINDING))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
    }

    @Test
    void 空keyや束縛なしはSQL発行前に拒否する() {
        StorageAclService service = new StorageAclService(repository, Clock.systemUTC());
        assertThatThrownBy(() -> service.releaseClaimed(" ", BINDING))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.releaseClaimed("forms/key", null))
                .isInstanceOf(BusinessException.class);
        verifyNoMoreInteractions(repository);
    }
}
