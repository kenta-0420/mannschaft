package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.dto.UnsealRequestCreateRequest;
import com.mannschaft.app.succession.dto.UnsealRequestResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * {@link UnsealRequestController} 統合テスト（F09.15 S2-D）。
 *
 * <p>Spring Boot 全コンテキスト起動 + MySQL Testcontainer による統合検証。
 * {@link AbstractSuccessionIntegrationTest} を継承して R2StorageService / PdfGeneratorService の
 * モック化を受け継ぎ、さらに {@link com.mannschaft.app.common.AccessControlService} と
 * {@link com.mannschaft.app.role.service.RoleService} を {@code @MockitoBean} でモック化する。
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>起票ゴールデンパス → 201 Created + sealStatus = UNSEAL_REQUESTED</li>
 *   <li>一次承認 → 200 OK + firstApproverUserId セット</li>
 *   <li>二次承認（開封）→ 200 OK + sealStatus = UNSEALED + autoResealAt 非null</li>
 *   <li>APPROVER_CONFLICT（申請者が自分の申請を承認しようとする）</li>
 *   <li>キャンセル → 200 OK + sealStatus = SEALED に戻る</li>
 *   <li>一覧取得（ADMIN）→ 200 OK + size >= 1</li>
 * </ul>
 */
@DisplayName("UnsealRequestController 統合テスト（F09.15 S2）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UnsealRequestControllerIntegrationTest extends AbstractSuccessionIntegrationTest {

    @Autowired
    private UnsealRequestController controller;

    @PersistenceContext
    private EntityManager em;

    @MockitoBean
    private com.mannschaft.app.common.AccessControlService accessControlService;
    @MockitoBean
    private com.mannschaft.app.role.service.RoleService roleService;

    private static final Long ORG_ID      = 970_001L;
    private static final Long USER_A      = 970_002L;  // 申請者
    private static final Long USER_B      = 970_003L;  // 一次承認者
    private static final Long USER_C      = 970_004L;  // 二次承認者
    private static final Long DWELLING_ID = 970_005L;
    private static final Long RESIDENT_ID = 970_006L;

    private UUID preRegId;

    @BeforeEach
    void setUp() {
        // ────── 権限スタブ ──────
        // 全ユーザーに unseal 権限（ゴールデンパステスト用）
        when(roleService.hasPermission(anyLong(), anyLong(), anyString(), anyString())).thenReturn(true);
        when(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString())).thenReturn(true);
        doNothing().when(accessControlService).checkAdminOrAbove(anyLong(), anyLong(), anyString());

        // ────── テストデータ挿入 ──────
        String encLastName  = encryptForTest("テスト");
        String encFirstName = encryptForTest("テスト");
        String encLastNameKana  = encryptForTest("テスト");
        String encFirstNameKana = encryptForTest("テスト");

        // 1) USER_A
        insertUser(USER_A, "user_a@example.com", "テスト太郎A",
                encLastName, encFirstName, encLastNameKana, encFirstNameKana);
        // 2) USER_B
        insertUser(USER_B, "user_b@example.com", "テスト太郎B",
                encLastName, encFirstName, encLastNameKana, encFirstNameKana);
        // 3) USER_C
        insertUser(USER_C, "user_c@example.com", "テスト太郎C",
                encLastName, encFirstName, encLastNameKana, encFirstNameKana);

        // 4) Organization
        em.createNativeQuery(
                "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility,"
                        + " supporter_enabled, version, created_at, updated_at, slug)"
                        + " VALUES (:orgId, 'テスト管理組合', 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), LEFT(REPLACE(UUID(), '-', ''), 22))")
                .setParameter("orgId", ORG_ID)
                .executeUpdate();

        // 5) dwelling_units
        em.createNativeQuery(
                "INSERT INTO dwelling_units (id, scope_type, organization_id, unit_number, unit_type,"
                        + " resident_count, created_at, updated_at)"
                        + " VALUES (:dwellingId, 'ORGANIZATION', :orgId, '101', 'STANDARD', 0, NOW(), NOW())")
                .setParameter("dwellingId", DWELLING_ID)
                .setParameter("orgId", ORG_ID)
                .executeUpdate();

        // 6) resident_registry
        em.createNativeQuery(
                "INSERT INTO resident_registry (id, dwelling_unit_id, user_id, resident_type,"
                        + " last_name, first_name, last_name_kana, first_name_kana,"
                        + " move_in_date, is_primary, is_verified,"
                        + " encryption_key_version,"
                        + " death_status, occupancy_status, is_secondary_home,"
                        + " created_at, updated_at)"
                        + " VALUES (:residentId, :dwellingId, :userId, 'OWNER',"
                        + " :encLastName, :encFirstName, :encLastNameKana, :encFirstNameKana,"
                        + " '2026-01-01', 0, 0,"
                        + " 1,"
                        + " 'ALIVE', 'UNKNOWN', 0,"
                        + " NOW(), NOW())")
                .setParameter("residentId", RESIDENT_ID)
                .setParameter("dwellingId", DWELLING_ID)
                .setParameter("userId", USER_A)
                .setParameter("encLastName", encLastName)
                .setParameter("encFirstName", encFirstName)
                .setParameter("encLastNameKana", encLastNameKana)
                .setParameter("encFirstNameKana", encFirstNameKana)
                .executeUpdate();

        // 7) succession_pre_registrations (BINARY(16) PK)
        UUID newPreRegId = UUID.randomUUID();
        em.createNativeQuery(
                "INSERT INTO succession_pre_registrations"
                        + " (id, organization_id, dwelling_unit_id, resident_registry_id, owner_user_id,"
                        + " seal_status, created_at, updated_at)"
                        + " VALUES (UUID_TO_BIN(:id), :orgId, :dwellingId, :residentId, :userId,"
                        + " 'SEALED', NOW(), NOW())")
                .setParameter("id", newPreRegId.toString())
                .setParameter("orgId", ORG_ID)
                .setParameter("dwellingId", DWELLING_ID)
                .setParameter("residentId", RESIDENT_ID)
                .setParameter("userId", USER_A)
                .executeUpdate();
        this.preRegId = newPreRegId;

        // 8) SecurityContext を USER_A に設定
        setSecurityContext(USER_A);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ─── テスト ──────────────────────────────────────────

    @Test
    @DisplayName("起票ゴールデンパス: 201 Created + sealStatus が UNSEAL_REQUESTED に遷移する")
    void createRequest_golden_path() {
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();

        ResponseEntity<ApiResponse<UnsealRequestResponse>> result =
                controller.createRequest(ORG_ID, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UnsealRequestResponse response = result.getBody().getData();
        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getRequestedBy()).isEqualTo(USER_A);

        // DB の seal_status が UNSEAL_REQUESTED になっていること
        em.flush();
        em.clear();
        Object sealStatus = em.createNativeQuery(
                "SELECT seal_status FROM succession_pre_registrations"
                        + " WHERE id = UUID_TO_BIN(:id)")
                .setParameter("id", preRegId.toString())
                .getSingleResult();
        assertThat(sealStatus).isEqualTo("UNSEAL_REQUESTED");
    }

    @Test
    @DisplayName("一次承認: 200 OK + firstApproverUserId = USER_B")
    void approve_success() {
        // USER_A で起票
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();
        UUID unsealReqId = controller.createRequest(ORG_ID, request).getBody().getData().getId();

        // USER_B に切り替えて一次承認
        setSecurityContext(USER_B);
        ResponseEntity<ApiResponse<UnsealRequestResponse>> result =
                controller.approve(ORG_ID, unsealReqId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        UnsealRequestResponse response = result.getBody().getData();
        assertThat(response.getFirstApproverUserId()).isEqualTo(USER_B);
    }

    @Test
    @DisplayName("二次承認（開封）: 200 OK + sealStatus = UNSEALED + autoResealAt 非null")
    void secondApprove_success() {
        // USER_A で起票
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();
        UUID unsealReqId = controller.createRequest(ORG_ID, request).getBody().getData().getId();

        // USER_B で一次承認
        setSecurityContext(USER_B);
        controller.approve(ORG_ID, unsealReqId, null);

        // USER_C で二次承認
        setSecurityContext(USER_C);
        ResponseEntity<ApiResponse<UnsealRequestResponse>> result =
                controller.secondApprove(ORG_ID, unsealReqId, null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        UnsealRequestResponse response = result.getBody().getData();
        assertThat(response.getAutoResealAt()).isNotNull();

        // DB の seal_status が UNSEALED になっていること
        em.flush();
        em.clear();
        Object sealStatus = em.createNativeQuery(
                "SELECT seal_status FROM succession_pre_registrations"
                        + " WHERE id = UUID_TO_BIN(:id)")
                .setParameter("id", preRegId.toString())
                .getSingleResult();
        assertThat(sealStatus).isEqualTo("UNSEALED");
    }

    @Test
    @DisplayName("APPROVER_CONFLICT: 申請者(USER_A)が自分の申請を一次承認しようとすると例外")
    void approve_approver_conflict() {
        // USER_A で起票
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();
        UUID unsealReqId = controller.createRequest(ORG_ID, request).getBody().getData().getId();

        // USER_A のまま一次承認を試みる
        assertThatThrownBy(() -> controller.approve(ORG_ID, unsealReqId, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SuccessionErrorCode.APPROVER_CONFLICT);
    }

    @Test
    @DisplayName("キャンセル: 200 OK + sealStatus が SEALED に戻る")
    void cancel_success() {
        // USER_A で起票
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();
        UUID unsealReqId = controller.createRequest(ORG_ID, request).getBody().getData().getId();

        // USER_A で申請をキャンセル
        ResponseEntity<ApiResponse<java.util.Map<String, String>>> result =
                controller.cancel(ORG_ID, unsealReqId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

        // DB の seal_status が SEALED に戻っていること
        em.flush();
        em.clear();
        Object sealStatus = em.createNativeQuery(
                "SELECT seal_status FROM succession_pre_registrations"
                        + " WHERE id = UUID_TO_BIN(:id)")
                .setParameter("id", preRegId.toString())
                .getSingleResult();
        assertThat(sealStatus).isEqualTo("SEALED");
    }

    @Test
    @DisplayName("一覧取得（ADMIN）: 200 OK + 起票後は size >= 1")
    void listRequests_admin_success() {
        // USER_A で起票
        UnsealRequestCreateRequest request = UnsealRequestCreateRequest.builder()
                .preRegistrationId(preRegId)
                .reason("相続調査のため")
                .build();
        controller.createRequest(ORG_ID, request);
        em.flush();

        // USER_A (ADMIN スタブ) で一覧取得
        ResponseEntity<ApiResponse<List<UnsealRequestResponse>>> result =
                controller.listRequests(ORG_ID);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UnsealRequestResponse> data = result.getBody().getData();
        assertThat(data).hasSizeGreaterThanOrEqualTo(1);
    }

    // ─── ヘルパー ──────────────────────────────────────

    private void setSecurityContext(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(userId), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private void insertUser(Long userId, String email, String displayName,
                            String encLastName, String encFirstName,
                            String encLastNameKana, String encFirstNameKana) {
        em.createNativeQuery(
                "INSERT INTO users (id, email, password_hash, last_name, first_name,"
                        + " last_name_kana, first_name_kana, display_name,"
                        + " is_searchable, status,"
                        + " handle_searchable, contact_approval_required,"
                        + " online_visibility, dm_receive_from, encryption_key_version,"
                        + " locale, timezone, reporting_restricted, follow_list_visibility,"
                        + " care_notification_enabled, offline_only,"
                        + " created_at, updated_at)"
                        + " VALUES (:userId, :email, 'hash',"
                        + " :encLastName, :encFirstName, :encLastNameKana, :encFirstNameKana,"
                        + " :displayName, 1, 'ACTIVE',"
                        + " 1, 1,"
                        + " 'NOBODY', 'ANYONE', 1,"
                        + " 'ja', 'Asia/Tokyo', 0, 'PUBLIC',"
                        + " 1, 0,"
                        + " NOW(), NOW())")
                .setParameter("userId", userId)
                .setParameter("email", email)
                .setParameter("encLastName", encLastName)
                .setParameter("encFirstName", encFirstName)
                .setParameter("encLastNameKana", encLastNameKana)
                .setParameter("encFirstNameKana", encFirstNameKana)
                .setParameter("displayName", displayName)
                .executeUpdate();
    }
}
