package com.mannschaft.app.ranch;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.service.RanchDevelopmentFixturePolicyGate;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spring標準Environmentで中央DEV境界を検証する。DB認可や測定boundsの証明ではない。 */
class RanchDevelopmentFixturePolicyGateTest {
    private static final String FLAG = "mannschaft.ranch.development-fixtures";

    @Test
    void explicitTrueAndIsolatedProfileAreBothRequired() {
        var environment = new MockEnvironment();
        var gate = new RanchDevelopmentFixturePolicyGate(environment);
        assertThat(gate.enabled()).isFalse();
        environment.setProperty(FLAG, "true");
        assertThat(gate.enabled()).isFalse();
        environment.setActiveProfiles("test", "ranch-isolated");
        assertThat(gate.enabled()).isTrue();
        for (String value : new String[]{"false", "TRUE", " true", "1", ""}) {
            environment.setProperty(FLAG, value);
            assertThat(gate.enabled()).isFalse();
        }
    }

    @Test
    void productionAlwaysWinsAndReservedDevPrefixCannotEscape() {
        var environment = new MockEnvironment().withProperty(FLAG, "true");
        var gate = new RanchDevelopmentFixturePolicyGate(environment);
        for (String profile : new String[]{"prod", "production"}) {
            environment.setActiveProfiles("ranch-isolated", "test", profile);
            assertThat(gate.enabled()).isFalse();
            assertThatThrownBy(() -> gate.requirePublicationAllowed("DEV_ACTIVITY_UI"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(RanchErrorCode.RANCH_004));
            assertThatThrownBy(() -> gate.requireConsumptionAllowed("DEV_ACTIVITY_UI"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(gate.readable("FORMAL_REASON")).isTrue();
        }
        environment.setActiveProfiles("ranch-isolated");
        assertThat(gate.readable("DEV_ACTIVITY_UI")).isTrue();
        assertThat(gate.readable("DEV_")).isFalse();
        assertThat(gate.readable("DEV_invalid")).isFalse();
    }

    @Test
    void currentWeekExceptionRequiresEmptyStoreWithoutExtraControlRestrictions() {
        var environment = new MockEnvironment().withProperty(FLAG, "true");
        environment.setActiveProfiles("ranch-isolated");
        var gate = new RanchDevelopmentFixturePolicyGate(environment);
        var now = Instant.parse("2032-01-06T12:00:00Z");
        assertThat(gate.isCurrentWeek(Instant.parse("2032-01-05T00:00:00Z"), now)).isTrue();
        assertThat(gate.isCurrentWeek(Instant.parse("2032-01-12T00:00:00Z"), now)).isFalse();
        gate.requireInitialCurrentWeek(true);
        assertThatThrownBy(() -> gate.requireInitialCurrentWeek(false)).isInstanceOf(BusinessException.class);
    }
}
