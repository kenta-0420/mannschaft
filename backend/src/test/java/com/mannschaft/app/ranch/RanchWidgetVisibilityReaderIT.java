package com.mannschaft.app.ranch;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetSettingRepository;
import com.mannschaft.app.ranch.service.RanchWidgetVisibilityReader;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** FE正式キーと本人dashboard保存設定からRanch可視性を投影する。 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RanchWidgetVisibilityReaderIT extends AbstractMySqlIntegrationTest {
    @Autowired private UserRepository users;
    @Autowired private DashboardWidgetSettingRepository settings;
    @Autowired private RanchWidgetVisibilityReader reader;

    @Test
    void defaultAndHiddenSettingUseDashboardCanonicalValueOnly() {
        Long me = users.saveAndFlush(RanchTestFixture.user()).getId();
        Long other = users.saveAndFlush(RanchTestFixture.user()).getId();
        assertThat(reader.visible(me)).isFalse();
        var setting = settings.saveAndFlush(DashboardWidgetSettingEntity.builder()
                .userId(me).scopeType(ScopeType.PERSONAL).scopeId(0L)
                .widgetKey(WidgetKey.PERSONAL_DINOSAUR_RANCH.name())
                .isVisible(true).sortOrder(30).build());
        assertThat(reader.visible(me)).isTrue();
        setting.changeVisibility(false);
        settings.saveAndFlush(setting);
        assertThat(reader.visible(me)).isFalse();
        assertThat(reader.visible(other)).isFalse();
    }
}
