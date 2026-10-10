package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.entity.RanchCareRuleEntity;
import com.mannschaft.app.ranch.entity.RanchOperationalControlEntity;
import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfiguredRanchRuleProviderTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
    private final RanchOperationalControlRepository controls = mock(RanchOperationalControlRepository.class);
    private final RanchCareRuleRepository careRules = mock(RanchCareRuleRepository.class);

    private ConfiguredRanchRuleProvider provider(boolean requested, MockEnvironment environment) {
        return new ConfiguredRanchRuleProvider(requested, environment, controls, careRules,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void developmentRulesAreOffByDefault() {
        var provider = provider(false, new MockEnvironment());
        assertThat(provider.careEnabled()).isFalse();
        assertThat(provider.currentCareRule(NOW)).isEmpty();
        assertThat(provider.currentEggRule(NOW)).isEmpty();
    }

    @Test
    void explicitNonProductionFixturesProvideSevenDayEggAndFiveCareUnits() {
        var provider = provider(true, new MockEnvironment());
        assertThat(provider.currentEggRule(NOW)).get()
                .extracting(RanchRuleProvider.EggRuleSnapshot::durationSeconds)
                .isEqualTo(604800L);
        assertThat(provider.currentEggRule(NOW).orElseThrow().smallCrackSeconds()).isEqualTo(259200L);
        var care = provider.currentCareRule(NOW).orElseThrow();
        assertThat(care.amountXp()).isEqualTo(20);
        assertThat(care.weeklyCapXp()).isEqualTo(100);
        assertThat(care.adultXp()).isEqualTo(100);
    }

    @Test
    void productionProfileRejectsFixtureFlag() {
        var environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var provider = provider(true, environment);
        assertThat(provider.currentCareRule(NOW)).isEmpty();
        assertThat(provider.currentEggRule(NOW)).isEmpty();
    }

    @Test
    void normalControlWithoutPublishedRuleCannotEnableCare() {
        when(controls.findById(1)).thenReturn(Optional.of(RanchOperationalControlEntity.builder()
                .id(1).careEnabled(true).deliveryPaused(true).build()));
        when(careRules.publishedAt(any(), any())).thenReturn(List.of());
        assertThat(provider(false, new MockEnvironment()).careEnabled()).isFalse();
    }

    @Test
    void publishedDatabaseRuleIsIndependentOfDeliveryPause() {
        UUID ruleId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        when(controls.findById(1)).thenReturn(Optional.of(RanchOperationalControlEntity.builder()
                .id(1).careEnabled(true).deliveryPaused(true).build()));
        when(careRules.publishedAt(any(), any())).thenReturn(List.of(RanchCareRuleEntity.builder()
                .id(ruleId).versionNumber(7).amountXp(25).weeklyCapXp(100).juvenileXp(50).adultXp(100)
                .effectiveAt(NOW).publishedAt(NOW.minusSeconds(1)).build()));
        var provider = provider(false, new MockEnvironment());
        assertThat(provider.careEnabled()).isTrue();
        var care = provider.currentCareRule(NOW).orElseThrow();
        assertThat(care.ruleId()).isEqualTo(ruleId);
        assertThat(care.ruleVersion()).isEqualTo("7");
        assertThat(care.amountXp()).isEqualTo(25);
        assertThat(provider.currentEggRule(NOW).orElseThrow().juvenileXp()).isEqualTo(50);
    }
}