package com.mannschaft.app.common;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** L1-A: 呼出側が利用する認可の公開契約を移設前の39メソッドに固定する。 */
class AccessControlServiceInterfaceContractTest {

    @Test
    void 同じ完全修飾名を認可interfaceとして維持する() {
        assertThat(AccessControlService.class.getName())
                .isEqualTo("com.mannschaft.app.common.AccessControlService");
        assertThat(Modifier.isPublic(AccessControlService.class.getModifiers())).isTrue();
        assertThat(AccessControlService.class.isInterface()).isTrue();
    }

    @Test
    void 公開39メソッドの引数と戻り値の型を維持する() {
        assertThat(Arrays.stream(AccessControlService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !method.isSynthetic())
                .map(method -> typeName(method.getGenericReturnType()) + " " + method.getName() + "("
                        + Arrays.stream(method.getGenericParameterTypes())
                                .map(AccessControlServiceInterfaceContractTest::typeName)
                                .collect(Collectors.joining(",")) + ")"))
                .containsExactlyInAnyOrder(
                        "Map<TeamUserKey,TeamMembershipState> getTeamMembershipStates(Collection<TeamUserKey>)",
                        "void checkMembership(Long,Long,String)",
                        "boolean isMember(Long,Long,String)",
                        "List<Long> listActiveMemberIds(Long,String)",
                        "boolean isRegularMemberOrAbove(Long,Long,String)",
                        "void checkRegularMemberOrAbove(Long,Long,String)",
                        "List<Long> listActiveRegularMemberIds(Long,String)",
                        "boolean isMemberOrDescendant(Long,Long,String)",
                        "boolean isMemberOrDescendant(Long,Long,String,boolean)",
                        "void checkMembershipOrDescendant(Long,Long,String)",
                        "void checkMembershipOrDescendant(Long,Long,String,boolean)",
                        "String getRoleName(Long,Long,String)",
                        "String resolveEffectiveRoleName(Long,Long,String)",
                        "Map<Long,String> resolveEffectiveRoleNames(Collection<Long>,Long,String)",
                        "Map<Long,String> resolveEffectiveRoleNamesIncludingOrgDescendants(Collection<Long>,Long)",
                        "boolean isSupporter(Long,Long,String)",
                        "boolean isAdminOrAbove(Long,Long,String)",
                        "boolean isAdmin(Long,Long,String)",
                        "boolean hasRoleOrAbove(Long,Long,String,String)",
                        "void checkAdminOrAbove(Long,Long,String)",
                        "void checkScopeAdminOnly(Long,Long,String)",
                        "boolean canActAsScopeAdminOnly(Long,Long,String)",
                        "void checkAdminOrHasPermission(Long,Long,String,String)",
                        "boolean hasAdminOrPermissionInScope(Long,Long,String,String)",
                        "void checkAdminOrHasPermissionInScope(Long,Long,String,String)",
                        "boolean isSystemAdmin(Long)",
                        "void checkSystemAdmin(Long)",
                        "void checkAdminOrAboveIncludingPlatform(Long,Long,String)",
                        "void checkNotLastSystemAdmin(Long)",
                        "void checkPermission(Long,Long,String,String)",
                        "boolean hasPermission(Long,Long,String,String)",
                        "void checkOwnerOrAdmin(Long,Long,Long,String)",
                        "void checkCareLink(Long,Long)",
                        "boolean hasActiveCareLink(Long,Long)",
                        "Map<Long,LocalDateTime> findActiveMembershipJoinedAtByScope(Long,String)",
                        "int countActiveDistinctMembers(String,Long)",
                        "Map<Long,Integer> countActiveDistinctMembersByScopes(String,Collection<Long>)",
                        "Set<Long> findAffiliatedScopeIds(Long,String)",
                        "Set<Long> findAdminOrAboveScopeIds(Long,String)");
    }

    @Test
    void 公開定数とnestedRecordの完全修飾名と構成を維持する() throws Exception {
        var constant = AccessControlService.class.getField("PLATFORM_SCOPE_TYPE");
        assertThat(Modifier.isStatic(constant.getModifiers())).isTrue();
        assertThat(Modifier.isFinal(constant.getModifiers())).isTrue();
        assertThat(constant.getType()).isEqualTo(String.class);
        assertThat(constant.get(null)).isEqualTo("PLATFORM");
        assertThat(Arrays.stream(AccessControlService.class.getFields()).map(field -> field.getName()))
                .containsExactly("PLATFORM_SCOPE_TYPE");
        assertRecord(AccessControlService.TeamUserKey.class, "TeamUserKey", "teamId:Long", "userId:Long");
        assertRecord(AccessControlService.TeamMembershipState.class, "TeamMembershipState",
                "active:boolean", "regularMemberOrAbove:boolean");
        assertThat(Arrays.stream(AccessControlService.class.getDeclaredClasses())
                .filter(type -> Modifier.isPublic(type.getModifiers())).map(Class::getSimpleName))
                .containsExactlyInAnyOrder("TeamUserKey", "TeamMembershipState");
    }

    private static void assertRecord(Class<?> type, String name, String... components) {
        assertThat(type.getName()).isEqualTo("com.mannschaft.app.common.AccessControlService$" + name);
        assertThat(Modifier.isPublic(type.getModifiers())).isTrue();
        assertThat(Modifier.isStatic(type.getModifiers())).isTrue();
        assertThat(type.isRecord()).isTrue();
        assertThat(Arrays.stream(type.getRecordComponents())
                .map(component -> component.getName() + ":" + typeName(component.getGenericType())))
                .containsExactly(components);
    }

    private static String typeName(Type type) {
        return type.getTypeName().replace("java.lang.", "").replace("java.util.", "")
                .replace("java.time.", "")
                .replace("com.mannschaft.app.common.AccessControlService$", "").replace(" ", "");
    }
}
