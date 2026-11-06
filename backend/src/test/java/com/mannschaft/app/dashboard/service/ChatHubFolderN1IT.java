package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.dto.ChatFolderResponse;
import com.mannschaft.app.dashboard.dto.ChatHubResponse;
import com.mannschaft.app.dashboard.dto.ContactFolderDto;
import com.mannschaft.app.dashboard.dto.ContactItemDto;
import com.mannschaft.app.dashboard.dto.DirectMessageItemDto;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * チャットハブ（ChatHubService#getChatHub）とフォルダ一覧（ChatFolderService#getFolders）の
 * N+1 解消を固定する試練（CMP-261103-1225 / #4270）。
 */
@Transactional
@DisplayName("ChatHubService / ChatFolderService の N+1 解消")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ChatHubFolderN1IT extends AbstractMySqlIntegrationTest {

    @Autowired
    private ChatHubService chatHubService;
    @Autowired
    private ChatFolderService chatFolderService;
    @Autowired
    private ChatChannelRepository channelRepository;
    @Autowired
    private ChatChannelMemberRepository memberRepository;
    @Autowired
    private ChatContactFolderRepository folderRepository;
    @Autowired
    private ChatContactFolderItemRepository folderItemRepository;
    @PersistenceContext
    private EntityManager em;

    private N1SeedSupport seed;
    private final LocalDateTime base = LocalDateTime.of(2026, 3, 1, 9, 0);

    @BeforeEach
    void setUp() {
        seed = new N1SeedSupport(em);
    }

    private ChatChannelEntity channel(ChannelType type, Long teamId, String name, LocalDateTime lastAt) {
        return channelRepository.save(ChatChannelEntity.builder()
                .channelType(type).teamId(teamId).name(name)
                .lastMessageAt(lastAt).lastMessagePreview(lastAt == null ? null : "preview-" + name)
                .build());
    }

    private void member(Long channelId, Long userId, LocalDateTime joinedAt, int unread) {
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(channelId).userId(userId).joinedAt(joinedAt).unreadCount(unread).build());
    }

    private Long dm(Long me, Long partner, LocalDateTime lastAt) {
        ChatChannelEntity ch = channel(ChannelType.DM, null, null, lastAt);
        member(ch.getId(), me, base, 1);
        member(ch.getId(), partner, base.plusMinutes(1), 0);
        return ch.getId();
    }

    private ChatContactFolderEntity folder(Long userId, String name, int sort) {
        return folderRepository.save(ChatContactFolderEntity.builder()
                .userId(userId).name(name).sortOrder(sort).build());
    }

    private void folderItem(Long folderId, FolderItemType type, Long itemId, String customName) {
        folderItemRepository.save(ChatContactFolderItemEntity.builder()
                .folderId(folderId).itemType(type).itemId(itemId).customName(customName).build());
    }

    private <T> T measure(Statistics s, long[] out, int idx, Supplier<T> call) {
        em.clear();
        s.clear();
        T r = call.get();
        out[idx] = s.getPrepareStatementCount();
        return r;
    }

    @Test
    @DisplayName("AC-1/AC-2/AC-6: DM 数・フォルダ数を増やしても getChatHub の SQL 数が一定で、相手解決・並び順が等価")
    void chatHubSqlCountIndependentOfDmsAndFolders() {
        // small: DM 1 本 + フォルダ 1 個（連絡先 1 件）
        Long small = seed.user("hub-small");
        Long sp = seed.user("hub-sp");
        dm(small, sp, base);
        ChatContactFolderEntity sf = folder(small, "sf", 0);
        folderItem(sf.getId(), FolderItemType.CONTACT, sp, null);

        // large: DM 4 本（うち相手 1 名は退会）+ GROUP_DM 1 本 + チームチャンネル 1 本 + フォルダ 3 個
        Long large = seed.user("hub-large");
        List<Long> partners = new ArrayList<>();
        List<Long> dmIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Long p = seed.user("hub-p" + i);
            partners.add(p);
            dmIds.add(dm(large, p, base.plusHours(i)));
        }
        seed.softDeleteUser(partners.get(3));
        ChatChannelEntity group = channel(ChannelType.GROUP_DM, null, "grp", null);
        member(group.getId(), large, base, 2);
        Long g1 = seed.user("hub-g1");
        Long g2 = seed.user("hub-g2");
        member(group.getId(), g2, base.plusMinutes(5), 0);
        member(group.getId(), g1, base.plusMinutes(2), 0); // joinedAt が早い g1 が相手になる
        Long team = seed.team("N1-hub-team", null);
        ChatChannelEntity tc = channel(ChannelType.TEAM_PUBLIC, team, "general", base.plusDays(1));
        member(tc.getId(), large, base, 3);
        for (int f = 0; f < 3; f++) {
            ChatContactFolderEntity folder = folder(large, "lf" + f, f);
            folderItem(folder.getId(), FolderItemType.CONTACT, partners.get(f), f == 1 ? "custom" : null);
            folderItem(folder.getId(), FolderItemType.CONTACT, f == 0 ? g1 : seed.user("hub-c" + f), null);
            folderItem(folder.getId(), FolderItemType.DM_CHANNEL, dmIds.get(f), null);
        }
        seed.flushAndClear();

        Statistics s = seed.stats();
        long[] counts = new long[2];
        ChatHubResponse smallRes = measure(s, counts, 0, () -> chatHubService.getChatHub(small));
        ChatHubResponse largeRes = measure(s, counts, 1, () -> chatHubService.getChatHub(large));
        System.out.println("N1 chat-hub SQL small=" + counts[0] + " large=" + counts[1]);

        assertThat(smallRes.directMessages()).extracting(DirectMessageItemDto::partnerId).containsExactly(sp);
        assertThat(smallRes.contacts()).hasSize(1);
        assertThat(smallRes.contacts().get(0).items()).extracting(ContactItemDto::hasActiveDm).containsExactly(true);

        // DM: lastMessageAt 降順（null は末尾）。GROUP_DM の相手は joinedAt 最古の g1
        assertThat(largeRes.directMessages()).extracting(DirectMessageItemDto::partnerId)
                .containsExactly(partners.get(3), partners.get(2), partners.get(1), partners.get(0), g1);
        // 退会（論理削除）した相手は修正前と同じく解決されず空文字（AC-6）
        assertThat(largeRes.directMessages().get(0).partnerDisplayName()).isEmpty();
        assertThat(largeRes.teamChannels()).hasSize(1);
        assertThat(largeRes.contacts()).extracting(ContactFolderDto::folderName).containsExactly("lf0", "lf1", "lf2");
        ContactFolderDto lf1 = largeRes.contacts().get(1);
        assertThat(lf1.items()).hasSize(2);
        assertThat(lf1.items()).extracting(ContactItemDto::displayName).contains("custom");
        assertThat(largeRes.contacts().get(0).items()).extracting(ContactItemDto::itemId)
                .containsExactlyInAnyOrder(partners.get(0), g1);
        assertThat(largeRes.summary().totalUnread()).isEqualTo(4 + 2 + 3);
        assertThat(largeRes.summary().totalContactCount()).isEqualTo(6);

        assertThat(counts[1]).as("DM 数・フォルダ数に依存しない SQL 数").isEqualTo(counts[0]);
    }

    @Test
    @DisplayName("AC-3: DM なし・フォルダなしでも空 IN でエラーにならない")
    void chatHubEmpty() {
        Long me = seed.user("hub-empty");
        seed.flushAndClear();
        ChatHubResponse res = chatHubService.getChatHub(me);
        assertThat(res.directMessages()).isEmpty();
        assertThat(res.contacts()).isEmpty();
        assertThat(res.teamChannels()).isEmpty();
    }

    @Test
    @DisplayName("AC-1/AC-2: フォルダ数を増やしても getFolders の SQL 数が一定で、各フォルダのアイテムが等価")
    void foldersSqlCountIndependentOfFolderCount() {
        Long small = seed.user("fd-small");
        ChatContactFolderEntity sf = folder(small, "only", 0);
        folderItem(sf.getId(), FolderItemType.CONTACT, 1L, null);

        Long large = seed.user("fd-large");
        for (int f = 0; f < 5; f++) {
            ChatContactFolderEntity folder = folder(large, "f" + f, f);
            for (int i = 0; i < f; i++) {
                folderItem(folder.getId(), FolderItemType.CONTACT, 1000L + f * 10 + i, null);
            }
        }
        seed.flushAndClear();

        Statistics s = seed.stats();
        long[] counts = new long[2];
        List<ChatFolderResponse> smallRes = measure(s, counts, 0, () -> chatFolderService.getFolders(small));
        List<ChatFolderResponse> largeRes = measure(s, counts, 1, () -> chatFolderService.getFolders(large));
        System.out.println("N1 chat-folders SQL small=" + counts[0] + " large=" + counts[1]);

        assertThat(smallRes).hasSize(1);
        assertThat(smallRes.get(0).getItems()).hasSize(1);
        assertThat(largeRes).extracting(ChatFolderResponse::getName).containsExactly("f0", "f1", "f2", "f3", "f4");
        for (int f = 0; f < 5; f++) {
            assertThat(largeRes.get(f).getItems()).hasSize(f);
        }
        assertThat(counts[1]).as("フォルダ数に依存しない SQL 数").isEqualTo(counts[0]);

        // AC-3: フォルダなし
        Long none = seed.user("fd-none");
        seed.flushAndClear();
        assertThat(chatFolderService.getFolders(none)).isEmpty();
    }
}
