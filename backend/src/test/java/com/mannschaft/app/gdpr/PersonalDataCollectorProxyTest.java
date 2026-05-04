package com.mannschaft.app.gdpr;

import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagLinkRepository;
import com.mannschaft.app.actionmemo.repository.ActionMemoTagRepository;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * PersonalDataCollector の proxy カテゴリ収集テスト（F14.1 Phase 13-γ）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalDataCollector proxy カテゴリ収集テスト（F14.1 Phase 13-γ）")
class PersonalDataCollectorProxyTest {

    // PersonalDataCollector が @InjectMocks で注入を受けるには、
    // 全依存フィールドの Mock を宣言する必要がある。
    @Mock private UserRepository userRepository;
    @Mock private OAuthAccountRepository oAuthAccountRepository;
    @Mock private MemberProfileRepository memberProfileRepository;
    @Mock private MemberPaymentRepository memberPaymentRepository;
    @Mock private ChartRecordRepository chartRecordRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private AuditLogRepository auditLogRepository;
    @Mock private NotificationRepository notificationRepository;
    @Mock private ActionMemoRepository actionMemoRepository;
    @Mock private ActionMemoTagRepository actionMemoTagRepository;
    @Mock private ActionMemoTagLinkRepository actionMemoTagLinkRepository;
    @Mock private UserActionMemoSettingsRepository userActionMemoSettingsRepository;
    @Mock private ErrorReportRepository errorReportRepository;
    @Mock private ProxyInputConsentRepository proxyInputConsentRepository;
    @Mock private ProxyInputRecordRepository proxyInputRecordRepository;
    @Mock private EncryptionService encryptionService;

    @InjectMocks private PersonalDataCollector sut;

    @Test
    @DisplayName("proxy_consents カテゴリを収集するとproxyUserIdが仮名化される")
    void collectProxyConsentsAnonymizesProxyUserId() throws Exception {
        Long userId = 100L;
        ProxyInputConsentEntity consent = buildConsent(userId, 200L);
        given(proxyInputConsentRepository.findAllBySubjectUserIdForExport(userId))
                .willReturn(List.of(consent));

        Map<String, String> result = sut.collect(userId, Set.of("proxy_consents"));

        assertThat(result).containsKey("proxy_input_consents.json");
        String json = result.get("proxy_input_consents.json");
        assertThat(json).contains("PROXY_USER_001");
        assertThat(json).doesNotContain("\"200\"");
    }

    @Test
    @DisplayName("proxy_records カテゴリを収集するとproxyUserIdがANONYMIZEDになる")
    void collectProxyRecordsAnonymizesProxyUserId() throws Exception {
        Long userId = 100L;
        ProxyInputRecordEntity record = buildRecord(userId, 200L);
        given(proxyInputRecordRepository.findBySubjectUserId(userId))
                .willReturn(List.of(record));

        Map<String, String> result = sut.collect(userId, Set.of("proxy_records"));

        assertThat(result).containsKey("proxy_input_records.json");
        String json = result.get("proxy_input_records.json");
        assertThat(json).contains("ANONYMIZED");
        assertThat(json).doesNotContain("\"200\"");
    }

    private ProxyInputConsentEntity buildConsent(Long subjectUserId, Long proxyUserId) {
        return ProxyInputConsentEntity.builder()
                .subjectUserId(subjectUserId)
                .proxyUserId(proxyUserId)
                .organizationId(1L)
                .consentMethod(ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveUntil(LocalDate.of(2026, 12, 31))
                .build();
    }

    private ProxyInputRecordEntity buildRecord(Long subjectUserId, Long proxyUserId) {
        return ProxyInputRecordEntity.builder()
                .proxyInputConsentId(1L)
                .subjectUserId(subjectUserId)
                .proxyUserId(proxyUserId)
                .featureScope("SURVEY")
                .targetEntityType("SURVEY_RESPONSE")
                .targetEntityId(999L)
                .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                .originalStorageLocation("書類棚A-1")
                .build();
    }
}
