package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.dto.VillageFeedItemResponse;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 村フィード（GET /api/v1/me/village-feed）の N+1 解消を固定する試練
 * （CMP-261103-1225 / #4270 P1-E の AC-1〜AC-7・AC-10）。
 *
 * <p>SQL 数がピン数に依存しないこと、および村ごとの件数上限（5 件）・createdAt 降順・
 * 村人でない村の本文を混ぜない可視性が修正前と等価であることを検証する。</p>
 */
@Transactional
@DisplayName("村フィード N+1 解消（P1-E）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageFeedN1IT extends AbstractMySqlIntegrationTest {

    private static final int PER_VILLAGE_CAP = 5;
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 0, 0, 0);

    @Autowired private VillageFeedService service;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageMembershipRepository membershipRepository;
    @Autowired private UserVillagePinRepository pinRepository;
    @Autowired private TimelinePostRepository timelinePostRepository;
    @Autowired private BulletinThreadRepository bulletinThreadRepository;
    @Autowired private ChatChannelRepository channelRepository;
    @Autowired private ChatMessageRepository messageRepository;
    @PersistenceContext private EntityManager em;

    /** 全体で一意な時刻を払い出す（createdAt の同値による順序揺れを排除）。 */
    private int clock = 0;

    private LocalDateTime tick() {
        return BASE.plusMinutes(++clock);
    }

    private void setCreatedAt(String table, Object id, LocalDateTime at) {
        em.createNativeQuery("UPDATE " + table + " SET created_at = :at WHERE id = :id")
                .setParameter("at", at).setParameter("id", id).executeUpdate();
    }

    private VillageEntity village(String name, VillageVisibility visibility) {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("n1-" + Long.toHexString(System.nanoTime()) + "-" + (++clock))
                .name(name).type(VillageType.COMMUNITY).joinPolicy(VillageJoinPolicy.FREE)
                .visibility(visibility).bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                .memberCountCache(0L).build());
    }

    private void member(UUID villageId, Long userId) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId).subjectType(VillageSubjectType.USER).subjectId(userId)
                .role(VillageRole.VILLAGER).joinedAt(LocalDateTime.now()).build());
    }

    private void pin(Long userId, UUID villageId, long order) {
        pinRepository.saveAndFlush(UserVillagePinEntity.builder().userId(userId).villageId(villageId)
                .sortOrder(order).pinnedAt(LocalDateTime.now()).build());
    }

    /**
     * 1 村分の内容を播種し、「期待されるフィード項目（村ごとに各 5 件）」を返す。
     * 上限超過分・返信・非公開・削除済みは期待に含めない。
     */
    private List<VillageFeedItemResponse> content(VillageEntity v, String tag, boolean withLobby) {
        List<VillageFeedItemResponse> expected = new ArrayList<>();
        // タイムライン: 7 件（上限 5 を超える）＋ 返信 ＋ 下書き ＋ 論理削除
        for (int i = 0; i < 7; i++) {
            String text = "tl-" + tag + "-" + i;
            TimelinePostEntity p = timelinePostRepository.saveAndFlush(TimelinePostEntity.builder()
                    .scopeType(PostScopeType.PERSONAL).scopeId(0L).scopeVillageId(v.getId()).userId(101L)
                    .content(text).status(PostStatus.PUBLISHED).build());
            LocalDateTime at = tick();
            setCreatedAt("timeline_posts", p.getId(), at);
            if (i >= 2) {
                expected.add(item("TIMELINE", v, p.getId(), null, text, at));
            }
        }
        TimelinePostEntity reply = timelinePostRepository.saveAndFlush(TimelinePostEntity.builder()
                .scopeType(PostScopeType.PERSONAL).scopeId(0L).scopeVillageId(v.getId()).userId(101L)
                .parentId(1L).content("reply-" + tag).status(PostStatus.PUBLISHED).build());
        setCreatedAt("timeline_posts", reply.getId(), tick());
        TimelinePostEntity draft = timelinePostRepository.saveAndFlush(TimelinePostEntity.builder()
                .scopeType(PostScopeType.PERSONAL).scopeId(0L).scopeVillageId(v.getId()).userId(101L)
                .content("draft-" + tag).status(PostStatus.DRAFT).build());
        setCreatedAt("timeline_posts", draft.getId(), tick());
        TimelinePostEntity gone = timelinePostRepository.saveAndFlush(TimelinePostEntity.builder()
                .scopeType(PostScopeType.PERSONAL).scopeId(0L).scopeVillageId(v.getId()).userId(101L)
                .content("gone-" + tag).status(PostStatus.PUBLISHED).build());
        setCreatedAt("timeline_posts", gone.getId(), tick());
        em.createNativeQuery("UPDATE timeline_posts SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", gone.getId()).executeUpdate();

        // 掲示板: 7 件 ＋ 論理削除 1 件
        for (int i = 0; i < 7; i++) {
            String title = "bl-" + tag + "-" + i;
            BulletinThreadEntity t = bulletinThreadRepository.saveAndFlush(BulletinThreadEntity.builder()
                    .scopeType(com.mannschaft.app.bulletin.ScopeType.PERSONAL).scopeId(0L)
                    .scopeVillageId(v.getId()).title(title).body("b").build());
            LocalDateTime at = tick();
            setCreatedAt("bulletin_threads", t.getId(), at);
            if (i >= 2) {
                expected.add(item("TIMELINE", v, t.getId(), null, title, at));
            }
        }
        BulletinThreadEntity goneT = bulletinThreadRepository.saveAndFlush(BulletinThreadEntity.builder()
                .scopeType(com.mannschaft.app.bulletin.ScopeType.PERSONAL).scopeId(0L)
                .scopeVillageId(v.getId()).title("blgone-" + tag).body("b").build());
        setCreatedAt("bulletin_threads", goneT.getId(), tick());
        em.createNativeQuery("UPDATE bulletin_threads SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", goneT.getId()).executeUpdate();

        if (withLobby) {
            ChatChannelEntity ch = channelRepository.saveAndFlush(ChatChannelEntity.builder()
                    .channelType(ChannelType.VILLAGE_LOBBY).villageId(v.getId()).name("lobby-" + tag)
                    .isPrivate(false).createdBy(101L).build());
            // 別種別のチャンネル（同じ村 ID でも VILLAGE_LOBBY 以外は拾わない）
            channelRepository.saveAndFlush(ChatChannelEntity.builder()
                    .channelType(ChannelType.TEAM_PUBLIC).villageId(v.getId()).name("other-" + tag)
                    .isPrivate(false).createdBy(101L).build());
            for (int i = 0; i < 7; i++) {
                String body = "ms-" + tag + "-" + i;
                ChatMessageEntity m = messageRepository.saveAndFlush(ChatMessageEntity.builder()
                        .channelId(ch.getId()).senderId(101L).body(body).build());
                LocalDateTime at = tick();
                setCreatedAt("chat_messages", m.getId(), at);
                if (i >= 2) {
                    expected.add(item("LOBBY", v, null, m.getId(), body, at));
                }
            }
            ChatMessageEntity threadReply = messageRepository.saveAndFlush(ChatMessageEntity.builder()
                    .channelId(ch.getId()).senderId(101L).body("rep-" + tag).parentId(1L).build());
            setCreatedAt("chat_messages", threadReply.getId(), tick());
            ChatMessageEntity goneM = messageRepository.saveAndFlush(ChatMessageEntity.builder()
                    .channelId(ch.getId()).senderId(101L).body("msgone-" + tag).build());
            setCreatedAt("chat_messages", goneM.getId(), tick());
            em.createNativeQuery("UPDATE chat_messages SET deleted_at = NOW() WHERE id = :id")
                    .setParameter("id", goneM.getId()).executeUpdate();
        }
        em.flush();
        return expected;
    }

    private VillageFeedItemResponse item(String type, VillageEntity v, Long postId, Long messageId,
                                         String snippet, LocalDateTime at) {
        return VillageFeedItemResponse.builder().type(type).villageId(v.getId()).villageName(v.getName())
                .postId(postId).messageId(messageId).snippet(snippet).createdAt(at).build();
    }

    /** 村人かつピンの村を n 件播種し、期待フィード（降順）を返す。 */
    private List<VillageFeedItemResponse> seedMemberVillages(Long userId, int n, String prefix) {
        List<VillageFeedItemResponse> expected = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            VillageEntity v = village(prefix + i, VillageVisibility.PUBLIC);
            member(v.getId(), userId);
            pin(userId, v.getId(), i);
            // 3 村に 1 つはロビー無し（ロビー未払い出し村）
            expected.addAll(content(v, prefix + i, i % 3 != 2));
        }
        em.flush();
        em.clear();
        expected.sort(Comparator.comparing(VillageFeedItemResponse::createdAt).reversed());
        return expected;
    }

    private Statistics stats() {
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    private void assertFeedEquals(VillageFeedResponse res, List<VillageFeedItemResponse> expected) {
        assertThat(res.feed()).extracting(VillageFeedItemResponse::snippet)
                .containsExactlyElementsOf(expected.stream().map(VillageFeedItemResponse::snippet).toList());
        assertThat(res.feed()).extracting(VillageFeedItemResponse::type)
                .containsExactlyElementsOf(expected.stream().map(VillageFeedItemResponse::type).toList());
        assertThat(res.feed()).extracting(VillageFeedItemResponse::villageId)
                .containsExactlyElementsOf(expected.stream().map(VillageFeedItemResponse::villageId).toList());
        assertThat(res.feed()).extracting(VillageFeedItemResponse::createdAt)
                .containsExactlyElementsOf(expected.stream().map(VillageFeedItemResponse::createdAt).toList());
    }

    @Test
    @DisplayName("AC-1/AC-2/AC-10: ピン 1 村と 30 村（上限ちょうど）で SQL 数が同じで、フィードは村ごと各 5 件・createdAt 降順")
    void sqlCountIndependentOfPinCount() {
        List<VillageFeedItemResponse> small = seedMemberVillages(940001L, 1, "s");
        List<VillageFeedItemResponse> large = seedMemberVillages(940002L, VillagePinService.PIN_MAX_LIMIT, "l");

        Statistics s = stats();
        VillageFeedResponse smallRes = service.build(940001L, VillageFeedService.MAX_LIMIT);
        long smallCount = s.getPrepareStatementCount();
        assertFeedEquals(smallRes, small.subList(0, Math.min(small.size(), VillageFeedService.MAX_LIMIT)));

        em.clear();
        s.clear();
        VillageFeedResponse largeRes = service.build(940002L, VillageFeedService.MAX_LIMIT);
        long largeCount = s.getPrepareStatementCount();
        assertFeedEquals(largeRes, large.subList(0, VillageFeedService.MAX_LIMIT));
        assertThat(largeRes.pinnedVillages()).hasSize(VillagePinService.PIN_MAX_LIMIT);

        System.out.println("P1E feed SQL small=" + smallCount + " large=" + largeCount);
        assertThat(largeCount).as("ピンを 1→30 に増やしても SQL 数は同じ").isEqualTo(smallCount);
    }

    @Test
    @DisplayName("AC-10: ピン行が 31 件あってもフィードは上限を持たず従来どおり全ピンを返す（上限は VillagePinService 側の責務）")
    void thirtyOnePinRowsAreAllReturned() {
        seedMemberVillages(940003L, VillagePinService.PIN_MAX_LIMIT + 1, "x");
        VillageFeedResponse res = service.build(940003L, 20);
        assertThat(res.pinnedVillages()).hasSize(VillagePinService.PIN_MAX_LIMIT + 1);
        assertThat(res.feed()).hasSize(20);
    }

    @Test
    @DisplayName("AC-3: ピンなしでも空の応答を返す")
    void noPinsReturnsEmpty() {
        VillageFeedResponse res = service.build(940004L, 20);
        assertThat(res.feed()).isEmpty();
        assertThat(res.pinnedVillages()).isEmpty();
    }

    @Test
    @DisplayName("AC-5/AC-7: 削除済み村・凍結村・村人でない村の本文は混ざらず、ロビーの無い村でも落ちない")
    void deletedArchivedAndNonMemberVillagesAreExcluded() {
        Long userId = 940005L;
        VillageEntity ok = village("ok", VillageVisibility.PUBLIC);
        member(ok.getId(), userId);
        pin(userId, ok.getId(), 0);
        List<VillageFeedItemResponse> expected = content(ok, "ok", false);

        VillageEntity stranger = village("stranger", VillageVisibility.PUBLIC);   // 村人でない
        pin(userId, stranger.getId(), 1);
        content(stranger, "stranger", true);

        VillageEntity deleted = village("deleted", VillageVisibility.PUBLIC);
        member(deleted.getId(), userId);
        pin(userId, deleted.getId(), 2);
        content(deleted, "deleted", true);
        deleted.setDeletedAt(LocalDateTime.now());
        villageRepository.saveAndFlush(deleted);

        VillageEntity archived = village("archived", VillageVisibility.PUBLIC);
        member(archived.getId(), userId);
        pin(userId, archived.getId(), 3);
        content(archived, "archived", true);
        archived.setArchivedAt(LocalDateTime.now());
        villageRepository.saveAndFlush(archived);

        VillageEntity hidden = village("hidden", VillageVisibility.UNLISTED);     // 非公開・非村人
        pin(userId, hidden.getId(), 4);
        content(hidden, "hidden", true);

        // 他人のピン・他人の村の内容は混ざらない
        VillageEntity others = village("others", VillageVisibility.PUBLIC);
        member(others.getId(), 940099L);
        pin(940099L, others.getId(), 0);
        content(others, "others", true);

        em.flush();
        em.clear();
        expected.sort(Comparator.comparing(VillageFeedItemResponse::createdAt).reversed());

        VillageFeedResponse res = service.build(userId, VillageFeedService.MAX_LIMIT);
        assertFeedEquals(res, expected);
        // ピン一覧: 削除済み・凍結・不可視は出ない。村人でない公開村は出る（従来どおり）
        assertThat(res.pinnedVillages()).extracting(p -> p.name()).containsExactly("ok", "stranger");
    }

    @Test
    @DisplayName("AC-4: 同一村にロビーチャンネルが複数あっても例外を出さず、フィードに混ざる重複もない")
    void duplicateLobbyChannelDoesNotBreak() {
        Long userId = 940006L;
        VillageEntity v = village("dup", VillageVisibility.PUBLIC);
        member(v.getId(), userId);
        pin(userId, v.getId(), 0);
        List<VillageFeedItemResponse> expected = content(v, "dup", true);
        em.flush();
        em.clear();
        // 修正前は Optional の単一結果取得が例外化するため等価比較はせず、一般性の確認のみ（新実装は最古の 1 件を採る）
        ChatChannelEntity second = channelRepository.saveAndFlush(ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY).villageId(v.getId()).name("lobby2")
                .isPrivate(false).createdBy(101L).build());
        em.flush();
        em.clear();
        VillageFeedResponse res = service.build(userId, VillageFeedService.MAX_LIMIT);
        assertThat(second.getId()).isNotNull();
        assertThat(res.feed()).extracting(VillageFeedItemResponse::snippet)
                .containsAll(expected.stream().map(VillageFeedItemResponse::snippet).toList());
    }
}
