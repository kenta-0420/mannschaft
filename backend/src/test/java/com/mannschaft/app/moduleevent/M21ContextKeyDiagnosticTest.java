package com.mannschaft.app.moduleevent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.SpringVersion;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定5クラスの実 context key を、ApplicationContext をロードせず測定する診断。
 * 等価関係の予想は検証せず、既存 R2 metadata 試験と同じ非初期化対照を検証する。
 * 設定値・環境変数・classpath・MCC 本文は出力しない。
 */
class M21ContextKeyDiagnosticTest {

    private static final List<String> TARGETS = List.of(
            "com.mannschaft.app.team.affiliation.TeamAffiliationNotificationScopeIT",
            "com.mannschaft.app.team.affiliation.TeamNotificationOutboxWriteIT",
            "com.mannschaft.app.team.affiliation.TeamOrgInviteCommittedIT",
            "com.mannschaft.app.schedule.ScheduleCommentPerformanceContractIT",
            "com.mannschaft.app.schedule.ScheduleCommentUnauthenticatedContractIT");
    private static boolean fixtureInitialized;

    @Test
    void 固定5クラスのcontextKeyを初期化せず測定する() throws Exception {
        assertThat(TARGETS).hasSize(5).doesNotHaveDuplicates();
        assertThat(configuration(InitializationProbe.class)).isNotNull();
        assertThat(fixtureInitialized).isFalse();

        List<MergedContextConfiguration> configurations = new ArrayList<>();
        for (String name : TARGETS) {
            Class<?> type = Class.forName(name, false, getClass().getClassLoader());
            assertThat(type.getName()).isEqualTo(name);
            configurations.add(configuration(type));
            assertThat(fixtureInitialized).isFalse();
        }
        assertThat(configurations).hasSize(5).doesNotContainNull();

        List<MergedContextConfiguration> groups = new ArrayList<>();
        List<Map<String, Object>> classes = new ArrayList<>();
        List<List<Boolean>> matrix = new ArrayList<>();
        for (int index = 0; index < configurations.size(); index++) {
            MergedContextConfiguration current = configurations.get(index);
            int group = groups.indexOf(current);
            if (group < 0) {
                group = groups.size();
                groups.add(current);
            }
            classes.add(Map.of("class", TARGETS.get(index), "group", group));
            matrix.add(configurations.stream().map(current::equals).toList());
        }

        Map<String, Object> result = Map.of(
                "classes", classes,
                "equalityMatrix", matrix,
                "staticProbeInitialized", fixtureInitialized,
                "versions", Map.of(
                        "java", System.getProperty("java.version"),
                        "spring", Objects.toString(SpringVersion.getVersion(), "unknown"),
                        "springBoot", Objects.toString(SpringBootVersion.getVersion(), "unknown")));
        Path output = Path.of("build", "context-key-probe", "result.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private static MergedContextConfiguration configuration(Class<?> type) {
        return BootstrapUtils.resolveTestContextBootstrapper(type).buildMergedContextConfiguration();
    }

    /** 共通 MySQL 基底も含む static 初期化を行わない既存方式の対照。 */
    static class InitializationProbe extends AbstractMySqlIntegrationTest {
        static {
            fixtureInitialized = true;
        }
    }
}
