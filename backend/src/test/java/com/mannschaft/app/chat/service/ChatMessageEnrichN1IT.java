package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.dto.AttachmentResponse;
import com.mannschaft.app.chat.dto.MessageResponse;
import com.mannschaft.app.chat.dto.ReactionResponse;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageAttachmentEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.entity.ChatMessageReactionEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageAttachmentRepository;
import com.mannschaft.app.chat.repository.ChatMessageReactionRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.N1SeedSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * チャットメッセージ一覧の付帯情報解決（ChatMessageService#enrichMessages）の N+1 解消を固定する試練
 * （CMP-261103-1225 / #4270）。
 */
@Transactional
@DisplayName("ChatMessageService#enrichMessages の N+1 解消")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatMessageEnrichN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ChatMessageService service;
    @Autowired
    private ChatChannelRepository channelRepository;
    @Autowired
    private ChatChannelMemberRepository memberRepository;
    @Autowired
    private ChatMessageRepository messageRepository;
    @Autowired
    private ChatMessageAttachmentRepository attachmentRepository;
    @Autowired
    private ChatMessageReactionRepository reactionRepository;
    @PersistenceContext
    private EntityManager em;

    private N1SeedSupport seed;

    @BeforeEach
    void setUp() {
        seed = new N1SeedSupport(em);
    }

    private Long channelWith(Long... members) {
        ChatChannelEntity ch = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.GROUP_DM).name("n1-msg").build());
        for (Long m : members) {
            memberRepository.save(ChatChannelMemberEntity.builder().channelId(ch.getId()).userId(m).build());
        }
        return ch.getId();
    }

    private ChatMessageEntity message(Long channelId, Long senderId, String body) {
        return messageRepository.save(ChatMessageEntity.builder()
                .channelId(channelId).senderId(senderId).body(body).build());
    }

    private void attach(Long messageId, String name) {
        attachmentRepository.save(ChatMessageAttachmentEntity.builder()
                .messageId(messageId).fileKey("k/" + messageId + "/" + name).fileName(name)
                .fileSize(1L).contentType("image/png").build());
    }

    private void react(Long messageId, Long userId, String emoji) {
        reactionRepository.save(ChatMessageReactionEntity.builder()
                .messageId(messageId).userId(userId).emoji(emoji).build());
    }

    private long measure(Statistics s, Runnable r) {
        em.clear();
        s.clear();
        r.run();
        return s.getPrepareStatementCount();
    }

    @Test
    @DisplayName("AC-1/AC-2/AC-6/AC-7: メッセージ数を増やしても SQL 数が一定で、添付・リアクション・送信者が等価")
    void sqlCountIndependentOfMessageCount() {
        Long viewer = seed.user("msg-viewer");
        Long alice = seed.user("msg-alice");
        Long gone = seed.user("msg-gone");

        Long smallCh = channelWith(viewer, alice);
        ChatMessageEntity sm = message(smallCh, alice, "hello");
        attach(sm.getId(), "a.png");
        react(sm.getId(), viewer, "👍");

        Long largeCh = channelWith(viewer, alice, gone);
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Long sender = i % 3 == 0 ? alice : (i % 3 == 1 ? gone : null);
            ChatMessageEntity m = message(largeCh, sender, "m" + i);
            ids.add(m.getId());
            for (int a = 0; a < i % 3; a++) {
                attach(m.getId(), "f" + a + ".png");
            }
            if (i % 2 == 0) {
                react(m.getId(), viewer, "👍");
                react(m.getId(), alice, "🎉");
            }
        }
        seed.softDeleteUser(gone);
        // 他チャンネルのメッセージ（混入してはならない）
        Long foreignCh = channelWith(alice);
        ChatMessageEntity fm = message(foreignCh, alice, "foreign");
        attach(fm.getId(), "x.png");
        react(fm.getId(), alice, "❌");
        seed.flushAndClear();

        Statistics s = seed.stats();
        @SuppressWarnings("unchecked")
        CursorPagedResponse<MessageResponse>[] res = new CursorPagedResponse[2];
        long smallCount = measure(s, () -> res[0] = service.listMessages(smallCh, viewer, null, 50, null));
        long largeCount = measure(s, () -> res[1] = service.listMessages(largeCh, viewer, null, 50, null));
        System.out.println("N1 chat-messages SQL small=" + smallCount + " large=" + largeCount);

        assertThat(res[0].getData()).hasSize(1);
        assertThat(res[0].getData().get(0).getEngagement().attachments())
                .extracting(AttachmentResponse::getFileName).containsExactly("a.png");

        List<MessageResponse> large = res[1].getData();
        assertThat(large).hasSize(12);
        Map<Long, MessageResponse> byId = large.stream()
                .collect(Collectors.toMap(MessageResponse::getId, Function.identity()));
        for (int i = 0; i < 12; i++) {
            MessageResponse r = byId.get(ids.get(i));
            assertThat(r.getEngagement().attachments()).hasSize(i % 3);
            assertThat(r.getEngagement().attachments()).allMatch(a -> a.getMessageId().equals(r.getId()));
            assertThat(r.getEngagement().reactions()).extracting(ReactionResponse::getEmoji)
                    .containsExactlyElementsOf(i % 2 == 0 ? List.of("👍", "🎉") : List.of());
            if (i % 3 == 2) {
                assertThat(r.getSender()).isNull();
            } else {
                assertThat(r.getSender()).isNotNull();
                assertThat(r.getSender().id()).isEqualTo(i % 3 == 0 ? alice : gone);
            }
        }
        assertThat(largeCount).as("メッセージ数に依存しない SQL 数").isEqualTo(smallCount);
    }

    @Test
    @DisplayName("AC-3: メッセージなしでも空 IN でエラーにならない")
    void emptyChannel() {
        Long viewer = seed.user("msg-empty");
        Long ch = channelWith(viewer);
        seed.flushAndClear();
        assertThat(service.listMessages(ch, viewer, null, 50, null).getData()).isEmpty();
    }
}
