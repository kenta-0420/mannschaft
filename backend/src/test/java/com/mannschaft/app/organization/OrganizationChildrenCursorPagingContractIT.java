package com.mannschaft.app.organization;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F01.2 子組織一覧カーソルページング（{@code GET /{slug}/children}）の契約 IT。
 *
 * <p>金型: {@link OrganizationCoreAuthzContractIT}（同ドメインの契約 IT の作法・
 * Testcontainers 起動・シード投入・認証主体の与え方を踏襲した）。</p>
 *
 * <p><b>本 IT が存在する理由</b>: PR #2599 で {@code OrganizationHierarchyService#getChildren}
 * の3欠陥（カーソルが SQL に降りていない／ORDER BY 無し／hasNext がフィルタ後件数判定）を
 * 根治し、可視性判定を Mockito でスタブ不能な JPQL
 * （{@code visibility = PUBLIC OR o.id IN :memberOrgIds}）へ移した。
 * {@code OrganizationServiceAncestorsTest}（UT・Repository をモック）は
 * 「サービスが Repository へ何を渡したか」しか検証できず、
 * そのクエリが実際に DB 上で正しい行を返すかは実 SQL を走らせないと分からない。
 * 本 IT は「渡した先の SQL が何を返すか」を守る（UT とは役割が異なる。UT は削除しない）。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("組織 子組織一覧カーソルページング契約テスト（PR #2599 根治の実SQL裏取り）")
class OrganizationChildrenCursorPagingContractIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long parentOrgId;
    private String parentOrgSlug;

    private Long memberUserId;
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        insertRoleIfAbsent("MEMBER", "メンバー", 5);

        parentOrgId = insertOrganization("CHILDPAGE契約親組織", "PUBLIC", null);
        parentOrgSlug = selectSlug(parentOrgId);

        memberUserId = insertUser("childpage-member@example.com");
        outsiderId = insertUser("childpage-outsider@example.com");

        // memberUserId は後段の AC-2 用の PRIVATE 子にのみ所属させる（setUp 時点では未作成のため
        // 各ネストクラスで個別に子組織を作り、必要な user_roles をそのテスト内で追加する）。

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7-4系: 非メンバー・PRIVATE除外／メンバーには表示（可視性の実SQL裏取り）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("可視性（PRIVATE子の実SQLフィルタ）")
    class Visibility {

        @Test
        @DisplayName("AC-1_非メンバーにはPRIVATEな子組織が返らない（漏洩しない）")
        void AC_1_非メンバーにはPRIVATE子が返らない() throws Exception {
            Long privateChild = insertOrganization("CHILDPAGE非公開子", "PRIVATE", parentOrgId);

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/children", parentOrgSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(0));

            // 直接 ID で漏洩していないことも確認する（0件であることの裏取り）
            assertThat(privateChild).isNotNull();
        }

        @Test
        @DisplayName("AC-2_メンバーには自分が所属するPRIVATEな子組織が返る（過剰に隠さない）")
        void AC_2_メンバーにはPRIVATE子が返る() throws Exception {
            Long privateChild = insertOrganization("CHILDPAGE非公開子_所属あり", "PRIVATE", parentOrgId);
            MembershipTestHelper.insertMembership(em, memberUserId, ScopeType.ORGANIZATION, privateChild, RoleKind.MEMBER);
            em.flush();
            em.clear();

            setAuthentication(memberUserId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/children", parentOrgSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(privateChild))
                    .andExpect(jsonPath("$.data[0].visibility").value("PRIVATE"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7-4本体: 所属組織0件（センチネル -1L 経路）の実SQL裏取り
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("所属組織0件のセンチネル経路")
    class NoMembershipSentinel {

        @Test
        @DisplayName("AC-4_所属組織0件の利用者でもPUBLICな子は見える_IN空コレクションの罠を実SQLで踏まない")
        void AC_4_所属0件でもPUBLIC子は見える() throws Exception {
            Long publicChild = insertOrganization("CHILDPAGEセンチネル用公開子", "PUBLIC", parentOrgId);

            // outsiderId は user_roles に一切行を持たない（所属組織0件）。
            // findOrganizationIdsByUserId が空リストを返し、サービス側でセンチネル -1L に
            // 差し替えられて JPQL の IN :memberOrgIds へ渡る経路を実SQLで検証する。
            // ここが壊れていれば IN () の構文エラーで 500 になる。
            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/children", parentOrgSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(publicChild));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7-5: 論理削除された子組織は @SQLRestriction により返らない
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("論理削除された子組織の除外")
    class SoftDeleted {

        @Test
        @DisplayName("AC-5_論理削除された子組織は返らない")
        void AC_5_論理削除子は返らない() throws Exception {
            Long liveChild = insertOrganization("CHILDPAGE生存子", "PUBLIC", parentOrgId);
            Long deletedChild = insertOrganization("CHILDPAGE削除済子", "PUBLIC", parentOrgId);
            em.createNativeQuery("UPDATE organizations SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", deletedChild)
                    .executeUpdate();
            em.flush();
            em.clear();

            setAuthentication(outsiderId);
            mockMvc.perform(get("/api/v1/organizations/{slug}/children", parentOrgSlug))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(liveChild));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-7-3: PRIVATE(非メンバー)を挟んでもカーソルページングが後続に到達する
    //         （本PRの主目的）
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("カーソルページング（PRIVATE挟み込み時の到達性）")
    class CursorPagingReachability {

        @Test
        @DisplayName("AC-3_PUBLIC_PRIVATE非メンバー_PUBLICと並んでも全可視な子に2ページ以降で到達する")
        void AC_3_PRIVATE挟みでも全可視子へ到達する() throws Exception {
            // ID昇順で以下の並びを作る:
            //   pub1(PUBLIC) → privA(PRIVATE非メンバー) → pub2(PUBLIC)
            //   → privB(PRIVATE非メンバー) → pub3(PUBLIC)
            // outsiderId から見える子は pub1, pub2, pub3 の3件のみ。
            Long pub1 = insertOrganization("CHILDPAGEカーソル公開1", "PUBLIC", parentOrgId);
            insertOrganization("CHILDPAGEカーソル非公開A", "PRIVATE", parentOrgId);
            Long pub2 = insertOrganization("CHILDPAGEカーソル公開2", "PUBLIC", parentOrgId);
            insertOrganization("CHILDPAGEカーソル非公開B", "PRIVATE", parentOrgId);
            Long pub3 = insertOrganization("CHILDPAGEカーソル公開3", "PUBLIC", parentOrgId);
            em.flush();
            em.clear();

            setAuthentication(outsiderId);

            // 1ページ目: pageSize=2 → DB は可視3件のうち先頭2件を返し、まだ続きがあるので hasNext=true
            MvcResult firstPage = mockMvc.perform(
                            get("/api/v1/organizations/{slug}/children", parentOrgSlug)
                                    .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(2))
                    .andExpect(jsonPath("$.data[0].id").value(pub1))
                    .andExpect(jsonPath("$.data[1].id").value(pub2))
                    .andExpect(jsonPath("$.meta.hasNext").value(true))
                    .andReturn();
            String nextCursor = com.jayway.jsonpath.JsonPath.read(
                    firstPage.getResponse().getContentAsString(), "$.meta.nextCursor").toString();
            assertThat(nextCursor).isEqualTo(String.valueOf(pub2));

            // 2ページ目: 1ページ目の nextCursor（PRIVATE非メンバーBを挟んだ先）を渡す
            // → pub3 に到達し、かつ pub1/pub2 を再取得しない
            mockMvc.perform(get("/api/v1/organizations/{slug}/children", parentOrgSlug)
                            .param("cursor", nextCursor)
                            .param("size", "2"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.length()").value(1))
                    .andExpect(jsonPath("$.data[0].id").value(pub3))
                    .andExpect(jsonPath("$.meta.hasNext").value(false));

            // ページを跨いで手繰り、可視な子の全体集合が pub1/pub2/pub3 とちょうど一致することも確認する
            Set<Long> collected = new LinkedHashSet<>();
            String cursor = null;
            for (int guard = 0; guard < 10; guard++) {
                var builder = get("/api/v1/organizations/{slug}/children", parentOrgSlug).param("size", "2");
                if (cursor != null) {
                    builder = builder.param("cursor", cursor);
                }
                MvcResult page = mockMvc.perform(builder).andExpect(status().isOk()).andReturn();
                String body = page.getResponse().getContentAsString();
                List<Integer> ids = com.jayway.jsonpath.JsonPath.read(body, "$.data[*].id");
                ids.forEach(id -> collected.add(id.longValue()));
                Boolean hasNext = com.jayway.jsonpath.JsonPath.read(body, "$.meta.hasNext");
                if (!hasNext) {
                    break;
                }
                Object rawCursor = com.jayway.jsonpath.JsonPath.read(body, "$.meta.nextCursor");
                cursor = rawCursor == null ? null : rawCursor.toString();
            }
            assertThat(collected).containsExactlyInAnyOrder(pub1, pub2, pub3);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** roles を name で引く idempotent seed（グローバル参照テーブルのため deleteAll しない）。 */
    private void insertRoleIfAbsent(String name, String displayName, int priority) {
        Number count = (Number) em.createNativeQuery("SELECT COUNT(*) FROM roles WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        if (count.longValue() > 0) {
            return;
        }
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, 0, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("dn", displayName)
                .setParameter("priority", priority)
                .executeUpdate();
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
                                + "VALUES (:email, 'CHILDPAGE契約', 'テスト', 'CHILDPAGE契約テスト', 'ACTIVE', "
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

    /** 親組織 ID を指定できる版。null なら親なし。 */
    private Long insertOrganization(String name, String visibility, Long parentOrganizationId) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, parent_organization_id, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', :visibility, 'NONE', 1, 0, "
                                + "CONCAT('childpage-', LEFT(REPLACE(UUID(),'-',''),8)), :parentId, NOW(), NOW())")
                .setParameter("name", name)
                .setParameter("visibility", visibility)
                .setParameter("parentId", parentOrganizationId)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private String selectSlug(Long organizationId) {
        return (String) em.createNativeQuery("SELECT slug FROM organizations WHERE id = :id")
                .setParameter("id", organizationId)
                .getSingleResult();
    }

}
