package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Springの生成でも明示登録の検証と未登録時の停止状態を保つ。 */
class DiagnosisApprovedQuestionnaireRegistryContextTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DiagnosisApprovedQuestionnaireRegistry.class)
            .withBean(ObjectMapper.class, () -> new ObjectMapper().findAndRegisterModules());

    @Test
    void springSelectsRegisteredMainConstructorAndMissingRegistrationStaysOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DiagnosisApprovedQuestionnaireRegistry.class);
            var registry = context.getBean(DiagnosisApprovedQuestionnaireRegistry.class);
            assertThat(registry.current()).isEmpty();
            assertThat(registry.supports("unregistered-version", "signed-centered-v1")).isFalse();
        });
    }

    @Test
    void partialRegistrationStillRefusesContextInsteadOfDefaultingToEmptyRegistry() {
        runner.withPropertyValues("mannschaft.diagnosis.approved-catalog.version=unregistered-version")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasRootCauseMessage("正式診断catalogの明示登録が不正です");
                });
    }
}
