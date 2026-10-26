package com.mannschaft.app.publicview.controller;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公開組織 API {@code GET /api/v1/public/organizations/{slug}} の slug 契約テスト（AC-A13）。
 *
 * <p>実 Security フィルタチェーン（{@code addFilters=false} を付けない）・Testcontainers MySQL・
 * 実 Service / Repository を通す。DB・認可・自分の Bean はモックしない。</p>
 *
 * <p>設計書 F01.2.1 §10.3: URL 識別子は slug に一本化。旧の数値 ID パスは受けない
 * （数値文字列は slug として引かれ、該当なしなので不在応答になる）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("公開組織 API slug 契約テスト（AC-A13）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PublicOrganizationSlugIT extends AbstractMySqlIntegrationTest {

    private static final String PATH = "/api/v1/public/organizations/{slug}";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private String nonce;
    private String publicSlug;
    private String privateSlug;
    private String archivedSlug;
    private String deletedSlug;
    private String absentSlug;
    private Long publicOrgId;

    @BeforeEach
    void setUp() {
        nonce = Long.toUnsignedString(System.nanoTime(), Character.MAX_RADIX);
        publicSlug = "slug-pub-" + nonce;
        privateSlug = "slug-priv-" + nonce;
        archivedSlug = "slug-arch-" + nonce;
        deletedSlug = "slug-del-" + nonce;
        absentSlug = "slug-absent-" + nonce;

        publicOrgId = insertOrganization("公開組織" + nonce, publicSlug, "PUBLIC", false, false);
        insertOrganization("非公開組織" + nonce, privateSlug, "PRIVATE", false, false);
        insertOrganization("凍結組織" + nonce, archivedSlug, "PUBLIC", true, false);
        insertOrganization("削除組織" + nonce, deletedSlug, "PUBLIC", false, true);
    }

    @Test
    @DisplayName("AC-A13: slug で公開組織が 200 で取れる（未ログイン）")
    void publicOrganization_byslug_returns200() throws Exception {
        mockMvc.perform(get(PATH, publicSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(publicOrgId))
                .andExpect(jsonPath("$.name").value("公開組織" + nonce));
    }

    @Test
    @DisplayName("AC-A13: 応答に timelinePostsPublic / publicEventsEnabled が含まれ、組織の公開設定を写す（FE は真のときだけタブを出し子 API を呼ぶ）")
    void publicOrganization_carriesTabVisibilityFlags() throws Exception {
        // 既定（設定なし）は両方 false
        mockMvc.perform(get(PATH, publicSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timelinePostsPublic").value(false))
                .andExpect(jsonPath("$.publicEventsEnabled").value(false));

        // 公開設定をした組織は true で返る
        String enabledSlug = "slug-enabled-" + nonce;
        Long enabledId = insertOrganization("設定済み組織" + nonce, enabledSlug, "PUBLIC", false, false);
        em.createNativeQuery("UPDATE organizations SET timeline_posts_public = 1, public_events_enabled = 1 "
                        + "WHERE id = :id")
                .setParameter("id", enabledId)
                .executeUpdate();

        mockMvc.perform(get(PATH, enabledSlug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timelinePostsPublic").value(true))
                .andExpect(jsonPath("$.publicEventsEnabled").value(true));
    }

    @Test
    @DisplayName("AC-A13: 非公開・archived・削除済み・不在の slug は、すべて同じステータス・同じエラーコード（PUBLIC_001 / 404）")
    void nonPublicAndAbsent_returnIdenticalNotFound() throws Exception {
        MvcResult absent = mockMvc.perform(get(PATH, absentSlug))
                .andExpect(status().isNotFound())
                .andReturn();
        String absentBody = absent.getResponse().getContentAsString();
        assertThat(absentBody).contains("PUBLIC_001");

        for (String slug : new String[] {privateSlug, archivedSlug, deletedSlug}) {
            MvcResult r = mockMvc.perform(get(PATH, slug))
                    .andExpect(status().isNotFound())
                    .andReturn();
            String body = r.getResponse().getContentAsString();
            assertThat(body)
                    .as("slug=%s は不在と同じエラーコードでなければならない（存在オラクル禁止）", slug)
                    .contains("PUBLIC_001");
            assertThat(stripSlugAndTime(body, slug))
                    .as("slug=%s のレスポンスは不在と区別できてはならない", slug)
                    .isEqualTo(stripSlugAndTime(absentBody, absentSlug));
        }
    }

    @Test
    @DisplayName("AC-A13: 旧の数値 ID パスは公開組織を返さず、不在と同じ応答（slug として引かれ該当なし）")
    void numericIdPath_isTreatedAsAbsentSlug() throws Exception {
        mockMvc.perform(get(PATH, String.valueOf(publicOrgId)))
                .andExpect(status().isNotFound());
        MvcResult r = mockMvc.perform(get(PATH, String.valueOf(publicOrgId))).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains("PUBLIC_001");
    }

    /** 応答に含まれうる要求 slug 由来の文字列と時刻値を除き、本質的な形だけを比較する。 */
    private static String stripSlugAndTime(String body, String slug) {
        return body.replace(slug, "<slug>")
                .replaceAll("\\d{4}-\\d{2}-\\d{2}T[0-9:.]+Z?", "<time>");
    }

    private Long insertOrganization(String name, String slug, String visibility,
                                    boolean archived, boolean deleted) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, archived_at, deleted_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', '" + visibility + "', 'NONE', 1, 0, :slug, "
                                + (archived ? "UTC_TIMESTAMP()" : "NULL") + ", "
                                + (deleted ? "UTC_TIMESTAMP()" : "NULL")
                                + ", UTC_TIMESTAMP(), UTC_TIMESTAMP())")
                .setParameter("name", name)
                .setParameter("slug", slug)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE slug = :slug")
                .setParameter("slug", slug)
                .getSingleResult()).longValue();
    }
}
