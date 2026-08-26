package com.mannschaft.app.quickmemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 認可根治戦役 第1波（個人領域）: quickmemo ドメインの自己スコープ 9 エンドポイントの API 契約テスト。
 *
 * <p><b>対象</b>: メモ ID を受け取らないため {@code QuickMemoAccessGuard} を経由しない EP 群。
 * これらは絞り込みキーが {@code SecurityUtils#getCurrentUserId()} に固定される構造的自己スコープであり、
 * 認可番人（{@code AuthzControllerGuardArchTest}）の呼び出しグラフ判定では認可シグナルとして
 * 検出されない。{@code @AuthorizedInService} で明示承認したうえで、本テストで回帰を固定する。</p>
 *
 * <ul>
 *   <li>{@code GET  /api/v1/quick-memos}          — 一覧</li>
 *   <li>{@code POST /api/v1/quick-memos}          — 作成（タグ紐付けの所有権検証を含む）</li>
 *   <li>{@code GET  /api/v1/quick-memos/trash}    — ゴミ箱一覧</li>
 *   <li>{@code GET  /api/v1/quick-memos/search}   — 検索</li>
 *   <li>{@code GET  /api/v1/quick-memos/settings} — リマインド設定取得</li>
 *   <li>{@code PUT  /api/v1/quick-memos/settings} — リマインド設定更新</li>
 *   <li>{@code GET    /api/v1/me/voice-input-consents/active} — 同意確認</li>
 *   <li>{@code POST   /api/v1/me/voice-input-consents}        — 同意登録</li>
 *   <li>{@code DELETE /api/v1/me/voice-input-consents/active} — 同意撤回</li>
 * </ul>
 *
 * <p><b>保証する内容</b>: いずれの EP も対象ユーザーをリクエストで指定できず、
 * 他ユーザーのメモ・設定・同意証跡は参照も変更もできない。</p>
 *
 * <p><b>金型</b>: {@code QuickMemoTagScopeContractIT}。{@code addFilters = false} のため
 * 未認証（401）の経路は存在せず本テストの対象外（{@code SecurityConfig} の
 * {@code .anyRequest().authenticated()} が担保する）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("quickmemo 自己スコープ API 契約テスト（認可根治 第1波）")
class QuickMemoSelfScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    /** メモ・タグ・設定・同意の所有者。 */
    private Long ownerId;
    /** owner とは無関係なユーザー（越境してはならない）。 */
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("qm-self-owner@example.com");
        outsiderId = insertUser("qm-self-outsider@example.com");
        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP1-4: 一覧 / 作成 / ゴミ箱 / 検索
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("メモの一覧・作成・ゴミ箱・検索 — すべて認証主体のメモに閉じる")
    class MemoSelfScope {

        @Test
        @DisplayName("一覧には他ユーザーのメモが混入しない")
        void 一覧は自己スコープに閉じる() throws Exception {
            Long memoId = createMemoAsOwner("owner のメモ");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/quick-memos"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/quick-memos"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(memoId));
        }

        @Test
        @DisplayName("作成したメモは作成者のものになる（他ユーザーからは見えない）")
        void 作成したメモは作成者に帰属する() throws Exception {
            setAuthentication(outsiderId);
            createMemoAsCurrentUser("outsider のメモ");

            setAuthentication(ownerId);
            Long ownerMemoId = createMemoAsCurrentUser("owner のメモ");

            // owner の一覧には自分のメモ1件だけが見える。
            mockMvc.perform(get("/api/v1/quick-memos"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(ownerMemoId));
        }

        @Test
        @DisplayName("他ユーザーの個人タグを紐付けたメモは作成できない（404で秘匿）")
        void 他ユーザーのタグは紐付けられない() throws Exception {
            setAuthentication(outsiderId);
            Long outsiderTagId = createPersonalTagAsCurrentUser("outsiderタグ");

            setAuthentication(ownerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "他人のタグを狙うメモ");
            body.put("tagIds", List.of(outsiderTagId));
            mockMvc.perform(post("/api/v1/quick-memos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_010"));
        }

        @Test
        @DisplayName("自分の個人タグを紐付けたメモは作成できる（正常系）")
        void 自分のタグは紐付けられる() throws Exception {
            setAuthentication(ownerId);
            Long ownerTagId = createPersonalTagAsCurrentUser("ownerタグ");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "自分のタグを付けたメモ");
            body.put("tagIds", List.of(ownerTagId));
            mockMvc.perform(post("/api/v1/quick-memos")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").exists());
        }

        @Test
        @DisplayName("ゴミ箱には他ユーザーの削除済みメモが混入しない")
        void ゴミ箱は自己スコープに閉じる() throws Exception {
            Long memoId = createMemoAsOwner("捨てるメモ");

            setAuthentication(ownerId);
            mockMvc.perform(delete("/api/v1/quick-memos/{id}", memoId))
                    .andExpect(status().isNoContent());

            mockMvc.perform(get("/api/v1/quick-memos/trash"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(memoId));

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/quick-memos/trash"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("検索では他ユーザーのメモ本文を掘り出せない")
        void 検索は自己スコープに閉じる() throws Exception {
            createMemoAsOwner("ヒミツの合言葉ZZZ");

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/quick-memos/search").param("q", "ヒミツの合言葉ZZZ"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/quick-memos/search").param("q", "ヒミツの合言葉ZZZ"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP5-6: リマインド設定（getSettings / updateSettings）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("リマインド設定 — 設定行の主キーが認証主体のため他ユーザーには到達できない")
    class Settings {

        @Test
        @DisplayName("設定は利用者ごとに独立している")
        void 設定は利用者ごとに独立する() throws Exception {
            setAuthentication(ownerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reminderEnabled", true);
            body.put("defaultOffset1Days", 1);
            body.put("defaultTime1", "09:00");
            mockMvc.perform(put("/api/v1/quick-memos/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reminderEnabled").value(true));

            mockMvc.perform(get("/api/v1/quick-memos/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(ownerId))
                    .andExpect(jsonPath("$.data.reminderEnabled").value(true));

            // outsider は既定値のまま。owner の変更は一切漏れない。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/quick-memos/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.userId").value(outsiderId))
                    .andExpect(jsonPath("$.data.reminderEnabled").value(false));
        }

        @Test
        @DisplayName("設定更新に伴う既存メモの再計算も自分のメモにしか及ばない")
        void 再計算は自分のメモにしか及ばない() throws Exception {
            // owner・outsider の双方が未整理メモを持つ状態を作る。
            Long ownerMemoId = createMemoAsOwner("owner の再計算対象");
            setAuthentication(outsiderId);
            Long outsiderMemoId = createMemoAsCurrentUser("outsider のメモ");

            // owner が ALL 指定で再計算を要求する。
            setAuthentication(ownerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("reminderEnabled", true);
            body.put("defaultOffset1Days", 30);
            body.put("defaultTime1", "10:00");
            mockMvc.perform(put("/api/v1/quick-memos/settings")
                            .param("apply_to", "ALL")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(body)))
                    .andExpect(status().isOk());

            // owner のメモにはリマインドが設定される。
            mockMvc.perform(get("/api/v1/quick-memos/{id}", ownerMemoId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reminders[0].scheduledAt").exists());

            // outsider のメモは一切書き換えられていない。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/quick-memos/{id}", outsiderMemoId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.reminders[0].scheduledAt").doesNotExist());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // EP7-9: 音声入力同意（getActiveConsent / grantConsent / revokeConsent）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("音声入力同意 — 同意証跡は (userId, version) で引き当てられ他ユーザーに漏れない")
    class VoiceInputConsent {

        @Test
        @DisplayName("同意の有無は利用者ごとに独立している")
        void 同意は利用者ごとに独立する() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/me/voice-input-consents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 1))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.hasConsent").value(true));

            mockMvc.perform(get("/api/v1/me/voice-input-consents/active").param("version", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasConsent").value(true));

            // outsider には owner の同意が一切反映されない。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/me/voice-input-consents/active").param("version", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasConsent").value(false));
        }

        @Test
        @DisplayName("他ユーザーの同意を撤回することはできない")
        void 他ユーザーの同意は撤回できない() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(post("/api/v1/me/voice-input-consents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 1))))
                    .andExpect(status().isCreated());

            // outsider の撤回要求は「自分の同意が無い」として404（自分の資源が存在しない。
            // revokeConsent は /api/v1/me 配下で認証コンテキストの userId のみを使うため、
            // 他人のIDを探索する余地はなく、単に「自分の有効な同意が無い」ことを表す）。
            setAuthentication(outsiderId);
            mockMvc.perform(delete("/api/v1/me/voice-input-consents/active"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("QM_030"));

            // owner の同意は撤回されずに残っている。
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/me/voice-input-consents/active").param("version", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hasConsent").value(true));

            // 本人による撤回は成功する（正常系）。
            mockMvc.perform(delete("/api/v1/me/voice-input-consents/active"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("同意はサーバが保持する現行ポリシーバージョン以下でのみ成立する")
        void 同意は現行バージョン以下でのみ成立する() throws Exception {
            setAuthentication(ownerId);
            mockMvc.perform(get("/api/v1/me/voice-input-consents/active").param("version", "999"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("QM_032"));

            mockMvc.perform(post("/api/v1/me/voice-input-consents")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("version", 999))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("QM_032"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private String json(Object body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private Long createMemoAsOwner(String title) throws Exception {
        setAuthentication(ownerId);
        return createMemoAsCurrentUser(title);
    }

    /** 現在の認証主体としてメモを1件作成し、その ID を返す。 */
    private Long createMemoAsCurrentUser(String title) throws Exception {
        String resp = mockMvc.perform(post("/api/v1/quick-memos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    /** 現在の認証主体として PERSONAL タグを1件作成する。名前はスコープ内一意のため nanoTime を混ぜる。 */
    private Long createPersonalTagAsCurrentUser(String namePrefix) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", namePrefix + (System.nanoTime() % 100_000_000L));
        body.put("color", "#0000FF");
        String resp = mockMvc.perform(post("/api/v1/me/tags")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data").path("id").asLong();
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, 'QM契約', 'テスト', 'QM契約テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }
}
