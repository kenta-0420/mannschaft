package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.tournament.dto.CreateTournamentRequest;
import com.mannschaft.app.tournament.dto.StatusChangeRequest;
import com.mannschaft.app.tournament.dto.UpdateTournamentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F08.7 順位UI Wave0 検分フォロー（B-1） — {@link TournamentController} 書込系認可番人テスト。
 *
 * <p>従来 TournamentController の書込系（create/update/delete/changeStatus/continue）には認可が一切無く、
 * 認証さえあれば他組織の大会を勝手に作成・更新・削除・ステータス変更・継続作成できる IDOR/権限昇格の穴に
 * なっていた。本テストは各書込ハンドラに主催組織 ADMIN/DEPUTY_ADMIN 限定の per-scope SpEL ガード
 * （{@code @accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')}）が宣言されていることを
 * Reflection で検証し、ガードが外れたら落ちる番人として機能する。</p>
 *
 * <p>認可テストの実装方針: 本コードベースは {@code @EnableMethodSecurity(prePostEnabled=true)}（#1266 点火済）
 * により {@code @PreAuthorize} が実機で実効する。{@code @AccessGuard} は SYSTEM_ADMIN を内部短絡で許可し、
 * 当該 scope の非管理者へは false を返す（= Spring Security が 403）。宣言の存在と SpEL 式の同一性を
 * 検証する手法は {@link com.mannschaft.app.faq.controller.AdminFaqControllerTest} と同流儀。</p>
 */
@DisplayName("TournamentController — 書込系認可番人（B-1）")
class TournamentControllerAuthorizationTest {

    private static final String ORG_EXPR =
            "@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')";

    @Test
    @DisplayName("create/update/delete/changeStatus/continue はすべて org admin の SpEL ガードを宣言している")
    void writeHandlersDeclareOrgScopeGuard() throws NoSuchMethodException {
        assertPreAuthorize("createTournament", ORG_EXPR, Long.class, CreateTournamentRequest.class);
        assertPreAuthorize("updateTournament", ORG_EXPR, Long.class, Long.class, UpdateTournamentRequest.class);
        assertPreAuthorize("deleteTournament", ORG_EXPR, Long.class, Long.class);
        assertPreAuthorize("changeStatus", ORG_EXPR, Long.class, Long.class, StatusChangeRequest.class);
        assertPreAuthorize("continueTournament", ORG_EXPR, Long.class, Long.class);
    }

    @Test
    @DisplayName("読取系（list/detail）は GET であり書込ガードの対象外（過剰認可で閲覧を塞がない）")
    void readHandlersAreGetAndNotAdminGated() throws NoSuchMethodException {
        Method list = TournamentController.class.getMethod(
                "listTournaments", Long.class, String.class, int.class, int.class);
        Method detail = TournamentController.class.getMethod("getTournament", Long.class, Long.class);

        assertThat(list.isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(detail.isAnnotationPresent(GetMapping.class)).isTrue();
        assertThat(list.getAnnotation(PreAuthorize.class))
                .as("大会一覧に org admin ガードが付くと一般会員が一覧を見られなくなる")
                .isNull();
        assertThat(detail.getAnnotation(PreAuthorize.class))
                .as("大会詳細に org admin ガードが付くと一般会員が詳細を見られなくなる")
                .isNull();
    }

    @Test
    @DisplayName("旧 hasRole('ADMIN') 形式の注釈は残っていない（点火時の一斉403を防止）")
    void noLegacyHasRoleAdminRemains() {
        for (Method m : TournamentController.class.getDeclaredMethods()) {
            PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
            if (annotation != null) {
                assertThat(annotation.value())
                        .as("%s に旧 hasRole('ADMIN') が残ると method-security 点火時に 403 になる", m.getName())
                        .doesNotContain("hasRole('ADMIN')");
            }
        }
    }

    private void assertPreAuthorize(String methodName, String expectedExpr, Class<?>... paramTypes)
            throws NoSuchMethodException {
        Method m = TournamentController.class.getMethod(methodName, paramTypes);
        PreAuthorize annotation = m.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("%s に @PreAuthorize が未付与だと非管理者が他組織の大会を改竄できる", methodName)
                .isNotNull();
        assertThat(annotation.value())
                .as("%s は当該 org の管理者のみを許可する SpEL ガードでなければならない", methodName)
                .isEqualTo(expectedExpr);
    }
}
