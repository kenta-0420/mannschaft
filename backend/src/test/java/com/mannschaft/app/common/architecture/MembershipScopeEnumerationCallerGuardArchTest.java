package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CMP-1014: status 条件の異なる所属列挙が正本窓口を迂回しないための番人。 */
@DisplayName("CMP-1014 所属スコープ列挙の呼出元ガード")
class MembershipScopeEnumerationCallerGuardArchTest {

    private static final String REPOSITORY_FQN =
            "com.mannschaft.app.membership.repository.MembershipRepository";
    private static final String USER_ROLE_REPOSITORY_FQN =
            "com.mannschaft.app.role.repository.UserRoleRepository";
    private static final String QUERY_SERVICE_FQN =
            "com.mannschaft.app.common.MembershipScopeQueryService";
    private static final String GUARDIAN_SERVICE_FQN =
            "com.mannschaft.app.auth.guardianship.GuardianChildViewService";

    @Test
    @DisplayName("raw current membership列挙は正本Query Serviceだけが呼ぶ")
    void rawMembershipEnumerationHasSingleCaller() {
        JavaClasses imported = productionClasses();

        Set<String> callers = callersOf(imported, REPOSITORY_FQN, "findActiveByUserAndScopeType");

        assertSingleAllowedCaller(callers, QUERY_SERVICE_FQN);
    }

    @Test
    @DisplayName("ACTIVEなroleとmembershipのUNION列挙も正本Query Serviceだけが呼ぶ")
    void activeUnionEnumerationHasSingleCaller() {
        JavaClasses imported = productionClasses();

        Set<String> teamCallers =
                callersOf(imported, USER_ROLE_REPOSITORY_FQN, "findTeamIdsByUserId");
        Set<String> organizationCallers =
                callersOf(imported, USER_ROLE_REPOSITORY_FQN, "findOrganizationIdsByUserId");

        assertSingleAllowedCaller(teamCallers, QUERY_SERVICE_FQN);
        assertSingleAllowedCaller(organizationCallers, QUERY_SERVICE_FQN);
    }

    @Test
    @DisplayName("Guardian subject専用列挙はGuardianChildViewServiceだけが呼ぶ")
    void guardianSubjectEnumerationHasSingleCallerClass() {
        JavaClasses imported = productionClasses();

        Set<String> teamCallers = callersOf(
                imported, QUERY_SERVICE_FQN, "findCurrentTeamIdsForAuthorizedGuardianSubject");
        Set<String> organizationCallers = callersOf(
                imported, QUERY_SERVICE_FQN, "findCurrentOrganizationIdsForAuthorizedGuardianSubject");

        assertSingleAllowedCaller(teamCallers, GUARDIAN_SERVICE_FQN);
        assertSingleAllowedCaller(organizationCallers, GUARDIAN_SERVICE_FQN);
    }

    @Test
    @DisplayName("番人は呼出元ゼロ・許可済み一クラス・別クラス混入を区別する")
    void guardDistinguishesMissingAndUnexpectedCallers() {
        assertThatThrownBy(() -> assertSingleAllowedCaller(Set.of(), QUERY_SERVICE_FQN))
                .isInstanceOf(AssertionError.class);
        assertThatCode(() -> assertSingleAllowedCaller(Set.of(QUERY_SERVICE_FQN), QUERY_SERVICE_FQN))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> assertSingleAllowedCaller(
                Set.of(QUERY_SERVICE_FQN, "com.mannschaft.app.todo.service.ProjectService"),
                QUERY_SERVICE_FQN))
                .isInstanceOf(AssertionError.class);
    }

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");
    }

    private static Set<String> callersOf(
            JavaClasses imported, String ownerFqn, String methodName) {
        return imported.stream()
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .filter(call -> targets(call, ownerFqn, methodName))
                .map(call -> call.getOrigin().getOwner().getFullName())
                .collect(Collectors.toSet());
    }

    private static boolean targets(JavaMethodCall call, String ownerFqn, String methodName) {
        return call.getTarget().getOwner().getFullName().equals(ownerFqn)
                && call.getTarget().getName().equals(methodName);
    }

    private static void assertSingleAllowedCaller(Set<String> actual, String allowedCaller) {
        assertThat(actual)
                .as("呼出元クラスは %s のみでなければならない", allowedCaller)
                .containsExactly(allowedCaller);
    }
}
