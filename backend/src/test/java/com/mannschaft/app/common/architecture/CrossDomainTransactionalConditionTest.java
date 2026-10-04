package com.mannschaft.app.common.architecture;

import com.mannschaft.app.gdpr.service.AccountPurgeService;
import com.mannschaft.app.resident.transitivetx.ProgrammaticTransactionalFixture;
import com.mannschaft.app.resident.transitivetx.TransitiveTransactionalFixture;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

/** D-3 の raw condition を検証する。凍結ルールの評価・ストア更新は行わない。 */
class CrossDomainTransactionalConditionTest {

    @Test
    @DisplayName("注釈をTemplate実行へ置き換えた実AccountPurgeServiceの越境を見逃さない")
    void accountPurgeTemplateExecutionRemainsDetected() {
        JavaClasses imported = new ClassFileImporter().importClasses(AccountPurgeService.class);
        JavaClass service = imported.get(AccountPurgeService.class);
        assertThat(service.isAnnotatedWith(Transactional.class)).isFalse();
        assertThat(service.getMethods()).noneMatch(method -> method.isAnnotatedWith(Transactional.class));

        assertThat(violations(imported)).anySatisfy(message -> assertThat(message)
            .contains("com.mannschaft.app.gdpr.service.AccountPurgeService (domain 'gdpr', @Transactional)")
            .contains("com.mannschaft.app.auth.repository.UserRepository (domain 'auth')"));
    }

    @Test
    @DisplayName("TransactionOperations経由のexecuteでも越境を検出する")
    void interfaceExecutionIsDetected() {
        assertThat(violations(ProgrammaticTransactionalFixture.InterfaceExecution.class))
            .contains("com.mannschaft.app.resident.transitivetx.ProgrammaticTransactionalFixture$InterfaceExecution"
                + " (domain 'resident', @Transactional) depends on other-domain repository "
                + "com.mannschaft.app.proxy.repository.TransitiveProxyRepository (domain 'proxy')");
    }

    @Test
    @DisplayName("TransactionTemplateのexecuteWithoutResultでも越境を検出する")
    void executionWithoutResultIsDetected() {
        assertThat(violations(ProgrammaticTransactionalFixture.TemplateExecutionWithoutResult.class))
            .isNotEmpty();
    }

    @Test
    @DisplayName("Templateを実行せず保持や設定するだけでは越境TXとみなさない")
    void unusedTemplateIsNotTransactional() {
        assertThat(violations(ProgrammaticTransactionalFixture.UnusedTemplate.class)).isEmpty();
    }

    @Test
    @DisplayName("明示トランザクション内の同一ドメインRepositoryは許可する")
    void sameDomainIsAllowed() {
        assertThat(violations(ProgrammaticTransactionalFixture.SameDomainExecution.class)).isEmpty();
    }

    @Test
    @DisplayName("既存のメソッドTransactional注釈による越境検出を維持する")
    void annotationExecutionRemainsDetected() {
        assertThat(violations(TransitiveTransactionalFixture.class)).anySatisfy(message -> assertThat(message)
            .contains("com.mannschaft.app.proxy.repository.InheritedProxyRepository (domain 'proxy')"));
    }

    private static List<String> violations(Class<?> fixture) {
        return violations(new ClassFileImporter().importClasses(fixture));
    }

    private static List<String> violations(JavaClasses imported) {
        return classes().should(CrossDomainTransactionalArchTest.notDependOnOtherDomainRepositories())
            .evaluate(imported).getFailureReport().getDetails();
    }
}
