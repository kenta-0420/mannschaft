package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.service.UserRowLockService;
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
import com.mannschaft.app.team.TeamErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * スコープ（TEAM/ORGANIZATION）へのロール付与＋メンバーシップ入会を行う共通経路。
 *
 * <p>招待承諾（{@link InviteAcceptanceFacade}）と参加申請承認（柱③-A・CMP-260901-1538）の双方が
 * 本サービスを経由することで、メンバーシップ付与ロジックを二重実装しない
 * （CLAUDE.md 障害対応の原則・根治治療）。</p>
 *
 * <p>越境トランザクション方針（原則5）: 本サービスは role ドメイン（{@link UserRoleRepository}）と
 * membership ドメイン（{@link MembershipService}）にまたがるため、公開メソッドに {@code @Transactional}
 * を持ち、呼び出し元のトランザクションに相乗りできる。クラス単位の {@code @Transactional} は付けない
 * （付けると private ヘルパ {@code doGrant} が D-3T の入口になる）。</p>
 *
 * <h2>ロック順（CMP-260826-1457 §7）</h2>
 * <p>全経路で「user 行 → スコープ管理者行 → user_roles → memberships」の順に取る
 * （{@code RoleService#removeMemberWithoutAdminCheck} と同じ）。最初のクエリを user 行の
 * {@code FOR UPDATE} にするので、待たされた後の在籍判定はロック取得後に始まる。</p>
 *
 * <h2>2つの入口の差（現状維持の範囲）</h2>
 * <ul>
 *   <li>{@link #grantRole}（招待）: user が ACTIVE であることを確認し（違えば ROLE_002 でトランザクションごと
 *       巻き戻す）、user_roles ∪ memberships の在籍を現在読みで判定する。既に在籍なら
 *       {@link GrantOutcome#ALREADY_JOINED} を返す。</li>
 *   <li>{@link #grantMemberRole}（参加申請承認）: 現行どおり ACTIVE を問わず、memberships のみを
 *       スナップショット読みで判定する。競合時の一意制約違反・user_roles のみ残存時の挙動も現行のまま
 *       （改善は別戦役 (c)）。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipGrantService {

    /** 付与の結果。{@code ALREADY_JOINED} のときは何も書いていない。 */
    public enum GrantOutcome {
        GRANTED,
        ALREADY_JOINED
    }

    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final MembershipService membershipService;
    private final UserRowLockService userRowLockService;

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
        doGrant(Mode.JOIN_REQUEST, scopeType, scopeId, userId, null, grantedBy, source);
    }

    /**
     * 指定ロールを {@code user_roles} に割当て、{@code memberships} へ MEMBER として入会させる（招待承諾用）。
     *
     * @param scopeType  "TEAM" または "ORGANIZATION"
     * @param scopeId    スコープ ID
     * @param userId     付与対象ユーザー ID
     * @param roleId     付与するロール ID（呼出側で非特権であることを検証済みであること）
     * @param grantedBy  付与を行った操作者ユーザー ID（招待者・承認者等）
     * @param source     入会経路（{@code MembershipCreateRequest#source}。例: INVITE_TOKEN）
     * @return 付与した（{@code GRANTED}）か、既に在籍で何もしなかった（{@code ALREADY_JOINED}）か
     * @throws BusinessException ROLE_002（user が ACTIVE でない）、TEAM_003（競合して在籍が先に確定した）
     */
    @Transactional
    public GrantOutcome grantRole(String scopeType, Long scopeId, Long userId, Long roleId, Long grantedBy,
                                  String source) {
        return doGrant(Mode.INVITE, scopeType, scopeId, userId, roleId, grantedBy, source);
    }

    private enum Mode {
        /** 招待承諾: ACTIVE 必須・在籍は user_roles ∪ memberships の現在読み・競合は業務エラーへ変換。 */
        INVITE,
        /** 参加申請承認: 現行維持（ACTIVE を問わず、memberships のスナップショット読み）。 */
        JOIN_REQUEST
    }

    private GrantOutcome doGrant(Mode mode, String scopeType, Long scopeId, Long userId, Long roleId,
                                 Long grantedBy, String source) {
        // 1. user 行を先にロックする（最初のクエリ。ロック待ちの後に在籍判定が始まるようにする）。
        UserRowLockService.UserState userState = userRowLockService.lock(userId);
        // 2. 招待のみ ACTIVE を確認する。退会競合は user ロック下のこの確認で直列化される。
        if (mode == Mode.INVITE && userState != UserRowLockService.UserState.ACTIVE) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        ScopeType st = "TEAM".equals(scopeType) ? ScopeType.TEAM : ScopeType.ORGANIZATION;

        // 3. 冪等性チェック（P1-2）: 既に当該スコープのメンバーであれば二重割当・重複入会を行わない。
        if (isAlreadyJoined(mode, st, scopeId, userId)) {
            log.info("ロール付与をスキップ（既にメンバー・冪等）: scopeType={}, scopeId={}, userId={}, source={}",
                    scopeType, scopeId, userId, source);
            return GrantOutcome.ALREADY_JOINED;
        }

        Long grantRoleId = roleId != null ? roleId : roleRepository.findByName(MEMBER_ROLE_NAME)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001)).getId();

        if (mode == Mode.INVITE) {
            try {
                grant(scopeType, scopeId, userId, grantRoleId, grantedBy, source, true);
            } catch (DataIntegrityViolationException e) {
                // user ロック下の現在読みを通った後の一意制約違反は、並行して在籍が確定した競合。
                // TX は巻き戻る。業務エラーに変換するので、呼び出し側は確保した使用回数を戻せる。
                log.warn("ロール付与で一意制約違反（並行して在籍が確定）: scopeType={}, scopeId={}, userId={}",
                        scopeType, scopeId, userId);
                throw new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
            }
        } else {
            grant(scopeType, scopeId, userId, grantRoleId, grantedBy, source, false);
        }

        log.info("ロール付与・入会完了: scopeType={}, scopeId={}, userId={}, roleId={}, source={}",
                scopeType, scopeId, userId, grantRoleId, source);
        return GrantOutcome.GRANTED;
    }

    private boolean isAlreadyJoined(Mode mode, ScopeType st, Long scopeId, Long userId) {
        if (mode == Mode.JOIN_REQUEST) {
            return membershipService.isActiveMember(userId, st, scopeId);
        }
        boolean hasUserRole = st == ScopeType.TEAM
                ? userRoleRepository.findByUserIdAndTeamIdForUpdate(userId, scopeId).isPresent()
                : userRoleRepository.findByUserIdAndOrganizationIdForUpdate(userId, scopeId).isPresent();
        return hasUserRole || membershipService.isActiveMemberForUpdate(userId, st, scopeId);
    }

    private void grant(String scopeType, Long scopeId, Long userId, Long roleId, Long grantedBy, String source,
                       boolean flushAtEnd) {
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

        if (flushAtEnd) {
            // 制約違反をこのメソッドの中で（呼び出し側のコミットを待たず）確定させる。
            userRoleRepository.flush();
        }
    }
}
