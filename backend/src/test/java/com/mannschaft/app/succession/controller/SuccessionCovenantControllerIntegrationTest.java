package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.SignedPdfResult;
import com.mannschaft.app.succession.SuccessionErrorCode;
import com.mannschaft.app.succession.dto.SignCovenantRequest;
import com.mannschaft.app.succession.dto.SuccessionCovenantResponse;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link SuccessionCovenantController} 統合テスト（F09.15 S1 第四陣）。
 *
 * <p>Spring Boot 全コンテキスト起動 + MySQL Testcontainer による統合検証。
 * Service 単体テスト・Controller MockMvc テストは PR #573 で実装済み。
 * 本クラスでは DB 挿入・認証・ビジネスロジック連鎖を一気通貫で検証する。</p>
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>POST /sign 正常系 → 201 Created でレコード保存・listMyCovenants で確認できる（ゴールデンパス）</li>
 *   <li>POST /sign 二重署名 → {@link SuccessionErrorCode#COVENANT_ALREADY_SIGNED}</li>
 *   <li>POST /sign 不正な確認項目 → {@link SuccessionErrorCode#COVENANT_CONFIRMED_ITEMS_INSUFFICIENT}</li>
 * </ul>
 *
 * <p>{@link AbstractSuccessionIntegrationTest} が {@link com.mannschaft.app.common.storage.R2StorageService}
 * と {@link com.mannschaft.app.common.pdf.PdfGeneratorService} をモック化済み。</p>
 */
@DisplayName("SuccessionCovenantController 統合テスト（F09.15 S1）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SuccessionCovenantControllerIntegrationTest extends AbstractSuccessionIntegrationTest {

    @Autowired
    private SuccessionCovenantController controller;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 960_001L;
    private static final Long USER_ID = 960_002L;
    private static final Long DWELLING_ID = 960_003L;
    private static final Long RESIDENT_ID = 960_004L;

    @BeforeEach
    void setUp() {
        String encLastName = encryptForTest("テスト");
        String encFirstName = encryptForTest("テスト");
        String encLastNameKana = encryptForTest("テスト");
        String encFirstNameKana = encryptForTest("テスト");

        // 1) users テーブルにテストユーザーを挿入
        //    NOT NULL かつ DEFAULT なしのカラムは明示指定が必要（CI の MySQL strict mode 対策）
        em.createNativeQuery(
                "INSERT INTO users (id, email, password_hash, last_name, first_name,"
                        + " last_name_kana, first_name_kana, display_name,"
                        + " is_searchable, status,"
                        + " handle_searchable, contact_approval_required,"
                        + " online_visibility, dm_receive_from, encryption_key_version,"
                        + " locale, timezone, reporting_restricted, follow_list_visibility,"
                        + " care_notification_enabled, offline_only,"
                        + " created_at, updated_at)"
                        + " VALUES (:userId, 'succession_test@example.com', 'hash',"
                        + " :encLastName, :encFirstName, :encLastNameKana, :encFirstNameKana,"
                        + " 'テスト太郎', 1, 'ACTIVE',"
                        + " 1, 1,"
                        + " 'NOBODY', 'ANYONE', 1,"
                        + " 'ja', 'Asia/Tokyo', 0, 'PUBLIC',"
                        + " 1, 0,"
                        + " NOW(), NOW())")
                .setParameter("userId", USER_ID)
                .setParameter("encLastName", encLastName)
                .setParameter("encFirstName", encFirstName)
                .setParameter("encLastNameKana", encLastNameKana)
                .setParameter("encFirstNameKana", encFirstNameKana)
                .executeUpdate();

        // 2) organizations テーブルにテスト組合を挿入
        em.createNativeQuery(
                "INSERT INTO organizations (id, name, org_type, visibility, hierarchy_visibility,"
                        + " supporter_enabled, version, created_at, updated_at, public_id)"
                        + " VALUES (:orgId, 'テスト管理組合', 'OTHER', 'PUBLIC', 'NONE', 1, 0, NOW(), NOW(), UUID_TO_BIN(UUID(), 1))")
                .setParameter("orgId", ORG_ID)
                .executeUpdate();

        // 3) dwelling_units テーブルにテスト居室を挿入
        //    resident_count は DEFAULT 0 だが CI strict mode では明示必要
        em.createNativeQuery(
                "INSERT INTO dwelling_units (id, scope_type, organization_id, unit_number, unit_type,"
                        + " resident_count, created_at, updated_at)"
                        + " VALUES (:dwellingId, 'ORGANIZATION', :orgId, '101', 'STANDARD', 0, NOW(), NOW())")
                .setParameter("dwellingId", DWELLING_ID)
                .setParameter("orgId", ORG_ID)
                .executeUpdate();

        // 4) resident_registry テーブルにテスト居住者を挿入
        //    CI strict mode では DEFAULT あり NOT NULL 列も明示必要。全 NOT NULL 列を列挙する。
        em.createNativeQuery(
                "INSERT INTO resident_registry (id, dwelling_unit_id, user_id, resident_type,"
                        + " last_name, first_name, last_name_kana, first_name_kana,"
                        + " move_in_date, is_primary, is_verified,"
                        + " encryption_key_version,"
                        + " death_status, occupancy_status, is_secondary_home,"
                        + " created_at, updated_at)"
                        + " VALUES (:residentId, :dwellingId, :userId, 'OWNER',"
                        + " :encLastName, :encFirstName, :encLastNameKana, :encFirstNameKana,"
                        + " '2020-01-01', 0, 0,"
                        + " 1,"
                        + " 'ALIVE', 'UNKNOWN', 0,"
                        + " NOW(), NOW())")
                .setParameter("residentId", RESIDENT_ID)
                .setParameter("dwellingId", DWELLING_ID)
                .setParameter("userId", USER_ID)
                .setParameter("encLastName", encLastName)
                .setParameter("encFirstName", encFirstName)
                .setParameter("encLastNameKana", encLastNameKana)
                .setParameter("encFirstNameKana", encFirstNameKana)
                .executeUpdate();

        // 5) SecurityContext をセット
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        // Mockito スタブ: pdfGeneratorService はダミーの SignedPdfResult を返す
        given(pdfGeneratorService.generateSignedCovenantPdf(any()))
                .willReturn(new SignedPdfResult(
                        new byte[]{1, 2, 3},
                        "a".repeat(64),           // sha256 (64 文字)
                        "token.test.signature",   // internalSignatureToken
                        Instant.now(),
                        "test-subject-id"         // subjectId
                ));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("POST /sign 正常系 → 201 Created でレコード保存・listMyCovenants で確認できる（ゴールデンパス）")
    void golden_path_sign_and_list() {
        SignCovenantRequest request = SignCovenantRequest.builder()
                .covenantType("PRIVACY_CONSENT")
                .residentRegistryId(RESIDENT_ID)
                .covenantVersion("v1.0.0")
                .confirmedItems(List.of("agree_personal_data_collection", "agree_data_retention_10y"))
                .build();

        ResponseEntity<ApiResponse<SuccessionCovenantResponse>> result =
                controller.signCovenant(request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        SuccessionCovenantResponse response = result.getBody().getData();
        assertThat(response).isNotNull();
        assertThat(response.getId()).isNotNull();
        assertThat(response.getCovenantType()).isEqualTo("PRIVACY_CONSENT");

        em.flush();

        // listMyCovenants で確認できる
        ResponseEntity<ApiResponse<List<SuccessionCovenantResponse>>> listResult =
                controller.listMyCovenants();

        assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<SuccessionCovenantResponse> data = listResult.getBody().getData();
        assertThat(data).hasSize(1);
        assertThat(data.get(0).getCovenantType()).isEqualTo("PRIVACY_CONSENT");
    }

    @Test
    @DisplayName("POST /sign 二重署名 → COVENANT_ALREADY_SIGNED")
    void sign_duplicate_throws_already_signed() {
        SignCovenantRequest request = SignCovenantRequest.builder()
                .covenantType("PRIVACY_CONSENT")
                .residentRegistryId(RESIDENT_ID)
                .covenantVersion("v1.0.0")
                .confirmedItems(List.of("agree_personal_data_collection", "agree_data_retention_10y"))
                .build();

        // 1 回目署名（成功）
        controller.signCovenant(request);
        em.flush();

        // 2 回目署名で COVENANT_ALREADY_SIGNED が投げられることをアサート
        assertThatThrownBy(() -> controller.signCovenant(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SuccessionErrorCode.COVENANT_ALREADY_SIGNED);
    }

    @Test
    @DisplayName("POST /sign 不正な確認項目 → COVENANT_CONFIRMED_ITEMS_INSUFFICIENT")
    void sign_invalid_confirmed_items() {
        SignCovenantRequest request = SignCovenantRequest.builder()
                .covenantType("PRIVACY_CONSENT")
                .residentRegistryId(RESIDENT_ID)
                .covenantVersion("v1.0.0")
                .confirmedItems(List.of())  // 空リスト（必須項目なし）
                .build();

        assertThatThrownBy(() -> controller.signCovenant(request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(SuccessionErrorCode.COVENANT_CONFIRMED_ITEMS_INSUFFICIENT);
    }
}
