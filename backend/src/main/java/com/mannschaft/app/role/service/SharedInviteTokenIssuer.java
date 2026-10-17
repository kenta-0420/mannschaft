package com.mannschaft.app.role.service;

import com.mannschaft.app.role.dto.InviteTokenResponse;
import com.mannschaft.app.role.dto.SharedInviteTokenCommand;
import com.mannschaft.app.role.entity.InviteTokenEntity;
import com.mannschaft.app.role.repository.InviteTokenRepository;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.event.TeamInviteTokenCreatedEvent;
import com.mannschaft.app.organization.event.OrganizationInviteTokenCreatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.LocalDateTime;

/** 共有招待のrole専属writer。親状態は読まず、親TXとtoken更新TXを物理的に分ける。 */
@Service
@RequiredArgsConstructor
public class SharedInviteTokenIssuer {
    private final InviteTokenRepository tokens;
    private final ApplicationEventPublisher events;

    @Transactional
    public InviteTokenResponse issue(SharedInviteTokenCommand command) {
        var builder = InviteTokenEntity.builder().token(UUID.randomUUID().toString())
                .roleId(command.roleId()).createdBy(command.createdBy())
                .expiresAt(command.expiresAt() == null ? null
                        : LocalDateTime.ofInstant(command.expiresAt(), UserZoneLocalDateTimeParser.SERVER_ZONE))
                .maxUses(command.maxUses()).usedCount(0).scopeClosureGeneration(command.scopeClosureGeneration());
        boolean team = "TEAM".equals(command.scopeType());
        if (team) {
            builder.teamId(command.scopeId());
        } else {
            builder.organizationId(command.scopeId());
        }
        var token = tokens.save(builder.build());
        if (team) {
            events.publishEvent(new TeamInviteTokenCreatedEvent(command.createdBy(), command.scopeId(), token.getId()));
        } else {
            events.publishEvent(new OrganizationInviteTokenCreatedEvent(command.createdBy(), command.scopeId(), token.getId()));
        }
        return new InviteTokenResponse(token.getId(), token.getToken(), command.roleName(), token.getExpiresAt(),
                token.getMaxUses(), token.getUsedCount(), token.getRevokedAt(), token.getCreatedAt());
    }

    /** 発行後の親閉鎖検出時は監査可能なtoken行を残し、既失効日時を上書きしない。 */
    @Transactional
    public void revokeForCompensation(Long id) {
        tokens.findByIdForUpdate(id).ifPresent(InviteTokenEntity::revoke);
    }
}
