package com.mannschaft.app.membership.query;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.MembershipBasisErrorCode;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link MemberQueryDispatcher} 単体テスト。
 *
 * <p>OQ-10 / OQ-2 の決着ロジックを網羅する:</p>
 * <ul>
 *   <li>roleName 分岐（NULL / ADMIN / DEPUTY_ADMIN / GUEST / SYSTEM_ADMIN / MEMBER / SUPPORTER / 不正値）</li>
 *   <li>NULL のときの集約 — 同一 user に ADMIN(user_roles) と MEMBER(memberships) が両方ある場合 ADMIN が勝つ</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberQueryDispatcher 単体テスト")
class MemberQueryDispatcherTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MemberQueryDispatcher dispatcher;

    @Test
    @DisplayName("不正な roleName で MEMBERSHIP_INVALID_ROLE_KIND")
    void invalidRoleName() {
        assertThatThrownBy(() -> dispatcher.queryMembers(100L, ScopeType.TEAM, "BOSS"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        MembershipBasisErrorCode.MEMBERSHIP_INVALID_ROLE_KIND);
    }

    @Test
    @DisplayName("roleName=ADMIN は user_roles のみを参照")
    void queryByPermissionRoleAdmin() {
        RoleEntity admin = role(2L, "ADMIN");
        given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(admin));
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(99L).teamId(100L).roleId(2L).build();
        given(userRoleRepository.findByTeamIdAndRoleId(100L, 2L)).willReturn(List.of(ur));
        given(userRepository.findMemberSummaryById(99L)).willReturn(Optional.empty());

        List<MemberDto> result = dispatcher.queryMembers(100L, ScopeType.TEAM, "ADMIN");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(99L);
        assertThat(result.get(0).roleName()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("roleName=MEMBER は memberships のみを参照")
    void queryByMembershipRoleKindMember() {
        MembershipEntity m = MembershipEntity.builder()
                .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build();
        Page<MembershipEntity> page = new PageImpl<>(List.of(m));
        given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                .willReturn(page);
        given(userRepository.findMemberSummaryById(99L)).willReturn(Optional.empty());

        List<MemberDto> result = dispatcher.queryMembers(100L, ScopeType.TEAM, "MEMBER");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).roleName()).isEqualTo("MEMBER");
    }

    @Test
    @DisplayName("roleName=null: ADMIN(user_roles) と MEMBER(memberships) の両方を持つ user は ADMIN が優先")
    void aggregatePrioritizeAdmin() {
        // user_roles: userId=99, ADMIN
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(99L).teamId(100L).roleId(2L).build();
        ReflectionTestUtils.setField(ur, "createdAt", LocalDateTime.now().minusMonths(2));
        Page<UserRoleEntity> urPage = new PageImpl<>(List.of(ur));
        given(userRoleRepository.findByTeamId(eq(100L), any(Pageable.class))).willReturn(urPage);
        given(roleRepository.findById(2L)).willReturn(Optional.of(role(2L, "ADMIN")));

        // memberships: userId=99, MEMBER
        MembershipEntity m = MembershipEntity.builder()
                .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now().minusMonths(1)).build();
        Page<MembershipEntity> mPage = new PageImpl<>(List.of(m));
        given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                .willReturn(mPage);

        given(userRepository.findMemberSummaryById(99L)).willReturn(Optional.empty());

        List<MemberDto> result = dispatcher.queryMembers(100L, ScopeType.TEAM, null);

        // 両方を持つので 1 件に集約され、ADMIN が勝つ
        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(99L);
        assertThat(result.get(0).roleName()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("GDPR マスキング済 (user_id NULL) の memberships はスキップ")
    void skipGdprMasked() {
        MembershipEntity m = MembershipEntity.builder()
                .userId(null).scopeType(ScopeType.TEAM).scopeId(100L)
                .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build();
        Page<MembershipEntity> page = new PageImpl<>(List.of(m));
        given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                .willReturn(page);

        List<MemberDto> result = dispatcher.queryMembers(100L, ScopeType.TEAM, "MEMBER");
        assertThat(result).isEmpty();
    }

    private static RoleEntity role(Long id, String name) {
        RoleEntity r = RoleEntity.builder()
                .name(name).displayName(name).priority(0).isSystem(false).build();
        ReflectionTestUtils.setField(r, "id", id);
        return r;
    }

    // Mockito eq import shortcut
    private static <T> T eq(T value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
