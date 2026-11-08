package com.mannschaft.app.common.testing;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 可視性 Resolver の実 MySQL 結合テストが共通の Spring 構成を共有することを固定する。
 * Context を load せず、対象クラスと MySQL 基底の static 初期化も行わない。
 */
class VisibilityResolverContextConfigurationTest {

    @Test
    void teamとorganizationとsurveyの構成keyは1種類になる() throws ClassNotFoundException {
        Set<MergedContextConfiguration> configurations = new HashSet<>();
        configurations.add(configuration("team.visibility.TeamVisibilityResolverIntegrationTest"));
        configurations.add(configuration("organization.visibility.OrganizationVisibilityResolverIntegrationTest"));
        configurations.add(configuration("survey.visibility.SurveyVisibilityResolverIntegrationTest"));
        assertThat(configurations).hasSize(1);
    }

    private static MergedContextConfiguration configuration(String name) throws ClassNotFoundException {
        Class<?> type = Class.forName("com.mannschaft.app." + name, false,
                VisibilityResolverContextConfigurationTest.class.getClassLoader());
        return BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
    }
}
