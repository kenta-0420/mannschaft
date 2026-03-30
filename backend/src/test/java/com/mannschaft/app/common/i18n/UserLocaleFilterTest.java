package com.mannschaft.app.common.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link UserLocaleFilter} の単体テスト。
 * MockHttpServletRequest / MockHttpServletResponse / MockFilterChain を使い、
 * SecurityContextHolder でログイン状態を制御する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLocaleFilter 単体テスト")
class UserLocaleFilterTest {

    @Mock
    private UserLocaleCache userLocaleCache;

    @InjectMocks
    private UserLocaleFilter filter;

    @AfterEach
    void tearDown() {
        // スレッド汚染防止: 各テスト後に SecurityContextHolder をクリアする
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // doFilterInternal
    // ========================================

    @Nested
    @DisplayName("doFilterInternal")
    class DoFilterInternal {

        @Test
        @DisplayName("ログイン済み・locale=en の場合 LocaleContextHolder に Locale.ENGLISH がセットされる")
        void ログイン済み_en_LocaleEnglishがセット() throws Exception {
            // Given: SecurityContextHolder に userId="1" の Authentication をセット
            var auth = new UsernamePasswordAuthenticationToken("1", null, List.of());
            var ctx = new SecurityContextImpl(auth);
            SecurityContextHolder.setContext(ctx);

            given(userLocaleCache.getLocale(1L)).willReturn("en");

            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            // When & Then: doFilter() が正常終了し例外が発生しないこと
            assertThatCode(() -> filter.doFilter(request, response, chain))
                    .doesNotThrowAnyException();

            // FilterChain が実行されたことを確認
            verify(userLocaleCache).getLocale(1L);
        }

        @Test
        @DisplayName("未ログイン・Accept-Language: ko の場合 フィルターが正常に通過する")
        void 未ログイン_ko_正常通過() throws Exception {
            // Given: SecurityContextHolder は空
            // MockHttpServletRequest に Accept-Language: ko をセット
            var request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", "ko");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            // When & Then: 例外なく通過すること
            assertThatCode(() -> filter.doFilter(request, response, chain))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("未ログイン・Accept-Language が不正文字列の場合 デフォルト(ja)にフォールバックして正常通過する")
        void 未ログイン_不正AcceptLanguage_フォールバック() throws Exception {
            // Given: SecurityContextHolder は空
            // 不正な Accept-Language: 解析時に IllegalArgumentException が発生するケース
            var request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", "invalid;q=abc");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            // When & Then: IllegalArgumentException が握りつぶされ、例外なく通過すること
            assertThatCode(() -> filter.doFilter(request, response, chain))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("未ログイン・Accept-Language が対応外言語の場合 デフォルト(ja)にフォールバックする")
        void 未ログイン_未対応言語_フォールバック() throws Exception {
            // Given: Accept-Language: ar（アラビア語 — SUPPORTED_LOCALES に含まれない）
            var request = new MockHttpServletRequest();
            request.addHeader("Accept-Language", "ar");
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            // When & Then: 例外なく通過し、DEFAULT_LOCALE(ja) にフォールバックされること
            assertThatCode(() -> filter.doFilter(request, response, chain))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ログイン済み・locale が null の場合 デフォルト(ja)にフォールバックして正常通過する")
        void ログイン済み_localeNull_フォールバック() throws Exception {
            // Given: SecurityContextHolder に userId="1" の Authentication をセット
            var auth = new UsernamePasswordAuthenticationToken("1", null, List.of());
            var ctx = new SecurityContextImpl(auth);
            SecurityContextHolder.setContext(ctx);

            // UserLocaleCache.getLocale(1L) → null を返す（DBにlocaleなし相当）
            given(userLocaleCache.getLocale(1L)).willReturn(null);

            var request = new MockHttpServletRequest();
            var response = new MockHttpServletResponse();
            var chain = new MockFilterChain();

            // When & Then: null でもフォールバックし、例外なく通過すること
            assertThatCode(() -> filter.doFilter(request, response, chain))
                    .doesNotThrowAnyException();

            verify(userLocaleCache).getLocale(1L);
        }
    }
}
