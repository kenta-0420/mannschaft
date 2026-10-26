package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link GuardianshipSwitchService#startSwitch} / {@link GuardianshipSwitchService#endSwitch} /
 * {@link GuardianshipSwitchService#evaluateSwitch} の単体テスト（F08.9 P3c 第一波）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>正常系: リンク有効＋年齢OK → 監査二重記録（audit_logs + proxy_input_records）</li>
 *   <li>リンクなし → 403 GUARDIANSHIP_LINK_NOT_FOUND / 監査なし</li>
 *   <li>年齢封印 → 403 GUARDIANSHIP_SWITCH_AGE_LOCKED / 監査なし</li>
 *   <li>care_links 経路でも成立</li>
 *   <li>endSwitch → 過去に一度でもリンクが存在すれば許容（現在の状態は問わない）・監査記録のみ（ステートレス）／
 *       一切の関係がなければ 403 GUARDIANSHIP_LINK_NOT_FOUND</li>
 * </ul>
 *
 * <p>Clock は {@code Asia/Tokyo} 固定で date-pin（CI を固定日付で塞がない・P3a 写経）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipSwitchService start/end テスト（F08.9 P3c）")
class GuardianshipSwitchStartEndServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 11L;
    /** 基準日 2026-04-01（JST）。2013-04-02 生まれ＝封印、2014-04-02 生まれ＝切替可。 */
    private static final LocalDate BASE_DATE = LocalDate.parse("2026-04-01");

    @Mock
    private ParentalConsentService parentalConsentService;
    @Mock
    private CareLinkService careLinkService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    private GuardianshipSwitchService service;

    @BeforeEach
    void setUp() {
        Clock fixedJstClock = Clock.fixed(
                BASE_DATE.atTime(LocalTime.NOON).atZone(JST).toInstant(), JST);
        JapanGuardianshipAgePolicy japanPolicy = new JapanGuardianshipAgePolicy();
        DefaultGuardianshipAgePolicy defaultPolicy = new DefaultGuardianshipAgePolicy();
        GuardianshipAgePolicyRegistry registry =
                new GuardianshipAgePolicyRegistry(java.util.List.of(japanPolicy, defaultPolicy), defaultPolicy);

        service = new GuardianshipSwitchService(
                parentalConsentService, careLinkService, userRepository, registry,
                auditLogService, proxyInputRecordRepository, fixedJstClock);
    }

    private UserEntity child(Long id, String birthDate, String countryCode) {
        UserEntity user = UserEntity.builder()
                .email("child" + id + "@example.com")
                .lastName("山田")
                .firstName("子" + id)
                .displayName("子" + id)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .birthDate(birthDate)
                .countryCode(countryCode)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Nested
    @DisplayName("startSwitch")
    class StartSwitch {

        @Test
        @DisplayName("正常系（parental_consent 経路）: 監査二重記録（audit_logs + proxy_input_records）")
        void ok_recordsBothAudits() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, "2014-04-02", "JP"))); // 2026-04-01 で切替可

            service.startSwitch(GUARDIAN_ID, CHILD_ID);

            // audit_logs: GUARDIANSHIP_SWITCH_STARTED / userId=保護者 / targetUserId=子
            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_SWITCH_STARTED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());

            // proxy_input_records: consentId=null / inputSource=GUARDIANSHIP_SWITCH / featureScope=PAYMENT
            ArgumentCaptor<ProxyInputRecordEntity> captor =
                    ArgumentCaptor.forClass(ProxyInputRecordEntity.class);
            verify(proxyInputRecordRepository).save(captor.capture());
            ProxyInputRecordEntity rec = captor.getValue();
            assertThat(rec.getProxyInputConsentId()).isNull();
            assertThat(rec.getSubjectUserId()).isEqualTo(CHILD_ID);
            assertThat(rec.getProxyUserId()).isEqualTo(GUARDIAN_ID);
            assertThat(rec.getFeatureScope()).isEqualTo("PAYMENT");
            assertThat(rec.getInputSource())
                    .isEqualTo(ProxyInputRecordEntity.InputSource.GUARDIANSHIP_SWITCH);
            assertThat(rec.getTargetEntityType()).isEqualTo("GUARDIANSHIP_SWITCH");
            assertThat(rec.getTargetEntityId()).isEqualTo(CHILD_ID);
        }

        @Test
        @DisplayName("正常系（care_links 経路）: parental_consent が false でも care_links ACTIVE PARENT で成立")
        void ok_viaCareLinks() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, "2014-04-02", "JP")));

            service.startSwitch(GUARDIAN_ID, CHILD_ID);

            verify(proxyInputRecordRepository).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("リンクなし → 403 GUARDIANSHIP_LINK_NOT_FOUND / 監査なし")
        void linkNotFound_403() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(false);

            assertThatThrownBy(() -> service.startSwitch(GUARDIAN_ID, CHILD_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);

            verify(auditLogService, never()).record(
                    anyString(), any(), any(), any(), any(), any(), any(), any(), any());
            verify(proxyInputRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("年齢封印 → 403 GUARDIANSHIP_SWITCH_AGE_LOCKED / 監査なし")
        void ageLocked_403() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, "2013-04-02", "JP"))); // 2026-04-01 で封印

            assertThatThrownBy(() -> service.startSwitch(GUARDIAN_ID, CHILD_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED);

            verify(proxyInputRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("birthDate 欠落 → 安全側で 403 AGE_LOCKED")
        void noBirthDate_403_ageLocked() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, null, "JP")));

            assertThatThrownBy(() -> service.startSwitch(GUARDIAN_ID, CHILD_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_SWITCH_AGE_LOCKED);
        }

        @Test
        @DisplayName("リンクはあるが子ユーザーが存在しない → 403 LINK_NOT_FOUND")
        void childNotFound_403_linkNotFound() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.startSwitch(GUARDIAN_ID, CHILD_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("evaluateSwitch（副作用なし verdict）")
    class EvaluateSwitch {

        @Test
        @DisplayName("リンク有効＋年齢OK → ALLOWED")
        void allowed() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, "2014-04-02", "JP")));

            assertThat(service.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                    .isEqualTo(GuardianshipSwitchService.SwitchVerdict.ALLOWED);
            // evaluateSwitch は副作用なし（監査を書かない）
            verify(proxyInputRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("リンクなし → LINK_NOT_FOUND")
        void linkNotFound() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(false);

            assertThat(service.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                    .isEqualTo(GuardianshipSwitchService.SwitchVerdict.LINK_NOT_FOUND);
        }

        @Test
        @DisplayName("年齢封印 → AGE_LOCKED")
        void ageLocked() {
            given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
            given(userRepository.findById(CHILD_ID))
                    .willReturn(Optional.of(child(CHILD_ID, "2013-04-02", "JP")));

            assertThat(service.evaluateSwitch(GUARDIAN_ID, CHILD_ID))
                    .isEqualTo(GuardianshipSwitchService.SwitchVerdict.AGE_LOCKED);
        }

        @Test
        @DisplayName("null 引数 → LINK_NOT_FOUND（防御的）")
        void nullArg() {
            assertThat(service.evaluateSwitch(null, CHILD_ID))
                    .isEqualTo(GuardianshipSwitchService.SwitchVerdict.LINK_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("endSwitch")
    class EndSwitch {

        @Test
        @DisplayName("正常系（parental_consent 経路・現在有効）: 監査記録のみ（ステートレス・proxy_input_records は書かない）")
        void ok_currentlyApproved_recordsAuditOnly() {
            given(parentalConsentService.hasEverBeenGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);

            service.endSwitch(GUARDIAN_ID, CHILD_ID);

            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_SWITCH_ENDED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());
            verify(proxyInputRecordRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系（care_links 経路）: parental_consent が false でも care_links の過去リンクで成立")
        void ok_viaCareLinksHistory() {
            given(parentalConsentService.hasEverBeenGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.hasEverBeenParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(true);

            service.endSwitch(GUARDIAN_ID, CHILD_ID);

            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_SWITCH_ENDED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("正常系（過去に存在したが現在は解除済み）: 現在有効性は問わず終了できる（安全側）")
        void ok_revokedLinkStillAllowsEnd() {
            // hasEverBeenGuardian は現在ステータスを問わない緩い存在チェック（REVOKED でも true）。
            // startSwitch/evaluateSwitch の isApprovedGuardian（現在有効性のみ）とは別判定であることを固定する。
            given(parentalConsentService.hasEverBeenGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);

            service.endSwitch(GUARDIAN_ID, CHILD_ID);

            verify(auditLogService).record(
                    eq(AuditEventType.GUARDIANSHIP_SWITCH_ENDED.name()),
                    eq(GUARDIAN_ID), eq(CHILD_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), anyString());
        }

        @Test
        @DisplayName("一切の関係がない childUserId → 403 GUARDIANSHIP_LINK_NOT_FOUND / 監査なし（IDOR 防止）")
        void neverLinked_403_noAudit() {
            given(parentalConsentService.hasEverBeenGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
            given(careLinkService.hasEverBeenParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(false);

            assertThatThrownBy(() -> service.endSwitch(GUARDIAN_ID, CHILD_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);

            verify(auditLogService, never()).record(
                    anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }
}
