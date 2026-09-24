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
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

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
