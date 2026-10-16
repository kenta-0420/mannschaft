package com.mannschaft.app.ranch;

import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import com.mannschaft.app.ranch.service.RanchShopControl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 通常公開の承認価格・DB制御と、独立した非本番fixtureを検証する。 */
class RanchShopControlTest {
    private final RanchOperationalControlRepository controls = mock(RanchOperationalControlRepository.class);
    private final RanchShopCatalogRepository catalog = mock(RanchShopCatalogRepository.class);
    private RanchShopControl provider(boolean requested, MockEnvironment environment) {
        return new RanchShopControl(requested, environment, controls, catalog);
    }

    @Test
    void defaultsOffAndProductionCannotEnableDevelopmentCatalog() {
        assertThat(provider(false, new MockEnvironment()).enabled()).isFalse();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");
        assertThat(provider(true, production).enabled()).isFalse();
        assertThat(provider(true, new MockEnvironment()).enabled()).isTrue();
    }

    @Test
    void normalShopControlWithoutApprovedPriceRemainsOff() {
        when(controls.findById(1)).thenReturn(Optional.of(RanchOperationalControlEntity.builder()
                .id(1).shopEnabled(true).deliveryPaused(true).build()));
        assertThat(provider(false, new MockEnvironment()).enabled()).isFalse();
    }

    @Test
    void approvedShopIsIndependentOfDeliveryAndCarePause() {
        when(controls.findById(1)).thenReturn(Optional.of(RanchOperationalControlEntity.builder()
                .id(1).shopEnabled(true).careEnabled(false).deliveryPaused(true).build()));
        when(catalog.hasApprovedItems()).thenReturn(true);
        assertThat(provider(false, new MockEnvironment()).enabled()).isTrue();
    }
}