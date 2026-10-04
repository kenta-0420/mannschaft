package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.service.RanchShopControl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/** 本番・既定OFFを開発fixture設定から分離する。 */
class RanchShopControlTest {
    @Test
    void defaultsOffAndProductionCannotEnableDevelopmentCatalog() {
        assertThat(new RanchShopControl(false, new MockEnvironment()).enabled()).isFalse();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThat(new RanchShopControl(true, production).enabled()).isFalse();
        assertThat(new RanchShopControl(true, new MockEnvironment()).enabled()).isTrue();
    }
}
