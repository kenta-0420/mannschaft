package com.mannschaft.app.membership;

import com.mannschaft.app.membership.service.QrTokenService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** F18.1 AC10の時刻契約。協力者なしの実HMAC/固定Clockを使う。 */
class PlatformQrTimeContractTest {
    private static final long NOW = 1_800_000_000L;
    private final QrTokenService service = new QrTokenService(Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));

    @Test
    void QR発行は注入Clockの発行秒と300秒期限を署名する() {
        var token = service.generateMemberCardQrToken("11111111-1111-4111-8111-111111111111", "secret");
        var payload = service.parseMemberCardToken(token);
        assertThat(payload.issuedAt()).isEqualTo(NOW);
        assertThat(payload.expiresAt()).isEqualTo(NOW + 300);
        assertThat(service.verifyMemberCardSignature(payload, "secret")).isTrue();
    }

    @Test
    void 期限ちょうどは許可し次秒を拒否する() {
        assertThat(service.hasValidMemberCardTimes(payload(NOW - 300, NOW))).isTrue();
        assertThat(service.hasValidMemberCardTimes(payload(NOW - 301, NOW - 1))).isFalse();
    }

    @Test
    void 未来発行と逆転期限とTTL改ざんとoverflowを拒否する() {
        assertThat(service.hasValidMemberCardTimes(payload(NOW + 1, NOW + 301))).isFalse();
        assertThat(service.hasValidMemberCardTimes(payload(NOW, NOW - 1))).isFalse();
        assertThat(service.hasValidMemberCardTimes(payload(NOW - 1, NOW + 300))).isFalse();
        assertThat(service.hasValidMemberCardTimes(payload(Long.MAX_VALUE, Long.MIN_VALUE))).isFalse();
        assertThat(service.hasValidMemberCardTimes(payload(-1, 299))).isFalse();
    }

    private QrTokenService.MemberCardTokenPayload payload(long issuedAt, long expiresAt) {
        return new QrTokenService.MemberCardTokenPayload("11111111-1111-4111-8111-111111111111", issuedAt, expiresAt, "signature");
    }
}
