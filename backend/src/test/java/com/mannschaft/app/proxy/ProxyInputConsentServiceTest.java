package com.mannschaft.app.proxy;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.proxy.service.CreateProxyConsentCommand;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import com.mannschaft.app.proxy.service.RevokeConsentCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyInputConsentService} の単体テスト。
 * 同意書の登録・承認・撤回のビジネスロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyInputConsentService 単体テスト")
class ProxyInputConsentServiceTest {

    @Mock
    private ProxyInputConsentRepository consentRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StorageService storageService;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ProxyInputConsentService service;

    private static final Long REQUEST_USER_ID = 1L;
    private static final Long SUBJECT_USER_ID = 100L;
    private static final Long PROXY_USER_ID = 200L;
    private static final Long ORG_ID = 10L;

    private CreateProxyConsentCommand buildValidCommand() {
        return new CreateProxyConsentCommand(
                SUBJECT_USER_ID,
                PROXY_USER_ID,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                "proxy-consents/2026/04/scan.pdf",
                null,
                null,
                LocalDate.now(),
                LocalDate.now().plusMonths(6),
                List.of(ProxyInputConsentScopeEntity.FeatureScope.SURVEY)
        );
    }

    private ProxyInputConsentEntity buildConsent(long subjectUserId, long proxyUserId) {
        return ProxyInputConsentEntity.create(
                subjectUserId, proxyUserId, ORG_ID,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                null, null, null,
                LocalDate.now(), LocalDate.now().plusMonths(6));
    }

    // ─────────────────────────────────────────────────────────────
    // createConsent
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("createConsent — 同意書登録")
    class CreateConsent {

        @Test
        @DisplayName("有効期間が 365 日超 → BusinessException")
        void shouldThrowWhenEffectivePeriodExceeds365Days() {
            CreateProxyConsentCommand cmd = new CreateProxyConsentCommand(
                    SUBJECT_USER_ID, PROXY_USER_ID,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null, null, null,
                    LocalDate.now(), LocalDate.now().plusDays(366),
                    List.of(ProxyInputConsentScopeEntity.FeatureScope.SURVEY));

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, cmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("有効期間が 0 以下（from >= until）→ BusinessException")
        void shouldThrowWhenEffectivePeriodIsZeroOrNegative() {
            CreateProxyConsentCommand cmd = new CreateProxyConsentCommand(
                    SUBJECT_USER_ID, PROXY_USER_ID,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null, null, null,
                    LocalDate.now(), LocalDate.now(),
                    List.of(ProxyInputConsentScopeEntity.FeatureScope.SURVEY));

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, cmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("scopes が空 → BusinessException")
        void shouldThrowWhenScopesEmpty() {
            CreateProxyConsentCommand cmd = new CreateProxyConsentCommand(
                    SUBJECT_USER_ID, PROXY_USER_ID,
                    ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                    null, null, null,
                    LocalDate.now(), LocalDate.now().plusMonths(6),
                    List.of());

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, cmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("consentMethod=GUARDIAN_BY_COURT かつ guardianCertificateS3Key が null → BusinessException")
        void shouldThrowWhenGuardianByCourtWithoutCertificate() {
            CreateProxyConsentCommand cmd = new CreateProxyConsentCommand(
                    SUBJECT_USER_ID, PROXY_USER_ID,
                    ProxyInputConsentEntity.ConsentMethod.GUARDIAN_BY_COURT,
                    null, null, null,
                    LocalDate.now(), LocalDate.now().plusMonths(6),
                    List.of(ProxyInputConsentScopeEntity.FeatureScope.SURVEY));

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, cmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("consentMethod=WITNESSED_ORAL かつ witnessUserId が null → BusinessException")
        void shouldThrowWhenWitnessedOralWithoutWitnessId() {
            CreateProxyConsentCommand cmd = new CreateProxyConsentCommand(
                    SUBJECT_USER_ID, PROXY_USER_ID,
                    ProxyInputConsentEntity.ConsentMethod.WITNESSED_ORAL,
                    null, null, null,
                    LocalDate.now(), LocalDate.now().plusMonths(6),
                    List.of(ProxyInputConsentScopeEntity.FeatureScope.SURVEY));

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, cmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("同一組み合わせの有効同意書が既存 → BusinessException（重複登録防止）")
        void shouldThrowWhenDuplicateConsentExists() {
            given(consentRepository.existsActiveConsent(
                    SUBJECT_USER_ID, PROXY_USER_ID, ORG_ID, LocalDate.now()))
                    .willReturn(true);

            assertThatThrownBy(() -> service.createConsent(REQUEST_USER_ID, ORG_ID, buildValidCommand()))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("正常登録 → save() と auditLogService.record() が呼ばれる")
        void shouldSaveConsentAndRecordAuditLog() {
            CreateProxyConsentCommand cmd = buildValidCommand();
            given(consentRepository.existsActiveConsent(any(), any(), any(), any())).willReturn(false);

            ArgumentCaptor<ProxyInputConsentEntity> captor = ArgumentCaptor.forClass(ProxyInputConsentEntity.class);
            given(consentRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            service.createConsent(REQUEST_USER_ID, ORG_ID, cmd);

            verify(consentRepository).save(any(ProxyInputConsentEntity.class));
            verify(auditLogService).record(
                    anyString(), anyLong(), anyLong(), any(), anyLong(),
                    any(), any(), any(), anyString());
        }
    }

    // ─────────────────────────────────────────────────────────────
    // approveConsent
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("approveConsent — 同意書承認")
    class ApproveConsent {

        @Test
        @DisplayName("同意書が存在しない → BusinessException")
        void shouldThrowWhenConsentNotFound() {
            given(consentRepository.findById(1L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.approveConsent(REQUEST_USER_ID, 1L))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("自己承認（requestUserId == consent.proxyUserId）→ BusinessException")
        void shouldThrowWhenSelfApproval() {
            ProxyInputConsentEntity consent = buildConsent(SUBJECT_USER_ID, REQUEST_USER_ID);
            given(consentRepository.findById(1L)).willReturn(Optional.of(consent));

            assertThatThrownBy(() -> service.approveConsent(REQUEST_USER_ID, 1L))
                    .isInstanceOf(BusinessException.class);

            verify(consentRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常承認 → consent.approve() が呼ばれ save() が実行される")
        void shouldApproveConsentSuccessfully() {
            ProxyInputConsentEntity consent = buildConsent(SUBJECT_USER_ID, PROXY_USER_ID);
            given(consentRepository.findById(1L)).willReturn(Optional.of(consent));
            given(consentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ProxyInputConsentEntity result = service.approveConsent(REQUEST_USER_ID, 1L);

            assertThat(result.getApprovedAt()).isNotNull();
            assertThat(result.getApprovedByUserId()).isEqualTo(REQUEST_USER_ID);
            verify(consentRepository).save(consent);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // revokeConsent
    // ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("revokeConsent — 同意書撤回")
    class RevokeConsent {

        private final RevokeConsentCommand revokeCmd = new RevokeConsentCommand(
                ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT, "本人希望", null);

        @Test
        @DisplayName("本人でも ADMIN でもない → BusinessException")
        void shouldThrowWhenNeitherSelfNorAdmin() {
            Long otherUserId = 999L;
            ProxyInputConsentEntity consent = buildConsent(SUBJECT_USER_ID, PROXY_USER_ID);
            given(consentRepository.findById(1L)).willReturn(Optional.of(consent));
            given(accessControlService.isAdminOrAbove(otherUserId, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.revokeConsent(otherUserId, 1L, revokeCmd))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("本人による撤回 → revokedAt がセットされ保存される")
        void shouldRevokeConsentBySelf() {
            ProxyInputConsentEntity consent = buildConsent(SUBJECT_USER_ID, PROXY_USER_ID);
            given(consentRepository.findById(1L)).willReturn(Optional.of(consent));
            given(consentRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            service.revokeConsent(SUBJECT_USER_ID, 1L, revokeCmd);

            assertThat(consent.getRevokedAt()).isNotNull();
            assertThat(consent.getRevokeMethod()).isEqualTo(ProxyInputConsentEntity.RevokeMethod.API_BY_SUBJECT);
            verify(consentRepository).save(consent);
            verify(auditLogService).record(
                    anyString(), anyLong(), anyLong(), any(), anyLong(),
                    any(), any(), any(), anyString());
        }
    }
}
