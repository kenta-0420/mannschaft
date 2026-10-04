package com.mannschaft.app.ranch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 開発fixtureは明示設定かつ非本番profileだけに限定する。 */
@Component
public final class ConfiguredRanchRuleProvider implements RanchRuleProvider {
    private static final String VERSION = "ranch-development-v1";
    private static final UUID CARE_RULE_ID = UUID.fromString("a97f4ca2-c636-4b95-bd61-77bec36b0605");

    private final boolean developmentFixturesEnabled;

    public ConfiguredRanchRuleProvider(
            @Value("${mannschaft.ranch.development-fixtures:false}") boolean requested,
            Environment environment) {
        developmentFixturesEnabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    @Override
    public boolean careEnabled() {
        return developmentFixturesEnabled;
    }

    @Override
    public Optional<CareRuleSnapshot> currentCareRule(Instant now) {
        if (!developmentFixturesEnabled) {
            return Optional.empty();
        }
        // 暫定開発用: 同一週の5回で成体へ到達。本番の運営公開値とは独立。
        return Optional.of(new CareRuleSnapshot(
                CARE_RULE_ID, VERSION, 20, 100, 60, 100, 1, 3, 6));
    }

    @Override
    public Optional<EggRuleSnapshot> currentEggRule(Instant now) {
        if (!developmentFixturesEnabled) {
            return Optional.empty();
        }
        return Optional.of(new EggRuleSnapshot(
                VERSION, 7 * 24 * 60 * 60L, 3 * 24 * 60 * 60L,
                5 * 24 * 60 * 60L, 60, 100, 1, 3, 6));
    }
}
