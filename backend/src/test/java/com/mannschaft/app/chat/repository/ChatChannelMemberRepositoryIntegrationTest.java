package com.mannschaft.app.chat.repository;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
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

    @Autowired
    private ChatChannelRepository channelRepository;

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

    private Long persistChannel(ChannelType type, Long teamId, Long organizationId) {
        ChatChannelEntity channel = channelRepository.save(ChatChannelEntity.builder()
                .channelType(type)
                .teamId(teamId)
                .organizationId(organizationId)
                .name("membership-revoke-contract")
                .createdBy(CALLER)
                .build());
        em.flush();
        return channel.getId();
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

    @Nested
    @DisplayName("membership scope channel 一括削除")
    class MembershipScopeChannelDeletion {

        @Test
        @DisplayName("TEAM/ORGANIZATION の対象種別・対象スコープだけを削除し、DM等は保持する")
        void スコープ対象だけを削除する() {
            Long teamPublic = persistChannel(ChannelType.TEAM_PUBLIC, 501L, null);
            Long teamPrivate = persistChannel(ChannelType.TEAM_PRIVATE, 501L, null);
            Long deletedTeamPrivate = persistChannel(ChannelType.TEAM_PRIVATE, 501L, null);
            Long otherTeam = persistChannel(ChannelType.TEAM_PUBLIC, 502L, null);
            Long orgPublic = persistChannel(ChannelType.ORG_PUBLIC, null, 601L);
            Long orgPrivate = persistChannel(ChannelType.ORG_PRIVATE, null, 601L);
            Long dm = persistChannel(ChannelType.DM, null, null);
            Long event = persistChannel(ChannelType.EVENT_CHAT, 501L, null);

            List.of(teamPublic, teamPrivate, deletedTeamPrivate, otherTeam, orgPublic, orgPrivate, dm, event)
                    .forEach(channelId -> persistMember(channelId, CALLER, ChannelMemberRole.MEMBER));
            channelRepository.findById(deletedTeamPrivate).orElseThrow().softDelete();
            em.flush();
            em.clear();

            int teamDeleted = repository.deleteByUserIdAndTeamScopeChannels(CALLER, 501L);
            int organizationDeleted = repository.deleteByUserIdAndOrganizationScopeChannels(CALLER, 601L);
            em.clear();

            assertThat(teamDeleted).isEqualTo(3);
            assertThat(organizationDeleted).isEqualTo(2);
            assertThat(repository.existsByChannelIdAndUserId(teamPublic, CALLER)).isFalse();
            assertThat(repository.existsByChannelIdAndUserId(teamPrivate, CALLER)).isFalse();
            assertThat(repository.existsByChannelIdAndUserId(deletedTeamPrivate, CALLER)).isFalse();
            assertThat(repository.existsByChannelIdAndUserId(orgPublic, CALLER)).isFalse();
            assertThat(repository.existsByChannelIdAndUserId(orgPrivate, CALLER)).isFalse();
            assertThat(repository.existsByChannelIdAndUserId(otherTeam, CALLER)).isTrue();
            assertThat(repository.existsByChannelIdAndUserId(dm, CALLER)).isTrue();
            assertThat(repository.existsByChannelIdAndUserId(event, CALLER)).isTrue();
        }
    }

    /**
     * 【未読カウント根治・red先行】{@link ChatChannelMemberRepository#incrementUnreadCountForOthers} 番人テスト。
     *
     * <p>{@code ChatChannelMemberEntity.incrementUnreadCount()} が dead code 化していた根治対応。
     * N+1 回避のため、メンバー数に依存しない 1 回の一括 UPDATE で「送信者以外」全員の
     * {@code unread_count} をインクリメントする。</p>
     */
    @Nested
    @DisplayName("incrementUnreadCountForOthers（未読カウント根治）")
    class IncrementUnreadCountForOthers {

        @Test
        @DisplayName("送信者以外の全メンバーのunread_countが+1され、送信者自身は増えない")
        void 送信者以外のunread_countのみ増える() {
            persistMember(TEAM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            persistMember(TEAM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            persistMember(TEAM_CHANNEL, OTHER, ChannelMemberRole.MEMBER);
            em.clear();

            int updated = repository.incrementUnreadCountForOthers(TEAM_CHANNEL, CALLER);
            em.clear();

            assertThat(updated).isEqualTo(2);
            assertThat(repository.findByChannelIdAndUserId(TEAM_CHANNEL, CALLER).orElseThrow().getUnreadCount())
                    .isEqualTo(0);
            assertThat(repository.findByChannelIdAndUserId(TEAM_CHANNEL, PARTNER).orElseThrow().getUnreadCount())
                    .isEqualTo(1);
            assertThat(repository.findByChannelIdAndUserId(TEAM_CHANNEL, OTHER).orElseThrow().getUnreadCount())
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("複数回呼び出すとunread_countが累積する")
        void 複数回呼び出すと累積する() {
            persistMember(TEAM_CHANNEL, CALLER, ChannelMemberRole.OWNER);
            persistMember(TEAM_CHANNEL, PARTNER, ChannelMemberRole.MEMBER);
            em.clear();

            repository.incrementUnreadCountForOthers(TEAM_CHANNEL, CALLER);
            repository.incrementUnreadCountForOthers(TEAM_CHANNEL, CALLER);
            em.clear();

            assertThat(repository.findByChannelIdAndUserId(TEAM_CHANNEL, PARTNER).orElseThrow().getUnreadCount())
                    .isEqualTo(2);
        }
    }
}
