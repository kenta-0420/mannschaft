package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.tax.SystemAdminTaxCodeController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 価格改定戦役（price-revisions）試練: 税コード CRUD・revision 作成 API の
 * {@code @PreAuthorize} 注釈照合（AC-13・AC-43）。
 *
 * <p>{@link BillingAuthorizationAnnotationTest} と同じ静的照合手法を用いる
 * （standaloneSetup 契約テストはメソッドセキュリティを実行しないため、注釈存在の機械照合で補完する）。
 * {@code SystemAdminTaxCodeController} / {@code PriceRevisionController} は本試練時点で未実装であり、
 * クラス自体が存在しないため red（コンパイルエラー）になることを是とする。</p>
 */
@DisplayName("価格改定 API @PreAuthorize 注釈 照合（AC-13/AC-43）")
class PriceRevisionAuthorizationAnnotationTest {

    private PreAuthorize preAuthorize(Class<?> type, String method, Class<?>... params) throws NoSuchMethodException {
        Method m = type.getMethod(method, params);
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre)
                .as("%s#%s に @PreAuthorize が必要（public 入口の認可）", type.getSimpleName(), method)
                .isNotNull();
        return pre;
    }

    @Test
    @DisplayName("AC-13: 税コード参照・登録・更新・削除は全て SYSTEM_ADMIN 限定")
    void ac13_taxCodeCrudRequiresSystemAdmin() throws Exception {
        for (Method m : SystemAdminTaxCodeController.class.getMethods()) {
            if (m.getDeclaringClass() != SystemAdminTaxCodeController.class) {
                continue;
            }
            PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
            assertThat(pre)
                    .as("%s に @PreAuthorize が必要", m.getName())
                    .isNotNull();
            assertThat(pre.value()).contains("SYSTEM_ADMIN");
        }
        assertThat(SystemAdminTaxCodeController.class.getMethods())
                .as("list/create/update/delete の4口が揃っていること（AC-4〜AC-7）")
                .extracting(Method::getName)
                .containsAll(java.util.List.of("list", "create", "update", "delete"));
    }

    @Test
    @DisplayName("AC-43: POST /price-revisions（revision 作成）は SYSTEM_ADMIN 限定")
    void ac43_createPriceRevisionRequiresSystemAdmin() throws Exception {
        PreAuthorize pre = preAuthorize(PriceRevisionController.class, "create",
                com.mannschaft.app.billing.api.dto.PriceRevisionCreateRequest.class, String.class);
        assertThat(pre.value()).contains("SYSTEM_ADMIN");
    }
}
