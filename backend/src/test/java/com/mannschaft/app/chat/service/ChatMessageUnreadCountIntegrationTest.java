package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.dto.SendMessageRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【未読カウント根治・red先行】チャット未読カウント結線の結合テスト。
 *
 * <p>実機E2Eで実証済みの障害: {@code ChatChannelMemberEntity.incrementUnreadCount()} が
 * プロダクションコード全域で呼び出し元ゼロの dead code だったため、メッセージ送信で受信者の
 * {@code chat_channel_members.unread_count} が一切増加せず、
 * {@link com.mannschaft.app.chat.service.InquiryAlertQueryService} の未読集計が常に 0 を返す
 * （業務アラート「問い合わせ」バッジが構造的に動かない）構造的バグを実 MySQL で検証する。</p>
 *
 * <p>本 IT は「メッセージ送信 → {@code chat_channel_members.unread_count} 一括インクリメント
 * → {@code sumUnreadCountByUserIdAndChannelIds} に反映される」までを実サービス
 * （{@link ChatMessageService#sendMessage}）・実リポジトリ・実 DB で通す。</p>
 */
@Transactional
@DisplayName("チャット未読カウント結線 結合テスト（送信→sum反映）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatMessageUnreadCountIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatMemberService chatMemberService;

    @Autowired
    private ChatChannelRepository channelRepository;

    @Autowired
    private ChatChannelMemberRepository memberRepository;

    private static final Long SENDER_ID = 970_001L;
    private static final Long RECEIVER_1_ID = 970_002L;
    private static final Long RECEIVER_2_ID = 970_003L;

    private Long channelId;

    @BeforeEach
    void setUp() {
        ChatChannelEntity channel = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.TEAM_PUBLIC)
                .teamId(970_100L)
                .name("未読カウント検証チャンネル")
                .createdBy(SENDER_ID)
                .build());
        channelId = channel.getId();

        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(SENDER_ID).role(ChannelMemberRole.OWNER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(RECEIVER_1_ID).role(ChannelMemberRole.MEMBER).build());
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(RECEIVER_2_ID).role(ChannelMemberRole.MEMBER).build());
    }

    @Test
    @DisplayName("【red先行】メッセージ送信で送信者以外の全メンバーのunread_countが+1され、sumUnreadCountByUserIdAndChannelIdsに反映される")
    void メッセージ送信で受信者の未読カウントが増えてsumに反映される() {
        // given: 送信前は全員 unread_count = 0
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_1_ID, List.of(channelId)))
                .isEqualTo(0);
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_2_ID, List.of(channelId)))
                .isEqualTo(0);

        // when: SENDER_ID がメッセージを送信する
        SendMessageRequest req = new SendMessageRequest("未読カウント検証メッセージ", null, null, null);
        chatMessageService.sendMessage(channelId, req, SENDER_ID);

        // then: 送信者以外の受信者2名は unread_count が +1 され、sum に反映される
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_1_ID, List.of(channelId)))
                .isEqualTo(1);
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_2_ID, List.of(channelId)))
                .isEqualTo(1);
        // 送信者自身の unread_count は増えない
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(SENDER_ID, List.of(channelId)))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("【非回帰】複数メッセージ送信でunread_countが累積し、既読処理（markAsRead）でリセットされる")
    void 複数送信で累積し既読処理でリセットされる() {
        // given: 2 通のメッセージを送信する
        chatMessageService.sendMessage(channelId,
                new SendMessageRequest("1通目", null, null, null), SENDER_ID);
        chatMessageService.sendMessage(channelId,
                new SendMessageRequest("2通目", null, null, null), SENDER_ID);

        // then: 受信者の unread_count は 2 に累積している
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_1_ID, List.of(channelId)))
                .isEqualTo(2);

        // when: 受信者が既読処理（ChatMemberService#markAsRead）を実行する
        chatMemberService.markAsRead(channelId, RECEIVER_1_ID);

        // then: 既読処理を行った受信者の unread_count は 0 にリセットされる
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_1_ID, List.of(channelId)))
                .isEqualTo(0);
        // 既読処理をしていない受信者は影響を受けない
        assertThat(memberRepository.sumUnreadCountByUserIdAndChannelIds(RECEIVER_2_ID, List.of(channelId)))
                .isEqualTo(2);
    }
}
