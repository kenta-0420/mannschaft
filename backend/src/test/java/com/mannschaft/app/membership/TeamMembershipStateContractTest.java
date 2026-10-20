package com.mannschaft.app.membership;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.AccessControlService.TeamUserKey;
import com.mannschaft.app.common.AccessControlService.TeamMembershipState;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 在籍資格の業務補完UT。役割の実SQL・SYSTEM_ADMIN排除は実MySQL ITで検査する。 */
@ExtendWith(MockitoExtension.class)
class TeamMembershipStateContractTest {
    private static final TeamUserKey KEY = new TeamUserKey(13L,7L);
    @Mock private MembershipRepository memberships;
    @Mock private UserRoleRepository roles;
    @InjectMocks private AccessControlService service;

    @Test
    void null空と不完全キーは照会しない() {
        assertThat(service.getTeamMembershipStates(null)).isEmpty();
        assertThat(service.getTeamMembershipStates(List.of())).isEmpty();
        assertThat(service.getTeamMembershipStates(Arrays.asList(null,new TeamUserKey(null,7L),new TeamUserKey(13L,null)))).isEmpty();
        verifyNoInteractions(memberships,roles);
    }
    @ParameterizedTest
    @EnumSource(RoleKind.class)
    void MEMBERは正式会員でSUPPORTERは在籍だけとする(RoleKind kind) {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of(row(KEY,kind,true)));
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of());
        assertThat(service.getTeamMembershipStates(List.of(KEY)).get(KEY))
                .isEqualTo(new TeamMembershipState(true,kind==RoleKind.MEMBER));
    }
    @Test
    void SUPPORTERでも同TEAM明示管理権限があれば正式以上とする() {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of(row(KEY,RoleKind.SUPPORTER,true)));
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of(admin(KEY)));
        assertThat(service.getTeamMembershipStates(List.of(KEY)).get(KEY)).isEqualTo(new TeamMembershipState(true,true));
    }
    @Test
    void 管理権限だけで在籍を作らず非所属を昇格しない() {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of());
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of(admin(KEY)));
        assertThat(service.getTeamMembershipStates(List.of(KEY)).get(KEY)).isEqualTo(new TeamMembershipState(false,false));
    }
    @Test
    void inactive在籍投影は管理権限があっても資格なしとする() {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of(row(KEY,RoleKind.MEMBER,false)));
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of(admin(KEY)));
        assertThat(service.getTeamMembershipStates(List.of(KEY)).get(KEY)).isEqualTo(new TeamMembershipState(false,false));
    }
    @Test
    void active不明の在籍投影は資格なしとする() {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of(row(KEY,RoleKind.MEMBER,null)));
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of());
        assertThat(service.getTeamMembershipStates(List.of(KEY)).get(KEY)).isEqualTo(new TeamMembershipState(false,false));
    }
    @Test
    void 重複を除きmembershipと明示権限を各一回だけ照会する() {
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of());
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of());
        assertThat(service.getTeamMembershipStates(Arrays.asList(KEY,KEY,null))).containsOnlyKeys(KEY);
        verify(memberships).findTeamMembershipStateRows(List.of(13L),List.of(7L));
        verify(roles).findExplicitTeamAdminKeys(List.of(13L),List.of(7L));
        verifyNoMoreInteractions(memberships,roles);
    }
    @Test
    void IN集合の交差行で未要求の資格を混入しない() {
        var other=new TeamUserKey(14L,8L);
        var cross=new TeamUserKey(13L,8L);
        when(memberships.findTeamMembershipStateRows(any(),any())).thenReturn(List.of(row(cross,RoleKind.MEMBER,true)));
        when(roles.findExplicitTeamAdminKeys(any(),any())).thenReturn(List.of(admin(cross)));
        assertThat(service.getTeamMembershipStates(List.of(KEY,other))).containsOnlyKeys(KEY,other)
                .allSatisfy((key,value)->assertThat(value).isEqualTo(new TeamMembershipState(false,false)));
    }
    private MembershipRepository.TeamMembershipStateRow row(TeamUserKey key,RoleKind kind,Boolean active) {
        return new MembershipRepository.TeamMembershipStateRow() {
            public Long getTeamId(){return key.teamId();}
            public Long getUserId(){return key.userId();}
            public RoleKind getRoleKind(){return kind;}
            public Boolean getActive(){return active;}
        };
    }
    private UserRoleRepository.TeamAdminKeyRow admin(TeamUserKey key) {
        return new UserRoleRepository.TeamAdminKeyRow() {
            public Long getTeamId(){return key.teamId();}
            public Long getUserId(){return key.userId();}
        };
    }
}
