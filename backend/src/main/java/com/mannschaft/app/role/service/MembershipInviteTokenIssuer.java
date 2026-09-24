package com.mannschaft.app.role.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.role.dto.MembershipInviteIssuedToken;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 承諾型招待の宛先付きトークン発行・補償失効を担う role ドメイン専属サービス（F04.12）。
 *
 * <p>越境トランザクション方針（原則5・設計書 §5）の要。発行フローの主体である
 * {@link MembershipInviteService} は複数ドメインの Repository を単一 {@code @Transactional} で
 * またがない。トークン発行（role ドメイン）を本サービスの独立した {@code @Transactional} メソッドに
 * 閉じ、カード投稿（chat ドメイン）は別サービスの別 {@code @Transactional} に閉じる。</p>
 *
 * <p>本番では発行 → カード投稿がそれぞれ独立コミットされる Saga となり、カード投稿失敗時は
 * {@link #revokeForCompensation(Long)} でトークンを失効して宙に浮いた PENDING を残さない。
 * 契約テスト（{@code @Transactional}）配下では周囲のテスト tx に参加するため、同一 tx で可視となる。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MembershipInviteTokenIssuer {

    private final InviteTokenRepository inviteTokenRepository;
    private final AuditLogService auditLogService;

    /**
     * 宛先付き承諾型トークンを発行する（{@code target_user_id} 非 NULL・{@code max_uses = 1}）。
     *
     * @param scopeType    スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
     * @param scopeId      招待先チーム/組織 ID
     * @param roleId       付与ロール ID（非特権のみ・呼出側で検証済み）
     * @param targetUserId 宛先ユーザー ID（DM 相手）
     * @param createdBy    発行者ユーザー ID
     * @param expiresAt    有効期限
     * @return 発行済みトークン
     */
    @Transactional
    public MembershipInviteIssuedToken issue(String scopeType, Long scopeId, Long roleId,
                                             Long targetUserId, Long createdBy, LocalDateTime expiresAt) {
        var builder = InviteTokenEntity.builder()
                .token(UUID.randomUUID().toString())
                .roleId(roleId)
                .createdBy(createdBy)
                .expiresAt(expiresAt)
                .maxUses(1)
                .usedCount(0)
                .targetUserId(targetUserId);
        if ("TEAM".equals(scopeType)) {
            builder.teamId(scopeId);
        } else {
            builder.organizationId(scopeId);
        }
        InviteTokenEntity token = inviteTokenRepository.save(builder.build());
        log.info("承諾型招待トークン発行: tokenId={}, scopeType={}, scopeId={}, targetUserId={}",
                token.getId(), scopeType, scopeId, targetUserId);
        return new MembershipInviteIssuedToken(token.getId(), token.getToken());
    }

    /**
     * カード投稿失敗時の補償: 発行済みトークンを失効させる（理由 COMPENSATED・宙に浮く PENDING を残さない）。
     *
     * @param tokenId 失効対象トークン ID
     */
    @Transactional
    public void revokeForCompensation(Long tokenId) {
        inviteTokenRepository.findById(tokenId).ifPresent(token -> {
            token.revoke();
            inviteTokenRepository.save(token);
            boolean team = token.getTeamId() != null;
            auditLogService.record(
                    (team
                            ? AuditEventType.TEAM_MEMBERSHIP_INVITE_COMPENSATED
                            : AuditEventType.ORGANIZATION_MEMBERSHIP_INVITE_COMPENSATED).name(),
                    token.getCreatedBy(),
                    token.getTargetUserId(),
                    token.getTeamId(),
                    token.getOrganizationId(),
                    null,
                    null,
                    null,
                    "{\"token_id\":" + tokenId + ",\"reason\":\"COMPENSATED\"}");
            log.warn("承諾型招待トークンを補償失効（カード投稿失敗）: tokenId={}", tokenId);
        });
    }
}
