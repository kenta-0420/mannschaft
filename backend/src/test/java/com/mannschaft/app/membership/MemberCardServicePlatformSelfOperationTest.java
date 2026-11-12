package com.mannschaft.app.membership;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.membership.service.QrTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PLATFORM（運営）会員証の本人操作（suspend / reactivate / regenerate）と、
 * TEAM 会員証の既存判定の回帰を検証する（CMP-261113-0209）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberCardService PLATFORM会員証の本人操作")
class MemberCardServicePlatformSelfOperationTest {

    @Mock private MemberCardRepository memberCardRepository;
    @Mock private MemberCardCheckinRepository checkinRepository;
    @Mock private CheckinLocationRepository locationRepository;
    @Mock private QrTokenService qrTokenService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private NameResolverService nameResolverService;
    @Mock private AccessControlService accessControlService;

    @InjectMocks private MemberCardService service;

    private static final Long OWNER = 1L;
    private static final Long OTHER = 2L;
    private static final Long CARD_ID = 10L;

    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    private void currentUser(Long id) {
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(id);
    }

    private MemberCardEntity card(ScopeType type, Long scopeId, CardStatus status) {
        return MemberCardEntity.builder()
                .userId(OWNER).scopeType(type).scopeId(scopeId)
                .cardCode("c").cardNumber("N-1").displayName("d")
                .status(status).issuedAt(LocalDateTime.now().minusDays(1))
                .suspendedAt(status == CardStatus.SUSPENDED ? LocalDateTime.now() : null)
                .checkinCount(0).totalSpend(BigDecimal.ZERO).qrSecret("s")
                .build();
    }

    private void stub(MemberCardEntity c) {
        given(memberCardRepository.findByIdAndDeletedAtIsNull(CARD_ID)).willReturn(Optional.of(c));
    }

    private static void assertCode(Throwable e, MembershipErrorCode code) {
        assertThat(e).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("PLATFORM: 本人は一時停止できる（スコープ判定を呼ばない）")
    void platformOwnerCanSuspend() {
        currentUser(OWNER);
        stub(card(ScopeType.PLATFORM, null, CardStatus.ACTIVE));
        given(memberCardRepository.save(any(MemberCardEntity.class))).willAnswer(i -> i.getArgument(0));

        assertThat(service.suspend(CARD_ID).getData().getStatus()).isEqualTo("SUSPENDED");
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("PLATFORM: 本人は一時停止を解除できる")
    void platformOwnerCanReactivate() {
        currentUser(OWNER);
        stub(card(ScopeType.PLATFORM, null, CardStatus.SUSPENDED));
        given(memberCardRepository.save(any(MemberCardEntity.class))).willAnswer(i -> i.getArgument(0));

        assertThat(service.reactivate(CARD_ID).getData().getStatus()).isEqualTo("ACTIVE");
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("PLATFORM: 本人はQR再生成できる")
    void platformOwnerCanRegenerate() {
        stub(card(ScopeType.PLATFORM, null, CardStatus.ACTIVE));
        given(qrTokenService.generateSecret()).willReturn("new");
        given(memberCardRepository.save(any(MemberCardEntity.class))).willAnswer(i -> i.getArgument(0));

        assertThat(service.regenerateQr(CARD_ID, OWNER).getData()).isNotNull();
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("PLATFORM: 他人の suspend / reactivate / regenerate は存在秘匿で拒否される")
    void platformOtherUserConcealed() {
        currentUser(OTHER);
        stub(card(ScopeType.PLATFORM, null, CardStatus.ACTIVE));

        assertThatThrownBy(() -> service.suspend(CARD_ID))
                .satisfies(e -> assertCode(e, MembershipErrorCode.MEMBERSHIP_001));
        assertThatThrownBy(() -> service.reactivate(CARD_ID))
                .satisfies(e -> assertCode(e, MembershipErrorCode.MEMBERSHIP_001));
        assertThatThrownBy(() -> service.regenerateQr(CARD_ID, OTHER))
                .satisfies(e -> assertCode(e, MembershipErrorCode.MEMBERSHIP_002));
        verify(memberCardRepository, never()).save(any());
        verifyNoInteractions(accessControlService);
    }

    @Test
    @DisplayName("TEAM（回帰）: 非メンバーの suspend は従来どおり存在秘匿")
    void teamNonMemberStillConcealed() {
        currentUser(OTHER);
        stub(card(ScopeType.TEAM, 100L, CardStatus.ACTIVE));
        given(accessControlService.isMember(OTHER, 100L, "TEAM")).willReturn(false);

        assertThatThrownBy(() -> service.suspend(CARD_ID))
                .satisfies(e -> assertCode(e, MembershipErrorCode.MEMBERSHIP_001));
    }

    @Test
    @DisplayName("TEAM（回帰）: 本人でも SUPPORTER 未満なら再生成は MEMBERSHIP_004")
    void teamRegenerateStillChecksRole() {
        stub(card(ScopeType.TEAM, 100L, CardStatus.ACTIVE));
        given(accessControlService.hasRoleOrAbove(OWNER, 100L, "TEAM", "SUPPORTER")).willReturn(false);

        assertThatThrownBy(() -> service.regenerateQr(CARD_ID, OWNER))
                .satisfies(e -> assertCode(e, MembershipErrorCode.MEMBERSHIP_004));
    }
}
