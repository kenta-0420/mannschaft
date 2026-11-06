package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 同一秒に投稿されたチャットメッセージの並び順・カーソル境界の契約テスト（CMP-261105-1507）。
 *
 * <p>{@code created_at} は秒精度のため、同秒の複数メッセージは {@code id} を第2キーにしないと
 * 順序が不定になり、カーソルページ境界で抜け・重なりが生じる。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("チャットメッセージ 同一秒の並び順・カーソル境界 IT")
class ChatMessageSameSecondOrderIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ChatChannelRepository channelRepository;

    @Autowired
    private ChatMessageRepository messageRepository;

    @PersistenceContext
    private EntityManager em;

    private Long channelId;
    /** 全件同一 created_at。id 昇順。 */
    private List<Long> ids;

    @BeforeEach
    void setUp() {
        channelId = channelRepository.save(ChatChannelEntity.builder()
                .channelType(ChannelType.DM)
                .createdBy(1L)
                .build()).getId();

        ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(messageRepository.save(ChatMessageEntity.builder()
                    .channelId(channelId).senderId(1L).body("同秒 " + i).build()).getId());
        }
        em.flush();
        // 全件を同一秒に揃える
        em.createNativeQuery("UPDATE chat_messages SET created_at = '2026-01-01 00:00:00' WHERE channel_id = :c")
                .setParameter("c", channelId).executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("最新順一覧は同秒でも id 降順")
    void 最新順は同秒でもid降順() {
        List<Long> got = messageRepository
                .findByChannelIdOrderByCreatedAtDescIdDesc(channelId, PageRequest.of(0, 10))
                .stream().map(ChatMessageEntity::getId).toList();
        assertThat(got).containsExactly(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("before カーソルのページ境界で抜け・重なりが無い")
    void beforeカーソルで抜け重なり無し() {
        List<Long> all = new ArrayList<>();
        List<ChatMessageEntity> page = messageRepository
                .findByChannelIdOrderByCreatedAtDescIdDesc(channelId, PageRequest.of(0, 2));
        while (!page.isEmpty()) {
            page.forEach(m -> all.add(m.getId()));
            Long cursor = page.get(page.size() - 1).getId();
            page = messageRepository.findByChannelIdAndIdLessThan(channelId, cursor, PageRequest.of(0, 2));
        }
        assertThat(all).containsExactly(ids.get(4), ids.get(3), ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("after カーソルは同秒でも id 昇順で境界を切る")
    void afterカーソルで抜け重なり無し() {
        List<Long> all = new ArrayList<>();
        Long cursor = ids.get(0);
        List<ChatMessageEntity> page = messageRepository.findMessagesAfterCursor(channelId, cursor, PageRequest.of(0, 2));
        while (!page.isEmpty()) {
            page.forEach(m -> all.add(m.getId()));
            cursor = page.get(page.size() - 1).getId();
            page = messageRepository.findMessagesAfterCursor(channelId, cursor, PageRequest.of(0, 2));
        }
        assertThat(all).containsExactly(ids.get(1), ids.get(2), ids.get(3), ids.get(4));
    }

    @Test
    @DisplayName("履歴コピー用の古い順は同秒でも id 昇順")
    void 古い順は同秒でもid昇順() {
        List<Long> got = messageRepository.findByChannelIdOrderByCreatedAtAscIdAsc(channelId)
                .stream().map(ChatMessageEntity::getId).toList();
        assertThat(got).containsExactlyElementsOf(ids);
    }

    private void softDelete(Long id) {
        em.createNativeQuery("UPDATE chat_messages SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", id).executeUpdate();
        em.clear();
    }

    @Test
    @DisplayName("before: カーソル対象が論理削除済みでも後続（古い側）が返る")
    void beforeカーソル対象が削除済みでも返る() {
        softDelete(ids.get(3));
        List<Long> got = messageRepository
                .findByChannelIdAndIdLessThan(channelId, ids.get(3), PageRequest.of(0, 10))
                .stream().map(ChatMessageEntity::getId).toList();
        assertThat(got).containsExactly(ids.get(2), ids.get(1), ids.get(0));
    }

    @Test
    @DisplayName("after: カーソル対象が論理削除済みでも後続（新しい側）が返り、削除済みは除外")
    void afterカーソル対象が削除済みでも返る() {
        softDelete(ids.get(1));
        softDelete(ids.get(3));
        List<Long> got = messageRepository
                .findMessagesAfterCursor(channelId, ids.get(1), PageRequest.of(0, 10))
                .stream().map(ChatMessageEntity::getId).toList();
        assertThat(got).containsExactly(ids.get(2), ids.get(4));
    }
}
