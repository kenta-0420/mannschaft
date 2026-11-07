package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.organization.event.OrganizationMemberAuditEvent;
import com.mannschaft.app.role.RoleErrorCode;
import com.mannschaft.app.role.dto.InviteClaimAuditContext;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.dto.InviteTokenSnapshot;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.event.TeamMemberAuditEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 招待の承諾・辞退を構成する role ドメインの短いトランザクション群（CMP-260826-1457・陣立て書 §3/§4/§9）。
 *
 * <p>{@link InviteAcceptanceFacade}（トランザクションなし）が各ドメインの短TXを順に呼ぶ同期 Saga の、
 * role 側の部品。各メソッドは role の Repository のみを使う（他ドメインには触れない）。
 * 全て REQUIRED なので、外側トランザクション（登録経路など）があればそれに参加し物理1TXになる。</p>
 *
 * <ul>
 *   <li>{@link #inspectScope}: 所属先の射影を行ロックなしで読む（T2）</li>
 *   <li>{@link #claim}: 招待行を最新値でロックして判定し、使用回数を1進めて確保記録を残す（T6）</li>
 *   <li>{@link #releaseClaim}: 付与されなかった確保を戻す（T8。ちょうど1回）</li>
 *   <li>{@link #recordJoined}: 確保を確定し招待監査を発行する（T9）</li>
 *   <li>{@link #decline}: 宛先本人が辞退する（失効）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InviteAcceptanceWriter {

    /** F04.12 特権ロール（承諾型招待では付与不可・join 側の保険ガード用）。 */
    private static final Set<String> PRIVILEGED_INVITE_ROLES = Set.of("ADMIN", "DEPUTY_ADMIN");

    private final InviteTokenRepository inviteTokenRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final InviteScopeStateService scopeStates;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 招待の所属先を射影で返す（T2）。招待行をロックしない読み取りで、Entity は読まない。
     * 招待が無ければ空。
     */
    @Transactional(readOnly = true)
    public Optional<InviteScopeRef> inspectScope(InviteLocator locator) {
        return switch (locator) {
            case InviteLocator.ByToken byToken -> inviteTokenRepository.findScopeByToken(byToken.token());
        };
    }

    /**
     * 承諾の確保（T6）。招待行を最新値でロックしたまま、現行どおりの優先順位で判定する。
     *
     * <ol>
     *   <li>招待が無い → ROLE_002</li>
     *   <li>無効（上限到達 → ROLE_003、期限切れ・失効 → ROLE_002）</li>
     *   <li>宛先違い → 403 ROLE_009</li>
     *   <li>特権ロール → 422 ROLE_009</li>
     *   <li>閉鎖世代が古い → ROLE_002</li>
     *   <li>ブロック → TEAM_004</li>
     *   <li>在籍済み（user_roles ∪ memberships）→ 409 TEAM_003</li>
     *   <li>配置先フォルダ不正 → {@code folderError}</li>
     * </ol>
     *
     * <p>ブロックとフォルダの判定材料は他ドメインから事前に（T3・T5）読んだ結果を受け取り、所定の位置でだけ
     * 投げる。通ったら使用回数を条件付き UPDATE で1進め、確保記録（CLAIMED）を残す。
     * {@code clearAutomatically} は使わず、加算後に管理下の Entity を refresh する。</p>
     *
     * @param blocked     T3 のブロック判定結果
     * @param folderError T5 のフォルダ事前確認で不正だった場合のエラーコード（問題なし・指定なしは null）
     */
    @Transactional
    public InviteClaimTicket claim(InviteLocator locator, Long userId, boolean blocked, ErrorCode folderError) {
        InviteTokenSnapshot token = lockSnapshot(locator)
                .orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        if (!InviteTokenEntity.isValidState(token.revokedAt(), token.expiresAt(), token.maxUses(),
                token.usedCount(), token.dbNow())) {
            if (token.maxUses() != null && token.usedCount() >= token.maxUses()) {
                throw new BusinessException(RoleErrorCode.ROLE_003);
            }
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        // F04.12 宛先照合（IDOR 防止）: 宛先付きトークンは指名相手のみ承諾可。NULL（共有リンク型）は照合スキップ。
        if (token.targetUserId() != null && !token.targetUserId().equals(userId)) {
            throw locator.targetMismatch();
        }

        // F04.12 特権ロール付与の保険ガード（C-1）。
        RoleEntity grantRole = roleRepository.findById(token.roleId()).orElse(null);
        if (grantRole != null && PRIVILEGED_INVITE_ROLES.contains(grantRole.getName())) {
            throw new BusinessException(RoleErrorCode.ROLE_009, HttpStatus.UNPROCESSABLE_ENTITY);
        }

        String scopeType = token.scopeType();
        Long scopeId = token.scopeId();

        // 判定点は親専属のfresh primary nonlocking read（既存の REQUIRES_NEW）。ロックなしで待ちが無い。
        if (!scopeStates.isCurrent(scopeType, scopeId, token.scopeClosureGeneration())) {
            throw new BusinessException(RoleErrorCode.ROLE_002);
        }

        if (blocked) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }

        // CMP-027: user_roles ∪ memberships の在籍で判定（SUPPORTER・memberships のみも在籍）。
        boolean alreadyJoined = "TEAM".equals(scopeType)
                ? userRoleRepository.existsByUserIdAndTeamId(userId, scopeId)
                : userRoleRepository.existsByUserIdAndOrganizationId(userId, scopeId);
        if (alreadyJoined) {
            throw new BusinessException(TeamErrorCode.TEAM_003, HttpStatus.CONFLICT);
        }

        if (folderError != null) {
            throw new BusinessException(folderError);
        }

        // 失効または上限到達なら 0 行。招待行をロック済みなので、ここで 0 行になるのは異常系のみ。
        if (inviteTokenRepository.incrementUsedCountIfAvailable(token.id()) != 1) {
            throw new BusinessException(RoleErrorCode.ROLE_003);
        }
        inviteTokenRepository.refreshIfManaged(token.id());

        UUID claimId = inviteTokenRepository.insertClaim(token.id(), userId);
        log.info("招待の使用回数を確保: claimId={}, tokenId={}, userId={}", claimId, token.id(), userId);
        return new InviteClaimTicket(claimId, token.id(), scopeType, scopeId, token.roleId(), token.createdBy());
    }

    /**
     * 付与されなかった確保を戻す（T8）。CLAIMED→RELEASED が1行のときだけ使用回数を1戻す。
     * 2回目以降・GRANTED・RELEASED への呼び出しは何もしない（冪等）。2本のUPDATEは1TXで、
     * 2本目が失敗すれば全巻き戻しで CLAIMED のまま残る。
     *
     * @throws IllegalStateException claim が存在しない（設計上起こりえない。ERROR を記録する）
     */
    @Transactional
    public void releaseClaim(UUID claimId) {
        if (inviteTokenRepository.markClaimReleased(claimId) == 1) {
            Long tokenId = inviteTokenRepository.findClaimTokenId(claimId)
                    .orElseThrow(() -> invalidClaim("release", claimId, "claim の招待が引けない"));
            if (inviteTokenRepository.decrementUsedCount(tokenId) != 1) {
                // used_count=0 の CLAIMED は不変式違反。巻き戻して CLAIMED のまま監視に出す。
                throw invalidClaim("release", claimId, "使用回数を戻せない（既に0）");
            }
            inviteTokenRepository.refreshIfManaged(tokenId);
            log.info("招待の使用回数を戻した: claimId={}, tokenId={}", claimId, tokenId);
            return;
        }
        String status = inviteTokenRepository.findClaimStatusLocked(claimId)
                .orElseThrow(() -> invalidClaim("release", claimId, "claim が存在しない"));
        log.info("claim の補償は無効（状態 {} のため何もしない）: claimId={}", status, claimId);
    }

    /**
     * 確保を確定する（T9）。CLAIMED→GRANTED が1行のときだけ招待監査イベントを発行する。
     * 既に GRANTED なら何もしない（重複・並行でも監査は1回）。
     *
     * @throws IllegalStateException claim が存在しない、または RELEASED（設計上起こりえない。ERROR を記録する）
     */
    @Transactional
    public void recordJoined(UUID claimId) {
        if (inviteTokenRepository.markClaimGranted(claimId) == 1) {
            InviteClaimAuditContext context = inviteTokenRepository.findClaimAuditContext(claimId)
                    .orElseThrow(() -> invalidClaim("recordJoined", claimId, "claim の招待が引けない"));
            publishInviteAudit(context);
            return;
        }
        String status = inviteTokenRepository.findClaimStatusLocked(claimId)
                .orElseThrow(() -> invalidClaim("recordJoined", claimId, "claim が存在しない"));
        if (!"GRANTED".equals(status)) {
            throw invalidClaim("recordJoined", claimId, "確定できない状態: " + status);
        }
    }

    /**
     * 宛先本人が招待を辞退する（F04.12）。招待行を最新値でロックし、宛先違いは 403、既に失効なら
     * 何もしない（冪等）。新規に辞退したときだけ {@code newlyDeclined=true} を返す。
     */
    @Transactional
    public InviteDeclineResult decline(InviteLocator locator, Long userId) {
        InviteTokenEntity token = (switch (locator) {
            case InviteLocator.ByToken byToken -> inviteTokenRepository.lockFreshByToken(byToken.token());
        }).orElseThrow(() -> new BusinessException(RoleErrorCode.ROLE_002));

        Long targetUserId = token.getTargetUserId();
        // 宛先本人のみ辞退可（宛先なし共有リンク型は辞退の概念を持たない）。宛先不一致は 403 ROLE_009（IDOR 防止）。
        if (targetUserId == null || !targetUserId.equals(userId)) {
            throw locator.targetMismatch();
        }

        // 既に失効済み（辞退/取消/補償）なら冪等に扱う。
        if (token.getRevokedAt() != null) {
            return new InviteDeclineResult(false, token.getId(), targetUserId, token.getTeamId(),
                    token.getOrganizationId());
        }

        token.revoke(); // revoked_at を立てる。used_count は updatable=false なので書き戻さない。
        log.info("承諾型招待を辞退（DECLINED）: tokenId={}, userId={}", token.getId(), userId);
        return new InviteDeclineResult(true, token.getId(), targetUserId, token.getTeamId(),
                token.getOrganizationId());
    }

    private Optional<InviteTokenSnapshot> lockSnapshot(InviteLocator locator) {
        return switch (locator) {
            case InviteLocator.ByToken byToken -> inviteTokenRepository.lockSnapshotByToken(byToken.token());
        };
    }

    /** 招待監査（T9 由来）。membership 側の JOINED は T7 の {@code MembershipService.join} が別途発行する。 */
    private void publishInviteAudit(InviteClaimAuditContext context) {
        if (context.teamId() != null) {
            eventPublisher.publishEvent(new TeamMemberAuditEvent(context.invitedBy(), context.userId(),
                    context.teamId(), TeamMemberAuditEvent.SubType.INVITED));
        } else {
            eventPublisher.publishEvent(new OrganizationMemberAuditEvent(context.invitedBy(), context.userId(),
                    context.organizationId(), OrganizationMemberAuditEvent.SubType.JOINED));
        }
    }

    private IllegalStateException invalidClaim(String operation, UUID claimId, String reason) {
        log.error("招待 claim の不正な遷移: operation={}, claimId={}, reason={}", operation, claimId, reason);
        return new IllegalStateException("招待 claim の不正な遷移 " + operation + ": " + claimId + " (" + reason + ")");
    }
}
