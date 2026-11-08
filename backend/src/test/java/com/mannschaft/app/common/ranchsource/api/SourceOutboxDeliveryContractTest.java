package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.ranch.reward.api.RanchRewardDeliveryOutcome;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公開値の意味だけを検証する。源TX・lease更新・認可・障害回復の証明にはしない。 */
class SourceOutboxDeliveryContractTest {
    private static final Instant NOW=Instant.parse("2026-10-05T00:00:00Z");
    private static final UUID EVENT=UUID.fromString("0199b686-0000-7000-8000-000000000001");
    private static final UUID TOKEN=UUID.fromString("0199b686-0000-7000-8000-000000000002");
    @Test void deferCannotBecomeTerminalAck() {
        var defer=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.DEFER,null,0);
        assertThatThrownBy(() -> new SourceOutboxAckRequest(EVENT,TOKEN,NOW,defer))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("源配送命令の定義が不正です");
        var deleted=new RanchRewardDeliveryOutcome(RanchRewardDeliveryOutcome.Outcome.ACCOUNT_DELETED,null,0);
        assertThatCode(() -> new SourceOutboxAckRequest(EVENT,TOKEN,NOW,deleted)).doesNotThrowAnyException();
    }
    @Test void unknownOrZeroSettingsAreNotReplacedWithDefaults() {
        assertThatThrownBy(() -> new SourceOutboxLeaseRequest(NOW,0,30,8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxLeaseRequest(null,50,30,8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxLeaseRequest(NOW,50,30,0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxFailureRequest(EVENT,TOKEN,NOW,0,1,300,"SOURCE_FAILED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxFailureRequest(EVENT,TOKEN,NOW,8,300,1,"SOURCE_FAILED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void deferTimeIsFiniteFutureAndCanonicalMicros() {
        assertThatCode(() -> new SourceOutboxDeferRequest(EVENT,TOKEN,NOW,NOW.plusSeconds(300),300)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SourceOutboxDeferRequest(EVENT,TOKEN,NOW,NOW,300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxDeferRequest(EVENT,TOKEN,NOW,NOW.plusSeconds(301),300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxDeferRequest(EVENT,TOKEN,NOW,NOW.plusNanos(1),300)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceOutboxDeferRequest(EVENT,TOKEN,Instant.MAX,Instant.MAX,300)).isInstanceOf(IllegalArgumentException.class);
    }
}
