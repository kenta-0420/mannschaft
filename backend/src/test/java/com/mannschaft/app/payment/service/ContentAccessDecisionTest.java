package com.mannschaft.app.payment.service;

import com.mannschaft.app.payment.dto.GateCheckResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** AC-8 可視性×会費ゲート3値合成の単体契約。 */
class ContentAccessDecisionTest {

    @Test
    void 可視性と課金を満たす場合はFULL() {
        assertThat(ContentAccessDecision.resolve(true, new GateCheckResponse(true, false, List.of())))
                .isEqualTo(ContentAccessState.FULL);
    }

    @Test
    void 可視性は満たすが未払いでタイトル可視ならLOCKED() {
        assertThat(ContentAccessDecision.resolve(true, new GateCheckResponse(false, false, List.of())))
                .isEqualTo(ContentAccessState.LOCKED);
    }

    @Test
    void 未充足ゲートがタイトル秘匿ならHIDDEN() {
        assertThat(ContentAccessDecision.resolve(true, new GateCheckResponse(false, true, List.of())))
                .isEqualTo(ContentAccessState.HIDDEN);
    }

    @Test
    void 可視性拒否または判定不能はHIDDEN() {
        assertThat(ContentAccessDecision.resolve(false, new GateCheckResponse(true, false, List.of())))
                .isEqualTo(ContentAccessState.HIDDEN);
        assertThat(ContentAccessDecision.resolve(true, null)).isEqualTo(ContentAccessState.HIDDEN);
    }
}
