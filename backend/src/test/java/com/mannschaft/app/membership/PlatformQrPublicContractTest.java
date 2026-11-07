package com.mannschaft.app.membership;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.entity.CheckinLocationEntity;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.repository.CheckinLocationRepository;
import com.mannschaft.app.membership.repository.MemberCardCheckinRepository;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import com.mannschaft.app.membership.service.CheckinLocationService;
import com.mannschaft.app.membership.service.MemberCardService;
import com.mannschaft.app.membership.service.QrTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 実Clock/HMACによる読取QR契約。実MySQL/認可ITを補完し、永続化の代替にはしない。 */
class PlatformQrPublicContractTest {
    private static final long NOW = 1_800_000_000L;
    private static final String CODE = "11111111-1111-4111-8111-111111111111";
    private static final String SECRET = "contract-signature-secret";
    private MemberCardRepository cards;
    private MemberCardCheckinRepository checkins;
    private CheckinLocationRepository locations;
    private QrTokenService tokens;
    private MemberCardService service;
    private CheckinLocationService locationService;

    @BeforeEach
    void 準備() {
        cards = mock(MemberCardRepository.class);
        checkins = mock(MemberCardCheckinRepository.class);
        locations = mock(CheckinLocationRepository.class);
        tokens = new QrTokenService(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
        service = new MemberCardService(cards, checkins, locations, tokens, null, null, null, null);
        locationService = new CheckinLocationService(locations, checkins, tokens, null, null);
    }

    @Test
    void PLATFORM署名は本人とカードだけを返しチェックインを書かない() {
        when(cards.findByCardCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(card(ScopeType.PLATFORM, CardStatus.ACTIVE)));
        assertThat(service.verifyPlatformQr(tokens.generateMemberCardQrToken(CODE, SECRET)))
                .isEqualTo(new MemberCardService.VerifiedPlatformCard(7L, 9L, NOW + 300));
        verifyNoInteractions(checkins, locations);
        verify(cards, never()).save(any());
        verify(cards, never()).saveAndFlush(any());
    }

    @Test
    void 別secretの有効形式署名を拒否する() {
        when(cards.findByCardCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(card(ScopeType.PLATFORM, CardStatus.ACTIVE)));
        rejectCard(tokens.generateMemberCardQrToken(CODE, "wrong-secret"), "MEMBERSHIP_006");
    }

    @ParameterizedTest
    @EnumSource(value = ScopeType.class, names = {"TEAM", "ORGANIZATION"})
    void 他scopeの有効署名を拒否する(ScopeType scope) {
        when(cards.findByCardCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(card(scope, CardStatus.ACTIVE)));
        rejectCard(tokens.generateMemberCardQrToken(CODE, SECRET), "MEMBERSHIP_012");
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, names = {"SUSPENDED", "REVOKED"})
    void 停止無効カードの有効署名を拒否する(CardStatus status) {
        when(cards.findByCardCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(card(ScopeType.PLATFORM, status)));
        rejectCard(tokens.generateMemberCardQrToken(CODE, SECRET), "MEMBERSHIP_003");
    }

    @Test
    void 削除済み不存在カードは解決しない() {
        when(cards.findByCardCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.empty());
        rejectCard(tokens.generateMemberCardQrToken(CODE, SECRET), "MEMBERSHIP_007");
    }

    @Test
    void TEAM受付署名は受付IDとteamだけを返しチェックインを書かない() {
        when(locations.findByLocationCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(location(ScopeType.TEAM, true)));
        assertThat(locationService.resolveTeamLocationQr(tokens.generateLocationQrToken(CODE, SECRET)))
                .isEqualTo(new CheckinLocationService.VerifiedTeamLocation(11L, 13L));
        verifyNoInteractions(cards, checkins);
        verify(locations, never()).save(any());
    }

    @Test
    void ORG受付の有効署名を拒否する() {
        when(locations.findByLocationCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(location(ScopeType.ORGANIZATION, true)));
        rejectLocation(tokens.generateLocationQrToken(CODE, SECRET), "MEMBERSHIP_013");
    }

    @Test
    void 無効TEAM受付を拒否する() {
        when(locations.findByLocationCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(location(ScopeType.TEAM, false)));
        rejectLocation(tokens.generateLocationQrToken(CODE, SECRET), "MEMBERSHIP_014");
    }

    @Test
    void TEAM受付の別secret署名を拒否する() {
        when(locations.findByLocationCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.of(location(ScopeType.TEAM, true)));
        rejectLocation(tokens.generateLocationQrToken(CODE, "wrong-secret"), "MEMBERSHIP_013");
    }

    @Test
    void 削除済み不存在受付を拒否する() {
        when(locations.findByLocationCodeAndDeletedAtIsNull(CODE)).thenReturn(Optional.empty());
        rejectLocation(tokens.generateLocationQrToken(CODE, SECRET), "MEMBERSHIP_013");
    }

    private void rejectCard(String token, String code) {
        assertThatThrownBy(() -> service.verifyPlatformQr(token)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode().getCode()).isEqualTo(code));
        verifyNoInteractions(checkins);
    }
    private void rejectLocation(String token, String code) {
        assertThatThrownBy(() -> locationService.resolveTeamLocationQr(token)).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getErrorCode().getCode()).isEqualTo(code));
        verifyNoInteractions(checkins);
    }
    private MemberCardEntity card(ScopeType scope, CardStatus status) {
        return MemberCardEntity.builder().id(9L).userId(7L).scopeType(scope)
                .scopeId(scope == ScopeType.PLATFORM ? null : 13L).status(status)
                .cardCode(CODE).qrSecret(SECRET).build();
    }
    private CheckinLocationEntity location(ScopeType scope, boolean active) {
        return CheckinLocationEntity.builder().id(11L).scopeType(scope).scopeId(13L)
                .locationCode(CODE).locationSecret(SECRET).isActive(active).build();
    }
}
