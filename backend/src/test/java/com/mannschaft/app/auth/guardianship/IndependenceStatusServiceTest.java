package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.dto.IndependenceStatusResponse;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.payment.MembershipBillingErrorCode;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.BDDMockito.given;

/**
 * {@link GuardianshipSwitchService#getIndependenceStatus} の単体テスト（F08.9 P3c-2）。
 *
 * <h3>テスト観点</h3>
 * <ul>
 *   <li>正常系: switchAllowed=true（小学生）/ false（封印済み）両方・境界日（sealDate）の値検証</li>
 *   <li>パスワード設定有無（passwordSet）の反映</li>
 *   <li>IDOR: 有効な保護者リンクなし → 403 GUARDIANSHIP_LINK_NOT_FOUND</li>
 *   <li>存在しない子 → 403</li>
 *   <li>birthDate 欠落 → 安全側（封印・sealDate=null）</li>
 * </ul>
 *
 * <p>Clock は {@code Asia/Tokyo} 固定で date-pin（CI を固定日付で塞がない）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipSwitchService.getIndependenceStatus テスト（F08.9 P3c-2）")
class IndependenceStatusServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 11L;
    /** 基準日 2026-04-01（JST）。 */
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

    private UserEntity child(Long id, String birthDate, String countryCode, String passwordHash) {
        UserEntity user = UserEntity.builder()
                .email("child" + id + "@example.com")
                .passwordHash(passwordHash)
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

    @Test
    @DisplayName("正常系（切替可・小学生）: switchAllowed=true / sealDate=2026-04-01 / passwordSet=false")
    void allowed_elementary() {
        given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
        given(userRepository.findById(CHILD_ID))
                .willReturn(Optional.of(child(CHILD_ID, "2014-04-02", "JP", null))); // 2026-04-01 で切替可

        IndependenceStatusResponse r = service.getIndependenceStatus(GUARDIAN_ID, CHILD_ID);

        assertThat(r.childUserId()).isEqualTo(CHILD_ID);
        assertThat(r.switchAllowed()).isTrue();
        assertThat(r.stageKey()).isEqualTo("elementary");
        // 2014-04-02 生まれ → 満12歳(2026-04-02)以降最初の4/1 = 2027-04-01。
        assertThat(r.sealDate()).isEqualTo(LocalDate.parse("2027-04-01"));
        assertThat(r.passwordSet()).isFalse();
    }

    @Test
    @DisplayName("正常系（封印済み・中学生）: switchAllowed=false / sealDate=2026-04-01 / passwordSet=true")
    void blocked_juniorHigh() {
        given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(true);
        given(userRepository.findById(CHILD_ID))
                .willReturn(Optional.of(child(CHILD_ID, "2013-04-02", "JP", "$2a$hash"))); // 2026-04-01 で封印

        IndependenceStatusResponse r = service.getIndependenceStatus(GUARDIAN_ID, CHILD_ID);

        assertThat(r.switchAllowed()).isFalse();
        assertThat(r.stageKey()).isEqualTo("junior_high");
        assertThat(r.sealDate()).isEqualTo(LocalDate.parse("2026-04-01"));
        assertThat(r.passwordSet()).isTrue();
    }

    @Test
    @DisplayName("IDOR: 有効な保護者リンクなし → 403 GUARDIANSHIP_LINK_NOT_FOUND（子のデータは引かない）")
    void idor_403() {
        given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(false);
        given(careLinkService.isActiveParentWatcher(GUARDIAN_ID, CHILD_ID)).willReturn(false);

        assertThatThrownBy(() -> service.getIndependenceStatus(GUARDIAN_ID, CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
    }

    @Test
    @DisplayName("リンクはあるが子が存在しない → 403 LINK_NOT_FOUND")
    void childNotFound_403() {
        given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
        given(userRepository.findById(CHILD_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getIndependenceStatus(GUARDIAN_ID, CHILD_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(MembershipBillingErrorCode.GUARDIANSHIP_LINK_NOT_FOUND);
    }

    @Test
    @DisplayName("birthDate 欠落 → 安全側（switchAllowed=false / stageKey=independent / sealDate=null）")
    void noBirthDate_safeSide() {
        given(parentalConsentService.isApprovedGuardian(GUARDIAN_ID, CHILD_ID)).willReturn(true);
        given(userRepository.findById(CHILD_ID))
                .willReturn(Optional.of(child(CHILD_ID, null, "JP", "$2a$hash")));

        IndependenceStatusResponse r = service.getIndependenceStatus(GUARDIAN_ID, CHILD_ID);

        assertThat(r.switchAllowed()).isFalse();
        assertThat(r.stageKey()).isEqualTo("independent");
        assertThat(r.sealDate()).isNull();
        assertThat(r.passwordSet()).isTrue();
    }
}
