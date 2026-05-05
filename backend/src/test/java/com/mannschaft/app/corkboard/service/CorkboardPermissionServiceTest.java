package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("CorkboardPermissionService 単体テスト")
class CorkboardPermissionServiceTest {

    @Mock private AccessControlService accessControlService;
    @InjectMocks private CorkboardPermissionService service;

    private CorkboardEntity teamBoard(String policy) {
        return CorkboardEntity.builder()
                .scopeType("TEAM").scopeId(10L).editPolicy(policy).name("チームボード").build();
    }

    private CorkboardEntity orgBoard(String policy) {
        return CorkboardEntity.builder()
                .scopeType("ORGANIZATION").scopeId(20L).editPolicy(policy).name("組織ボード").build();
    }

    private CorkboardEntity personalBoard(Long ownerId) {
        return CorkboardEntity.builder()
                .scopeType("PERSONAL").ownerId(ownerId).name("個人ボード").build();
    }

    @Test
    @DisplayName("ADMIN_ONLY: ADMIN なら通過")
    void adminOnly_admin通過() {
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(true);
        assertThatCode(() -> service.checkEditPermission(teamBoard("ADMIN_ONLY"), 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ADMIN_ONLY: 一般メンバーは CORKBOARD_009")
    void adminOnly_member拒否() {
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(false);
        assertThatThrownBy(() -> service.checkEditPermission(teamBoard("ADMIN_ONLY"), 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((BusinessException) e).getErrorCode().getCode()).isEqualTo("CORKBOARD_009"));
    }

    @Test
    @DisplayName("ALL_MEMBERS: メンバーなら通過")
    void allMembers_member通過() {
        given(accessControlService.isMember(1L, 20L, "ORGANIZATION")).willReturn(true);
        assertThatCode(() -> service.checkEditPermission(orgBoard("ALL_MEMBERS"), 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ALL_MEMBERS: 非メンバーは CORKBOARD_009")
    void allMembers_nonMember拒否() {
        given(accessControlService.isMember(1L, 20L, "ORGANIZATION")).willReturn(false);
        assertThatThrownBy(() -> service.checkEditPermission(orgBoard("ALL_MEMBERS"), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("PERSONAL: 所有者は通過、他人は CORKBOARD_009")
    void personal_所有者のみ通過() {
        assertThatCode(() -> service.checkEditPermission(personalBoard(1L), 1L))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> service.checkEditPermission(personalBoard(2L), 1L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("未知のポリシーは CORKBOARD_009")
    void 未知ポリシー拒否() {
        assertThatThrownBy(() -> service.checkEditPermission(teamBoard("UNKNOWN_POLICY"), 1L))
                .isInstanceOf(BusinessException.class);
    }

    // ===========================================================
    //  F09.8 件A: canEdit (boolean 返却版)
    //  詳細レスポンス DTO の viewerCanEdit フラグ算出用
    // ===========================================================

    @Test
    @DisplayName("canEdit: PERSONAL 所有者なら true")
    void canEdit_PERSONAL所有者_true() {
        assertThat(service.canEdit(personalBoard(1L), 1L)).isTrue();
    }

    @Test
    @DisplayName("canEdit: PERSONAL 非所有者なら false")
    void canEdit_PERSONAL非所有者_false() {
        assertThat(service.canEdit(personalBoard(2L), 1L)).isFalse();
    }

    @Test
    @DisplayName("canEdit: ADMIN_ONLY ボード × ADMIN なら true")
    void canEdit_AdminOnly_Admin_true() {
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(true);
        assertThat(service.canEdit(teamBoard("ADMIN_ONLY"), 1L)).isTrue();
    }

    @Test
    @DisplayName("canEdit: ADMIN_ONLY ボード × 非ADMIN なら false")
    void canEdit_AdminOnly_NonAdmin_false() {
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(false);
        assertThat(service.canEdit(teamBoard("ADMIN_ONLY"), 1L)).isFalse();
    }

    @Test
    @DisplayName("canEdit: ALL_MEMBERS ボード × メンバーなら true")
    void canEdit_AllMembers_Member_true() {
        given(accessControlService.isMember(1L, 20L, "ORGANIZATION")).willReturn(true);
        assertThat(service.canEdit(orgBoard("ALL_MEMBERS"), 1L)).isTrue();
    }

    @Test
    @DisplayName("canEdit: ALL_MEMBERS ボード × 非メンバーなら false")
    void canEdit_AllMembers_NonMember_false() {
        given(accessControlService.isMember(1L, 20L, "ORGANIZATION")).willReturn(false);
        assertThat(service.canEdit(orgBoard("ALL_MEMBERS"), 1L)).isFalse();
    }

    @Test
    @DisplayName("canEdit: 引数 null・未知スコープ・未知ポリシーは false（例外を投げない）")
    void canEdit_異常入力_false() {
        assertThat(service.canEdit(null, 1L)).isFalse();
        assertThat(service.canEdit(personalBoard(1L), null)).isFalse();
        assertThat(service.canEdit(teamBoard("UNKNOWN_POLICY"), 1L)).isFalse();

        CorkboardEntity unknown = CorkboardEntity.builder()
                .scopeType("UNKNOWN").scopeId(99L).editPolicy("ADMIN_ONLY").name("謎ボード").build();
        assertThat(service.canEdit(unknown, 1L)).isFalse();
    }
}
