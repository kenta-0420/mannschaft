package com.mannschaft.app.proxy.batch;

import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * ProxyPurgeBackfillBatchService 単体テスト（Phase D-6）。
 */
@ExtendWith(MockitoExtension.class)
class ProxyPurgeBackfillBatchServiceTest {

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @InjectMocks
    private ProxyPurgeBackfillBatchService sut;

    @Test
    @DisplayName("孤児が複数件存在する場合、records を物理削除し consents を論理削除する")
    void backfill_deletesOrphans() {
        given(proxyInputRecordRepository.deleteOrphanBySubjectUserId()).willReturn(3);
        given(proxyInputConsentRepository.logicalDeleteOrphanBySubjectUserId()).willReturn(2);

        sut.backfill();

        verify(proxyInputRecordRepository).deleteOrphanBySubjectUserId();
        verify(proxyInputConsentRepository).logicalDeleteOrphanBySubjectUserId();
    }

    @Test
    @DisplayName("孤児が 0 件の場合も正常終了する")
    void backfill_noOrphans() {
        given(proxyInputRecordRepository.deleteOrphanBySubjectUserId()).willReturn(0);
        given(proxyInputConsentRepository.logicalDeleteOrphanBySubjectUserId()).willReturn(0);

        sut.backfill();

        verify(proxyInputRecordRepository).deleteOrphanBySubjectUserId();
        verify(proxyInputConsentRepository).logicalDeleteOrphanBySubjectUserId();
    }
}
