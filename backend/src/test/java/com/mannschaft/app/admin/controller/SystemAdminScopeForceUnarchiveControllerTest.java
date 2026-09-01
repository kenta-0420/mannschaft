package com.mannschaft.app.admin.controller;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 柱①「ADMINゼロ根治」AC8 — {@link SystemAdminScopeForceUnarchiveController} の受け入れテスト（試練・red）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §10.12 / §15。
 * 「候補ゼロ→archive。SYSTEM_ADMINのforce-unarchiveはADMIN指名を伴わない限り拒否される」。
 * 本テストは Bean Validation 契約（{@code newAdminUserId} 必須）と、コントローラ骨格が
 * まだ業務ロジックを実装していないことの双方を検証する。MockMvc 経由の認可・状態遷移の
 * 全体像は出陣後、他IT（{@code LastAdminSuccessionAcceptanceIT} 系）で拡充する。</p>
 */
@DisplayName("SystemAdminScopeForceUnarchiveController 受け入れテスト（AC8・柱①ADMINゼロ根治）")
class SystemAdminScopeForceUnarchiveControllerTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        validatorFactory.close();
    }

    @Test
    @DisplayName("AC8: newAdminUserIdを指名しないリクエストはBean Validationで拒否される")
    void ADMIN指名なしのリクエストはバリデーション違反になる() {
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        // newAdminUserId を設定しない（未指名）

        Set<ConstraintViolation<SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest>> violations =
                validator.validate(req);

        assertThat(violations).isNotEmpty();
    }

    @Test
    @DisplayName("AC8: newAdminUserIdを指名したリクエストはBean Validationを通過する")
    void ADMIN指名ありのリクエストはバリデーション違反にならない() {
        SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest req =
                new SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest();
        req.setNewAdminUserId(2L);

        Set<ConstraintViolation<SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest>> violations =
                validator.validate(req);

        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("AC8: 候補ゼロでarchiveされたスコープの救済経路は未実装のまま呼び出すと例外になる")
    void 未実装のコントローラ呼び出しは例外になる() {
        // SecurityUtils.getCurrentUserId() が Spring セキュリティコンテキスト依存のため、
        // ここではコントローラを直接呼ばず「骨格が実装済みメソッドを一切持たない」ことを
        // クラス構造で確認する（実際の認可・状態遷移は出陣後に MockMvc IT で拡充する）。
        java.lang.reflect.Method method;
        try {
            method = SystemAdminScopeForceUnarchiveController.class.getDeclaredMethod(
                    "forceUnarchive", String.class, Long.class,
                    SystemAdminScopeForceUnarchiveController.ForceUnarchiveRequest.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("forceUnarchiveメソッドが見つからない", e);
        }
        assertThat(method).isNotNull();
        // TODO 出陣で実装後: MockMvc + Testcontainers で
        // 「ADMIN不在スコープへの newAdminUserId 未指定リクエストは422、指定時は200で
        // archived_at が解除され newAdminUserId がADMIN化される」ことを検証する。
        // 骨格段階ではこの後段が未実装のため、以下は意図的に失敗させ red を維持する。
        throw new AssertionError("出陣で実装: force-unarchive のADMIN指名強制ロジックが未実装");
    }
}
