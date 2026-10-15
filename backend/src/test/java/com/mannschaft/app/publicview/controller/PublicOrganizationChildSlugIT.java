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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公開組織配下の子 API（posts / timeline-posts / events / activities / faqs）の slug 契約テスト（AC-A13）。
 *
 * <p>公開組織ページ {@code /public/organizations/{slug}} が slug を渡して呼ぶ子 API を、
 * 親 {@code GET /api/v1/public/organizations/{slug}} と同じく slug で受ける。
 * 実 Security フィルタ・Testcontainers MySQL・実 Service / Repository を通す。</p>
 *
 * <ul>
 *   <li>公開組織の slug で各子 API が 200 で取れる</li>
 *   <li>非公開・archived・削除済・不在の slug は、親と同じ 404 / PUBLIC_001 で、本文まで不在と同一</li>
 *   <li>旧の数値 ID パスは不在と同じ応答（slug として引かれ該当なし）</li>
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@DisplayName("公開組織 子 API slug 契約テスト（AC-A13）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PublicOrganizationChildSlugIT extends AbstractMySqlIntegrationTest {

    private static final String BASE = "/api/v1/public/organizations/";

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
    private Long publicActivityId;

    @BeforeEach
    void setUp() {
        nonce = Long.toUnsignedString(System.nanoTime(), Character.MAX_RADIX);
        publicSlug = "child-pub-" + nonce;
        privateSlug = "child-priv-" + nonce;
        archivedSlug = "child-arch-" + nonce;
        deletedSlug = "child-del-" + nonce;
        absentSlug = "child-absent-" + nonce;

        publicOrgId = insertOrganization("公開組織" + nonce, publicSlug, "PUBLIC", false, false);
        insertOrganization("非公開組織" + nonce, privateSlug, "PRIVATE", false, false);
        insertOrganization("凍結組織" + nonce, archivedSlug, "PUBLIC", true, false);
        insertOrganization("削除組織" + nonce, deletedSlug, "PUBLIC", false, true);

        // timeline / events は組織側フラグが true のときだけ公開される
        em.createNativeQuery("UPDATE organizations SET timeline_posts_public = 1, public_events_enabled = 1 "
                        + "WHERE id = :id")
                .setParameter("id", publicOrgId)
                .executeUpdate();

        publicActivityId = insertActivity(publicOrgId, "公開活動" + nonce);
    }

    /** 一覧系（slug で 200 になるべき）の子パス。 */
    private List<String> listSuffixes() {
        return List.of("/posts", "/timeline-posts", "/events", "/activities", "/faqs");
    }

    /** 詳細系を含む全子パス（非公開・不在の畳み込み検証用）。 */
    private List<String> allSuffixes() {
        return List.of("/posts", "/posts/999999999", "/timeline-posts", "/events",
                "/activities", "/activities/" + publicActivityId, "/faqs");
    }

    @Test
    @DisplayName("AC-A13: 公開組織の slug で子 API（一覧）が 200 で取れる（未ログイン）")
    void childListApis_bySlug_return200() throws Exception {
        for (String suffix : listSuffixes()) {
            mockMvc.perform(get(BASE + publicSlug + suffix))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("AC-A13: 公開組織の slug で活動記録詳細が 200 で取れる")
    void activityDetail_bySlug_returns200() throws Exception {
        mockMvc.perform(get(BASE + publicSlug + "/activities/" + publicActivityId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AC-A13: 公開組織の slug + 不在の投稿 ID は型エラー（400）でなく不在応答（404）")
    void postDetail_bySlug_absentPost_returns404() throws Exception {
        mockMvc.perform(get(BASE + publicSlug + "/posts/999999999"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("AC-A13: 非公開・archived・削除済・不在の slug は、全子 API で同じ 404 / PUBLIC_001（本文も同一）")
    void nonPublicAndAbsent_collapseToIdentical404_onEveryChild() throws Exception {
        for (String suffix : allSuffixes()) {
            String absentBody = mockMvc.perform(get(BASE + absentSlug + suffix))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(absentBody).as("不在 %s", suffix).contains("PUBLIC_001");

            for (String slug : new String[] {privateSlug, archivedSlug, deletedSlug}) {
                String body = mockMvc.perform(get(BASE + slug + suffix))
                        .andExpect(status().isNotFound())
                        .andReturn().getResponse().getContentAsString();
                assertThat(body).as("slug=%s %s は PUBLIC_001", slug, suffix).contains("PUBLIC_001");
                assertThat(normalize(body, slug))
                        .as("slug=%s %s は不在と区別できてはならない", slug, suffix)
                        .isEqualTo(normalize(absentBody, absentSlug));
            }
        }
    }

    @Test
    @DisplayName("AC-A13: 旧の数値 ID パスは公開組織の子 API を返さず、不在と同じ 404 / PUBLIC_001")
    void numericIdPath_isTreatedAsAbsentSlug_onEveryChild() throws Exception {
        for (String suffix : allSuffixes()) {
            String body = mockMvc.perform(get(BASE + publicOrgId + suffix))
                    .andExpect(status().isNotFound())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).as("数値 ID %s", suffix).contains("PUBLIC_001");
        }
    }

    private static String normalize(String body, String slug) {
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

    private Long insertActivity(Long orgId, String title) {
        em.createNativeQuery("INSERT INTO activity_results ("
                        + "scope_type, scope_id, template_id, title, activity_date, "
                        + "activity_time_start, activity_time_end, location, venue_id, description, "
                        + "field_values, attachments, visibility, status, schedule_id, created_by, "
                        + "deleted_at, created_at, updated_at) VALUES ("
                        + "'ORGANIZATION', :scopeId, 1, :title, '2026-05-01', :ts, :te, 'loc', 1, '説明', "
                        + "'{}', '{}', 'PUBLIC', 'PUBLISHED', 1, 1, NULL, UTC_TIMESTAMP(), UTC_TIMESTAMP())")
                .setParameter("scopeId", orgId)
                .setParameter("title", title)
                .setParameter("ts", LocalTime.of(10, 0))
                .setParameter("te", LocalTime.of(12, 0))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM activity_results WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult()).longValue();
    }
}
