package com.mannschaft.app.chat.event;

import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * メンバーシップ離脱時に、同じスコープのチャット参加行を整合させるリスナー。
 *
 * <p>同期処理で削除失敗を発行元トランザクションへ伝播する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMembershipRevokeListener {

    private final ChatChannelMemberRepository chatChannelMemberRepository;

    /**
     * TEAM / ORGANIZATION の REMOVED イベントだけを対象に、同一スコープの参加行を削除する。
     *
     * <p>{@link Transactional#propagation()} を {@link Propagation#MANDATORY} に固定し、発行元の
     * 離脱トランザクションなしでは実行させない。DM・GROUP_DM、および village / event / tournament のチャネルはクエリの種別・
     * スコープ条件の双方で除外する。例外は捕捉しないため、失敗時は発行元トランザクションが
     * ロールバックされる。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "離脱した利用者のスコープチャット参加行を同期削除し、認可状態を membership と一致させるため")
    @EventListener(condition = "#event.changeType().name() == 'REMOVED'")
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMembershipChanged(MembershipChangedEvent event) {
        if (event.changeType() != MembershipChangedEvent.ChangeType.REMOVED) {
            return;
        }

        if ("TEAM".equalsIgnoreCase(event.scopeType())) {
            int deleted = chatChannelMemberRepository.deleteByUserIdAndTeamScopeChannels(
                    event.userId(), event.scopeId());
            log.info("チーム離脱に伴うチャット参加行削除: userId={}, teamId={}, deletedCount={}",
                    event.userId(), event.scopeId(), deleted);
            return;
        }

        if ("ORGANIZATION".equalsIgnoreCase(event.scopeType())) {
            int deleted = chatChannelMemberRepository.deleteByUserIdAndOrganizationScopeChannels(
                    event.userId(), event.scopeId());
            log.info("組織離脱に伴うチャット参加行削除: userId={}, organizationId={}, deletedCount={}",
                    event.userId(), event.scopeId(), deleted);
        }
    }
}
