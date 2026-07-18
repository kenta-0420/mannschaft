package com.mannschaft.app.role.service;

import com.mannschaft.app.chat.service.ChatMembershipInviteCardService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InvitableScopesResponse;
import com.mannschaft.app.role.dto.InviteCardData;
import com.mannschaft.app.role.dto.MembershipInviteRequest;
import com.mannschaft.app.role.dto.MembershipInviteResponse;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * チャットからチーム/組織への承諾型招待サービス（F04.12）。
 *
 * <p>設計書: docs/features/F04.12_chat_membership_invite.md §4・§5・§6。</p>
 *
 * <p><strong>越境トランザクション方針（原則5・§5）:</strong> 発行は role ドメイン（トークン発行=
 * {@link MembershipInviteTokenIssuer}）と chat ドメイン（カード投稿=
 * {@link ChatMembershipInviteCardService}）をそれぞれ独立した {@code @Transactional} に閉じる。
 * 本メソッド自身は {@link Propagation#SUPPORTS} で、周囲に tx があれば参加（契約テスト＝同一 tx で可視）、
 * なければ非 tx で走り各ドメイン書込が独立コミットする Saga を成す。カード投稿失敗時はトークンを
 * 補償失効して宙に浮いた PENDING を残さない。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class MembershipInviteService {

    private static final String INVITE_MEMBERS_PERMISSION = "INVITE_MEMBERS";
    private static final Set<String> PRIVILEGED_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");
    private static final Set<Integer> ALLOWED_EXPIRES_IN_DAYS = Set.of(1, 7, 30, 90);
    private static final int DEFAULT_EXPIRES_IN_DAYS = 7;
    private static final String DEFAULT_ROLE_NAME = "MEMBER";

    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final TeamService teamService;
    private final OrganizationService organizationService;
    private final AccessControlService accessControlService;
    private final MembershipInviteTokenIssuer tokenIssuer;
    private final ChatMembershipInviteCardService cardService;

    /**
     * DM 相手を指定スコープへ招待する（宛先付きトークン発行 ＋ 招待カード投稿）。
     *
     * @param channelId   DM チャンネル ID（{@code channel_type = 'DM'} であること）
     * @param request     招待リクエスト（scopeType/scopeId/roleId/expiresInDays）
     * @param actorUserId 実行ユーザー ID
     * @return 発行結果
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public MembershipInviteResponse issueMembershipInvite(
            Long channelId, MembershipInviteRequest request, Long actorUserId) {
        String scopeType = normalizeScopeType(request.scopeType());
        Long scopeId = request.scopeId();

        // 1. 【入力検証】DM 相手（宛先）導出（DM 種別・2名・当事者ガード込み・B-9）。
        Long targetUserId = cardService.resolveDmCounterpart(channelId, actorUserId);

        // 2. 【入力検証】有効期限日数（許容値 {1,7,30,90} のみ・null は既定 7）。
        int expiresInDays = resolveExpiresInDays(request.expiresInDays());

        // 3. 【入力検証】付与ロール（非特権のみ・特権指定は 422 ROLE_009・C-1）。
        Long roleId = resolveNonPrivilegedRoleId(request.roleId());

        // 4. 【スコープ存在・アーカイブ検証】（404 / 422）。
        String scopeName = validateScopeAndResolveName(scopeType, scopeId);

        // 5. 【認可】実行ユーザーが scope の ADMIN or INVITE_MEMBERS 権限保有 DEPUTY_ADMIN か（403）。
        if (!canIssue(actorUserId, scopeId, scopeType)) {
            throw scopeAdminForbidden(scopeType);
        }

        // 6. 【既メンバー】宛先が既に scope メンバーなら発行不可（409）。
        if (accessControlService.isMember(targetUserId, scopeId, scopeType)) {
            throw alreadyMemberConflict(scopeType);
        }

        // 7. 【重複 PENDING】同一宛先 × 同一スコープの有効 PENDING が既存なら発行不可（409 ROLE_003・③）。
        if (hasActivePendingInvite(targetUserId, scopeType, scopeId)) {
            throw new BusinessException(RoleErrorCode.ROLE_003, HttpStatus.CONFLICT);
        }

        // 8. 【role ドメイン tx】宛先付きトークン発行（独立コミット）。
        LocalDateTime expiresAt = LocalDateTime.now().plusDays(expiresInDays);
        InviteTokenEntity token = tokenIssuer.issue(
                scopeType, scopeId, roleId, targetUserId, actorUserId, expiresAt);

        // 9. 【chat ドメイン tx】DM に INVITE_CARD を投稿。失敗時はトークンを補償失効して再送出（Saga 補償）。
        Long cardMessageId;
        try {
            String preview = scopeName + " への招待を送りました";
            cardMessageId = cardService.postInviteCard(channelId, actorUserId, token.getId(), preview);
        } catch (RuntimeException e) {
            tokenIssuer.revokeForCompensation(token.getId());
            throw e;
        }

        log.info("承諾型招待の発行完了: tokenId={}, channelId={}, scopeType={}, scopeId={}, targetUserId={}",
                token.getId(), channelId, scopeType, scopeId, targetUserId);

        return new MembershipInviteResponse(
                token.getId(), token.getToken(), targetUserId,
                scopeType, scopeId, scopeName, "PENDING", expiresAt, cardMessageId);
    }

    /**
     * 招待を取消す（{@code revoked_at} を立てカードを取消済み表示・audit に CANCELLED 記録）。
     *
     * <p>取消は発行者、または対象スコープの ADMIN/DEPUTY_ADMIN のみ可。宛先付きトークンでない、
     * または当該チャンネルの招待でない tokenId は BOLA 遮断のため拒否する。</p>
     *
     * @param channelId   DM チャンネル ID
     * @param tokenId     取消対象の招待トークン ID
     * @param actorUserId 実行ユーザー ID（発行者 or 対象スコープ ADMIN）
     */
    @Transactional
    public void revokeMembershipInvite(Long channelId, Long tokenId, Long actorUserId) {
        InviteTokenEntity token = inviteTokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        String scopeType = token.getTeamId() != null ? "TEAM" : "ORGANIZATION";
        Long scopeId = token.getTeamId() != null ? token.getTeamId() : token.getOrganizationId();

        // 発行者本人、または対象スコープ ADMIN/DEPUTY_ADMIN のみ取消可。
        boolean isIssuer = actorUserId.equals(token.getCreatedBy());
        if (!isIssuer && !canIssue(actorUserId, scopeId, scopeType)) {
            throw scopeAdminForbidden(scopeType);
        }

        token.revoke();
        log.info("承諾型招待を取消: tokenId={}, actorUserId={}", tokenId, actorUserId);
    }

    /**
     * 招待カードの描画契約データ（{@link InviteCardData}）を解決する（F04.12・設計書 §5・A-4）。
     *
     * <p>chat ドメインのメッセージ取得経路が {@code message_type = 'INVITE_CARD'} のメッセージに対して
     * ドメイン間 Service 呼び出しで使用する。Entity を漏らさず値オブジェクトで返す（原則1・ArchUnit D-1）。</p>
     *
     * <p><strong>{@code isTarget} の判定（IDOR 核心・設計書 §5）:</strong>
     * {@code viewerUserId == token.targetUserId} で「閲覧者が宛先本人か」を判定する。
     * 宛先本人にのみ参加/辞退ボタンを活性化し、発行者側は「承諾待ち」表示とする。</p>
     *
     * <p><strong>{@code status} の導出（状態機械・設計書 §5・M-1）:</strong> カードは状態を持たず
     * {@code invite_tokens} + メンバーシップから都度導出する。優先順位は
     * JOINED（宛先が既にスコープメンバー）→ REVOKED（{@code revoked_at} 非 NULL）→
     * EXPIRED（{@code expires_at < NOW()}・ちょうどは有効）→ PENDING。</p>
     *
     * @param tokenId      招待トークン ID（{@code chat_messages.invite_token_id}）
     * @param viewerUserId 現在の閲覧ユーザー ID（宛先本人判定に使用。未認証は null）
     * @return 招待カード描画データ。トークンが存在しない場合は null（カードは inviteData 無しで描画される）
     */
    public InviteCardData resolveInviteCardData(Long tokenId, Long viewerUserId) {
        if (tokenId == null) {
            return null;
        }
        InviteTokenEntity token = inviteTokenRepository.findById(tokenId).orElse(null);
        if (token == null) {
            // トークンは revoke でも物理削除されない（revoked_at を立てるのみ）。
            // 到達し得ない想定だが、万一欠落していれば inviteData 無しで返す（500 を出さない）。
            log.warn("招待カードのトークンが見つかりません: tokenId={}", tokenId);
            return null;
        }

        boolean isTeam = token.getTeamId() != null;
        String scopeType = isTeam ? "TEAM" : "ORGANIZATION";
        Long scopeId = isTeam ? token.getTeamId() : token.getOrganizationId();
        String scopeName = resolveScopeName(scopeType, scopeId);

        boolean isTarget = viewerUserId != null && viewerUserId.equals(token.getTargetUserId());
        String status = deriveCardStatus(token, scopeType, scopeId);

        return new InviteCardData(
                token.getId(), token.getToken(),
                scopeType, scopeId, scopeName,
                status, isTarget, token.getExpiresAt());
    }

    /**
     * 招待カードの表示状態を導出する（設計書 §5 状態遷移表）。
     *
     * <p>宛先が既にスコープメンバーなら承諾済み＝JOINED。以降は失効理由の優先度で判定する。</p>
     */
    private String deriveCardStatus(InviteTokenEntity token, String scopeType, Long scopeId) {
        Long targetUserId = token.getTargetUserId();
        if (targetUserId != null && accessControlService.isMember(targetUserId, scopeId, scopeType)) {
            return "JOINED";
        }
        if (token.getRevokedAt() != null) {
            return "REVOKED";
        }
        LocalDateTime expiresAt = token.getExpiresAt();
        // M-1: 既存 isValid() は expiresAt.isBefore(now) で判定するため、ちょうど（==）は有効（PENDING）。
        if (expiresAt != null && expiresAt.isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "PENDING";
    }

    /**
     * 自分が招待発行できる（ADMIN または INVITE_MEMBERS 権限保有 DEPUTY_ADMIN の）スコープ一覧を返す。
     *
     * <p>認可の真実源は BE（設計書 B-6）。管理スコープ 0 件でもエラーにせず空を返す。</p>
     *
     * @param userId 実行ユーザー ID
     * @return 招待発行可能スコープ一覧
     */
    public InvitableScopesResponse getInvitableScopes(Long userId) {
        List<InvitableScopesResponse.InvitableScope> teams =
                collectInvitableScopes(userId, "TEAM");
        List<InvitableScopesResponse.InvitableScope> organizations =
                collectInvitableScopes(userId, "ORGANIZATION");
        return new InvitableScopesResponse(teams, organizations);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private List<InvitableScopesResponse.InvitableScope> collectInvitableScopes(
            Long userId, String scopeType) {
        List<InvitableScopesResponse.InvitableScope> result = new ArrayList<>();
        // ADMIN/DEPUTY_ADMIN 所属スコープを一括列挙（N+1 回避）した上で INVITE_MEMBERS 権限で絞り込む。
        for (Long scopeId : accessControlService.findAdminOrAboveScopeIds(userId, scopeType)) {
            String roleName = accessControlService.getRoleName(userId, scopeId, scopeType);
            if (!canIssueWithRole(userId, scopeId, scopeType, roleName)) {
                continue;
            }
            String name = resolveScopeName(scopeType, scopeId);
            result.add(new InvitableScopesResponse.InvitableScope(scopeId, name, roleName));
        }
        return result;
    }

    /** 実行ユーザーが当該スコープで招待発行できるか（ADMIN or INVITE_MEMBERS 権限保有 DEPUTY_ADMIN）。 */
    private boolean canIssue(Long userId, Long scopeId, String scopeType) {
        String roleName = accessControlService.getRoleName(userId, scopeId, scopeType);
        return canIssueWithRole(userId, scopeId, scopeType, roleName);
    }

    private boolean canIssueWithRole(Long userId, Long scopeId, String scopeType, String roleName) {
        if ("ADMIN".equals(roleName)) {
            return true;
        }
        if ("DEPUTY_ADMIN".equals(roleName)) {
            return accessControlService.hasPermission(userId, scopeId, scopeType, INVITE_MEMBERS_PERMISSION);
        }
        return false;
    }

    private int resolveExpiresInDays(Integer expiresInDays) {
        if (expiresInDays == null) {
            return DEFAULT_EXPIRES_IN_DAYS;
        }
        if (!ALLOWED_EXPIRES_IN_DAYS.contains(expiresInDays)) {
            // 許容値外は 422（承諾型は必ず期限あり・無期限非対応）。
            throw new BusinessException(RoleErrorCode.ROLE_002, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return expiresInDays;
    }

    /**
     * 付与ロール ID を解決する。null なら当該スコープの MEMBER ロール、
     * 特権ロール（ADMIN/DEPUTY_ADMIN）指定は 422（ROLE_009・C-1 権限昇格封鎖）。
     */
    private Long resolveNonPrivilegedRoleId(Long requestedRoleId) {
        if (requestedRoleId == null) {
            return roleRepository.findByName(DEFAULT_ROLE_NAME)
                    .map(RoleEntity::getId)
                    .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        }
        RoleEntity role = roleRepository.findById(requestedRoleId)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_001));
        if (PRIVILEGED_ROLES.contains(role.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return role.getId();
    }

    /**
     * スコープの存在・アーカイブ検証を行い表示名を返す（404 / 422）。
     *
     * <p>他ドメイン（team/organization）のデータは各ドメイン Service 経由で取得する
     * （CLAUDE.md「ドメイン間のデータ取得は Service のメソッド呼び出し経由」・原則 1・原則 5）。
     * Entity/Repository を直接参照しない。</p>
     */
    private String validateScopeAndResolveName(String scopeType, Long scopeId) {
        if ("TEAM".equals(scopeType)) {
            TeamService.TeamSummary team = teamService.findTeamSummary(scopeId)
                    .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
            if (team.archived()) {
                throw new BusinessException(TeamErrorCode.TEAM_002, HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return team.name();
        }
        OrganizationService.OrganizationSummary org = organizationService.findOrganizationSummary(scopeId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001, HttpStatus.NOT_FOUND));
        if (org.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003, HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return org.name();
    }

    private String resolveScopeName(String scopeType, Long scopeId) {
        if ("TEAM".equals(scopeType)) {
            return teamService.findTeamSummary(scopeId)
                    .map(TeamService.TeamSummary::name).orElse(null);
        }
        return organizationService.findOrganizationSummary(scopeId)
                .map(OrganizationService.OrganizationSummary::name).orElse(null);
    }

    private boolean hasActivePendingInvite(Long targetUserId, String scopeType, Long scopeId) {
        List<InviteTokenEntity> candidates = "TEAM".equals(scopeType)
                ? inviteTokenRepository.findByTargetUserIdAndTeamIdAndRevokedAtIsNull(targetUserId, scopeId)
                : inviteTokenRepository.findByTargetUserIdAndOrganizationIdAndRevokedAtIsNull(targetUserId, scopeId);
        return candidates.stream().anyMatch(InviteTokenEntity::isValid);
    }

    private String normalizeScopeType(String scopeType) {
        return scopeType == null ? null : scopeType.toUpperCase();
    }

    private BusinessException scopeAdminForbidden(String scopeType) {
        return "TEAM".equals(scopeType)
                ? new BusinessException(TeamErrorCode.TEAM_048, HttpStatus.FORBIDDEN)
                : new BusinessException(OrgErrorCode.ORG_048, HttpStatus.FORBIDDEN);
    }

    private BusinessException alreadyMemberConflict(String scopeType) {
        return "TEAM".equals(scopeType)
                ? new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT)
                : new BusinessException(OrgErrorCode.ORG_007, HttpStatus.CONFLICT);
    }
}
