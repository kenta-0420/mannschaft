package com.mannschaft.app.role.service;

import com.mannschaft.app.role.dto.InviteScopeState;

import java.util.Optional;

/**
 * 個人招待の判定用読取境界。実装は親scopeのdomainが所有し、role更新TXには参加しない。
 *
 * <p>受諾は新しいprimary TXのnonlocking readを判定点とする。発行後の共有lockによる再確認は
 * role発行TXがcommitしてからだけ呼ぶ。interfaceの静的解析上の未展開ではなく、物理TX分離を実DB試験で守る。</p>
 */
public interface InviteScopeStatePort {
    String scopeType();

    Optional<InviteScopeState> readCommittedStateForInviteDecision(Long scopeId);

    Optional<InviteScopeState> recheckCommittedStateAfterInviteIssue(Long scopeId);
}
