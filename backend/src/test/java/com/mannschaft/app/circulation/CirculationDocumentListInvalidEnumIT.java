package com.mannschaft.app.circulation;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-260920-1041: 回覧一覧APIが不正な circulation_mode 値を持つ 1 行で常時 500 になる欠陥の試練。
 *
 * <p><b>再現した実障害</b>: 共有開発DBの {@code circulation_documents.circulation_mode} 列に
 * アプリケーションが一度も書き込んだことのない不正値（例: {@code PARALLEL}）が混入していた行があり、
 * 一覧取得（{@code GET .../circulations}）が
 * {@code InvalidDataAccessApiUsageException: No enum constant CirculationMode.PARALLEL} で
 * 全体 500（COMMON_999）になっていた。不正値は 1 行だけなのに、同じページに含まれる正常な行まで
 * 巻き添えで表示不能になっていた。</p>
 *
 * <p><b>是正内容</b>: {@link CirculationModeConverter} が読み込み時に未知の enum 文字列を検知し、
 * ログに ERROR として残した上で縮退値 {@link CirculationMode#UNKNOWN} へ写像する。
 * これにより 1 行の異常が一覧全体を落とさなくなる。</p>
 */
@Transactional
@AutoConfigureMockMvc(addFilters = false)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("回覧一覧: 不正なcirculation_mode値を含む行があっても一覧取得は成功する（CMP-260920-1041）")
class CirculationDocumentListInvalidEnumIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private Long teamId;
    private Long memberId;

    @BeforeEach
    void setUp() {
        teamId = insertTeam("CMP260920チーム");
        memberId = insertUser("cmp260920-member@example.com");
        MembershipTestHelper.insertMembership(em, memberId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        insertDocument(teamId, memberId, "正常な回覧文書 " + System.nanoTime(), "SIMULTANEOUS");
        insertDocument(teamId, memberId, "不正enum混入回覧文書 " + System.nanoTime(), "PARALLEL");

        em.flush();
        em.clear();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(memberId.toString(), null, List.of()));
    }

    @Test
    @DisplayName("不正値の行が混在していても一覧取得は500にならず、正常行は正しく・異常行はUNKNOWNとして返る")
    void 不正値混在でも一覧取得は成功する() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{teamId}/circulations", teamId)
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[*].circulationMode",
                        containsInAnyOrder("SIMULTANEOUS", "UNKNOWN")));
    }

    /** JPA が拒否する未知の enum 文字列を再現するため、このテストに限り直接 SQL で投入する。 */
    private void insertDocument(Long scopeId, Long createdBy, String title, String circulationMode) {
        em.createNativeQuery(
                        "INSERT INTO circulation_documents "
                                + "(scope_type, scope_id, created_by, title, body, "
                                + "circulation_mode, sequential_count, status, priority, "
                                + "reminder_enabled, reminder_interval_hours, stamp_display_style, "
                                + "total_recipient_count, stamped_count, attachment_count, comment_count, "
                                + "export_status, "
                                + "created_at, updated_at) "
                                + "VALUES ('TEAM', :scopeId, :createdBy, :title, '本文', "
                                + ":circulationMode, 0, 'ACTIVE', 'NORMAL', "
                                + "0, 24, 'STANDARD', "
                                + "0, 0, 0, 0, "
                                + "'NOT_GENERATED', "
                                + "NOW(), NOW())")
                .setParameter("scopeId", scopeId)
                .setParameter("createdBy", createdBy)
                .setParameter("title", title)
                .setParameter("circulationMode", circulationMode)
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
                                + "VALUES (:email, 'CMP260920', 'テスト', 'CMP260920テスト', 'ACTIVE', "
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

    private Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, slug, "
                                + "created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, 0, "
                                + "CONCAT('cmp260920-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }
}
