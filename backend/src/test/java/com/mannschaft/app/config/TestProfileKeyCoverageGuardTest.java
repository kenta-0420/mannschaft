package com.mannschaft.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同名のテストプロファイルが main 側の設定キーを隠蔽する事故を検出する番人。
 *
 * <p>Gradle の test クラスパスでは test/resources が優先される。値は起動用途によって
 * 異なってよいが、main/resources/application-test.yml の leaf キーを test 側で黙って
 * 取り落とさないことを保証する。test 側だけの fixture 設定は許容する。</p>
 * <p>意図的な省略は完全なキーと理由を列挙する。省略が不要になった行も検出するため、
 * 番人を緩める目的の広い prefix や恒久的な未使用免除は設けない。</p>
 */
class TestProfileKeyCoverageGuardTest {

    private static final Map<String, String> INTENTIONAL_OMISSIONS = Map.of(
            "spring.datasource.url", "実 MySQL の接続先は各 IT の DynamicPropertySource が供給する",
            "spring.datasource.driver-class-name", "main の JDBC Testcontainers 用 driver は IT の実 MySQL 接続では使わない");

    @Test
    void mainのテストプロファイルのキーはtest側にも存在する() {
        Properties main = loadYaml(new FileSystemResource("src/main/resources/application-test.yml"));
        Properties test = loadYaml(new FileSystemResource("src/test/resources/application-test.yml"));

        assertThat(keys(main)).isNotEmpty();
        assertThat(keys(test)).isNotEmpty();
        assertThat(missingKeys(main, test))
                .as("main の application-test.yml は Gradle test で読まれない。欠落キーを同期するか完全なキーと省略理由を明記する")
                .isEmpty();
        assertThat(keys(main)).containsAll(INTENTIONAL_OMISSIONS.keySet());
        assertThat(keys(test)).doesNotContainAnyElementsOf(INTENTIONAL_OMISSIONS.keySet());
        assertThat(INTENTIONAL_OMISSIONS.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    void 入れ子の機能フラグ欠落を検出する() {
        Properties main = yaml("feature:\n  shift-budget:\n    enabled: true\n");

        assertThat(missingKeys(main, yaml("other: true\n")))
                .containsExactly("feature.shift-budget.enabled");
    }

    @Test
    void 同じ親の別キーを省略免除しない() {
        Properties main = yaml("spring:\n  datasource:\n    url: example\n    username: example\n");

        assertThat(missingKeys(main, yaml("other: true\n")))
                .containsExactly("spring.datasource.username");
    }

    @Test
    void 値の相違とtest専用キーは欠落とみなさない() {
        Properties main = yaml("flag: true\n");
        Properties test = yaml("flag: false\nfixture: value\n");

        assertThat(missingKeys(main, test)).isEmpty();
    }

    @Test
    void 配列要素のleafキー欠落も検出する() {
        Properties main = yaml("keys:\n  - kid: v1\n    active: true\n");
        Properties test = yaml("keys:\n  - kid: v1\n");

        assertThat(missingKeys(main, test)).containsExactly("keys[0].active");
    }

    @Test
    void booleanと数値とnullのleafキーも落とさない() {
        Properties main = yaml("enabled: false\nlimit: 0\noptional: null\n");

        assertThat(keys(main)).containsExactly("enabled", "limit", "optional");
        assertThat(missingKeys(main, yaml("fixture: value\n")))
                .containsExactly("enabled", "limit", "optional");
    }

    private static Set<String> missingKeys(Properties main, Properties test) {
        Set<String> missing = keys(main);
        missing.removeAll(keys(test));
        missing.removeAll(INTENTIONAL_OMISSIONS.keySet());
        return missing;
    }

    private static Set<String> keys(Properties properties) {
        Set<String> keys = new TreeSet<>();
        properties.keySet().forEach(key -> keys.add(key.toString()));
        return keys;
    }

    private static Properties yaml(String content) {
        return loadYaml(new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static Properties loadYaml(Resource resource) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource);
        factory.afterPropertiesSet();
        return Objects.requireNonNull(factory.getObject(), "YAML の読み取り結果が存在しない: " + resource);
    }
}
