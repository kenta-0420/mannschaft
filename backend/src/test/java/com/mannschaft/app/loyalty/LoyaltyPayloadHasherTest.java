package com.mannschaft.app.loyalty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.loyalty.dto.ApproveRequest;
import com.mannschaft.app.loyalty.dto.RequestActionRequest;
import com.mannschaft.app.loyalty.dto.RedeemRequest;
import com.mannschaft.app.loyalty.dto.SelfAwardRequest;
import com.mannschaft.app.loyalty.dto.StaffAwardRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** AC07/26: token再解析なしの型付き再送hashと、operation/path/body境界を固定する。 */
class LoyaltyPayloadHasherTest {
    private final LoyaltyPayloadHasher hasher = new LoyaltyPayloadHasher();
    private final UUID item = UUID.fromString("019a1000-0000-7000-8000-000000000001");
    private final UUID other = UUID.fromString("019a1000-0000-7000-8000-000000000002");
    private StaffAwardRequest award() { return new StaffAwardRequest("expired.opaque.token", item, 2L, 3L); }

    @Test
    void AC07_hashは決定的な32byteでrawtokenそのものではない() {
        byte[] first = hasher.staffAward(1L, award());
        assertThat(first).hasSize(32).isNotEqualTo(new byte[32]);
        assertThat(hasher.staffAward(1L, award())).isEqualTo(first);
    }

    @Test
    void AC07_JSON空白とキー順が異なっても型付きbodyが同じなら同じhash() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        StaffAwardRequest parsed = mapper.readValue("{ \"expectedProgramVersion\":3,\"ruleId\":\""
                + item + "\",\"expectedRuleVersion\":2,\"qrToken\":\"expired.opaque.token\" }", StaffAwardRequest.class);
        assertThat(hasher.staffAward(1L, parsed)).isEqualTo(hasher.staffAward(1L, award()));
    }

    @Test
    void AC07_teamと全確認内容とtokenがそれぞれ照合範囲に入る() {
        byte[] original = hasher.staffAward(1L, award());
        assertThat(hasher.staffAward(2L, award())).isNotEqualTo(original);
        for (StaffAwardRequest changed : new StaffAwardRequest[]{
                new StaffAwardRequest("different", item, 2L, 3L),
                new StaffAwardRequest(award().qrToken(), other, 2L, 3L),
                new StaffAwardRequest(award().qrToken(), item, 4L, 3L),
                new StaffAwardRequest(award().qrToken(), item, 2L, 4L)}) {
            assertThat(hasher.staffAward(1L, changed)).isNotEqualTo(original);
        }
    }

    @Test
    void AC07_同じ値でもoperationが異なればhashが異なる() {
        byte[] original = hasher.staffAward(1L, award());
        assertThat(hasher.selfSubmit(1L, new SelfAwardRequest(award().qrToken(), item, 2L, 3L))).isNotEqualTo(original);
        assertThat(hasher.redeem(1L, new RedeemRequest(award().qrToken(), item, 2L, 3L))).isNotEqualTo(original);
    }

    @Test
    void AC26_承認は申請pathと照合コードと版を束縛する() {
        byte[] original = hasher.approve(1L, item, new ApproveRequest(2L, "ABCDEFGH"));
        assertThat(hasher.approve(1L, other, new ApproveRequest(2L, "ABCDEFGH"))).isNotEqualTo(original);
        assertThat(hasher.approve(1L, item, new ApproveRequest(3L, "ABCDEFGH"))).isNotEqualTo(original);
        assertThat(hasher.approve(1L, item, new ApproveRequest(2L, "BCDEFGHA"))).isNotEqualTo(original);
    }

    @Test
    void AC26_取消却下のoperationと申請pathと版が独立する() {
        byte[] original = hasher.action(RequestOperation.CANCEL, 1L, item, new RequestActionRequest(2L));
        assertThat(hasher.action(RequestOperation.REJECT, 1L, item, new RequestActionRequest(2L))).isNotEqualTo(original);
        assertThat(hasher.action(RequestOperation.CANCEL, 1L, other, new RequestActionRequest(2L))).isNotEqualTo(original);
        assertThat(hasher.action(RequestOperation.CANCEL, 1L, item, new RequestActionRequest(3L))).isNotEqualTo(original);
    }
}
