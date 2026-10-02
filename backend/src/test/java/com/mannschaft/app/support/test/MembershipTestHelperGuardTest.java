package com.mannschaft.app.support.test;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MembershipTestHelper#insertUserRole} の fail-closed 番人（CMP-027・段B1）の回帰ピン。
 *
 * <p>所属ロール（MEMBER / SUPPORTER）を {@code user_roles} へ張ることは V60.010 移行後の本番で
 * 成立しえないため、ヘルパーは実行時に {@link IllegalArgumentException} で拒否する。挙動は実装済だが、
 * 誤って緩められても気づけるよう専用の回帰テストで固定する。DB は不要（例外は em に触れる前に飛ぶ／
 * 正常系は mock EntityManager で最小限に成立させる）。</p>
 */
class MembershipTestHelperGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "SUPPORTER"})
    @DisplayName("所属ロール(MEMBER/SUPPORTER)を user_roles へ張ろうとすると IllegalArgumentException")
    void 所属ロールはuser_rolesへ張れない(String roleName) {
        EntityManager em = mock(EntityManager.class); // ガードは em に触れる前に発火するため未スタブで良い
        assertThatThrownBy(() -> MembershipTestHelper.insertUserRole(em, 1L, roleName, 10L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memberships");
    }

    @Test
    @DisplayName("権限ロール(ADMIN)は従来どおり user_roles へ張れる（IllegalArgumentException を飛ばさない）")
    void 権限ロールは従来どおり許可される() {
        EntityManager em = mock(EntityManager.class);
        Query q = mock(Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(q);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(2L); // roles.id 解決
        when(q.executeUpdate()).thenReturn(1);

        assertThatCode(() -> MembershipTestHelper.insertUserRole(em, 1L, "ADMIN", 10L, null))
                .doesNotThrowAnyException();
    }
}
