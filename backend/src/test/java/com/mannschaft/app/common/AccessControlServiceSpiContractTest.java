package com.mannschaft.app.common;

import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** L1-A AC7/8/10: coreが所有するSPIと依存の向きを固定する。 */
class AccessControlServiceSpiContractTest {
    static final String CARE_LINK_QUERY = "com.mannschaft.app.role.api.CareLinkQuery";
    static final String WITHDRAWAL_ERROR = "com.mannschaft.app.role.api.LastAdminWithdrawalErrorProvider";
    static final String IMPLEMENTATION = "com.mannschaft.app.role.service.DefaultAccessControlService";

    @Test
    void family照会は見守り者と対象者のIDを受けるcore所有interfaceにする() throws Exception {
        Class<?> type = requiredClass(CARE_LINK_QUERY);
        assertPublicInterface(type);
        assertThat(type.getDeclaredMethods()).hasSize(1);
        assertThat(type.getMethod("hasActiveCareLink", Long.class, Long.class).getReturnType())
                .isEqualTo(boolean.class);
    }

    @Test
    void 退会エラーはcore所有interfaceから共通ErrorCodeとして返す() throws Exception {
        Class<?> type = requiredClass(WITHDRAWAL_ERROR);
        assertPublicInterface(type);
        assertThat(type.getDeclaredMethods()).hasSize(1);
        assertThat(type.getMethod("lastAdminWithdrawalError").getReturnType()).isEqualTo(ErrorCode.class);
    }

    @Test
    void common契約とrole認可実装から外側業務型への直接依存を除く() throws Exception {
        requiredClass(IMPLEMENTATION);
        String contract = source("common/AccessControlService.java");
        assertThat(contract).doesNotContain("import com.mannschaft.app.",
                "com.mannschaft.app.role.", "com.mannschaft.app.family.",
                "com.mannschaft.app.gdpr.", "com.mannschaft.app.membership.",
                "com.mannschaft.app.organization.", "com.mannschaft.app.common.visibility.");
        assertThat(source("role/service/DefaultAccessControlService.java"))
                .doesNotContain("com.mannschaft.app.family.", "com.mannschaft.app.gdpr.",
                        "com.mannschaft.app.common.visibility.");
        assertThat(Arrays.stream(AccessControlService.class.getDeclaredFields())
                .filter(field -> !field.isSynthetic()).map(field -> field.getType().getName()))
                .containsExactly("java.lang.String");
    }

    static Class<?> requiredClass(String name) {
        Class<?>[] loaded = new Class<?>[1];
        assertThatCode(() -> loaded[0] = Class.forName(name))
                .as("L1-Aで追加する本番型 %s が存在する", name).doesNotThrowAnyException();
        return loaded[0];
    }

    private static void assertPublicInterface(Class<?> type) {
        assertThat(type.isInterface()).isTrue();
        assertThat(Modifier.isPublic(type.getModifiers())).isTrue();
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/mannschaft/app", relative))
                .replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
    }
}
