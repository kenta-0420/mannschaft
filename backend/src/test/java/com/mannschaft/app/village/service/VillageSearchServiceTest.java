package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageInternalSearchItemResponse;
import com.mannschaft.app.village.dto.VillageInternalSearchResponse;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link VillageSearchService} 単体テスト（F17.1 B10）。
 *
 * <p>設計書 §4.12 / §5 / §6.1 に従い以下を検証:</p>
 * <ul>
 *   <li>type=POST / MESSAGE / MEMBER / ALL のフィルタ挙動</li>
 *   <li>村人のみ検索可（非村人は VILLAGE_007 → 404）</li>
 *   <li>空クエリ / 最低文字数（&lt;2）/不正 type は VILLAGE_051</li>
 *   <li>MEMBER 検索結果に userId が混入しないこと（§6.1）</li>
 *   <li>削除済み村は VILLAGE_001</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageSearchService 単体テスト")
class VillageSearchServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000010");
    private static final Long ACTOR_USER_ID = 300L;
    private static final Long LOBBY_CHANNEL_ID = 9999L;

    @Mock private VillageRepository villageRepository;
    @Mock private VillageMembershipRepository membershipRepository;
    @Mock private UserVillageNicknameRepository nicknameRepository;
    @Mock private BulletinThreadRepository bulletinThreadRepository;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private ChatMessageRepository chatMessageRepository;
    @Mock private ChatChannelRepository chatChannelRepository;

    @InjectMocks
    private VillageSearchService service;

    private VillageEntity activeVillage;

    @BeforeEach
    void setUp() {
        activeVillage = VillageEntity.builder()
                .slug("search-test")
                .name("検索村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .memberCountCache(0L)
                .build();
        activeVillage.setId(VILLAGE_ID);
    }

    // ============================================================
    // 入力バリデーション
    // ============================================================

    @Test
    @DisplayName("q が null なら VILLAGE_051（VILLAGE_SEARCH_INVALID_QUERY）")
    void search_nullQuery_throws() {
        assertThatThrownBy(() -> service.search(VILLAGE_ID, null, null, 0, 20, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
    }

    @Test
    @DisplayName("q が 1 文字なら VILLAGE_051")
    void search_tooShortQuery_throws() {
        assertThatThrownBy(() -> service.search(VILLAGE_ID, "a", null, 0, 20, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
    }

    @Test
    @DisplayName("type に未知の値を指定すると VILLAGE_051")
    void search_unknownType_throws() {
        assertThatThrownBy(() -> service.search(VILLAGE_ID, "整骨", "BOGUS", 0, 20, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
    }

    // ============================================================
    // 権限
    // ============================================================

    @Test
    @DisplayName("削除済み村は VILLAGE_NOT_FOUND")
    void search_deletedVillage_404() {
        activeVillage.setDeletedAt(LocalDateTime.now());
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));

        assertThatThrownBy(() -> service.search(VILLAGE_ID, "整骨", "ALL", 0, 20, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND);
    }

    @Test
    @DisplayName("非村人は VILLAGE_NOT_MEMBER（IDOR 対策で 404）")
    void search_notMember_404() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.search(VILLAGE_ID, "整骨", "ALL", 0, 20, ACTOR_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    @Test
    @DisplayName("認証ユーザー ID が null（未認証）の場合 VILLAGE_NOT_MEMBER")
    void search_unauthenticated_404() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));

        assertThatThrownBy(() -> service.search(VILLAGE_ID, "整骨", "ALL", 0, 20, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(VillageErrorCode.NOT_MEMBER);
    }

    // ============================================================
    // タイプフィルタ
    // ============================================================

    @Test
    @DisplayName("type=POST のとき bulletin + timeline のみ検索される")
    void search_typePost_onlyPosts() {
        prepareValidMembership();
        given(bulletinThreadRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of(bulletinThread(1L, "整骨院の話", "本文ボディ", t(1))));
        given(bulletinThreadRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(1L);
        given(timelinePostRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of(timelinePost(2L, "整骨師の腰痛体操", t(2))));
        given(timelinePostRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(1L);

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", "POST", 0, 20, ACTOR_USER_ID);

        assertThat(res.items()).hasSize(2);
        assertThat(res.items()).extracting(VillageInternalSearchItemResponse::type)
                .containsOnly("POST");
        assertThat(res.items()).extracting(VillageInternalSearchItemResponse::postKind)
                .containsExactlyInAnyOrder("BULLETIN_THREAD", "TIMELINE_POST");
        assertThat(res.total()).isEqualTo(2L);
    }

    @Test
    @DisplayName("type=MESSAGE のとき lobby メッセージのみ検索される")
    void search_typeMessage_onlyMessages() {
        prepareValidMembership();
        prepareLobbyChannel();
        given(chatMessageRepository.searchByChannelIdAndKeyword(eq(LOBBY_CHANNEL_ID), anyString(), any()))
                .willReturn(List.of(chatMessage(11L, "整骨に関する一言", t(3))));
        given(chatMessageRepository.countByChannelIdAndKeyword(eq(LOBBY_CHANNEL_ID), anyString())).willReturn(1L);

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", "MESSAGE", 0, 20, ACTOR_USER_ID);

        assertThat(res.items()).hasSize(1);
        assertThat(res.items().get(0).type()).isEqualTo("MESSAGE");
        assertThat(res.items().get(0).channelId()).isEqualTo(LOBBY_CHANNEL_ID);
    }

    @Test
    @DisplayName("type=MEMBER のときニックネームのみ検索される + userId は返却されない")
    void search_typeMember_noUserIdLeak() {
        prepareValidMembership();
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(101L, 102L));
        UUID nickRowId = UUID.fromString("01956c00-0000-7000-8000-0000000000bb");
        UserVillageNicknameEntity nick = UserVillageNicknameEntity.builder()
                .userId(101L)
                .nickname("山田太郎")
                .avatarR2Key("avatar/100.jpg")
                .lastChangedAt(LocalDateTime.now())
                .changeCountThisMonth(0L)
                .build();
        nick.setId(nickRowId);
        given(nicknameRepository.searchByUserIdsAndKeyword(anyCollection(), anyString(), any()))
                .willReturn(List.of(nick));
        given(nicknameRepository.countByUserIdsAndKeyword(anyCollection(), anyString())).willReturn(1L);

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "山田", "MEMBER", 0, 20, ACTOR_USER_ID);

        assertThat(res.items()).hasSize(1);
        VillageInternalSearchItemResponse item = res.items().get(0);
        assertThat(item.type()).isEqualTo("MEMBER");
        assertThat(item.nickname()).isEqualTo("山田太郎");
        assertThat(item.avatarR2Key()).isEqualTo("avatar/100.jpg");
        // §6.1 個人特定情報保護: id は nickname 行の UUID であり、user_id ではない
        assertThat(item.id()).isEqualTo(nickRowId.toString());
        // record の全フィールドを精査して "userId" 名のフィールドが存在しないことを保証
        for (Field f : VillageInternalSearchItemResponse.class.getDeclaredFields()) {
            assertThat(f.getName()).isNotEqualTo("userId");
        }
    }

    @Test
    @DisplayName("type=ALL（デフォルト）のとき POST + MESSAGE + MEMBER 全て取得")
    void search_typeAll_includesAllKinds() {
        prepareValidMembership();
        prepareLobbyChannel();
        given(bulletinThreadRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of(bulletinThread(1L, "整骨タイトル", "本文", t(5))));
        given(bulletinThreadRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(1L);
        given(timelinePostRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of(timelinePost(2L, "整骨投稿", t(4))));
        given(timelinePostRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(1L);
        given(chatMessageRepository.searchByChannelIdAndKeyword(eq(LOBBY_CHANNEL_ID), anyString(), any()))
                .willReturn(List.of(chatMessage(11L, "整骨", t(3))));
        given(chatMessageRepository.countByChannelIdAndKeyword(eq(LOBBY_CHANNEL_ID), anyString())).willReturn(1L);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(101L));
        UserVillageNicknameEntity nick = UserVillageNicknameEntity.builder()
                .userId(101L).nickname("整骨太郎").lastChangedAt(LocalDateTime.now()).changeCountThisMonth(0L).build();
        nick.setId(UUID.randomUUID());
        given(nicknameRepository.searchByUserIdsAndKeyword(anyCollection(), anyString(), any()))
                .willReturn(List.of(nick));
        given(nicknameRepository.countByUserIdsAndKeyword(anyCollection(), anyString())).willReturn(1L);

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", null, 0, 20, ACTOR_USER_ID);

        assertThat(res.items()).hasSize(4);
        assertThat(res.items()).extracting(VillageInternalSearchItemResponse::type)
                .containsExactlyInAnyOrder("POST", "POST", "MESSAGE", "MEMBER");
        assertThat(res.total()).isEqualTo(4L);
    }

    @Test
    @DisplayName("ロビーチャネル未生成なら MESSAGE 検索は空（村は有効）")
    void search_noLobbyChannel_messageEmpty() {
        prepareValidMembership();
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.empty());

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", "MESSAGE", 0, 20, ACTOR_USER_ID);

        assertThat(res.items()).isEmpty();
        assertThat(res.total()).isZero();
    }

    @Test
    @DisplayName("ページサイズは MAX_PAGE_SIZE=50 でクリップされる")
    void search_pageSize_capped() {
        prepareValidMembership();
        // total は 0 でも response の size が clip されているか確認できれば良い
        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", "POST", 0, 9999, ACTOR_USER_ID);
        assertThat(res.size()).isEqualTo(VillageSearchService.MAX_PAGE_SIZE);
    }

    /**
     * AC-11 / AC-12: 1タイプあたりの取得上限（Service 内部の
     * {@code PER_TYPE_FETCH_HARD_CAP}。private のためテストからは直接参照できないが、
     * 実装値は 50。ここではそれを模して bulletinThreadRepository のモックに
     * 「頭打ちになった後のプール」をそのまま返させる）超のヒット数を持つタイプがあるとき、
     * total が実際に到達可能な件数（= 取得済みプールサイズそのもの）を超えず、
     * その total から算出した最終ページを要求しても空配列にならないこと。
     *
     * <p><b>検証したい不変条件は「total はモックが返したプールの件数（{@code cappedPool.size()}）を
     * 超えない」ことであり、{@link VillageSearchService#MAX_PAGE_SIZE}（ページサイズ上限）とは
     * 無関係</b>。両者はたまたま実装値が同じ 50 なだけで意味が異なる別の定数のため、
     * {@code MAX_PAGE_SIZE} には錨を下ろさず {@code cappedPool.size()} を直接の期待値にする
     * （将来どちらかの定数値が変わっても、この不変条件が偶然の一致で緑のまま形骸化したり、
     * 無関係な理由で赤くなったりしないようにするため）。</p>
     *
     * <p>POST タイプの実件数（countPosts）は 60 件だが、SQL 取得は 1タイプあたりの上限で
     * 頭打ちになり、実際に取得できているのは {@code cappedPool.size()} 件のみ。旧実装は
     * total にキャップ無しの 60 をそのまま返しており、その total を信じてページ送りすると
     * プールを超える範囲は例外も出ずに空配列を返し続けていた。</p>
     */
    @Test
    @DisplayName("AC-11: 1タイプが上限超のヒット数を持つとき total は実取得可能件数を超えない")
    void search_total_neverExceedsRetrievablePool() {
        prepareValidMembership();
        // 実装の PER_TYPE_FETCH_HARD_CAP（50）を模した「頭打ち後のプール」。
        // MAX_PAGE_SIZE とは無関係な、この検証専用のローカル値。
        List<BulletinThreadEntity> cappedPool = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            cappedPool.add(bulletinThread(i, "整骨" + i, "本文" + i, t((int) i)));
        }
        given(bulletinThreadRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(cappedPool);
        // 実件数（キャップ無し）は 60 件だが、実際に取得できているのはプール分のみ。
        given(bulletinThreadRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(60L);
        given(timelinePostRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of());
        given(timelinePostRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(0L);

        VillageInternalSearchResponse res = service.search(VILLAGE_ID, "整骨", "POST", 0, 20, ACTOR_USER_ID);

        // 不変条件本体: total はモックが実際に返したプールの件数を超えない。
        assertThat(res.total()).isLessThanOrEqualTo((long) cappedPool.size());
        assertThat(res.total()).isEqualTo((long) cappedPool.size());
    }

    @Test
    @DisplayName("AC-12: total が示す最終ページを要求しても空配列にならない")
    void search_lastPageFromTotal_isNotEmpty() {
        prepareValidMembership();
        List<BulletinThreadEntity> cappedPool = new ArrayList<>();
        for (long i = 1; i <= 50; i++) {
            cappedPool.add(bulletinThread(i, "整骨" + i, "本文" + i, t((int) i)));
        }
        given(bulletinThreadRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(cappedPool);
        given(bulletinThreadRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(60L);
        given(timelinePostRepository.searchByVillageIdAndKeyword(eq(VILLAGE_ID), anyString(), any()))
                .willReturn(List.of());
        given(timelinePostRepository.countByVillageIdAndKeyword(eq(VILLAGE_ID), anyString())).willReturn(0L);

        int size = 20;
        // まず total を得る（page=0 で問い合わせ）
        VillageInternalSearchResponse first = service.search(VILLAGE_ID, "整骨", "POST", 0, size, ACTOR_USER_ID);
        int lastPage = (int) ((first.total() - 1) / size);

        VillageInternalSearchResponse lastRes =
                service.search(VILLAGE_ID, "整骨", "POST", lastPage, size, ACTOR_USER_ID);

        assertThat(lastRes.items()).isNotEmpty();
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    private void prepareValidMembership() {
        given(villageRepository.findById(VILLAGE_ID)).willReturn(Optional.of(activeVillage));
        given(membershipRepository.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                VILLAGE_ID, VillageSubjectType.USER, ACTOR_USER_ID))
                .willReturn(Optional.of(member(ACTOR_USER_ID)));
    }

    private void prepareLobbyChannel() {
        ChatChannelEntity ch = ChatChannelEntity.builder()
                .channelType(ChannelType.VILLAGE_LOBBY)
                .villageId(VILLAGE_ID)
                .name("井戸端会議")
                .isPrivate(false)
                .isArchived(false)
                .activeThreadCount(0)
                .build();
        setBaseEntityId(ch, LOBBY_CHANNEL_ID);
        given(chatChannelRepository.findByVillageIdAndChannelType(VILLAGE_ID, ChannelType.VILLAGE_LOBBY))
                .willReturn(Optional.of(ch));
    }

    private VillageMembershipEntity member(Long userId) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(VILLAGE_ID)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.VILLAGER)
                .joinedAt(LocalDateTime.now())
                .build();
        m.setId(UUID.randomUUID());
        return m;
    }

    private BulletinThreadEntity bulletinThread(Long id, String title, String body, LocalDateTime createdAt) {
        // F17.1 Phase 1: 村スコープは scopeVillageId で識別する。scopeType は既存値の PERSONAL を流用
        // （Repository クエリでは scopeVillageId のみで絞り込むため、scopeType 値はテスト用ダミー）。
        BulletinThreadEntity t = BulletinThreadEntity.builder()
                .categoryId(1L)
                .scopeType(com.mannschaft.app.bulletin.ScopeType.PERSONAL)
                .scopeId(0L)
                .scopeVillageId(VILLAGE_ID)
                .title(title)
                .body(body)
                .build();
        setBaseEntityId(t, id);
        setBaseEntityCreatedAt(t, createdAt);
        return t;
    }

    private TimelinePostEntity timelinePost(Long id, String content, LocalDateTime createdAt) {
        TimelinePostEntity p = TimelinePostEntity.builder()
                .scopeType(com.mannschaft.app.timeline.PostScopeType.PERSONAL)
                .scopeId(0L)
                .scopeVillageId(VILLAGE_ID)
                .userId(101L)
                .content(content)
                .status(PostStatus.PUBLISHED)
                .build();
        setBaseEntityId(p, id);
        setBaseEntityCreatedAt(p, createdAt);
        return p;
    }

    private ChatMessageEntity chatMessage(Long id, String body, LocalDateTime createdAt) {
        ChatMessageEntity m = ChatMessageEntity.builder()
                .channelId(LOBBY_CHANNEL_ID)
                .senderId(101L)
                .body(body)
                .build();
        setBaseEntityId(m, id);
        setBaseEntityCreatedAt(m, createdAt);
        return m;
    }

    private static LocalDateTime t(int seconds) {
        return LocalDateTime.of(2026, 5, 14, 12, 0, seconds);
    }

    /** BaseEntity の id を Reflection で設定（テスト専用）。 */
    static void setBaseEntityId(Object entity, Long id) {
        try {
            Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    static void setBaseEntityCreatedAt(Object entity, LocalDateTime createdAt) {
        try {
            Field f = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(entity, createdAt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
