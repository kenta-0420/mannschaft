package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link AccountPurgeCompletionService} 単体テスト（残債1・ArchUnit D-3 是正）。
 *
 * <p>他ドメイン（billing）からの purge 完了報告を gdpr ドメイン内の bulk update に橋渡しする
 * 公開 API の契約（SUCCESS 更新の委譲・対象 0 件でも例外にしない）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AccountPurgeCompletionService 単体テスト（per-domain 完了報告）")
class AccountPurgeCompletionServiceTest {

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;

    @InjectMocks
    private AccountPurgeCompletionService service;

    @Test
    @DisplayName("markDomainSuccess: リポジトリの bulk update（markSuccess）へ委譲する")
    void markDomainSuccess_delegatesToBulkUpdate() {
        given(completionStatusRepository.markSuccess(eq(9L), eq("billing"), any())).willReturn(1);

        service.markDomainSuccess(9L, "billing");

        verify(completionStatusRepository).markSuccess(eq(9L), eq("billing"), any());
    }

    @Test
    @DisplayName("markDomainSuccess: 対象レコードなし（0 件更新）でも例外にしない（WARN ログのみ）")
    void markDomainSuccess_zeroUpdated_doesNotThrow() {
        given(completionStatusRepository.markSuccess(eq(9L), eq("billing"), any())).willReturn(0);

        assertThatCode(() -> service.markDomainSuccess(9L, "billing"))
                .doesNotThrowAnyException();
    }
}
