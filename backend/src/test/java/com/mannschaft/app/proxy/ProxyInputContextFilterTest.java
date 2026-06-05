package com.mannschaft.app.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyInputContextFilter} の単体テスト。
 * 代理入力ヘッダーの検証・DB再検証・ProxyInputContextの活性化ロジックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyInputContextFilter 単体テスト")
class ProxyInputContextFilterTest {

    @Mock
    private ProxyInputConsentRepository proxyInputConsentRepository;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private com.mannschaft.app.auth.guardianship.GuardianshipSwitchService guardianshipSwitchService;

    /** F08.9 P3c: フィルタは ObjectProvider 遅延解決（多数の @WebMvcTest スライスに Bean 追加を強いないため）。 */
    @Mock
    private org.springframework.beans.factory.ObjectProvider<com.mannschaft.app.auth.guardianship.GuardianshipSwitchService>
            guardianshipSwitchServiceProvider;

    @InjectMocks
    private ProxyInputContextFilter filter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest buildRequest(String proxyFor, String consentId,
                                                String inputSource, String storage) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/surveys/1/responses");
        if (proxyFor != null) request.addHeader(ProxyInputContextFilter.HEADER_PROXY_FOR, proxyFor);
        if (consentId != null) request.addHeader(ProxyInputContextFilter.HEADER_PROXY_CONSENT, consentId);
        if (inputSource != null) request.addHeader(ProxyInputContextFilter.HEADER_PROXY_SOURCE, inputSource);
        if (storage != null) request.addHeader(ProxyInputContextFilter.HEADER_PROXY_STORAGE, storage);
        return request;
    }

    private void authenticateAs(long userId) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private ProxyInputConsentEntity buildConsent(long subjectUserId, long proxyUserId) {
        return ProxyInputConsentEntity.create(
                subjectUserId, proxyUserId, 1L,
                ProxyInputConsentEntity.ConsentMethod.PAPER_SIGNED,
                null, null, null,
                LocalDate.now(), LocalDate.now().plusMonths(6));
    }

    @Nested
    @DisplayName("ヘッダーなし — 通常入力としてスルー")
    class NoProxyHeader {

        @Test
        @DisplayName("X-Proxy-For-User-Id がない場合、proxyInputContext はアクティブにならず chain.doFilter() が呼ばれる")
        void shouldPassThroughWhenNoProxyHeader() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/surveys/1/responses");
            MockHttpServletResponse response = new MockHttpServletResponse();
            FilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            verify(proxyInputContext, never()).activate(anyLong(), anyLong(), any(), any());
        }
    }

    @Nested
    @DisplayName("必須ヘッダー不足 — 400 Bad Request")
    class MissingRequiredHeaders {

        // 注（F08.9 P3c）: X-Proxy-Consent-Id が null のケースは「後見切替経路」へ分岐するため、
        // ここでは 400 にならない（旧 shouldReturn400WhenConsentIdMissing は GuardianshipSwitch へ移管）。
        // 紙同意書経路（consent-id あり）の必須ヘッダー不足のみを以下で検証する。

        @Test
        @DisplayName("X-Proxy-Input-Source が null（consent-id あり）→ 400")
        void shouldReturn400WhenInputSourceMissing() throws Exception {
            MockHttpServletRequest request = buildRequest("100", "1", null, "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("X-Proxy-Original-Storage が空文字 → 400")
        void shouldReturn400WhenStorageBlank() throws Exception {
            MockHttpServletRequest request = buildRequest("100", "1", "PAPER_FORM", "   ");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
    }

    @Nested
    @DisplayName("数値パース失敗 — 400 Bad Request")
    class InvalidNumericHeaders {

        @Test
        @DisplayName("X-Proxy-For-User-Id に 'abc' → 400")
        void shouldReturn400WhenProxyForIsNotNumeric() throws Exception {
            MockHttpServletRequest request = buildRequest("abc", "1", "PAPER_FORM", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }

        @Test
        @DisplayName("X-Proxy-Consent-Id に 'xyz' → 400")
        void shouldReturn400WhenConsentIdIsNotNumeric() throws Exception {
            MockHttpServletRequest request = buildRequest("100", "xyz", "PAPER_FORM", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
    }

    @Nested
    @DisplayName("無効な InputSource — 400 Bad Request")
    class InvalidInputSource {

        @Test
        @DisplayName("InputSource が 'INVALID_SOURCE' → 400")
        void shouldReturn400WhenInputSourceInvalid() throws Exception {
            MockHttpServletRequest request = buildRequest("100", "1", "INVALID_SOURCE", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
    }

    @Nested
    @DisplayName("未認証 — 401 Unauthorized")
    class Unauthenticated {

        @Test
        @DisplayName("SecurityContext に userId なし → 401")
        void shouldReturn401WhenNotAuthenticated() throws Exception {
            MockHttpServletRequest request = buildRequest("100", "1", "PAPER_FORM", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }
    }

    @Nested
    @DisplayName("同意書検証失敗 — 403 Forbidden")
    class ConsentValidationFailed {

        @Test
        @DisplayName("findValidConsent が empty → 403")
        void shouldReturn403WhenConsentNotFound() throws Exception {
            authenticateAs(200L);
            given(proxyInputConsentRepository.findValidConsent(1L, 200L)).willReturn(Optional.empty());

            MockHttpServletRequest request = buildRequest("100", "1", "PAPER_FORM", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }

        @Test
        @DisplayName("subject_user_id が同意書の subjectUserId と不一致 → 403")
        void shouldReturn403WhenSubjectUserIdMismatch() throws Exception {
            authenticateAs(200L);
            ProxyInputConsentEntity consent = buildConsent(999L, 200L);
            given(proxyInputConsentRepository.findValidConsent(1L, 200L)).willReturn(Optional.of(consent));

            MockHttpServletRequest request = buildRequest("100", "1", "PAPER_FORM", "金庫No.1");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        }
    }

    @Nested
    @DisplayName("正常系 — proxyInputContext がアクティブ化される")
    class HappyPath {

        @Test
        @DisplayName("全検証パス → proxyInputContext.activate() が呼ばれ chain.doFilter() が実行される")
        void shouldActivateContextWhenAllValid() throws Exception {
            authenticateAs(200L);
            ProxyInputConsentEntity consent = buildConsent(100L, 200L);
            given(proxyInputConsentRepository.findValidConsent(1L, 200L)).willReturn(Optional.of(consent));

            MockHttpServletRequest request = buildRequest("100", "1", "PAPER_FORM", "理事会金庫No.3");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            // F08.9 P3b: 同意書スコープ集合を伴う 5 引数 activate に変更（スコープ未設定の consent は空集合）
            verify(proxyInputContext).activate(100L, 1L, "PAPER_FORM", "理事会金庫No.3", java.util.Set.of());
        }
    }

    @Nested
    @DisplayName("後見切替経路（F08.9 P3c） — consent-id ヘッダなし")
    class GuardianshipSwitch {

        /** consent-id を伴わず X-Proxy-For-User-Id のみのリクエスト（後見切替経路）。 */
        private MockHttpServletRequest switchRequest(String proxyFor) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/payment-items/1/checkout");
            if (proxyFor != null) {
                request.addHeader(ProxyInputContextFilter.HEADER_PROXY_FOR, proxyFor);
            }
            return request;
        }

        @Test
        @DisplayName("リンク有効＋年齢OK → PAYMENT スコープのみで activate し chain 続行")
        void allowed_activatesWithPaymentScope() throws Exception {
            authenticateAs(200L);
            given(guardianshipSwitchServiceProvider.getObject()).willReturn(guardianshipSwitchService);
            given(guardianshipSwitchService.evaluateSwitch(200L, 100L))
                    .willReturn(com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict.ALLOWED);

            MockHttpServletRequest request = switchRequest("100");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilterInternal(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            // originalStorageLocation は NOT NULL 列のため null ではなく固定値を渡す
            // （null だと切替中の F14.1 代理入力で proxy_input_records 保存が 500 になる）。
            verify(proxyInputContext).activate(
                    100L, null, "GUARDIANSHIP_SWITCH",
                    ProxyInputContextFilter.SWITCH_STORAGE_LOCATION_NA,
                    java.util.Set.of(com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope.PAYMENT));
        }

        @Test
        @DisplayName("年齢封印 → 403 / activate されない")
        void ageLocked_403() throws Exception {
            authenticateAs(200L);
            given(guardianshipSwitchServiceProvider.getObject()).willReturn(guardianshipSwitchService);
            given(guardianshipSwitchService.evaluateSwitch(200L, 100L))
                    .willReturn(com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict.AGE_LOCKED);

            MockHttpServletRequest request = switchRequest("100");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            verify(proxyInputContext, never()).activate(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("リンクなし → 403 / activate されない")
        void linkNotFound_403() throws Exception {
            authenticateAs(200L);
            given(guardianshipSwitchServiceProvider.getObject()).willReturn(guardianshipSwitchService);
            given(guardianshipSwitchService.evaluateSwitch(200L, 100L))
                    .willReturn(com.mannschaft.app.auth.guardianship.GuardianshipSwitchService.SwitchVerdict.LINK_NOT_FOUND);

            MockHttpServletRequest request = switchRequest("100");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
            verify(proxyInputContext, never()).activate(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("未認証（保護者の SecurityContext なし）→ 401")
        void unauthenticated_401() throws Exception {
            MockHttpServletRequest request = switchRequest("100");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
        }

        @Test
        @DisplayName("X-Proxy-For-User-Id が非数値 → 400")
        void nonNumericProxyFor_400() throws Exception {
            authenticateAs(200L);
            MockHttpServletRequest request = switchRequest("abc");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, new MockFilterChain());

            assertThat(response.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        }
    }

    // Mockito の any() を static import なしで使えるようにするためのヘルパー
    private static <T> T any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
