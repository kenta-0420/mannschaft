package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link MembershipGrantService} の単体テスト。
 *
 * <p>招待承諾（{@code InviteService#joinByInvite}）と参加申請承認（柱③-A・CMP-260901-1538）の
 * 双方が経由する共通のロール付与・入会経路であることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipGrantService 単体テスト")
class MembershipGrantServiceTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private MembershipService membershipService;

    @InjectMocks
    private MembershipGrantService service;

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long USER_ID = 1L;
    private static final Long GRANTED_BY = 2L;
    private static final Long ROLE_ID = 3L;

    @Test
    @DisplayName("grantRole: TEAM スコープで user_roles 割当と membership 入会の両方を行う")
    void grantRole_チーム() {
        given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "INVITE_TOKEN");

        ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(userRoleRepository).save(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getTeamId()).isEqualTo(TEAM_ID);
        assertThat(roleCaptor.getValue().getRoleId()).isEqualTo(ROLE_ID);
        assertThat(roleCaptor.getValue().getUserId()).isEqualTo(USER_ID);

        ArgumentCaptor<MembershipCreateRequest> membershipCaptor =
                ArgumentCaptor.forClass(MembershipCreateRequest.class);
        verify(membershipService).join(membershipCaptor.capture());
        MembershipCreateRequest req = membershipCaptor.getValue();
        assertThat(req.getUserId()).isEqualTo(USER_ID);
        assertThat(req.getScopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(req.getScopeId()).isEqualTo(TEAM_ID);
        assertThat(req.getRoleKind()).isEqualTo(RoleKind.MEMBER);
        assertThat(req.getInvitedBy()).isEqualTo(GRANTED_BY);
        assertThat(req.getSource()).isEqualTo("INVITE_TOKEN");
    }

    @Test
    @DisplayName("grantRole: ORGANIZATION スコープでも同様に動作する")
    void grantRole_組織() {
        given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.grantRole("ORGANIZATION", ORG_ID, USER_ID, ROLE_ID, GRANTED_BY, "JOIN_REQUEST");

        ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(userRoleRepository).save(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(roleCaptor.getValue().getTeamId()).isNull();
    }

    @Test
    @DisplayName("grantMemberRole: MEMBER ロールを解決して grantRole と同じ経路で付与する")
    void grantMemberRole_MEMBERロール解決() {
        given(roleRepository.findByName("MEMBER"))
                .willReturn(Optional.of(RoleEntity.builder().id(ROLE_ID).name("MEMBER").build()));
        given(userRoleRepository.save(any(UserRoleEntity.class))).willAnswer(inv -> inv.getArgument(0));

        service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST");

        ArgumentCaptor<UserRoleEntity> roleCaptor = ArgumentCaptor.forClass(UserRoleEntity.class);
        verify(userRoleRepository).save(roleCaptor.capture());
        assertThat(roleCaptor.getValue().getRoleId()).isEqualTo(ROLE_ID);
        verify(membershipService).join(any(MembershipCreateRequest.class));
    }

    @Test
    @DisplayName("grantRole: 既にアクティブメンバーなら冪等にスキップする（二重付与防止・レビューP1-2）")
    void grantRole_既にアクティブメンバーならスキップ() {
        given(membershipService.isActiveMember(USER_ID, ScopeType.TEAM, TEAM_ID)).willReturn(true);

        service.grantRole("TEAM", TEAM_ID, USER_ID, ROLE_ID, GRANTED_BY, "JOIN_REQUEST");

        verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
        verify(membershipService, never()).join(any(MembershipCreateRequest.class));
    }

    @Test
    @DisplayName("grantMemberRole: MEMBER ロールがマスタに存在しなければ例外で入会もしない")
    void grantMemberRole_ロール不在() {
        given(roleRepository.findByName("MEMBER")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.grantMemberRole("TEAM", TEAM_ID, USER_ID, GRANTED_BY, "JOIN_REQUEST"))
                .isInstanceOf(BusinessException.class);
        verify(userRoleRepository, never()).save(any(UserRoleEntity.class));
        verify(membershipService, never()).join(any(MembershipCreateRequest.class));
    }
}
