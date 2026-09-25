package com.mannschaft.app.member.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.dto.MemberSubtabVisibilityResponse;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CMP-260919-1140 Phase 1: {@link MemberSubtabVisibilityService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemberSubtabVisibilityService 単体テスト")
class MemberSubtabVisibilityServiceTest {

    @Mock
    private MemberSubtabRoleVisibilityRepository repository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private NameResolverService nameResolverService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MemberSubtabVisibilityService service;

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new MemberSubtabVisibilityService(
                repository, accessControlService, auditLogService, nameResolverService, objectMapper);
    }

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("非メンバー(未認証含む)はデフォルト値(MEMBER)を返す・DBは参照しない")
        void 非メンバー_デフォルト値() {
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            MemberSubtabVisibilityResponse response = service.getSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID);

            assertThat(response.getSubtabs()).hasSize(2);
            assertThat(response.getSubtabs()).allMatch(item -> item.isDefault() && item.getMinRole() == MinRole.MEMBER);
            verify(repository, never()).findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID);
        }

        @Test
        @DisplayName("検分指摘B(2巡目・P2): SUPPORTERはisMember()ではtrueだが、"
                + "ロール閾値(MEMBER以上)ではデフォルト値を返す・DBは参照しない")
        void SUPPORTER_デフォルト値_isMemberはtrueでも拒否() {
            // isMember() は SUPPORTER も所属者として true を返すため、旧実装ではここで実設定が
            // 漏れていた（検分指摘B）。hasRoleOrAbove(...,"MEMBER") を false のままにしても、
            // SUPPORTER の isMember() が true になりうる状況を再現するためスタブしておく。
            given(accessControlService.isMember(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            MemberSubtabVisibilityResponse response = service.getSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID);

            assertThat(response.getSubtabs()).allMatch(item -> item.isDefault() && item.getMinRole() == MinRole.MEMBER);
            verify(repository, never()).findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID);
        }

        @Test
        @DisplayName("メンバーは DB 設定 + デフォルトの合成結果を返す")
        void メンバー_合成結果() {
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);
            MemberSubtabRoleVisibilityEntity entity = MemberSubtabRoleVisibilityEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(ORG_ID)
                    .subtabKey("member_profiles").minRole(MinRole.PUBLIC).updatedBy(9L)
                    .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(List.of(entity));

            MemberSubtabVisibilityResponse response = service.getSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID);

            assertThat(response.getSubtabs()).anySatisfy(item -> {
                if (item.getSubtabKey().equals("member_profiles")) {
                    assertThat(item.getMinRole()).isEqualTo(MinRole.PUBLIC);
                    assertThat(item.isDefault()).isFalse();
                }
            });
        }
    }

    @Nested
    @DisplayName("updateSettings")
    class UpdateSettings {

        @Test
        @DisplayName("ADMIN 以外・パーミッションなしは例外伝播")
        void 権限なし_例外伝播() {
            UpdateMemberSubtabVisibilityRequest request = buildRequest("member_profiles", MinRole.SUPPORTER);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", MemberSubtabVisibilityService.PERMISSION_NAME);

            assertThatThrownBy(() -> service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request))
                    .isInstanceOf(BusinessException.class);
            verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("ADMIN は無条件で更新可・デフォルト不一致は UPSERT")
        void ADMIN_更新可_upsert() {
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_profiles")).willReturn(Optional.empty());

            UpdateMemberSubtabVisibilityRequest request = buildRequest("member_profiles", MinRole.PUBLIC);
            service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request);

            verify(repository).save(org.mockito.ArgumentMatchers.any(MemberSubtabRoleVisibilityEntity.class));
            verify(auditLogService).record(
                    org.mockito.ArgumentMatchers.eq(MemberSubtabVisibilityService.AUDIT_EVENT_TYPE),
                    org.mockito.ArgumentMatchers.eq(USER_ID),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.eq(ORG_ID),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    org.mockito.ArgumentMatchers.isNull(),
                    anyString());
        }

        @Test
        @DisplayName("デフォルト値一致は既存レコードを DELETE する")
        void デフォルト一致_delete() {
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            MemberSubtabRoleVisibilityEntity existing = MemberSubtabRoleVisibilityEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(ORG_ID)
                    .subtabKey("member_profiles").minRole(MinRole.PUBLIC).updatedBy(9L)
                    .build();
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_profiles")).willReturn(Optional.of(existing));

            UpdateMemberSubtabVisibilityRequest request = buildRequest("member_profiles", MinRole.MEMBER);
            service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request);

            verify(repository).deleteByScopeTypeAndScopeIdAndSubtabKey(ScopeType.ORGANIZATION, ORG_ID, "member_profiles");
        }

        @Test
        @DisplayName("一覧タブに PUBLIC を指定すると MEMBER_016（422）")
        void 一覧タブ_PUBLIC拒否() {
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            UpdateMemberSubtabVisibilityRequest request = buildRequest("member_list", MinRole.PUBLIC);

            assertThatThrownBy(() -> service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_016"));
            verify(repository, never()).save(org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("検分修正(4巡目・P2): 所属のない SYSTEM_ADMIN は checkMembership の 403 に阻まれず更新できる")
        void 所属のないSYSTEM_ADMINは更新できる() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            // checkMembership を呼べば必ず COMMON_002 を投げる非会員状態をスタブし、
            // それでも到達しないこと（＝ checkMembership 呼び出し自体をスキップすること）を検証する。
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_profiles")).willReturn(Optional.empty());

            UpdateMemberSubtabVisibilityRequest request = buildRequest("member_profiles", MinRole.PUBLIC);
            service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request);

            verify(accessControlService, never()).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
            verify(accessControlService, never())
                    .checkPermission(USER_ID, ORG_ID, "ORGANIZATION", MemberSubtabVisibilityService.PERMISSION_NAME);
            verify(repository).save(org.mockito.ArgumentMatchers.any(MemberSubtabRoleVisibilityEntity.class));
        }

        @Test
        @DisplayName("未知の subtab_key は 400")
        void 未知キー_400() {
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            UpdateMemberSubtabVisibilityRequest request = buildRequest("unknown_key", MinRole.MEMBER);

            assertThatThrownBy(() -> service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_001"));
        }
    }

    @Nested
    @DisplayName("assertViewable(外側の門)")
    class AssertViewable {

        @Test
        @DisplayName("SYSTEM_ADMIN は無条件で通過")
        void システム管理者_通過() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            service.assertViewable(USER_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST);
            verify(repository, never()).findByScopeTypeAndScopeIdAndSubtabKey(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), anyString());
        }

        @Test
        @DisplayName("ADMIN は無条件で通過")
        void 組織管理者_通過() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            service.assertViewable(USER_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST);
        }

        @Test
        @DisplayName("既定値(MEMBER): MEMBER ロールの閲覧者は通過")
        void 既定値_MEMBER通過() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_list")).willReturn(Optional.empty());
            given(accessControlService.getRoleName(USER_ID, ORG_ID, "ORGANIZATION")).willReturn("MEMBER");

            service.assertViewable(USER_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST);
        }

        @Test
        @DisplayName("既定値(MEMBER): 非会員(PUBLIC)は拒否される（現状挙動と等価）")
        void 既定値_非会員拒否() {
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_list")).willReturn(Optional.empty());
            given(accessControlService.getRoleName(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(null);

            assertThatThrownBy(() ->
                    service.assertViewable(USER_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_LIST))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("未認証(userId=null)は PUBLIC 扱い")
        void 未認証_PUBLIC扱い() {
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_profiles")).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.assertViewable(null, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("紹介タブが PUBLIC 設定なら未認証でも通過")
        void 紹介タブPUBLIC設定_未認証でも通過() {
            MemberSubtabRoleVisibilityEntity entity = MemberSubtabRoleVisibilityEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION).scopeId(ORG_ID)
                    .subtabKey("member_profiles").minRole(MinRole.PUBLIC).updatedBy(9L)
                    .build();
            given(repository.findByScopeTypeAndScopeIdAndSubtabKey(
                    ScopeType.ORGANIZATION, ORG_ID, "member_profiles")).willReturn(Optional.of(entity));

            service.assertViewable(null, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);
        }
    }

    private static UpdateMemberSubtabVisibilityRequest buildRequest(String subtabKey, MinRole minRole) {
        UpdateMemberSubtabVisibilityRequest request = new UpdateMemberSubtabVisibilityRequest();
        UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem item =
                new UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem();
        item.setSubtabKey(subtabKey);
        item.setMinRole(minRole);
        request.setSubtabs(List.of(item));
        return request;
    }
}
