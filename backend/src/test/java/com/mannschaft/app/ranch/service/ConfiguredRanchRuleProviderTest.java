package com.mannschaft.app.ranch.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredRanchRuleProviderTest {
    @Test
    void developmentRulesAreOffByDefault() {
        var provider = new ConfiguredRanchRuleProvider(false, new MockEnvironment());
        assertThat(provider.careEnabled()).isFalse();
        assertThat(provider.currentCareRule(Instant.EPOCH)).isEmpty();
        assertThat(provider.currentEggRule(Instant.EPOCH)).isEmpty();
    }

    @Test
    void explicitNonProductionFixturesProvideSevenDayEggAndFiveCareUnits() {
        var provider = new ConfiguredRanchRuleProvider(true, new MockEnvironment());
        assertThat(provider.currentEggRule(Instant.EPOCH)).get()
                .extracting(RanchRuleProvider.EggRuleSnapshot::durationSeconds)
                .isEqualTo(604800L);
        assertThat(provider.currentEggRule(Instant.EPOCH).orElseThrow().smallCrackSeconds())
                .isEqualTo(259200L);
        var care = provider.currentCareRule(Instant.EPOCH).orElseThrow();
        assertThat(care.amountXp()).isEqualTo(20);
        assertThat(care.weeklyCapXp()).isEqualTo(100);
        assertThat(care.adultXp()).isEqualTo(100);
    }

    @Test
    void productionProfileRejectsFixtureFlag() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var provider = new ConfiguredRanchRuleProvider(true, environment);
        assertThat(provider.currentCareRule(Instant.EPOCH)).isEmpty();
        assertThat(provider.currentEggRule(Instant.EPOCH)).isEmpty();
    }
}
