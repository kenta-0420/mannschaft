package com.mannschaft.app.property.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.dto.ChangeStatusRequest;
import com.mannschaft.app.property.dto.PropertyWorkPackageRequest;
import com.mannschaft.app.property.dto.PropertyWorkPackageResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PropertyWorkPackageController} 統合テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>{@code AbstractMySqlIntegrationTest} を継承し、Spring コンテキストと MySQL を共有して
 * Controller を直接 Bean 経由で呼ぶ統合テスト。</p>
 *
 * <p>重要観点:</p>
 * <ul>
 *   <li>POST /property-history → 201（パッケージ作成 + TimelinePost 自動投稿）</li>
 *   <li>GET /property-history/{id} → 200</li>
 *   <li>PATCH /property-history/{id}/status → 200（ステータス変更）</li>
 *   <li>DELETE /property-history/{id} → 204（論理削除）</li>
 *   <li>POST /property-history/{id}/export?format=pdf → application/pdf</li>
 *   <li>POST /property-history/{id}/export?format=xlsx → xlsx Content-Type + ZIP シグネチャ</li>
 * </ul>
 */
@DisplayName("PropertyWorkPackageController 統合テスト（F09.13 Phase 1-ζ-A）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PropertyWorkPackageControllerIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PropertyWorkPackageController controller;

    @PersistenceContext
    private EntityManager em;

    private static final String SCOPE_TEAMS = "teams";
    private static final Long TEAM_ID = 991_001L;

    private Long userId;

    @BeforeEach
    void setUp() {
        userId = insertUser("pwp-test-" + System.nanoTime() + "@example.jp", "履歴", "テスト");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        // 認可根治戦役 Wave3-B5: Controller に checkMembership/checkAdminOrAbove を追加したため、
        // 本テストの被験者は全テストで当該チームの ADMIN として振る舞う前提で ADMIN を付与する
        // （非会員/非ADMINの拒否経路は PropertyScopeContractIT が担当する）。
        grantTeamAdminToCurrentUser();
    }

    /** users INSERT helper（FK 制約クリア用）。 */
    private Long insertUser(String email, String lastName, String firstName) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, :ln, :fn, :dn, 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .setParameter("ln", lastName)
                .setParameter("fn", firstName)
                .setParameter("dn", lastName + " " + firstName)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    private PropertyWorkPackageRequest sampleRequest(WorkPackageVisibility visibility) {
        return new PropertyWorkPackageRequest(
                null,                                  // dwellingUnitId
                WorkType.RENOVATION,
                "外壁塗装",
                "南側外壁修繕 " + System.nanoTime(),
                "概要説明",
                null,                                  // incidentId
                null,                                  // incidentDate
                null,                                  // incidentNarrative
                java.time.LocalDate.of(2026, 6, 1),
                java.time.LocalDate.of(2026, 8, 31),
                null, null,
                null,                                  // vendorId
                12_000_000L, 11_500_000L, null,
                "JPY",
                null,                                  // budgetTransactionId
                java.time.LocalDate.of(2031, 8, 31),
                Boolean.TRUE,
                visibility,
                List.of("修繕"),
                0L);                                   // version
    }

    private Long createPackage(WorkPackageVisibility visibility) {
        ResponseEntity<ApiResponse<PropertyWorkPackageResponse>> resp =
                controller.createPackage(SCOPE_TEAMS, TEAM_ID, sampleRequest(visibility));
        return resp.getBody().getData().id();
    }

    @Test
    @DisplayName("POST /property-history → 201 でパッケージが作成される")
    void create_returns201() {
        ResponseEntity<ApiResponse<PropertyWorkPackageResponse>> resp =
                controller.createPackage(SCOPE_TEAMS, TEAM_ID,
                        sampleRequest(WorkPackageVisibility.MEMBERS_MASKED));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PropertyWorkPackageResponse body = resp.getBody().getData();
        assertThat(body.id()).isNotNull();
        assertThat(body.workType()).isEqualTo(WorkType.RENOVATION);
        assertThat(body.status()).isEqualTo(WorkPackageStatus.PLANNED);
        assertThat(body.scopeType()).isEqualTo("TEAM");
        assertThat(body.scopeId()).isEqualTo(TEAM_ID);
        assertThat(body.timelinePostId()).isNotNull(); // TimelinePost 自動投稿成功
    }

    @Test
    @DisplayName("GET /property-history/{id} → 200 で詳細を取得できる")
    void get_returns200() {
        Long id = createPackage(WorkPackageVisibility.MEMBERS_MASKED);

        ApiResponse<PropertyWorkPackageResponse> resp =
                controller.getPackage(SCOPE_TEAMS, TEAM_ID, id);
        assertThat(resp.getData().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("PATCH /property-history/{id}/status → 200 でステータスが切り替わる")
    void changeStatus_returns200() {
        Long id = createPackage(WorkPackageVisibility.MEMBERS_MASKED);

        ApiResponse<PropertyWorkPackageResponse> resp = controller.changeStatus(
                SCOPE_TEAMS, TEAM_ID, id,
                new ChangeStatusRequest(WorkPackageStatus.IN_PROGRESS));
        assertThat(resp.getData().status()).isEqualTo(WorkPackageStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("DELETE /property-history/{id} → 204 で論理削除される")
    void delete_returns204() {
        Long id = createPackage(WorkPackageVisibility.MEMBERS_MASKED);

        ResponseEntity<Void> resp = controller.deletePackage(SCOPE_TEAMS, TEAM_ID, id);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * Export 系統合テストでは、テスト用ユーザーに対して
     * <ul>
     *   <li>{@code user_roles} に当該チームの ADMIN ロール（マスキングサービスが
     *       role_name で判定するため）</li>
     *   <li>{@code memberships} に当該チームのアクティブな MEMBER 区分</li>
     * </ul>
     * を付与する。これにより {@code PropertyWorkPackageMaskingService.isVisible()} が
     * true を返し、ADMINS_ONLY/MEMBERS_MASKED/PUBLIC_MASKED いずれの可視性でも
     * export が正常に応答するようになる。
     *
     * <p>ヘルパは {@link MembershipTestHelper} に集約してある（F09.13 Phase 2-α-1）。</p>
     */
    private void grantTeamAdminToCurrentUser() {
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", TEAM_ID, null);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, TEAM_ID, RoleKind.MEMBER);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("POST /property-history/{id}/export?format=pdf → application/pdf + %PDF- 始まり")
    void exportSinglePdf() {
        Long id = createPackage(WorkPackageVisibility.MEMBERS_MASKED);

        ResponseEntity<byte[]> resp = controller.exportSingle(
                SCOPE_TEAMS, TEAM_ID, id, "pdf");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resp.getBody()).isNotEmpty();
        // %PDF-
        assertThat(new String(resp.getBody(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("POST /property-history/{id}/export?format=xlsx → xlsx Content-Type + PK 始まり")
    void exportSingleXlsx() {
        Long id = createPackage(WorkPackageVisibility.MEMBERS_MASKED);

        ResponseEntity<byte[]> resp = controller.exportSingle(
                SCOPE_TEAMS, TEAM_ID, id, "xlsx");

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getHeaders().getContentType().toString())
                .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(resp.getBody()).isNotEmpty();
        // ZIP signature
        assertThat(resp.getBody()[0]).isEqualTo((byte) 'P');
        assertThat(resp.getBody()[1]).isEqualTo((byte) 'K');
    }
}
