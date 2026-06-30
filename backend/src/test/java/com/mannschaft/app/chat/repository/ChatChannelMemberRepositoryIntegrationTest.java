package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ChatChannelMemberRepository} の per-user 拡張クエリ番人テスト。
 *
 * <p>チャンネル契約フル是正・第一陣（2026-06-30）で追加した以下を検証する:</p>
 * <ul>
 *   <li>{@link ChatChannelMemberRepository#findByChannelIdAndUserIdNot}（AC-B8: 自分以外メンバー）</li>
 *   <li>{@link ChatChannelMemberRepository#findByChannelIdInAndUserIdNot}（一括 DM 相手解決）</li>
 *   <li>{@link ChatChannelMemberRepository#countGroupedByChannelIds}（一括メンバー数集計）</li>
 * </ul>
 */
@Transactional
@DisplayName("ChatChannelMemberRepository per-user 拡張クエリ番人テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatChannelMemberRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ChatChannelMemberRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long DM_CHANNEL = 9001L;
    private static final Long TEAM_CHANNEL = 9002L;
    private static final Long CALLER = 100L;
    private static final Long PARTNER = 200L;
    private static final Long OTHER = 300L;

    private ChatChannelMemberEntity persistMember(Long channelId, Long userId, ChannelMemberRole role) {
        ChatChannelMemberEntity m = ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).role(role).build();
        em.persist(m);
        em.flush();
        return m;
    }

    @Nested
    @DisplayName("findByChannelIdAndUserIdNot（AC-B8）")
    class FindByChannelIdAndUserIdNot {

        @Test
        @DisplayName("AC-B8: 自分以外のメンバー行のみ返る")
        void 自分以外のメンバーが返る() {
            persistMember(DM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            persistMember(DM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            em.clear();

            List<ChatChannelMemberEntity> result = repository.findByChannelIdAndUserIdNot(DM_CHANNEL, CALLER);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(PARTNER);
        }

        @Test
        @DisplayName("自分しかいなければ空")
        void 自分のみなら空() {
            persistMember(DM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            em.clear();

            assertThat(repository.findByChannelIdAndUserIdNot(DM_CHANNEL, CALLER)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findByChannelIdInAndUserIdNot（一括 DM 相手解決）")
    class FindByChannelIdInAndUserIdNot {

        @Test
        @DisplayName("複数チャンネルの自分以外メンバーを一括取得する")
        void 複数チャンネルの相手を一括取得() {
            persistMember(DM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            persistMember(DM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, CALLER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, OTHER, ChannelMemberRole.MEMBER);
            em.clear();

            List<ChatChannelMemberEntity> result =
                    repository.findByChannelIdInAndUserIdNot(List.of(DM_CHANNEL, TEAM_CHANNEL), CALLER);

            assertThat(result).extracting(ChatChannelMemberEntity::getUserId)
                    .containsExactlyInAnyOrder(PARTNER, OTHER);
        }
    }

    @Nested
    @DisplayName("countGroupedByChannelIds（一括メンバー数集計）")
    class CountGroupedByChannelIds {

        @Test
        @DisplayName("チャンネルごとのメンバー数を集計して返す")
        void チャンネルごとのメンバー数を返す() {
            persistMember(DM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            persistMember(DM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, CALLER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, OTHER, ChannelMemberRole.MEMBER);
            em.clear();

            Map<Long, Long> counts = repository.countGroupedByChannelIds(List.of(DM_CHANNEL, TEAM_CHANNEL))
                    .stream()
                    .collect(Collectors.toMap(
                            ChatChannelMemberRepository.ChannelMemberCount::getChannelId,
                            ChatChannelMemberRepository.ChannelMemberCount::getMemberCount));

            assertThat(counts).containsEntry(DM_CHANNEL, 2L).containsEntry(TEAM_CHANNEL, 3L);
        }
    }
}
