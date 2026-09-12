package com.mannschaft.app.membership.query;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
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

    @Mock
    private MediaUrlResolver mediaUrlResolver;

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
    @DisplayName("画像URL根治Phase2_avatarが署名付き表示URLへ解決されMemberDtoに乗る")
    void avatarが署名付き表示URLへ解決される() {
        RoleEntity admin = role(2L, "ADMIN");
        given(roleRepository.findByName("ADMIN")).willReturn(Optional.of(admin));
        UserRoleEntity ur = UserRoleEntity.builder()
                .userId(99L).teamId(100L).roleId(2L).build();
        given(userRoleRepository.findByTeamIdAndRoleId(100L, 2L)).willReturn(List.of(ur));

        UserRepository.MemberSummary summary = org.mockito.Mockito.mock(UserRepository.MemberSummary.class);
        given(summary.getDisplayName()).willReturn("yamada");
        given(summary.getAvatarUrl()).willReturn("user/99/avatar/raw.png");
        given(userRepository.findMemberSummaryById(99L)).willReturn(Optional.of(summary));

        given(mediaUrlResolver.resolve("user/99/avatar/raw.png"))
                .willReturn("https://cdn.example.com/signed/avatar.png");

        List<MemberDto> result = dispatcher.queryMembers(100L, ScopeType.TEAM, "ADMIN");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).avatarUrl())
                .isEqualTo("https://cdn.example.com/signed/avatar.png");
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

    // ========================================
    // CMP-260910-1555: ページ内だけ実体化する経路
    // ========================================

    /**
     * 是正前は {@code TeamService#getMembers} が {@code queryMembers}（常に全員を実体化し、
     * ユーザー 1 人ごとに users を引く N+1）を呼んでからメモリ上で切り出していたため、
     * 1 ページ取るたびに全員ぶんの処理が走り、全ページを取ると総処理量が N^2/ページサイズになった。
     * {@code queryMembersPage} はページ内のぶんだけ実体化することでこれを O(N) に戻す。
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("queryMembersPage（ページ内のみ実体化）")
    class QueryMembersPage {

        private void givenTeamWithMembers(int memberCount) {
            List<MembershipEntity> memberships = new java.util.ArrayList<>();
            for (int i = 1; i <= memberCount; i++) {
                memberships.add(MembershipEntity.builder()
                        .userId((long) i).scopeType(ScopeType.TEAM).scopeId(100L)
                        .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build());
            }
            given(userRoleRepository.findByTeamId(eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(memberships));
        }

        @Test
        @DisplayName("users の実体化はページ内の人数ぶんだけ・1 クエリで行う（全員を引かない）")
        void ページ内だけ実体化する() {
            givenTeamWithMembers(250);
            given(userRepository.findMemberSummariesByIds(any())).willReturn(List.of());

            Page<MemberDto> page = dispatcher.queryMembersPage(
                    100L, ScopeType.TEAM, null, Pageable.ofSize(100).withPage(1));

            assertThat(page.getContent()).hasSize(100);
            assertThat(page.getTotalElements()).isEqualTo(250);

            // 実体化は 1 クエリ、しかも渡す ID は当該ページの 100 件だけ
            org.mockito.ArgumentCaptor<List<Long>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            org.mockito.Mockito.verify(userRepository).findMemberSummariesByIds(captor.capture());
            assertThat(captor.getValue()).hasSize(100);
            assertThat(captor.getValue().get(0)).isEqualTo(101L);
            assertThat(captor.getValue().get(99)).isEqualTo(200L);

            // 是正前の N+1 経路（1 人ずつ users を引く）は使わない
            org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never())
                    .findMemberSummaryById(org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("最終ページは端数だけ返し、総件数は絞り込み後の全件を示す")
        void 最終ページの端数() {
            givenTeamWithMembers(250);
            given(userRepository.findMemberSummariesByIds(any())).willReturn(List.of());

            Page<MemberDto> page = dispatcher.queryMembersPage(
                    100L, ScopeType.TEAM, null, Pageable.ofSize(100).withPage(2));

            assertThat(page.getContent()).hasSize(50);
            assertThat(page.getTotalElements()).isEqualTo(250);
        }

        @Test
        @DisplayName("範囲外のページは空を返す（例外にしない）")
        void 範囲外ページは空() {
            givenTeamWithMembers(10);

            Page<MemberDto> page = dispatcher.queryMembersPage(
                    100L, ScopeType.TEAM, null, Pageable.ofSize(100).withPage(5));

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isEqualTo(10);
            // 空ページでは実体化クエリ自体を投げない
            org.mockito.Mockito.verify(userRepository, org.mockito.Mockito.never())
                    .findMemberSummariesByIds(any());
        }

        @Test
        @DisplayName("ロール名の解決は roleId の種類数ぶんだけ（user_roles の行数に比例しない）")
        void ロール解決は行数に比例しない() {
            // 同じ ADMIN ロールを持つ 200 名。是正前は 1 行ごとに roleRepository.findById を
            // 呼んでいたため、1 ページ表示するだけで 200 クエリが出ていた（N+1）。
            List<UserRoleEntity> userRoles = new java.util.ArrayList<>();
            for (int i = 1; i <= 200; i++) {
                userRoles.add(UserRoleEntity.builder()
                        .userId((long) i).teamId(100L).roleId(2L).build());
            }
            given(userRoleRepository.findByTeamId(eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(userRoles));
            given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of()));
            given(roleRepository.findAllById(List.of(2L))).willReturn(List.of(role(2L, "ADMIN")));
            given(userRepository.findMemberSummariesByIds(any())).willReturn(List.of());

            Page<MemberDto> page = dispatcher.queryMembersPage(
                    100L, ScopeType.TEAM, null, Pageable.ofSize(100).withPage(0));

            assertThat(page.getTotalElements()).isEqualTo(200);
            assertThat(page.getContent()).hasSize(100);
            // 重複を除いた roleId は 1 種類なので、まとめ引きは 1 回だけ
            org.mockito.Mockito.verify(roleRepository, org.mockito.Mockito.times(1))
                    .findAllById(List.of(2L));
            // 1 行ごとに引く経路は使わない
            org.mockito.Mockito.verify(roleRepository, org.mockito.Mockito.never())
                    .findById(org.mockito.ArgumentMatchers.anyLong());
        }

        @Test
        @DisplayName("集約結果・並び順は queryMembers と一致する（ページングの意味論を変えていない）")
        void 全件ページは従来と一致する() {
            RoleEntity admin = role(2L, "ADMIN");
            given(roleRepository.findAllById(List.of(2L))).willReturn(List.of(admin));
            UserRoleEntity ur = UserRoleEntity.builder()
                    .userId(99L).teamId(100L).roleId(2L).build();
            given(userRoleRepository.findByTeamId(eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(ur)));
            MembershipEntity m = MembershipEntity.builder()
                    .userId(99L).scopeType(ScopeType.TEAM).scopeId(100L)
                    .roleKind(RoleKind.MEMBER).joinedAt(LocalDateTime.now()).build();
            given(membershipRepository.findByScopeAndActive(eq(ScopeType.TEAM), eq(100L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(m)));
            given(userRepository.findMemberSummariesByIds(any())).willReturn(List.of());

            Page<MemberDto> page = dispatcher.queryMembersPage(
                    100L, ScopeType.TEAM, null, Pageable.unpaged());

            // 同一 user の ADMIN(user_roles) と MEMBER(memberships) は 1 行に畳まれ ADMIN が勝つ
            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).userId()).isEqualTo(99L);
            assertThat(page.getContent().get(0).roleName()).isEqualTo("ADMIN");
            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }
}
