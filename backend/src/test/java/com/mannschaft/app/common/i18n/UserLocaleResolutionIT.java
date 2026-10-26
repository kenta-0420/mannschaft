package com.mannschaft.app.common.i18n;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.support.RequestContextUtils;

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
 * <h2>未認証象限（殿の差し戻し・Codex 指摘対応）</h2>
 * <p>未認証・{@code Accept-Language} の有無で<b>応答文言</b>が変わる HTTP 経路を探索したが
 * 存在しない（認証必須 EP を未認証で叩くと 401・空ボディ、{@code POST /api/v1/error-reports} の
 * バリデーションエラーはハードコード日本語固定で locale 非依存）。そのため未認証象限は、
 * {@code GET /api/i18n/supported-locales}（{@code permitAll}・200 を返す唯一の言語非依存な
 * 公開 GET EP）へ本物の {@code MockMvc}（{@code addFilters} 既定 true・Spring Security の
 * フィルタ鎖を含む実 HTTP 経路）でリクエストし、{@code DispatcherServlet} が実際に使った
 * locale を {@link RequestContextUtils#getLocale(jakarta.servlet.http.HttpServletRequest)} で
 * 検証する（レスポンス本文ではなく、DispatcherServlet が request に残す
 * {@code LocaleResolver} 参照経由で問い合わせるため、"DispatcherServlet が実際に使った値" を
 * 直接読める。Filter が残す {@code RESOLVED_LOCALE_ATTRIBUTE} を直に読むのではなく、
 * こちらを正としたのは、Resolver が呼ばれず属性が孤立して残るだけの回帰があっても
 * {@code RequestContextUtils} 経由なら検知できるため）。</p>
 *
 * <p><b>実 HTTP 経路化で追加発見・根治した実害</b>: 直接呼び出し版（Codex 指摘前）では見えなかった
 * 別欠陥が本物のフィルタ鎖を通したことで発覚した。Spring Security の
 * {@code AnonymousAuthenticationFilter} は未ログインリクエストにも
 * principal="anonymousUser"（{@code String}）・{@code isAuthenticated()==true} の
 * {@code AnonymousAuthenticationToken} を {@code SecurityContextHolder} にセットする。
 * {@code UserLocaleFilter} はこれを「ログイン済み」と誤判定し、{@code Long.parseLong("anonymousUser")}
 * が失敗して {@code DEFAULT_LOCALE(ja)} に固定フォールバックしていたため、未ログイン利用者の
 * {@code Accept-Language} が常に無視されていた（本試練の未認証・en ケースで実際に red になった）。
 * {@code UserLocaleFilter} 側で {@code AnonymousAuthenticationToken} を除外する修正で根治した。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("利用者 DB locale の優先解決（CMP-260923-1640 試練）")
class UserLocaleResolutionIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/notification-type-preferences";

    /** {@code permitAll}・200 を返す言語非依存の公開 GET EP（未認証象限の検体）。 */
    private static final String PUBLIC_PATH = "/api/i18n/supported-locales";

    /** SCHEDULE_CREATED のラベル（messages_ja.properties / messages_en.properties で文言が異なる）。 */
    private static final String LABEL_JA = "スケジュール作成通知";
    private static final String LABEL_EN = "Schedule created";

    private static final long JA_USER_ID = 900_101L;

    @Autowired
    private MockMvc mockMvc;

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
    @DisplayName("未認証・Accept-Language ヘッダー無し → DispatcherServletがjaを使う（実HTTP経路・フィルタ鎖込み）")
    void 未認証_ヘッダー無し_ja() throws Exception {
        MvcResult result = mockMvc.perform(get(PUBLIC_PATH))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(RequestContextUtils.getLocale(result.getRequest()).getLanguage()).isEqualTo("ja");
    }

    @Test
    @DisplayName("未認証・Accept-Language: en → DispatcherServletがenを使う（実HTTP経路・フィルタ鎖込み）")
    void 未認証_ヘッダーen_en() throws Exception {
        MvcResult result = mockMvc.perform(get(PUBLIC_PATH).header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(RequestContextUtils.getLocale(result.getRequest()).getLanguage()).isEqualTo("en");
    }

    @Test
    @DisplayName("未認証・サポート外言語(fr) → DispatcherServletがjaにフォールバックする（実HTTP経路・フィルタ鎖込み）")
    void 未認証_サポート外言語fr_ja() throws Exception {
        MvcResult result = mockMvc.perform(get(PUBLIC_PATH).header("Accept-Language", "fr"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(RequestContextUtils.getLocale(result.getRequest()).getLanguage()).isEqualTo("ja");
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
