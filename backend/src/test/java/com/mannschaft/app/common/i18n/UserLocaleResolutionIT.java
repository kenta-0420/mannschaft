package com.mannschaft.app.common.i18n;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260923-1640 試練: 利用者の DB locale が Accept-Language / サーバー既定ロケールより
 * 優先されることを実 HTTP 境界（フィルタ鎖 + DispatcherServlet の LocaleResolver を含む）で固定する。
 *
 * <p><b>実フィルタ鎖・実 DispatcherServlet を通す理由</b>: 本欠陥は controller / service 単体では
 * 再現しない。{@code UserLocaleFilter} が {@code LocaleContextHolder} に正しい値をセットしても、
 * {@code DispatcherServlet}（{@code FrameworkServlet#processRequest}）が {@code LocaleResolver} の
 * 解決結果で上書きするため、{@code @AutoConfigureMockMvc(addFilters = false)} や
 * {@code @WebMvcTest} 単体では再現できない。{@code addFilters} を明示せず既定（true）のまま使う。</p>
 *
 * <p>正本: 2026-09-24 実測（本陣 BE:8080, e2e-user id=23, DB locale=ja）—
 * Accept-Language なし → ラベルが英語になる／Accept-Language: ja → 日本語 になる。</p>
 *
 * <h2>未認証象限（殿の差し戻しで追加）の実装方針</h2>
 * <p>未認証・{@code Accept-Language} の有無で応答文言が変わる HTTP 経路を本リポジトリ内で
 * 探索したが、<b>実在しない</b>ことを実証した上で確認した:</p>
 * <ul>
 *   <li>認証必須 EP を未認証で叩いた場合（{@code GET /api/v1/notification-type-preferences} 等）:
 *       {@code HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)} が 401 を返すのみで
 *       レスポンスボディは空（実測: {@code Body = }）。MessageSource を一切経由しない。</li>
 *   <li>{@code POST /api/v1/error-reports}（permitAll・Bean Validation エラーを誘発）:
 *       {@code GlobalExceptionHandler#handleValidationException} は
 *       {@code ErrorResponse.of(CommonErrorCode.COMMON_001, fieldErrors)} を返すが、これは
 *       {@code CommonErrorCode#getMessage()} の<b>ハードコードされた日本語文字列</b>を使うだけで
 *       {@code MessageSource}/{@code LocaleContextHolder} を経由しない。実測でも
 *       {@code Accept-Language} 無し／{@code en} のいずれも同一の日本語文言
 *       （"入力内容に不備があります" 等）が返ることを確認した（差が無い＝locale 解決とは無関係）。</li>
 *   <li>{@code messages_en.properties} には {@code error.common.*} / {@code error.translation.*} /
 *       {@code error.visibility.*} / {@code error.ad_*} 等の限られたキーしか無く、これらを
 *       直接 {@code new BusinessException(...)} で投げている箇所（{@code resolveMessage()} 経由で
 *       {@code LocaleContextHolder} を見る唯一の経路）はすべて認証必須ドメインに属し、
 *       permitAll から到達できない。{@code AUTH_*} コード（未認証で到達しうる auth 系 EP が
 *       投げる唯一のコード体系）には {@code messages_en.properties} に対応キーが1件も無く、
 *       常に {@code NoSuchMessageException} → {@code ErrorCode.getMessage()}（日本語固定）に
 *       フォールバックするため、そもそも locale で文言が変わらない。</li>
 * </ul>
 * <p>そのため未認証象限は、DispatcherServlet が実際に行う手順
 * （{@code UserLocaleFilter} 実行 → リクエスト属性へ解決結果を保存 → その後
 * {@code localeResolver.resolveLocale(request)} を呼ぶ）を、実 Spring コンテキストから
 * 取得した本物の {@link UserLocaleFilter} / {@link UserLocaleResolver} Bean（モック不使用）で
 * 直接再現して検証する（{@link #未認証_ヘッダー無し_ja()} 以下3件）。HTTP 経由の {@code MockMvc}
 * では検証できる EP が存在しないための代替であり、フィルタ・リゾルバ自体は本物である。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("利用者 DB locale の優先解決（CMP-260923-1640 試練）")
class UserLocaleResolutionIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/notification-type-preferences";

    /** SCHEDULE_CREATED のラベル（messages_ja.properties / messages_en.properties で文言が異なる）。 */
    private static final String LABEL_JA = "スケジュール作成通知";
    private static final String LABEL_EN = "Schedule created";

    private static final long JA_USER_ID = 900_101L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserLocaleFilter userLocaleFilter;

    @Autowired
    private UserLocaleResolver userLocaleResolver;

    @PersistenceContext
    private EntityManager em;

    private Locale originalDefaultLocale;

    @BeforeEach
    void setUp() {
        // AcceptHeaderLocaleResolver は Accept-Language ヘッダー不在時に
        // 「サーバーの既定ロケール」（= JVM の Locale.getDefault()）へ倒れる。
        // CI 環境依存で red/green が揺れないよう、ここで明示的に en へ固定する。
        originalDefaultLocale = Locale.getDefault();
        Locale.setDefault(Locale.ENGLISH);

        insertUserWithLocale(JA_USER_ID, "ja");
    }

    @AfterEach
    void tearDown() {
        Locale.setDefault(originalDefaultLocale);
    }

    @Test
    @DisplayName("ログイン済み・DB locale=ja・Accept-Language ヘッダー無し → 日本語ラベルを返す")
    void ログイン済み_DBロケールja_ヘッダー無し_日本語ラベル() throws Exception {
        mockMvc.perform(get(PATH).with(authentication(jaUserAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.notificationType=='SCHEDULE_CREATED')].label")
                        .value(LABEL_JA));
    }

    @Test
    @DisplayName("ログイン済み・DB locale=ja・Accept-Language: en → DB locale が優先され日本語ラベルを返す")
    void ログイン済み_DBロケールja_ヘッダーen_DBロケール優先で日本語ラベル() throws Exception {
        mockMvc.perform(get(PATH)
                        .with(authentication(jaUserAuthentication()))
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.notificationType=='SCHEDULE_CREATED')].label")
                        .value(LABEL_JA));
    }

    @Test
    @DisplayName("未認証・Accept-Language ヘッダー無し → ja が解決される（DispatcherServletのlocaleResolver呼び出しを再現）")
    void 未認証_ヘッダー無し_ja() throws Exception {
        Locale resolved = resolveLocaleAsDispatcherServletWould(null);
        assertThat(resolved.getLanguage()).isEqualTo("ja");
    }

    @Test
    @DisplayName("未認証・Accept-Language: en → en が解決される（DispatcherServletのlocaleResolver呼び出しを再現）")
    void 未認証_ヘッダーen_en() throws Exception {
        Locale resolved = resolveLocaleAsDispatcherServletWould("en");
        assertThat(resolved.getLanguage()).isEqualTo("en");
    }

    @Test
    @DisplayName("未認証・サポート外言語(fr) → ja にフォールバックする（DispatcherServletのlocaleResolver呼び出しを再現）")
    void 未認証_サポート外言語fr_ja() throws Exception {
        Locale resolved = resolveLocaleAsDispatcherServletWould("fr");
        assertThat(resolved.getLanguage()).isEqualTo("ja");
    }

    /**
     * 未認証リクエストに対して、実際の Spring Bean である {@link UserLocaleFilter} →
     * {@link UserLocaleResolver} の順で呼び出し、DispatcherServlet が
     * {@code localeResolver.resolveLocale(request)} を呼ぶ時点と同じ状態
     * （フィルタ実行後のリクエスト属性が付いた状態）を再現して解決結果を返す。
     * モックは使わず、本物の Bean・本物の {@link MockHttpServletRequest} を使う。
     */
    private Locale resolveLocaleAsDispatcherServletWould(String acceptLanguage) throws Exception {
        SecurityContextHolder.clearContext();
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
            if (acceptLanguage != null) {
                request.addHeader("Accept-Language", acceptLanguage);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();
            Locale[] resolvedHolder = new Locale[1];
            // DispatcherServlet は UserLocaleFilter 実行 “後” に localeResolver.resolveLocale(request) を
            // 呼ぶ。フィルタチェーンの終端（= フィルタ通過後）でそれを再現する。
            FilterChain dispatcherServletSimulation = (req, res) ->
                    resolvedHolder[0] = userLocaleResolver.resolveLocale((HttpServletRequest) req);
            userLocaleFilter.doFilter(request, response, dispatcherServletSimulation);
            return resolvedHolder[0];
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 実 {@code JwtAuthenticationFilter} が生成する Authentication と同じ形（principal =
     * userId の生 String）で組み立てる。{@code SecurityMockMvcRequestPostProcessors.user(String)} は
     * principal が {@link org.springframework.security.core.userdetails.UserDetails} になり
     * {@code UserLocaleFilter} の {@code auth.getPrincipal() instanceof String} 判定に一致しないため使わない。
     */
    private static UsernamePasswordAuthenticationToken jaUserAuthentication() {
        return new UsernamePasswordAuthenticationToken(String.valueOf(JA_USER_ID), null, List.of());
    }

    private void insertUserWithLocale(long userId, String locale) {
        em.createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "id, email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:id, :email, '試練', '通知', :displayName, 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, "
                                + ":locale, 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("id", userId)
                .setParameter("email", "cmp-260923-1640-" + userId + "@example.com")
                .setParameter("displayName", "試練通知" + userId)
                .setParameter("locale", locale)
                .executeUpdate();
    }
}
