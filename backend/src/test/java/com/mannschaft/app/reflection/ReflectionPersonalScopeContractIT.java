package com.mannschaft.app.reflection;

import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F06.5 振り返り（テーマ・エントリ・想起）EP の認可契約テスト
 * （認可根治戦役 第1波・個人領域 ロットC）。
 *
 * <p>本 IT が固定する保証:</p>
 * <ul>
 *   <li><b>テーマ ID / エントリ ID を受け取る EP</b>: 対象は<b>作成者本人のもの</b>に限る
 *       （{@code ReflectionAccessGuard}）。他ユーザーの ID は 404（{@code REFLECTION_001}）で
 *       存在を秘匿し、越境操作（更新・削除・アーカイブ・想起記録）が成立しないこと。</li>
 *   <li><b>テーマ作成の親テーマ参照</b>: 他ユーザーのテーマを親に指定できないこと（404 秘匿）。</li>
 *   <li><b>正常系の非回帰</b>: 所有者本人は従来どおり参照・更新・削除できること。</li>
 *   <li><b>未認証</b>: 401。</li>
 * </ul>
 *
 * <p>金型: {@code TodoPersonalScopeContractIT}（{@code @AutoConfigureMockMvc(addFilters=false)} +
 * 実 MySQL + 手動 SecurityContext + {@code @EnabledIf isDockerAvailable}）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("振り返り 個人スコープ 認可契約テスト（第1波 ロットC）")
class ReflectionPersonalScopeContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReflectionThemeRepository themeRepository;

    @Autowired
    private ReflectionEntryRepository entryRepository;

    @PersistenceContext
    private EntityManager em;

    private Long ownerId;
    private Long attackerId;

    private UUID ownerThemeId;
    private UUID ownerArchivedThemeId;
    private UUID ownerEntryId;
    private UUID attackerThemeId;
    private UUID attackerEntryId;

    @BeforeEach
    void setUp() {
        ownerId = insertUser("refl-authz-owner@example.com");
        attackerId = insertUser("refl-authz-attacker@example.com");

        ownerThemeId = saveTheme(ownerId, "REFLAUTHZ 所有者テーマ", false);
        ownerArchivedThemeId = saveTheme(ownerId, "REFLAUTHZ 所有者アーカイブ済テーマ", true);
        attackerThemeId = saveTheme(attackerId, "REFLAUTHZ 他ユーザーのテーマ", false);

        ownerEntryId = saveEntry(ownerId, ownerThemeId, LocalDate.now());
        attackerEntryId = saveEntry(attackerId, attackerThemeId, LocalDate.now());

        em.flush();
        em.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // 1. テーマ詳細・更新・削除
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("1. テーマ詳細/更新/削除（作成者本人限定・404秘匿）")
    class ThemeCrud {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}", ownerThemeId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーの詳細取得→404秘匿")
        void 他ユーザーの詳細取得は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}", ownerThemeId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REFLECTION_001"));
        }

        @Test
        @DisplayName("正常系: 所有者本人の詳細取得は200")
        void 所有者の詳細取得は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}", ownerThemeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("REFLAUTHZ 所有者テーマ"));
        }

        @Test
        @DisplayName("無関係な他ユーザーの更新→404秘匿（更新も成立しない）")
        void 他ユーザーの更新は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}", ownerThemeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"越境更新\"}"))
                    .andExpect(status().isNotFound());

            assertThat(themeRepository.findById(ownerThemeId).orElseThrow().getTitle())
                    .isEqualTo("REFLAUTHZ 所有者テーマ");
        }

        @Test
        @DisplayName("正常系: 所有者本人の更新は200")
        void 所有者の更新は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}", ownerThemeId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"REFLAUTHZ 改題\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("REFLAUTHZ 改題"));
        }

        @Test
        @DisplayName("無関係な他ユーザーの削除→404秘匿（論理削除も成立しない）")
        void 他ユーザーの削除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/me/reflections/themes/{id}", ownerThemeId))
                    .andExpect(status().isNotFound());

            // @Transactional 内では findById が1次キャッシュに当たるため entity の状態を見る。
            assertThat(themeRepository.findById(ownerThemeId).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 所有者本人の削除は204で論理削除される")
        void 所有者の削除は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/me/reflections/themes/{id}", ownerThemeId))
                    .andExpect(status().isNoContent());

            assertThat(themeRepository.findById(ownerThemeId).orElseThrow().getDeletedAt()).isNotNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 2. テーマ アーカイブ / 復元
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("2. テーマ アーカイブ/復元（作成者本人限定・404秘匿）")
    class ThemeArchive {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}/archive", ownerThemeId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーのアーカイブ→404秘匿（アーカイブも成立しない）")
        void 他ユーザーのアーカイブは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}/archive", ownerThemeId))
                    .andExpect(status().isNotFound());

            assertThat(themeRepository.findById(ownerThemeId).orElseThrow().getArchivedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 所有者本人のアーカイブは200")
        void 所有者のアーカイブは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}/archive", ownerThemeId))
                    .andExpect(status().isOk());

            assertThat(themeRepository.findById(ownerThemeId).orElseThrow().getArchivedAt()).isNotNull();
        }

        @Test
        @DisplayName("無関係な他ユーザーの復元→404秘匿（復元も成立しない）")
        void 他ユーザーの復元は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}/restore", ownerArchivedThemeId))
                    .andExpect(status().isNotFound());

            assertThat(themeRepository.findById(ownerArchivedThemeId).orElseThrow().getArchivedAt())
                    .isNotNull();
        }

        @Test
        @DisplayName("正常系: 所有者本人の復元は200")
        void 所有者の復元は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(patch("/api/v1/me/reflections/themes/{id}/restore", ownerArchivedThemeId))
                    .andExpect(status().isOk());

            assertThat(themeRepository.findById(ownerArchivedThemeId).orElseThrow().getArchivedAt())
                    .isNull();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 3. テーマ作成（親テーマ参照の認可）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("3. テーマ作成（親テーマは自分のものだけ指定できる）")
    class ThemeCreate {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(post("/api/v1/me/reflections/themes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"新テーマ\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("他ユーザーのテーマを親に指定→404秘匿（作成されない）")
        void 他ユーザーを親に指定は404() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/me/reflections/themes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"REFLAUTHZ 子テーマ\",\"parentThemeId\":\""
                                    + attackerThemeId + "\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REFLECTION_001"));
        }

        @Test
        @DisplayName("正常系: 自分のテーマを親に指定すると201")
        void 自分のテーマを親に指定は201() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/me/reflections/themes")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"REFLAUTHZ 子テーマOK\",\"parentThemeId\":\""
                                    + ownerThemeId + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.parentThemeId").value(ownerThemeId.toString()));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 4. エントリ一覧 / upsert
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("4. エントリ一覧/upsert（テーマ所有者限定・404秘匿）")
    class EntryList {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}/entries", ownerThemeId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーのテーマ配下エントリ一覧→404秘匿")
        void 他ユーザーの一覧は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}/entries", ownerThemeId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 所有者本人の一覧は200で自分のエントリが返る")
        void 所有者の一覧は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/reflections/themes/{id}/entries", ownerThemeId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", hasItem(ownerEntryId.toString())))
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(attackerEntryId.toString()))));
        }

        @Test
        @DisplayName("他ユーザーのテーマIDへの upsert→404秘匿（作成されない）")
        void 他ユーザーのテーマへのupsertは404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/me/reflections/entries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(upsertBody(ownerThemeId, LocalDate.now().plusDays(1))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REFLECTION_001"));

            assertThat(entryRepository.findByThemeIdOrderByTargetDateDesc(ownerThemeId)).hasSize(1);
        }

        @Test
        @DisplayName("正常系: 自分のテーマへの upsert は200")
        void 自分のテーマへのupsertは200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(put("/api/v1/me/reflections/entries")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(upsertBody(ownerThemeId, LocalDate.now().plusDays(1))))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 5. エントリ詳細 / 削除 / 想起
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("5. エントリ詳細/削除/想起（作成者本人限定・404秘匿）")
    class EntryDetail {

        @Test
        @DisplayName("未認証は401")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/reflections/entries/{id}", ownerEntryId))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("無関係な他ユーザーのエントリ詳細→404秘匿")
        void 他ユーザーの詳細は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/entries/{id}", ownerEntryId))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("REFLECTION_001"));
        }

        @Test
        @DisplayName("正常系: 所有者本人のエントリ詳細は200")
        void 所有者の詳細は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/reflections/entries/{id}", ownerEntryId))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("無関係な他ユーザーのエントリ削除→404秘匿（論理削除も成立しない）")
        void 他ユーザーの削除は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(delete("/api/v1/me/reflections/entries/{id}", ownerEntryId))
                    .andExpect(status().isNotFound());

            assertThat(entryRepository.findById(ownerEntryId).orElseThrow().getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("正常系: 所有者本人のエントリ削除は204")
        void 所有者の削除は204() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(delete("/api/v1/me/reflections/entries/{id}", ownerEntryId))
                    .andExpect(status().isNoContent());

            assertThat(entryRepository.findById(ownerEntryId).orElseThrow().getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("無関係な他ユーザーの想起記録→404秘匿")
        void 他ユーザーの想起記録は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/me/reflections/entries/{id}/recall", ownerEntryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recalledContent\":{\"note\":\"越境記録\"},"
                                    + "\"selfRating\":\"REMEMBERED\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 所有者本人の想起記録は200")
        void 所有者の想起記録は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(post("/api/v1/me/reflections/entries/{id}/recall", ownerEntryId)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"recalledContent\":{\"note\":\"思い出した\"},"
                                    + "\"selfRating\":\"REMEMBERED\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("無関係な他ユーザーの想起履歴一覧→404秘匿")
        void 他ユーザーの想起履歴は404() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/entries/{id}/recalls", ownerEntryId))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("正常系: 所有者本人の想起履歴一覧は200")
        void 所有者の想起履歴は200() throws Exception {
            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/reflections/entries/{id}/recalls", ownerEntryId))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // 6. 認可根治戦役 第7波ロットB: 自己スコープ EP
    //
    // ReflectionArchiveController#getFolders / ReflectionArchiveController#search /
    // ReflectionArchiveController#bulkArchive / ReflectionLinkableSlotController#listLinkableSlots /
    // ReflectionSettingsController#getSettings / ReflectionSettingsController#updateSettings /
    // ReflectionTermSuggestionController#suggest / ReflectionThemeController#listThemes /
    // ReflectionTodayController#getToday / ReflectionVocabCardController#getVocabCards
    // の自己スコープ性を固定する。
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("6. 自己スコープ EP（アーカイブフォルダ/検索/一括アーカイブ・科目紐づけ候補・"
            + "通知設定・学期提案・今日ビュー・単語帳）")
    class SelfScopedEndpoints {

        @Test
        @DisplayName("未認証は401（一覧系）")
        void 未認証は401() throws Exception {
            SecurityContextHolder.clearContext();
            mockMvc.perform(get("/api/v1/me/reflections/themes"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/me/reflections/archive/folders"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/me/reflections/today"))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/api/v1/me/reflections/settings"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("テーマ一覧・アーカイブフォルダ・アーカイブ検索は自己スコープ（他人のテーマは混入しない）")
        void テーマ関連一覧は自己スコープ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/themes"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[*].id", not(hasItem(ownerThemeId.toString()))));

            mockMvc.perform(get("/api/v1/me/reflections/archive/search")
                            .param("archived", "true"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[*].id", not(hasItem(ownerArchivedThemeId.toString()))));
        }

        @Test
        @DisplayName("一括アーカイブは認証主体所有のテーマのみを対象にする（同条件でも他人のテーマは変化しない）")
        void 一括アーカイブは自己スコープ() throws Exception {
            // bulkArchive は academicYear/termLabel/subjectName の3フィールドすべて null だと
            // 全件一括アーカイブ防止の安全弁（REFLECTION_011）で400になる仕様のため、
            // 条件に合致するテーマを owner・attacker 双方に用意し、同一条件で叩く。
            int targetYear = 2031;
            UUID ownerTargetThemeId = saveThemeWithAcademicYear(ownerId, "REFLAUTHZ 一括対象(所有者)", targetYear);
            UUID attackerTargetThemeId = saveThemeWithAcademicYear(attackerId, "REFLAUTHZ 一括対象(攻撃者)", targetYear);

            setAuth(attackerId);
            mockMvc.perform(post("/api/v1/me/reflections/archive/bulk-archive")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"academicYear\":" + targetYear + "}"))
                    .andExpect(status().isOk());

            em.flush();
            em.clear();
            assertThat(themeRepository.findById(attackerTargetThemeId).orElseThrow().getArchivedAt())
                    .isNotNull();
            assertThat(themeRepository.findById(ownerTargetThemeId).orElseThrow().getArchivedAt())
                    .isNull();
        }

        @Test
        @DisplayName("想起通知設定の取得・更新は認証主体本人にしか作用しない")
        void 想起通知設定は自己スコープ() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(put("/api/v1/me/reflections/settings")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"remindHour\":9}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remindHour").value(9));

            setAuth(ownerId);
            mockMvc.perform(get("/api/v1/me/reflections/settings"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.remindHour").value(not(9)));
        }

        @Test
        @DisplayName("今日ビュー・単語帳・学期提案・科目紐づけ候補は認証主体のデータのみで解決される")
        void その他の自己スコープEPは200() throws Exception {
            setAuth(attackerId);
            mockMvc.perform(get("/api/v1/me/reflections/today"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/me/reflections/term-suggestion"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/me/reflections/linkable-slots"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/me/reflections/cards")
                            .param("from", LocalDate.now().minusDays(7).toString())
                            .param("to", LocalDate.now().toString()))
                    .andExpect(status().isOk());
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private String upsertBody(UUID themeId, LocalDate targetDate) {
        return "{\"themeId\":\"" + themeId + "\",\"targetDate\":\"" + targetDate
                + "\",\"structuredContent\":{\"main_theme\":\"契約テスト\"}}";
    }

    private UUID saveTheme(Long userId, String title, boolean archived) {
        ReflectionThemeEntity theme = ReflectionThemeEntity.builder()
                .userId(userId)
                .title(title)
                .build();
        if (archived) {
            theme.archive();
        }
        return themeRepository.save(theme).getId();
    }

    /** 一括アーカイブ契約テスト用: academicYear 条件に合致する未アーカイブテーマを作成する。 */
    private UUID saveThemeWithAcademicYear(Long userId, String title, int academicYear) {
        ReflectionThemeEntity theme = ReflectionThemeEntity.builder()
                .userId(userId)
                .title(title)
                .academicYear(academicYear)
                .build();
        return themeRepository.save(theme).getId();
    }

    private UUID saveEntry(Long userId, UUID themeId, LocalDate targetDate) {
        return entryRepository.save(ReflectionEntryEntity.builder()
                .themeId(themeId)
                .userId(userId)
                .targetDate(targetDate)
                .structuredContent("{\"main_theme\":\"契約テストの本文\"}")
                .build()).getId();
    }

    private void setAuth(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
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
                                + "VALUES (:email, 'REFLAUTHZ', 'テスト', 'REFLAUTHZ テスト', 'ACTIVE', "
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
