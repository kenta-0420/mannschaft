package com.mannschaft.app.postal;

import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PostalCodePolicyController} MockMvc 結合テスト（F02.10 §391）。
 *
 * <p>AC-10 / AC-11: GET /api/v1/postal-code/policies が 200 で JP を含む配列を返し、
 * 未認証でも到達できる（permitAll）ことを検証する。実レジストリ（{@link PostalCodePolicyRegistry}）を
 * Import して単一の真実源の内容をそのまま返すことを確認する。</p>
 */
@WebMvcTest(PostalCodePolicyController.class)
@Import(PostalCodePolicyRegistry.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("PostalCodePolicyController 結合テスト (F02.10 §391)")
class PostalCodePolicyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    // WebMvcTest が要求する依存の最小モック注入
    @MockitoBean
    private AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;

    /** @WebMvcTest コンテキスト用: @EnableMethodSecurity 有効化後の SpEL ガード依存解決 */
    @MockitoBean
    private AccessGuard accessGuard;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-10/11: GET /api/v1/postal-code/policies は 200 で JP を含む配列を返す")
    void getPolicies_returns200WithJp() throws Exception {
        mockMvc.perform(get("/api/v1/postal-code/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.countryCode == 'JP')]").exists())
                .andExpect(jsonPath("$.data[?(@.countryCode == 'JP')].example").value("123-4567"));
    }

    @Test
    @DisplayName("未認証でも到達できる（permitAll・addFilters=false 確認）")
    void getPolicies_anonymousCanAccess() throws Exception {
        SecurityContextHolder.clearContext();

        mockMvc.perform(get("/api/v1/postal-code/policies"))
                .andExpect(status().isOk());
    }
}
