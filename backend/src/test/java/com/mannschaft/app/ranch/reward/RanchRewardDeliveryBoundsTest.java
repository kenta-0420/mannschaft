package com.mannschaft.app.ranch.reward;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RanchRewardDeliveryBoundsTest {
    private static final String PREFIX = "mannschaft.ranch.delivery.bounds.";
    private static final RanchRewardPolicySnapshot.DeliverySettings VALID =
            new RanchRewardPolicySnapshot.DeliverySettings(25, 30, 4, 2, 20);

    @Test
    void 未登録と不完全な登録は公開値を許さない() {
        MockEnvironment env = new MockEnvironment();
        RanchRewardDeliveryBounds bounds = new RanchRewardDeliveryBounds(env);
        assertThat(bounds.registered()).isEmpty();
        assertThatThrownBy(() -> bounds.validate(VALID)).isInstanceOf(IllegalStateException.class);
        env.setProperty(PREFIX + "version", "measured-v1");
        assertThatThrownBy(() -> bounds.validate(VALID)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 登録された五値の範囲内だけを許す() {
        MockEnvironment env = registered();
        RanchRewardDeliveryBounds bounds = new RanchRewardDeliveryBounds(env);
        assertThat(bounds.registered().orElseThrow().version()).isEqualTo("measured-v1");
        bounds.validate(VALID);
        assertThatThrownBy(() -> bounds.validate(
                new RanchRewardPolicySnapshot.DeliverySettings(26, 30, 4, 2, 20)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 負値や逆転した測定境界を拒否する() {
        MockEnvironment env = registered();
        env.setProperty(PREFIX + "lease-seconds.min", "29");
        env.setProperty(PREFIX + "lease-seconds.max", "29");
        assertThatThrownBy(() -> new RanchRewardDeliveryBounds(env).validate(VALID))
                .isInstanceOf(IllegalArgumentException.class);
        env.setProperty(PREFIX + "lease-seconds.min", "31");
        assertThatThrownBy(() -> new RanchRewardDeliveryBounds(env).validate(VALID))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment registered() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(PREFIX + "version", "measured-v1");
        exact(env, "batch-size", 25);
        exact(env, "lease-seconds", 30);
        exact(env, "max-attempts", 4);
        exact(env, "initial-backoff-seconds", 2);
        exact(env, "max-backoff-seconds", 20);
        return env;
    }

    private static void exact(MockEnvironment env, String name, int value) {
        env.setProperty(PREFIX + name + ".min", Integer.toString(value));
        env.setProperty(PREFIX + name + ".max", Integer.toString(value));
    }
}
