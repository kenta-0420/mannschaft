package com.mannschaft.app.proxy.event;

import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyPurgeEventListener 単体テスト")
class ProxyPurgeEventListenerTest {

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;

    @InjectMocks
    private ProxyPurgeEventListener listener;

    private static final Long USER_ID = 500L;

    @Test
    @DisplayName("正常系: records 物理削除 + consents 論理削除 が両方呼ばれる")
    void 正常_両Repositoryが呼ばれる() {
        listener.on(new AccountPurgedEvent(USER_ID, "hash"));

        verify(proxyInputRecordRepository, times(1)).deleteAllBySubjectUserId(USER_ID);
        verify(proxyInputConsentRepository, times(1)).logicalDeleteAllBySubjectUserId(USER_ID);
    }

    @Test
    @DisplayName("正常系: 削除対象 0 件でも例外なし")
    void 正常_0件() {
        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(proxyInputRecordRepository, times(1)).deleteAllBySubjectUserId(USER_ID);
        verify(proxyInputConsentRepository, times(1)).logicalDeleteAllBySubjectUserId(USER_ID);
    }

    @Test
    @DisplayName("異常系: records 物理削除失敗でも consents 論理削除は継続実行される")
    void 異常_records失敗時もconsents継続() {
        willThrow(new RuntimeException("DB error on records"))
                .given(proxyInputRecordRepository).deleteAllBySubjectUserId(USER_ID);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(proxyInputRecordRepository, times(1)).deleteAllBySubjectUserId(USER_ID);
        // 2 操作目は失敗しても継続実行される
        verify(proxyInputConsentRepository, times(1)).logicalDeleteAllBySubjectUserId(USER_ID);
    }

    @Test
    @DisplayName("異常系: consents 論理削除失敗でも例外を伝播させない")
    void 異常_consents失敗_伝播せず() {
        willThrow(new RuntimeException("DB error on consents"))
                .given(proxyInputConsentRepository).logicalDeleteAllBySubjectUserId(USER_ID);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(proxyInputRecordRepository, times(1)).deleteAllBySubjectUserId(USER_ID);
        verify(proxyInputConsentRepository, times(1)).logicalDeleteAllBySubjectUserId(USER_ID);
    }

    @Test
    @DisplayName("異常系: 両 Repository が失敗しても例外を伝播させない")
    void 異常_両方失敗_伝播せず() {
        willThrow(new RuntimeException("DB error on records"))
                .given(proxyInputRecordRepository).deleteAllBySubjectUserId(USER_ID);
        willThrow(new RuntimeException("DB error on consents"))
                .given(proxyInputConsentRepository).logicalDeleteAllBySubjectUserId(USER_ID);

        assertThatCode(() -> listener.on(new AccountPurgedEvent(USER_ID, "hash")))
                .doesNotThrowAnyException();

        verify(proxyInputRecordRepository, times(1)).deleteAllBySubjectUserId(USER_ID);
        verify(proxyInputConsentRepository, times(1)).logicalDeleteAllBySubjectUserId(USER_ID);
    }
}
