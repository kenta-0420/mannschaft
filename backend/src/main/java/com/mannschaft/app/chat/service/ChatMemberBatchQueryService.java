package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * チャンネルメンバーの一括照会サービス（CMP-261103-1225 / #4270 の N+1 解消用・read-only）。
 *
 * <p>ダッシュボード・業務アラートなど chat 以外のドメインが、チャンネルごとに
 * {@link ChatChannelMemberRepository} を呼んでいた箇所（チャンネル数比例の SQL）を、
 * チャンネル群まとめて 1 クエリで引けるようにする。他ドメインから chat の Repository を
 * 直接参照しない（番人 D-5）ため、本 Service を経由させる。戻り値は ID と数値だけで、
 * Entity をシグネチャに出さない（番人 D-1 API 境界）。</p>
 *
 * <p>入力が空なら SQL を発行せず空 Map を返す（{@code IN ()} を発行しない）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMemberBatchQueryService {

    private final ChatChannelMemberRepository chatChannelMemberRepository;

    /**
     * 指定ユーザーが参加しているチャンネルについて、チャンネル ID → 未読数を返す。
     *
     * <p>参加していないチャンネルはキーを持たない（呼び出し側はキーの有無で参加判定できる）。
     * 単票版 {@code findByChannelIdAndUserId(channelId, userId)} をチャンネルごとに呼ぶのと同じ結果になる。</p>
     *
     * @param userId     ユーザー ID
     * @param channelIds 対象チャンネル ID 群
     * @return チャンネル ID → 未読数（参加チャンネルのみ）
     */
    public Map<Long, Integer> findUnreadCountByChannelForMember(Long userId, Collection<Long> channelIds) {
        Map<Long, Integer> result = new HashMap<>();
        if (userId == null || channelIds == null || channelIds.isEmpty()) {
            return result;
        }
        for (ChatChannelMemberEntity m : chatChannelMemberRepository.findByUserIdAndChannelIdIn(userId, channelIds)) {
            result.putIfAbsent(m.getChannelId(), m.getUnreadCount());
        }
        return result;
    }

    /**
     * チャンネルごとに「指定ユーザー以外で参加日時が最も古いメンバー」のユーザー ID を返す（DM 相手の解決用）。
     *
     * <p>単票版 {@code findByChannelIdOrderByJoinedAtAsc(channelId)} から自分以外の先頭を採るのと同じ規則で、
     * 同時刻参加は ID の小さい方を採る。他メンバーがいないチャンネルはキーを持たない。</p>
     *
     * @param channelIds 対象チャンネル ID 群
     * @param userId     除外するユーザー ID（呼出ユーザー）
     * @return チャンネル ID → 相手ユーザー ID
     */
    public Map<Long, Long> findFirstOtherMemberUserIdByChannel(Collection<Long> channelIds, Long userId) {
        Map<Long, Long> result = new HashMap<>();
        if (channelIds == null || channelIds.isEmpty()) {
            return result;
        }
        for (ChatChannelMemberEntity m : chatChannelMemberRepository
                .findByChannelIdInAndUserIdNotOrderByJoinedAtAscIdAsc(channelIds, userId)) {
            result.putIfAbsent(m.getChannelId(), m.getUserId());
        }
        return result;
    }
}
