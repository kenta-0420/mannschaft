package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * メッセージ削除時の未読数の減算（{@code decrementUnreadCountForUnreadersOf}）が、
 * 「そのメンバーが未読として数えた投稿」だけを対象にすることを固定する（CMP-261105-1507）。
 */
@Transactional
@DisplayName("メッセージ削除時の未読数の減算")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatUnreadDecrementOnDeleteIT extends AbstractMySqlIntegrationTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 1, 9, 0);

    @Autowired
    private ChatChannelRepository channelRepository;
    @Autowired
    private ChatChannelMemberRepository memberRepository;
    @PersistenceContext
    private EntityManager em;

    private Long channel() {
        return channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.GROUP_DM).name("unread-dec").build()).getId();
    }

    private Long member(Long channelId, long userId, int unread, LocalDateTime joinedAt, LocalDateTime lastReadAt) {
        return memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).unreadCount(unread)
                .joinedAt(joinedAt).lastReadAt(lastReadAt).build()).getId();
    }

    private int unreadOf(Long memberId) {
        em.flush();
        em.clear();
        return memberRepository.findById(memberId).orElseThrow().getUnreadCount();
    }

    @Test
    @DisplayName("参加後に届き、まだ読んでいない投稿の削除では未読数が 1 減る")
    void 未読の投稿の削除で減る() {
        Long ch = channel();
        Long m = member(ch, 9001L, 2, T0, null);

        memberRepository.decrementUnreadCountForUnreadersOf(ch, 9000L, T0.plusHours(1));

        assertThat(unreadOf(m)).isEqualTo(1);
    }

    @Test
    @DisplayName("途中参加のメンバーは、参加前の古い投稿の削除で新着の未読数を減らされない")
    void 参加前の投稿の削除では減らない() {
        Long ch = channel();
        Long m = member(ch, 9002L, 1, T0.plusDays(1), null);

        memberRepository.decrementUnreadCountForUnreadersOf(ch, 9000L, T0);

        assertThat(unreadOf(m)).isEqualTo(1);
    }

    @Test
    @DisplayName("既読済みの投稿の削除では未読数が減らない")
    void 既読済みの投稿の削除では減らない() {
        Long ch = channel();
        Long m = member(ch, 9003L, 1, T0, T0.plusHours(2));

        memberRepository.decrementUnreadCountForUnreadersOf(ch, 9000L, T0.plusHours(1));

        assertThat(unreadOf(m)).isEqualTo(1);
    }

    @Test
    @DisplayName("送信者本人と、未読数 0 のメンバーは対象外（0 未満にもならない）")
    void 送信者と未読ゼロは対象外() {
        Long ch = channel();
        Long sender = member(ch, 9000L, 1, T0, null);
        Long zero = member(ch, 9004L, 0, T0, null);

        memberRepository.decrementUnreadCountForUnreadersOf(ch, 9000L, T0.plusHours(1));

        assertThat(unreadOf(sender)).isEqualTo(1);
        assertThat(unreadOf(zero)).isZero();
    }
}
