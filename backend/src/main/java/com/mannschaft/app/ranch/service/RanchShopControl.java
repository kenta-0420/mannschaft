package com.mannschaft.app.ranch.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 本番SKU公開の承認まではOFF。開発fixtureも明示指定の非本番だけ許す。 */
@Component
public final class RanchShopControl {
    private final boolean developmentEnabled;

    public RanchShopControl(@Value("${mannschaft.ranch.shop.development-fixtures:false}")
                            boolean requested, Environment environment) {
        developmentEnabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
    }

    public boolean enabled() {
        return developmentEnabled;
    }
}
