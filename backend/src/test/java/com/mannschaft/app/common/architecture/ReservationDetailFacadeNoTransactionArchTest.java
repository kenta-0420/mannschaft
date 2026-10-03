package com.mannschaft.app.common.architecture;

import com.mannschaft.app.reservation.service.ReservationDetailFacade;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 認可ファサードの型の固定（CMP-260923-0954 W3a / plan4 AC-15）。
 * Facade に {@code @Transactional} が付くと、認可が tx の内側に戻り D-3T の違反になるため禁止する。
 */
@DisplayName("ReservationDetailFacade は @Transactional を持たない")
class ReservationDetailFacadeNoTransactionArchTest {

    private final JavaClasses productionClasses = ProductionClasses.get();

    @Test
    @DisplayName("クラスに @Transactional が無い")
    void クラスに付かない() {
        classes().that().haveFullyQualifiedName(ReservationDetailFacade.class.getName())
                .should().notBeAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .andShould().notBeAnnotatedWith(jakarta.transaction.Transactional.class)
                .allowEmptyShould(false)
                .check(productionClasses);
    }

    @Test
    @DisplayName("メソッドに @Transactional が無い")
    void メソッドに付かない() {
        methods().that().areDeclaredIn(ReservationDetailFacade.class)
                .should().notBeAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
                .andShould().notBeAnnotatedWith(jakarta.transaction.Transactional.class)
                .allowEmptyShould(false)
                .check(productionClasses);
    }
}
