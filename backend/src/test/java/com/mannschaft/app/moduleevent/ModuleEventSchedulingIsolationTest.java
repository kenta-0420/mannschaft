package com.mannschaft.app.moduleevent;

import com.mannschaft.app.config.SchedulingConfig;
import com.mannschaft.app.config.ShedLockConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.ConfigurationClassPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.io.IOException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Modulith の依存追加後も自動バッチ起動と分散ロックのプロファイル境界を守る回帰試験。
 *
 * <p>共通 application.yml と実設定クラス、Moments の自動構成を Spring 自身に解析させる。
 * Bean 定義の登録までで止め、refresh・Bean の生成・DB 接続・タイマー起動は行わない。
 * test・openapi-gen・e2e の自動起動停止と、local の既存 Scheduling/ShedLock 維持を検証する。</p>
 */
@DisplayName("Modulith 依存による自動スケジュール起動のプロファイル境界")
class ModuleEventSchedulingIsolationTest {

    @ParameterizedTest(name = "{0}: 自動スケジュールと分散ロックの登録={1}")
    @CsvSource({
            "test, false",
            "openapi-gen, false",
            "e2e, false",
            "local, true"
    })
    void 共通設定とModulith自動構成を読み込んでもプロファイルごとの起動契約を守る(
            String profile, boolean schedulingEnabled) throws IOException, ClassNotFoundException {
        var environment = new StandardEnvironment();
        // 実行端末の上書きが共通設定の欠落を補い、回帰を偽の緑にしないようにする。
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.setActiveProfiles(profile);
        var loader = new YamlPropertySourceLoader();
        for (var source : loader.load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var factory = new DefaultListableBeanFactory();
        var reader = new AnnotatedBeanDefinitionReader(factory, environment);
        reader.register(SchedulingConfig.class, ShedLockConfig.class,
                Class.forName("org.springframework.modulith.moments.autoconfigure.MomentsAutoConfiguration",
                        false, getClass().getClassLoader()));
        var processor = new ConfigurationClassPostProcessor();
        processor.setEnvironment(environment);

        processor.postProcessBeanDefinitionRegistry(factory);

        assertAll(
                () -> assertThat(factory.containsBeanDefinition(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                        .as("%s の自動スケジュール起動", profile)
                        .isEqualTo(schedulingEnabled),
                () -> assertThat(hasConfiguration(factory, SchedulingConfig.class))
                        .as("%s の既存 SchedulingConfig", profile)
                        .isEqualTo(schedulingEnabled),
                () -> assertThat(hasConfiguration(factory, ShedLockConfig.class))
                        .as("%s の分散ロック設定", profile)
                        .isEqualTo(schedulingEnabled));
    }

    private static boolean hasConfiguration(DefaultListableBeanFactory factory, Class<?> configuration) {
        return Arrays.stream(factory.getBeanDefinitionNames())
                .map(factory::getBeanDefinition)
                .anyMatch(definition -> configuration.getName().equals(definition.getBeanClassName()));
    }
}
