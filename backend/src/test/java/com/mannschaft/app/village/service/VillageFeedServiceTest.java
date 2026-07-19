package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.dto.VillageFeedItemResponse;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.dto.VillagePinnedSummaryResponse;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link VillageFeedService} 単体テスト（F17.1 B10 §4.13）。
 *
 * <p>検証観点:</p>
 * <ul>
 *   <li>ピン無しユーザー → 空フィード</li>
 *   <li>limit が 0 以下なら DEFAULT_LIMIT、上限超なら MAX_LIMIT にクリップ</li>
 *   <li>TIMELINE と LOBBY の混合 + createdAt 降順</li>
 *   <li>削除/凍結済み村は除外</li>
 *   <li>limit を超える結果は切り詰められる</li>
 *   <li>ロビーチャネル未払い出し村は LOBBY ゼロでも TIMELINE は返す</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageFeedService 単体テスト")
class VillageFeedServiceTest {

    private static final Long USER_ID = 700L;

    @Mock private UserVillagePinRepository pinRepository;
    @Mock private VillageRepository villageRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private ChatChannelRepository chatChannelRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    /** 村アイコンの署名 URL 解決（#2355）。未スタブでも resolveAll は空 Map を返す（Mockito 既定）。 */
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private VillageFeedService service;

    // ============================================================
    // ユーティリティ
    // ============================================================

    private VillageEntity activeVillage(UUID id, String name) {
        VillageEntity v = VillageEntity.builder()
                .slug(name).name(name)
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(1L)
                .build();
        v.setId(id);
        return v;
    }

    private VillageEntity deletedVillage(UUID id) {
        VillageEntity v = activeVillage(id, "削除済み村");
        v.setDeletedAt(LocalDateTime.now());
        return v;
    }

    private UserVillagePinEntity pin(UUID villageId, long sortOrder) {
        UserVillagePinEntity p = UserVillagePinEntity.builder()
                .userId(USER_ID).villageId(villageId).sortOrder(sortOrder)
                .pinnedAt(LocalDateTime.now()).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private TimelinePostEntity timelinePost(Long id, UUID villageId, String content, LocalDateTime createdAt) {
        // F17.1 Phase 1: scopeType は既存値の PERSONAL を流用（scopeVillageId のみで絞り込む）。
        TimelinePostEntity p = TimelinePostEntity.builder()
                .scopeType(com.mannschaft.app.timeline.PostScopeType.PERSONAL)
                .scopeId(0L).scopeVillageId(villageId).userId(101L)
                .content(content).status(PostStatus.PUBLISHED).build();
        VillageSearchServiceTest.setBaseEntityId(p, id);
        VillageSearchServiceTest.setBaseEntityCreatedAt(p, createdAt);
        return p;
    }

    private ChatMessageEntity chatMessage(Long id, Long channelId, String body, LocalDateTime createdAt) {
        ChatMessageEntity m = ChatMessageEntity.builder()
                .channelId(channelId).senderId(101L).body(body).build();
        VillageSearchServiceTest.setBaseEntityId(m, id);
        VillageSearchServiceTest.setBaseEntityCreatedAt(m, createdAt);
        return m;
    }

    private ChatChannelEntity lobby(UUID villageId, Long channelId) {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY)
                .villageId(villageId).name("井戸端会議")
                .isPrivate(false).isArchived(false).activeThreadCount(0).build();
        VillageSearchServiceTest.setBaseEntityId(ch, channelId);
        return ch;
    }

    private static LocalDateTime t(int hour) {
        return LocalDateTime.of(2026, 5, 14, hour, 0, 0);
    }

    // ============================================================
    // テスト
    // ============================================================

    @Test
    @DisplayName("ピンが 0 件なら空のフィード + 空のピン一覧を返す")
    void build_noPins_returnsEmpty() {
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID)).willReturn(List.of());

        VillageFeedResponse res = service.build(USER_ID, 20);

        assertThat(res.feed()).isEmpty();
        assertThat(res.pinnedVillages()).isEmpty();
    }

    @Test
    @DisplayName("limit が 0 以下なら DEFAULT_LIMIT に補正される")
    void clampLimit_zero_returnsDefault() {
        assertThat(VillageFeedService.clampLimit(0)).isEqualTo(VillageFeedService.DEFAULT_LIMIT);
        assertThat(VillageFeedService.clampLimit(-5)).isEqualTo(VillageFeedService.DEFAULT_LIMIT);
    }

    @Test
    @DisplayName("limit が MAX を超えると MAX にクリップされる")
    void clampLimit_overMax_capped() {
        assertThat(VillageFeedService.clampLimit(9999)).isEqualTo(VillageFeedService.MAX_LIMIT);
    }

    @Test
    @DisplayName("削除済み村はピン残っていてもフィード対象から除外される")
    void build_excludesDeletedVillage() {
        UUID vId = UUID.randomUUID();
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(vId, 0L)));
        given(villageRepository.findById(vId)).willReturn(Optional.of(deletedVillage(vId)));

        VillageFeedResponse res = service.build(USER_ID, 20);

        assertThat(res.feed()).isEmpty();
        assertThat(res.pinnedVillages()).isEmpty();
    }

    @Test
    @DisplayName("複数村のタイムライン投稿 + 井戸端メッセージを createdAt 降順で集約")
    void build_multipleVillages_sortedByCreatedAtDesc() {
        UUID vId1 = UUID.randomUUID();
        UUID vId2 = UUID.randomUUID();
        VillageEntity v1 = activeVillage(vId1, "東村");
        VillageEntity v2 = activeVillage(vId2, "西村");

        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(vId1, 0L), pin(vId2, 1L)));
        given(villageRepository.findById(vId1)).willReturn(Optional.of(v1));
        given(villageRepository.findById(vId2)).willReturn(Optional.of(v2));

        given(timelinePostRepository.findLatestByVillageId(eq(vId1), any()))
                .willReturn(List.of(timelinePost(10L, vId1, "東の投稿", t(10))));
        given(timelinePostRepository.findLatestByVillageId(eq(vId2), any()))
                .willReturn(List.of(timelinePost(20L, vId2, "西の投稿", t(8))));
        given(bulletinThreadRepository.findLatestByVillageId(any(), any()))
                .willReturn(List.of());

        given(chatChannelRepository.findByVillageIdAndChannelType(vId1, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(lobby(vId1, 1001L)));
        given(chatChannelRepository.findByVillageIdAndChannelType(vId2, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(lobby(vId2, 1002L)));

        given(chatMessageRepository.findLatestRootMessagesByChannelId(eq(1001L), any()))
                .willReturn(List.of(chatMessage(30L, 1001L, "東のおはよう", t(11))));
        given(chatMessageRepository.findLatestRootMessagesByChannelId(eq(1002L), any()))
                .willReturn(List.of(chatMessage(40L, 1002L, "西のおはよう", t(9))));

        VillageFeedResponse res = service.build(USER_ID, 10);

        // 11, 10, 9, 8 の順
        assertThat(res.feed()).hasSize(4);
        assertThat(res.feed().get(0).type()).isEqualTo("LOBBY");
        assertThat(res.feed().get(0).snippet()).contains("東のおはよう");
        assertThat(res.feed().get(1).type()).isEqualTo("TIMELINE");
        assertThat(res.feed().get(1).snippet()).contains("東の投稿");
        assertThat(res.feed().get(2).type()).isEqualTo("LOBBY");
        assertThat(res.feed().get(3).type()).isEqualTo("TIMELINE");

        assertThat(res.pinnedVillages()).hasSize(2);
        assertThat(res.pinnedVillages()).extracting(VillagePinnedSummaryResponse::name)
                .containsExactlyInAnyOrder("東村", "西村");
    }

    @Test
    @DisplayName("limit を超える結果は切り詰められる")
    void build_truncatesToLimit() {
        UUID vId = UUID.randomUUID();
        VillageEntity v = activeVillage(vId, "包村");
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(vId, 0L)));
        given(villageRepository.findById(vId)).willReturn(Optional.of(v));
        given(timelinePostRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of(
                        timelinePost(1L, vId, "1", t(1)),
                        timelinePost(2L, vId, "2", t(2)),
                        timelinePost(3L, vId, "3", t(3))
                ));
        given(bulletinThreadRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of());
        given(chatChannelRepository.findByVillageIdAndChannelType(vId, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty());

        VillageFeedResponse res = service.build(USER_ID, 2);

        assertThat(res.feed()).hasSize(2);
        // createdAt 降順、最新 2 件
        assertThat(res.feed()).extracting(VillageFeedItemResponse::snippet)
                .containsExactly("3", "2");
    }

    @Test
    @DisplayName("ロビーチャネル未払い出しでも TIMELINE は返す")
    void build_noLobbyChannel_timelineOnly() {
        UUID vId = UUID.randomUUID();
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(vId, 0L)));
        given(villageRepository.findById(vId)).willReturn(Optional.of(activeVillage(vId, "新規村")));
        given(timelinePostRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of(timelinePost(1L, vId, "投稿だけ", t(5))));
        given(bulletinThreadRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of());
        given(chatChannelRepository.findByVillageIdAndChannelType(vId, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty());

        VillageFeedResponse res = service.build(USER_ID, 20);

        assertThat(res.feed()).hasSize(1);
        assertThat(res.feed().get(0).type()).isEqualTo("TIMELINE");
    }

    @Test
    @DisplayName("掲示板スレッドも TIMELINE 型としてフィードに含まれる")
    void build_includesBulletinThreads() {
        UUID vId = UUID.randomUUID();
        given(pinRepository.findByUserIdOrderBySortOrderAsc(USER_ID))
                .willReturn(List.of(pin(vId, 0L)));
        given(villageRepository.findById(vId)).willReturn(Optional.of(activeVillage(vId, "板の村")));
        given(timelinePostRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of());
        BulletinThreadEntity t = BulletinThreadEntity.builder()
                .categoryId(1L)
                .scopeType(com.mannschaft.app.bulletin.ScopeType.PERSONAL)
                .scopeId(0L).scopeVillageId(vId)
                .title("回覧板タイトル").body("本文").build();
        VillageSearchServiceTest.setBaseEntityId(t, 7L);
        VillageSearchServiceTest.setBaseEntityCreatedAt(t, t(12));
        given(bulletinThreadRepository.findLatestByVillageId(eq(vId), any()))
                .willReturn(List.of(t));
        given(chatChannelRepository.findByVillageIdAndChannelType(vId, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty());

        VillageFeedResponse res = service.build(USER_ID, 20);

        assertThat(res.feed()).hasSize(1);
        assertThat(res.feed().get(0).type()).isEqualTo("TIMELINE");
        assertThat(res.feed().get(0).snippet()).contains("回覧板タイトル");
    }
}
