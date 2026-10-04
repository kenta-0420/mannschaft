package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.repository.RanchOperationalControlRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 通常公開はDB制御と承認SKU、隔離fixtureは非本番の明示設定に限定する。 */
@Component
@Transactional
public class RanchShopControl {
    private final boolean developmentEnabled;
    private final RanchOperationalControlRepository controls;
    private final RanchShopCatalogRepository catalog;

    public RanchShopControl(@Value("${mannschaft.ranch.shop.development-fixtures:false}")
                            boolean requested, Environment environment,
                            RanchOperationalControlRepository controls,
                            RanchShopCatalogRepository catalog) {
        developmentEnabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        this.controls = controls;
        this.catalog = catalog;
    }

    public boolean enabled() {
        if (developmentEnabled) return true;
        return controls.findById(1).map(control -> control.isShopEnabled()).orElse(false)
                && catalog.hasApprovedItems();
    }
}