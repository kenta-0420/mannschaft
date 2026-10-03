package com.mannschaft.app.property.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.property.PropertyHistoryErrorCode;
import com.mannschaft.app.property.VendorCategory;
import com.mannschaft.app.property.dto.VendorRequest;
import com.mannschaft.app.property.dto.VendorResponse;
import com.mannschaft.app.property.dto.VendorSuggestionResponse;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.repository.VendorRepository;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VendorController} 統合テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>{@code AbstractMySqlIntegrationTest} を継承して MySQL Testcontainer を共有し、
 * Controller を直接 Bean 経由で呼ぶ統合テスト。Vendor 作成 → IDOR 検証 → CRUD 動作確認。</p>
 *
 * <p>重要観点:</p>
 * <ul>
 *   <li>POST /vendors → 201 + body</li>
 *   <li>GET /vendors → 一覧取得（Page）</li>
 *   <li>GET /vendors/{id} → 200</li>
 *   <li><strong>GET /vendors/{id} で他スコープ vendor の ID を当てた場合 PROPERTY_005（404）— IDOR テスト</strong></li>
 *   <li>PUT /vendors/{id} → 200</li>
 *   <li>DELETE /vendors/{id} → 204（論理削除）</li>
 *   <li>GET /vendors/search?q= → サジェスト</li>
 * </ul>
 *
 * <p>{@code @WebMvcTest} は使わず {@code @SpringBootTest} (継承元) で Controller / Service /
 * Repository を実 DB に配線。Security は SecurityContextHolder で擬似ログインして偽装。</p>
 */
@DisplayName("VendorController 統合テスト（F09.13 Phase 1-ζ-A）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VendorControllerIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private VendorController vendorController;

    @Autowired
    private VendorRepository vendorRepository;

    @PersistenceContext
    private EntityManager em;

    private static final String SCOPE_TEAMS = "teams";
    private static final Long TEAM_ID = 990_001L;
    private static final Long OTHER_TEAM_ID = 990_999L;

    private Long userId;

    @BeforeEach
    void setUpAuthentication() {
        userId = insertUser("vendor-test-" + System.nanoTime() + "@example.jp",
                "業者", "テスト");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        // 認可根治戦役 Wave3-B5: VendorController に checkMembership/checkAdminOrAbove を追加したため、
        // 本テストの被験者は TEAM_ID の ADMIN として振る舞う前提で付与する
        // （checkMembership は memberships 表、checkAdminOrAbove は user_roles 表を見るため両方必要）。
        MembershipTestHelper.insertUserRole(em, userId, "ADMIN", TEAM_ID, null);
        MembershipTestHelper.insertMembership(em, userId, ScopeType.TEAM, TEAM_ID, RoleKind.MEMBER);
        em.flush();
        em.clear();
    }

    /** users テーブルにテスト用ユーザーを INSERT し ID を返す（FK 制約クリア用）。 */
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

    private VendorRequest sampleRequest(String name) {
        return new VendorRequest(
                name,
                "テストカナ",
                VendorCategory.CONSTRUCTION,
                "03-1234-5678",
                "info@example.jp",
                "https://example.jp",
                "100-0001",
                "東京都千代田区1-2-3",
                "代表 太郎",
                "担当 花子",
                "建設業許可123",
                java.time.LocalDate.of(2030, 12, 31),
                "備考",
                Boolean.TRUE,  // isActive
                null);          // version (POST 時は不要)
    }

    @Test
    @DisplayName("POST /vendors → 201 + body で作成された業者が返る")
    void create_returns201() {
        ResponseEntity<ApiResponse<VendorResponse>> resp = vendorController.createVendor(
                SCOPE_TEAMS, TEAM_ID, sampleRequest("○○塗装工業 " + System.nanoTime()));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(resp.getBody().getData().id()).isNotNull();
        assertThat(resp.getBody().getData().name()).startsWith("○○塗装工業");
        assertThat(resp.getBody().getData().scopeType()).isEqualTo("TEAM");
        assertThat(resp.getBody().getData().scopeId()).isEqualTo(TEAM_ID);
    }

    @Test
    @DisplayName("GET /vendors/{id} → 200 で詳細を取得できる")
    void get_returns200() {
        Long id = vendorController.createVendor(
                SCOPE_TEAMS, TEAM_ID, sampleRequest("業者A " + System.nanoTime()))
                .getBody().getData().id();

        ApiResponse<VendorResponse> got = vendorController.getVendor(SCOPE_TEAMS, TEAM_ID, id);
        assertThat(got.getData().id()).isEqualTo(id);
    }

    @Test
    @DisplayName("IDOR: 他スコープ vendor の ID を当てると PROPERTY_005（404 で他存在を漏らさない）")
    void get_idor_throwsPROPERTY_005() {
        // 他スコープ（OTHER_TEAM_ID）で vendor を作成
        VendorEntity foreign = vendorRepository.save(VendorEntity.builder()
                .scopeType("TEAM")
                .scopeId(OTHER_TEAM_ID)
                .name("他チームの業者 " + System.nanoTime())
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(userId)
                .build());

        // 自分（TEAM_ID）のスコープから他スコープの vendor を引こうとすると PROPERTY_005
        assertThatThrownBy(() -> vendorController.getVendor(SCOPE_TEAMS, TEAM_ID, foreign.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
    }

    @Test
    @DisplayName("PUT /vendors/{id} → 200 で更新できる")
    void update_returns200() {
        Long id = vendorController.createVendor(
                SCOPE_TEAMS, TEAM_ID, sampleRequest("業者B " + System.nanoTime()))
                .getBody().getData().id();

        VendorRequest update = sampleRequest("業者B 改名 " + System.nanoTime());
        ApiResponse<VendorResponse> updated = vendorController.updateVendor(
                SCOPE_TEAMS, TEAM_ID, id, update);
        assertThat(updated.getData().name()).startsWith("業者B 改名");
    }

    @Test
    @DisplayName("IDOR: 他スコープ vendor の更新は PROPERTY_005")
    void update_idor_throwsPROPERTY_005() {
        VendorEntity foreign = vendorRepository.save(VendorEntity.builder()
                .scopeType("TEAM")
                .scopeId(OTHER_TEAM_ID)
                .name("他チーム業者 " + System.nanoTime())
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(userId)
                .build());

        assertThatThrownBy(() -> vendorController.updateVendor(
                SCOPE_TEAMS, TEAM_ID, foreign.getId(), sampleRequest("乗っ取り")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
    }

    @Test
    @DisplayName("DELETE /vendors/{id} → 204 で論理削除される")
    void delete_returns204() {
        Long id = vendorController.createVendor(
                SCOPE_TEAMS, TEAM_ID, sampleRequest("業者C " + System.nanoTime()))
                .getBody().getData().id();

        ResponseEntity<Void> resp = vendorController.deleteVendor(SCOPE_TEAMS, TEAM_ID, id);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 取得しようとすると PROPERTY_005（@SQLRestriction で除外）
        assertThatThrownBy(() -> vendorController.getVendor(SCOPE_TEAMS, TEAM_ID, id))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
    }

    @Test
    @DisplayName("IDOR: 他スコープ vendor の削除は PROPERTY_005")
    void delete_idor_throwsPROPERTY_005() {
        VendorEntity foreign = vendorRepository.save(VendorEntity.builder()
                .scopeType("TEAM")
                .scopeId(OTHER_TEAM_ID)
                .name("他チーム業者 " + System.nanoTime())
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(userId)
                .build());

        assertThatThrownBy(() ->
                vendorController.deleteVendor(SCOPE_TEAMS, TEAM_ID, foreign.getId()))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", PropertyHistoryErrorCode.PROPERTY_005);
    }

    @Test
    @DisplayName("GET /vendors → 一覧で同スコープの有効業者のみ返る")
    void list_returnsActiveVendorsOnly() {
        // 同スコープに 2 件作る
        vendorController.createVendor(SCOPE_TEAMS, TEAM_ID,
                sampleRequest("自スコープ業者A " + System.nanoTime()));
        vendorController.createVendor(SCOPE_TEAMS, TEAM_ID,
                sampleRequest("自スコープ業者B " + System.nanoTime()));
        // 他スコープに 1 件作る（IDOR 隔離されるはず）
        vendorRepository.save(VendorEntity.builder()
                .scopeType("TEAM")
                .scopeId(OTHER_TEAM_ID)
                .name("他スコープ業者 " + System.nanoTime())
                .nameKana("カナ")
                .category(VendorCategory.CONSTRUCTION)
                .isActive(true)
                .createdBy(userId)
                .build());

        PagedResponse<VendorResponse> page = vendorController.listVendors(
                SCOPE_TEAMS, TEAM_ID, null, null, null, 0, 20);

        // 自スコープに紐づくものだけ返ること
        assertThat(page.getData())
                .extracting(VendorResponse::scopeId)
                .allMatch(s -> s.equals(TEAM_ID));
    }

    @Test
    @DisplayName("GET /vendors/search?q= → 部分一致サジェスト")
    void search_returnsSuggestions() {
        vendorController.createVendor(SCOPE_TEAMS, TEAM_ID,
                sampleRequest("塗装専門 " + System.nanoTime()));

        ApiResponse<List<VendorSuggestionResponse>> resp =
                vendorController.searchVendors(SCOPE_TEAMS, TEAM_ID, "塗装");
        assertThat(resp.getData()).isNotEmpty();
        assertThat(resp.getData().get(0).name()).contains("塗装");
    }
}
