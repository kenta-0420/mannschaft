package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * スコープ（TEAM/ORGANIZATION）へのロール付与＋メンバーシップ入会を行う共通経路。
 *
 * <p>{@link InviteService#joinByInvite(String, Long, Long)} が実装していた「user_roles への
 * ロール割当 ＋ memberships への MEMBER 入会」の一連処理を抽出したもの。招待承諾（既存）と
 * 参加申請承認（柱③-A・CMP-260901-1538）の双方が本サービスを経由することで、メンバーシップ
 * 付与ロジックを二重実装しない（CLAUDE.md 障害対応の原則・根治治療）。</p>
 *
 * <p>越境トランザクション方針（原則5）: 本サービス自体は role ドメイン（{@link UserRoleRepository}）と
 * membership ドメイン（{@link MembershipService}）にまたがるため、呼び出し元の {@code @Transactional}
 * に相乗り可能な {@code @Transactional} を持つ（既存 {@code InviteService.joinByInvite} と同じ構成）。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipGrantService {

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final MembershipService membershipService;

    private static final String MEMBER_ROLE_NAME = "MEMBER";

    /**
     * MEMBER ロールを付与して入会させる（他ドメインが role ドメインの {@link RoleRepository} を
     * 直接注入せずに済む Service 経路。D-5 ArchUnit 準拠: クラスは別ドメイン Repository に
     * 直接依存しない）。柱③-A 参加申請承認から使用する。
     *
     * @param scopeType "TEAM" または "ORGANIZATION"
     * @param scopeId   スコープ ID
     * @param userId    付与対象ユーザー ID
     * @param grantedBy 承認者ユーザー ID
     * @param source    入会経路（例: JOIN_REQUEST）
     */
    @Transactional
    public void grantMemberRole(String scopeType, Long scopeId, Long userId, Long grantedBy, String source) {
        RoleEntity memberRole = roleRepository.findByName(MEMBER_ROLE_NAME)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        grantRole(scopeType, scopeId, userId, memberRole.getId(), grantedBy, source);
    }

    /**
     * 指定ロールを {@code user_roles} に割当て、{@code memberships} へ MEMBER として入会させる。
     *
     * @param scopeType  "TEAM" または "ORGANIZATION"
     * @param scopeId    スコープ ID
     * @param userId     付与対象ユーザー ID
     * @param roleId     付与するロール ID（呼出側で非特権であることを検証済みであること）
     * @param grantedBy  付与を行った操作者ユーザー ID（招待者・承認者等）
     * @param source     入会経路（{@code MembershipCreateRequest#source}。例: INVITE_TOKEN, JOIN_REQUEST）
     */
    @Transactional
    public void grantRole(String scopeType, Long scopeId, Long userId, Long roleId, Long grantedBy, String source) {
        var roleBuilder = UserRoleEntity.builder()
                .userId(userId)
                .roleId(roleId)
                .grantedBy(grantedBy);
        if ("TEAM".equals(scopeType)) {
            roleBuilder.teamId(scopeId);
        } else {
            roleBuilder.organizationId(scopeId);
        }
        userRoleRepository.save(roleBuilder.build());

        // F00.5 認可基盤根治: memberships にも MEMBER として入会させる（既存 InviteService と同じ流儀）。
        MembershipCreateRequest membershipReq = new MembershipCreateRequest();
        membershipReq.setUserId(userId);
        membershipReq.setScopeType("TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION);
        membershipReq.setScopeId(scopeId);
        membershipReq.setRoleKind(RoleKind.MEMBER);
        membershipReq.setInvitedBy(grantedBy);
        membershipReq.setSource(source);
        membershipService.join(membershipReq);

        log.info("ロール付与・入会完了: scopeType={}, scopeId={}, userId={}, roleId={}, source={}",
                scopeType, scopeId, userId, roleId, source);
    }
}
