package com.mannschaft.app.team.service;

import com.mannschaft.app.notification.fanout.FanoutEnqueueCommand;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.role.fanout.OrganizationAdminsFanoutRecipientSource;
import com.mannschaft.app.role.fanout.TeamAffiliationOperatorsFanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * {@link TeamAffiliationNotifier} の実装。fan-out ジョブを呼び出し元のトランザクションで enqueue する（F01.2.1 §6.7）。
 *
 * <p>受信者ソースは2つ（どちらも受信者が数名のため {@code shard_count=1} 固定）:
 * {@code ORGANIZATION_ADMINS}（{@code scope_ref}=組織 ID）と
 * {@code TEAM_AFFILIATION_OPS}（{@code scope_ref}=チーム ID）。</p>
 *
 * <p>冪等キーは {@code UUID.nameUUIDFromBytes("F01.2.1:" + notificationType + ":" + membershipId)}。
 * membershipId は AUTO_INCREMENT で再利用されないため、同じ操作の二重 enqueue だけが1件に収束する。
 * 新版の enqueue は冪等 SQL（{@code INSERT ... ON DUPLICATE KEY UPDATE id = id}）で登録するので、
 * 重複しても例外を投げず、呼び出し側のトランザクションを rollback-only にしない。</p>
 *
 * <p>本クラス自身は {@code @Transactional} を持たない（トランザクションは呼び出し元のもの。
 * enqueue 側が {@code Propagation.MANDATORY} で進行中のトランザクションを要求する）。</p>
 */
@Component
@RequiredArgsConstructor
public class FanoutTeamAffiliationNotifier implements TeamAffiliationNotifier {

    private final NotificationFanoutJobService fanoutJobService;

    @Override
    public void enqueue(TeamAffiliationNotice notice) {
        fanoutJobService.enqueueInCurrentTransaction(toCommand(notice));
    }

    @Override
    public void enqueueAfterCommit(TeamAffiliationNotice notice) {
        fanoutJobService.enqueueInOwnTransaction(toCommand(notice));
    }

    private static FanoutEnqueueCommand toCommand(TeamAffiliationNotice notice) {
        String scopeType = switch (notice.recipientScope()) {
            case ORGANIZATION_ADMINS -> OrganizationAdminsFanoutRecipientSource.SCOPE_TYPE;
            case TEAM_AFFILIATION_OPERATORS -> TeamAffiliationOperatorsFanoutRecipientSource.SCOPE_TYPE;
        };
        return new FanoutEnqueueCommand(
                scopeType,
                String.valueOf(notice.recipientScopeId()),
                notice.notificationType().name(),
                idempotencyKey(notice),
                notice.organizationId(),
                notice.notificationType().getPriority(),
                notice.actorUserId(),
                notice.notificationType().getSourceType(),
                notice.membershipId(),
                notice.actionUrl(),
                false,
                notice.messageKind(),
                notice.messageArgs(),
                FanoutEnqueueCommand.ShardMode.FIXED_SINGLE);
    }

    /** 冪等キー（§6.7）。同じ通知種別・同じ加盟 ID の二重 enqueue を1件に収束させる。 */
    static UUID idempotencyKey(TeamAffiliationNotice notice) {
        return UUID.nameUUIDFromBytes(
                ("F01.2.1:" + notice.notificationType().name() + ":" + notice.membershipId())
                        .getBytes(StandardCharsets.UTF_8));
    }
}
