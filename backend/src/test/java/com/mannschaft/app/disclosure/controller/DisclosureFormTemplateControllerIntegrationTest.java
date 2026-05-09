package com.mannschaft.app.disclosure.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.disclosure.DisclosureErrorCode;
import com.mannschaft.app.disclosure.dto.DisclosureFormTemplateResponse;
import com.mannschaft.app.disclosure.entity.DisclosureFormTemplateEntity;
import com.mannschaft.app.disclosure.repository.DisclosureFormTemplateRepository;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DisclosureFormTemplateController} 統合テスト（F09.14 Phase 2-ζ-A）。
 *
 * <p>Spring コンテキストと MySQL を {@link AbstractDisclosureIntegrationTest} 経由で共有し、
 * Controller を直接 Bean として呼び出してリポジトリ層・Service 層との連携を検証する。</p>
 *
 * <h3>検証観点</h3>
 * <ul>
 *   <li>GET /disclosure-templates → アクティブテンプレ一覧（システム提供 + 当該組織カスタム）</li>
 *   <li>GET /disclosure-templates/{id} → 詳細取得</li>
 *   <li>{@code prefectureCode} フィルタ動作（一致 + 全国共通の包含）</li>
 *   <li>IDOR: 他組織のカスタムテンプレ ID 取得 → DISCLOSURE_002</li>
 * </ul>
 *
 * <h3>シード戦略</h3>
 * <p>{@code application-test.yml} で {@code flyway.enabled=false} のため、
 * V61.014 の {@code MLIT_STANDARD_2024} シードはテスト環境では投入されない。
 * このため各テストの {@code @BeforeEach} で必要なテンプレートを直接 {@code save()} する。</p>
 */
@DisplayName("DisclosureFormTemplateController 統合テスト（F09.14 Phase 2-ζ-A）")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DisclosureFormTemplateControllerIntegrationTest extends AbstractDisclosureIntegrationTest {

    @Autowired
    private DisclosureFormTemplateController controller;

    @Autowired
    private DisclosureFormTemplateRepository templateRepository;

    @PersistenceContext
    private EntityManager em;

    /** 本組織 ID（カスタム作成元）。シードと衝突しない大きな値を使う。 */
    private static final Long ORG_ID = 911_001L;

    /** 別組織 ID（IDOR 検証用）。 */
    private static final Long ORG_OTHER_ID = 911_002L;

    private Long userId;
    private Long systemTemplateId;
    private Long customTemplateId;
    private Long customOtherOrgTemplateId;

    @BeforeEach
    void setUp() {
        userId = insertUser("dft-test-" + System.nanoTime() + "@example.jp");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        // 認証済ユーザは ORG_ID の MEMBER として所属させておく
        // （本フェーズでは Service 層の権限判定はしないが、E2E 整合性のため）
        MembershipTestHelper.insertMembership(em, userId, ScopeType.ORGANIZATION, ORG_ID, RoleKind.MEMBER);
        em.flush();

        systemTemplateId = saveSystemTemplate("MLIT_STANDARD_2024_TEST", "2024.1", null);
        customTemplateId = saveCustomTemplate("CUSTOM_ORG_911001", "1.0", ORG_ID);
        customOtherOrgTemplateId = saveCustomTemplate("CUSTOM_ORG_911002", "1.0", ORG_OTHER_ID);
    }

    @Test
    @DisplayName("GET /disclosure-templates?organizationId=N → システム提供 + 当該組織カスタムを返す（他組織カスタムは含まない）")
    void list_returnsSystemAndOwnCustom() {
        ApiResponse<List<DisclosureFormTemplateResponse>> resp =
                controller.listAvailable(null, ORG_ID);

        List<Long> ids = resp.getData().stream().map(DisclosureFormTemplateResponse::id).toList();
        assertThat(ids).contains(systemTemplateId, customTemplateId);
        // クロステナント遮断: 別組織のカスタムは含まれてはならない
        assertThat(ids).doesNotContain(customOtherOrgTemplateId);
    }

    @Test
    @DisplayName("GET /disclosure-templates/{id} → 自組織のカスタムテンプレ詳細を取得できる")
    void get_returnsOwnCustom() {
        ApiResponse<DisclosureFormTemplateResponse> resp =
                controller.get(customTemplateId, ORG_ID);

        DisclosureFormTemplateResponse body = resp.getData();
        assertThat(body.id()).isEqualTo(customTemplateId);
        assertThat(body.code()).isEqualTo("CUSTOM_ORG_911001");
        assertThat(body.scopeId()).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("GET /disclosure-templates/{id} → システム提供テンプレは任意組織から取得可")
    void get_systemTemplateAccessibleFromAnyOrg() {
        ApiResponse<DisclosureFormTemplateResponse> resp =
                controller.get(systemTemplateId, ORG_ID);
        assertThat(resp.getData().id()).isEqualTo(systemTemplateId);
        assertThat(resp.getData().isSystemTemplate()).isTrue();
    }

    @Test
    @DisplayName("IDOR: 他組織のカスタムテンプレ ID 取得 → DISCLOSURE_002")
    void get_otherOrgCustom_throwsDisclosure002() {
        assertThatThrownBy(() -> controller.get(customOtherOrgTemplateId, ORG_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(DisclosureErrorCode.DISCLOSURE_002);
    }

    @Test
    @DisplayName("GET /disclosure-templates?prefectureCode=13 → 都道府県絞り込み + 全国共通を含む")
    void list_filterByPrefecture_includesNational() {
        // 全国共通 + 東京専用 + 大阪専用 を投入
        Long tokyoTplId = saveSystemTemplate("TOKYO_2025", "1.0", "13");
        Long osakaTplId = saveSystemTemplate("OSAKA_2025", "1.0", "27");

        ApiResponse<List<DisclosureFormTemplateResponse>> resp =
                controller.listAvailable("13", ORG_ID);

        List<Long> ids = resp.getData().stream().map(DisclosureFormTemplateResponse::id).toList();
        // 東京 + 全国共通（prefectureCode IS NULL）は含む、大阪は含まない
        assertThat(ids).contains(tokyoTplId, systemTemplateId);
        assertThat(ids).doesNotContain(osakaTplId);
    }

    // =========================================================================
    // ヘルパー
    // =========================================================================

    private Long insertUser(String email) {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES (:email, '様式', '太郎', '様式 太郎', 'ACTIVE', "
                        + "1, 1, 1, "
                        + "'NOBODY', 'ANYONE', 1, "
                        + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, "
                        + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    /** システム提供テンプレを永続化する。 */
    private Long saveSystemTemplate(String code, String version, String prefectureCode) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("システム提供 " + code)
                .prefectureCode(prefectureCode)
                .version(version)
                .isStandard(true)
                .isSystemTemplate(true)
                .scopeType(null)
                .scopeId(null)
                .formSchema("{\"sections\":[]}")
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }

    /** 組織カスタムテンプレを永続化する。 */
    private Long saveCustomTemplate(String code, String version, Long scopeId) {
        DisclosureFormTemplateEntity entity = DisclosureFormTemplateEntity.builder()
                .code(code)
                .name("カスタム " + code)
                .prefectureCode(null)
                .version(version)
                .isStandard(false)
                .isSystemTemplate(false)
                .scopeType("ORGANIZATION")
                .scopeId(scopeId)
                .formSchema("{\"sections\":[]}")
                .effectiveFrom(LocalDate.of(2024, 4, 1))
                .isActive(true)
                .build();
        return templateRepository.save(entity).getId();
    }
}
