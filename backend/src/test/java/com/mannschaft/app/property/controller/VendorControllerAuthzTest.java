package com.mannschaft.app.property.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.property.entity.VendorEntity;
import com.mannschaft.app.property.service.VendorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link VendorController} の閲覧系（一覧/サジェスト/単体取得）認可単体テスト
 * （CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「業者マスタ」機能の GET が
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。
 * ただし <b>ORGANIZATION スコープのみ</b> ADMIN 必須とし、<b>TEAM スコープは
 * {@code PropertyScopeContractIT} が過去戦役 Wave3-B5 で固定した契約（一般メンバーの
 * 業者一覧取得は200）を維持する</b>。本テストはスコープ差分を番人として固定する
 * （2026-09-17: 最初の是正がスコープを区別せず TEAM 側の契約を巻き込んで CI を赤化させた）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorController 認可単体テスト（閲覧系・スコープ差分）")
class VendorControllerAuthzTest {

    @Mock private VendorService vendorService;
    @Mock private AccessControlService accessControlService;

    private VendorController controller;
    private MockedStatic<SecurityUtils> securityUtils;

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long VENDOR_ID = 5L;

    @BeforeEach
    void setUp() {
        controller = new VendorController(vendorService, accessControlService);
        securityUtils = Mockito.mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    // ───────────────────────── ORGANIZATION スコープ: ADMIN 必須 ─────────────────────────

    @Test
    @DisplayName("listVendors: ORGANIZATIONスコープのMEMBERは403（COMMON_002）で拒否される")
    void listVendors_organizationMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION");

        assertThatThrownBy(() -> controller.listVendors("organizations", SCOPE_ID, null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("listVendors: ORGANIZATIONスコープのADMINは200相当で取得できる")
    void listVendors_organizationAdmin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION");
        given(vendorService.listActiveVendors(eq("ORGANIZATION"), eq(SCOPE_ID), any()))
                .willReturn(Page.empty(PageRequest.of(0, 20)));

        assertThatCode(() -> controller.listVendors("organizations", SCOPE_ID, null, null, null, 0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("searchVendors: ORGANIZATIONスコープのMEMBERは403（COMMON_002）で拒否される")
    void searchVendors_organizationMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION");

        assertThatThrownBy(() -> controller.searchVendors("organizations", SCOPE_ID, "塗装"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("getVendor: ORGANIZATIONスコープのMEMBERは403（COMMON_002）で拒否される")
    void getVendor_organizationMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION");

        assertThatThrownBy(() -> controller.getVendor("organizations", SCOPE_ID, VENDOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("getVendor: ORGANIZATIONスコープのADMINは200相当で取得できる")
    void getVendor_organizationAdmin_isAllowed() {
        doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "ORGANIZATION");
        given(vendorService.getVendor("ORGANIZATION", SCOPE_ID, VENDOR_ID))
                .willReturn(VendorEntity.builder()
                        .scopeType("ORGANIZATION")
                        .scopeId(SCOPE_ID)
                        .name("テスト業者")
                        .isActive(true)
                        .build());

        assertThatCode(() -> controller.getVendor("organizations", SCOPE_ID, VENDOR_ID))
                .doesNotThrowAnyException();
    }

    // ───────────────────────── TEAM スコープ: 従来どおり checkMembership ─────────────────────────

    @Test
    @DisplayName("listVendors: TEAMスコープのMEMBERは200相当で取得できる（Wave3-B5契約維持）")
    void listVendors_teamMember_isAllowed() {
        doNothing().when(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        given(vendorService.listActiveVendors(eq("TEAM"), eq(SCOPE_ID), any()))
                .willReturn(Page.empty(PageRequest.of(0, 20)));

        assertThatCode(() -> controller.listVendors("teams", SCOPE_ID, null, null, null, 0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("listVendors: TEAMスコープの非メンバーは403（COMMON_002）で拒否される")
    void listVendors_teamNonMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> controller.listVendors("teams", SCOPE_ID, null, null, null, 0, 20))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }

    @Test
    @DisplayName("searchVendors: TEAMスコープのMEMBERは200相当で取得できる（Wave3-B5契約維持）")
    void searchVendors_teamMember_isAllowed() {
        doNothing().when(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        given(vendorService.suggestByName(eq("TEAM"), eq(SCOPE_ID), any()))
                .willReturn(java.util.List.of());

        assertThatCode(() -> controller.searchVendors("teams", SCOPE_ID, "塗装"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getVendor: TEAMスコープのMEMBERは200相当で取得できる（Wave3-B5契約維持）")
    void getVendor_teamMember_isAllowed() {
        doNothing().when(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        given(vendorService.getVendor("TEAM", SCOPE_ID, VENDOR_ID))
                .willReturn(VendorEntity.builder()
                        .scopeType("TEAM")
                        .scopeId(SCOPE_ID)
                        .name("テスト業者")
                        .isActive(true)
                        .build());

        assertThatCode(() -> controller.getVendor("teams", SCOPE_ID, VENDOR_ID))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getVendor: TEAMスコープの非メンバーは403（COMMON_002）で拒否される")
    void getVendor_teamNonMember_isForbidden() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

        assertThatThrownBy(() -> controller.getVendor("teams", SCOPE_ID, VENDOR_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
    }
}
