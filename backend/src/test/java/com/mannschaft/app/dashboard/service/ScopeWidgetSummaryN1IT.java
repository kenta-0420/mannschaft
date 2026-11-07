package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * スコープダッシュボードのチャット・掲示板ウィジェット集計（ScopeWidgetSummaryService）の
 * N+1 解消を固定する試練（CMP-261103-1225 / #4270）。
 */
@Transactional
@DisplayName("ScopeWidgetSummaryService の N+1 解消")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScopeWidgetSummaryN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScopeWidgetSummaryService service;
    @Autowired
    private ChatChannelRepository channelRepository;
    @Autowired
    private ChatChannelMemberRepository memberRepository;
    @PersistenceContext
    private EntityManager em;

    private N1SeedSupport seed;
    private final LocalDateTime base = LocalDateTime.of(2026, 3, 1, 9, 0);

    @BeforeEach
    void setUp() {
        seed = new N1SeedSupport(em);
    }

    private ChatChannelEntity channel(ChannelType type, Long teamId, Long orgId, String name, LocalDateTime lastAt) {
        return channelRepository.save(ChatChannelEntity.builder()
                .channelType(type).teamId(teamId).organizationId(orgId).name(name)
                .lastMessageAt(lastAt).lastMessagePreview("pv-" + name).build());
    }

    private void member(Long channelId, Long userId, int unread) {
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).unreadCount(unread).build());
    }

    private <T> T measure(Statistics s, long[] out, int idx, Supplier<T> call) {
        em.clear();
        s.clear();
        T r = call.get();
        out[idx] = s.getPrepareStatementCount();
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("AC-1/AC-2/AC-7: buildChatSummary はチャンネル数に依存せず、未参加チャンネルを含めない")
    void chatSummarySqlCountIndependentOfChannelCount() {
        Long me = seed.user("sw-chat");
        Long other = seed.user("sw-other");
        Long smallTeam = seed.team("N1-sw-small", null);
        ChatChannelEntity only = channel(ChannelType.TEAM_PUBLIC, smallTeam, null, "only", base);
        member(only.getId(), me, 2);

        Long largeTeam = seed.team("N1-sw-large", null);
        for (int i = 0; i < 6; i++) {
            ChatChannelEntity ch = channel(ChannelType.TEAM_PUBLIC, largeTeam, null, "c" + i, base.plusHours(i));
            if (i % 3 != 2) {
                member(ch.getId(), me, i + 1); // c0,c1,c3,c4 に参加（未読 1+2+4+5=12）
            }
            member(ch.getId(), other, 100); // 他人の未読は混入しない
        }
        seed.flushAndClear();

        Statistics s = seed.stats();
        long[] counts = new long[2];
        Map<String, Object> smallRes = measure(s, counts, 0, () -> service.buildChatSummary("TEAM", smallTeam, me));
        Map<String, Object> largeRes = measure(s, counts, 1, () -> service.buildChatSummary("TEAM", largeTeam, me));
        System.out.println("N1 scope-chat SQL small=" + counts[0] + " large=" + counts[1]);

        assertThat(smallRes.get("total_unread")).isEqualTo(2L);
        assertThat(largeRes.get("total_unread")).isEqualTo(12L);
        List<Map<String, Object>> channels = (List<Map<String, Object>>) largeRes.get("channels");
        // lastMessageAt 降順で参加チャンネルの先頭 3 件
        assertThat(channels).extracting(m -> m.get("name")).containsExactly("c4", "c3", "c1");
        assertThat(channels).extracting(m -> m.get("unread_count")).containsExactly(5, 4, 2);
        assertThat(counts[1]).as("チャンネル数に依存しない SQL 数").isEqualTo(counts[0]);

        // AC-3: チャンネルなし
        Long emptyTeam = seed.team("N1-sw-empty", null);
        seed.flushAndClear();
        Map<String, Object> empty = service.buildChatSummary("TEAM", emptyTeam, me);
        assertThat(empty.get("total_unread")).isEqualTo(0L);
        assertThat((List<?>) empty.get("channels")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("AC-1/AC-2: buildOrgUnreadThreads / buildThreadListForScope はスレッド数・チャンネル数に依存しない")
    void orgUnreadThreadsSqlCountIndependentOfThreadsAndChannels() {
        Long me = seed.user("sw-org");
        Long smallOrg = seed.organization("N1-sw-org-small", null);
        seed.bulletinThread("ORGANIZATION", smallOrg, me, "only", false, base);
        ChatChannelEntity sc = channel(ChannelType.ORG_PUBLIC, null, smallOrg, "o-only", base);
        member(sc.getId(), me, 1);

        Long largeOrg = seed.organization("N1-sw-org-large", null);
        for (int i = 0; i < 8; i++) {
            Long th = seed.bulletinThread("ORGANIZATION", largeOrg, me, "t" + i, i == 0, base.plusHours(i));
            if (i % 2 == 1) {
                seed.readThread(th, me); // t1,t3,t5,t7 既読 → 未読 4
            }
        }
        for (int i = 0; i < 5; i++) {
            ChatChannelEntity ch = channel(ChannelType.ORG_PUBLIC, null, largeOrg, "oc" + i, base.plusHours(i));
            if (i != 4) {
                member(ch.getId(), me, 1); // 4 チャンネル参加 → 未読 4
            }
        }
        // スレッド一覧（先頭 3 件のページ取得）の比較用: ちょうど 3 件の組織。
        // Spring Data の Page は「取得件数 = ページサイズ」のとき件数クエリを足すため、
        // 1 件と 8 件ではなく 3 件と 8 件（どちらも件数クエリあり）で比べ、スレッド数だけを変える。
        Long midOrg = seed.organization("N1-sw-org-mid", null);
        for (int i = 0; i < 3; i++) {
            Long th = seed.bulletinThread("ORGANIZATION", midOrg, me, "m" + i, false, base.plusHours(i));
            if (i == 1) {
                seed.readThread(th, me);
            }
        }
        seed.flushAndClear();

        Statistics s = seed.stats();
        long[] counts = new long[5];
        Map<String, Object> smallRes = measure(s, counts, 0, () -> service.buildOrgUnreadThreads(smallOrg, me));
        Map<String, Object> largeRes = measure(s, counts, 1, () -> service.buildOrgUnreadThreads(largeOrg, me));
        List<Map<String, Object>> smallList = measure(s, counts, 2,
                () -> service.buildThreadListForScope("ORGANIZATION", smallOrg, me));
        List<Map<String, Object>> largeList = measure(s, counts, 3,
                () -> service.buildThreadListForScope("ORGANIZATION", largeOrg, me));
        List<Map<String, Object>> midList = measure(s, counts, 4,
                () -> service.buildThreadListForScope("ORGANIZATION", midOrg, me));
        System.out.println("N1 scope-org-unread SQL small=" + counts[0] + " large=" + counts[1]
                + " / thread-list small=" + counts[2] + " mid(3)=" + counts[4] + " large(8)=" + counts[3]);

        assertThat(smallRes.get("bulletin_count")).isEqualTo(1L);
        assertThat(smallRes.get("chat_count")).isEqualTo(1L);
        assertThat(largeRes.get("bulletin_count")).isEqualTo(4L);
        assertThat(largeRes.get("chat_count")).isEqualTo(4L);
        List<Map<String, Object>> threads = (List<Map<String, Object>>) largeRes.get("bulletin_threads");
        // ピン留め t0 → 更新日時降順 t7, t6
        assertThat(threads).extracting(m -> m.get("title")).containsExactly("t0", "t7", "t6");
        assertThat(threads).extracting(m -> m.get("is_read")).containsExactly(false, true, false);
        assertThat(largeList).extracting(m -> m.get("title")).containsExactly("t0", "t7", "t6");
        assertThat(largeList).extracting(m -> m.get("is_read")).containsExactly(false, true, false);
        assertThat(smallList).extracting(m -> m.get("is_read")).containsExactly(false);
        assertThat(midList).extracting(m -> m.get("title")).containsExactly("m2", "m1", "m0");
        assertThat(midList).extracting(m -> m.get("is_read")).containsExactly(false, true, false);

        assertThat(counts[1]).as("スレッド数・チャンネル数に依存しない SQL 数").isEqualTo(counts[0]);
        assertThat(counts[3]).as("スレッド一覧もスレッド数に依存しない SQL 数（3 件と 8 件）").isEqualTo(counts[4]);
    }
}
