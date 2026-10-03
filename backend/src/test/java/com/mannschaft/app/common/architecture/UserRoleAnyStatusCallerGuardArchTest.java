package com.mannschaft.app.common.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-052: 状態を問わない在籍判定は、保護者による家族時間割閲覧の被参照者確認だけに限定する。
 *
 * <p>{@code existsAnyStatusByUserIdAndTeamId} は PENDING_PARENTAL_CONSENT / FROZEN の子を
 * 閲覧対象として残す意図的な例外である。権限付与や通常の在籍確認へ流用すると、非 ACTIVE
 * ユーザーを許可するため、この呼出し元を厳密に一つへ固定する。</p>
 */
@DisplayName("CMP-052 状態を問わない在籍判定の呼出元ガード")
class UserRoleAnyStatusCallerGuardArchTest {

    private static final String REPOSITORY_FQN =
            "com.mannschaft.app.role.repository.UserRoleRepository";
    private static final String METHOD_NAME = "existsAnyStatusByUserIdAndTeamId";
    private static final List<String> PARAMETER_TYPE_FQNS = List.of(
            Long.class.getName(),
            Long.class.getName());
    private static final String ALLOWED_CALLER_FQN =
            "com.mannschaft.app.timetable.personal.service.FamilyPersonalTimetableService";

    @Test
    @DisplayName("AC-1: 本番呼出しは FamilyPersonalTimetableService の一箇所だけである")
    void 本番呼出しは許可済みサービスの一箇所だけである() {
        JavaClasses imported = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.mannschaft.app");

        assertThat(imported.contain(REPOSITORY_FQN))
                .as("対象メソッドの所有者 %s が本番クラスとして存在すること", REPOSITORY_FQN)
                .isTrue();

        assertAllowedCallers(findCallersOfAnyStatusMembershipCheck(imported));
    }

    @Test
    @DisplayName("AC-2: ガードは呼出しゼロ・一箇所・複数箇所を区別して検出できる")
    void ガード自身が呼出し数の異常を検出できる() {
        assertThatThrownBy(() -> assertAllowedCallers(List.of()))
                .as("呼出しゼロは対象機能の消失なので失敗しなければならない")
                .isInstanceOf(AssertionError.class);

        assertThatCode(() -> assertAllowedCallers(List.of(ALLOWED_CALLER_FQN)))
                .as("許可済みサービスだけの一箇所は通過しなければならない")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> assertAllowedCallers(List.of(ALLOWED_CALLER_FQN, ALLOWED_CALLER_FQN)))
                .as("同じサービス内の二箇所でも失敗しなければならない")
                .isInstanceOf(AssertionError.class);

        assertThatThrownBy(() -> assertAllowedCallers(List.of(
                ALLOWED_CALLER_FQN,
                "com.mannschaft.app.role.service.RoleService")))
                .as("別サービスの追加も失敗しなければならない")
                .isInstanceOf(AssertionError.class);
    }

    private static List<String> findCallersOfAnyStatusMembershipCheck(JavaClasses imported) {
        return imported.stream()
                .flatMap(javaClass -> javaClass.getMethodCallsFromSelf().stream())
                .filter(UserRoleAnyStatusCallerGuardArchTest::targetsAnyStatusMembershipCheck)
                .map(call -> call.getOrigin().getOwner().getFullName())
                .toList();
    }

    private static boolean targetsAnyStatusMembershipCheck(JavaMethodCall call) {
        return call.getTarget().getOwner().getFullName().equals(REPOSITORY_FQN)
                && call.getTarget().getName().equals(METHOD_NAME)
                && call.getTarget().getRawParameterTypes().stream()
                        .map(parameterType -> parameterType.getName())
                        .toList()
                        .equals(PARAMETER_TYPE_FQNS);
    }

    private static void assertAllowedCallers(List<String> callerFqns) {
        assertThat(callerFqns)
                .as("%s#%s の本番呼出し元は %s の一箇所だけでなければならない",
                        REPOSITORY_FQN, METHOD_NAME, ALLOWED_CALLER_FQN)
                .containsExactly(ALLOWED_CALLER_FQN);
    }
}
