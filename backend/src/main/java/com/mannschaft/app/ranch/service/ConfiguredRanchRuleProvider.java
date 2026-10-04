package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.repository.RanchCareRuleRepository;
import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 通常公開は運営行と公開済みDB規則、開発fixtureは明示かつ非本番で分離する。 */
@Component
@Transactional
public class ConfiguredRanchRuleProvider implements RanchRuleProvider {
    private static final String VERSION = "ranch-development-v1";
    private static final UUID CARE_RULE_ID = UUID.fromString("a97f4ca2-c636-4b95-bd61-77bec36b0605");
    private final boolean developmentFixturesEnabled;
    private final RanchOperationalControlRepository controls;
    private final RanchCareRuleRepository careRules;
    private final Clock clock;

    public ConfiguredRanchRuleProvider(
            @Value("${mannschaft.ranch.development-fixtures:false}") boolean requested,
            Environment environment, RanchOperationalControlRepository controls,
            RanchCareRuleRepository careRules, Clock clock) {
        developmentFixturesEnabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        this.controls = controls;
        this.careRules = careRules;
        this.clock = clock;
    }

    @Override
    public boolean careEnabled() {
        return currentCareRule(Instant.now(clock)).isPresent();
    }

    @Override
    public Optional<CareRuleSnapshot> currentCareRule(Instant now) {
        if (developmentFixturesEnabled) {
            // 個人1体の隔離fixtureは配送pauseや通常公開OFFから独立する。
            return Optional.of(new CareRuleSnapshot(
                    CARE_RULE_ID, VERSION, 20, 100, 60, 100, 1, 3, 6));
        }
        if (!controls.findById(1).map(control -> control.isCareEnabled()).orElse(false)) {
            return Optional.empty();
        }
        return careRules.publishedAt(now, PageRequest.of(0, 1)).stream().findFirst()
                .map(rule -> new CareRuleSnapshot(rule.getId(), Long.toString(rule.getVersionNumber()),
                        rule.getAmountXp(), rule.getWeeklyCapXp(), rule.getJuvenileXp(),
                        rule.getAdultXp(), 1, 3, 6));
    }

    @Override
    public Optional<EggRuleSnapshot> currentEggRule(Instant now) {
        return currentCareRule(now).map(care -> new EggRuleSnapshot(
                care.ruleVersion(), 7 * 24 * 60 * 60L, 3 * 24 * 60 * 60L,
                5 * 24 * 60 * 60L, care.juvenileXp(), care.adultXp(),
                care.affinityGain(), care.warmAffinity(), care.closeAffinity()));
    }
}