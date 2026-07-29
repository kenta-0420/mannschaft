package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TeamFriendQueryService} のキャッシュ挙動検証（issue #2496 の回帰防止）。
 *
 * <h2>なぜこのテストが必要なのか</h2>
 * <p>本サービスのキャッシュは <b>導入以来一度も発火していなかった</b>。
 * {@code listFriendsResponse} が同一 Bean 内の {@code this.listFriends()} を呼んでおり、
 * Spring のキャッシュ AOP（プロキシ方式）が作用しなかったためである。
 * Mockito だけの単体テストでは AOP プロキシが介在しないため、
 * <b>「キャッシュが効いていない」ことを一切検知できない</b>。
 * よって {@code IncidentBannerServiceCacheTest} と同じ流儀で、
 * {@link AnnotationConfigApplicationContext} に {@code @EnableCaching} と
 * {@link ConcurrentMapCacheManager} を載せた最小コンテキストを起動し、
 * <b>実プロキシ越し</b >に検証する（MySQL / Redis / Testcontainers は使わない）。</p>
 *
 * <h2>固定する不変条件</h2>
 * <ol>
 *   <li>キャッシュが<b>実際に発火する</b>（同一条件の 2 回目は DB を引かない）</li>
 *   <li>キャッシュヒット時も<b>認可は必ず実行される</b>（温めた後でも非メンバーは 403）</li>
 *   <li>行為者（{@code userId}）ごとにキャッシュエントリが分かれる</li>
 *   <li>失効（{@code allEntries} 相当）後は再取得される</li>
 * </ol>
 */
@DisplayName("TeamFriendQueryService キャッシュ挙動 検証 (issue #2496)")
class TeamFriendQueryServiceCacheTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long NON_MEMBER_ID = 99L;
    private static final Long TEAM_ID = 10L;
    private static final Long FRIEND_TEAM_ID = 20L;
    private static final String SCOPE_TEAM = "TEAM";

    /**
     * 最小キャッシュ有効コンテキスト構成。依存は static モックを共有して verify する。
     *
     * <p>{@link TeamFriendQueryService} は {@code @Bean} メソッドで {@code new} せず
     * {@code @Import} で登録する。{@code @Bean} メソッドの引数として自分自身を受け取ると
     * Spring が循環参照を検出して起動に失敗するため、クラス本来のコンストラクタ
     * （{@code self} 引数に {@code @Lazy} が付いている）を Spring 自身に使わせる必要がある。
     * これにより本番と同じ「遅延解決される自己プロキシ」が注入される。</p>
     */
    @Configuration
    @EnableCaching
    @Import(TeamFriendQueryService.class)
    static class CacheSliceConfig {

        static final TeamFriendRepository FRIEND_REPO = mock(TeamFriendRepository.class);
        static final TeamRepository TEAM_REPO = mock(TeamRepository.class);
        static final AccessControlService ACCESS_CONTROL = mock(AccessControlService.class);

        @Bean
        ConcurrentMapCacheManager cacheManager() {
            return new ConcurrentMapCacheManager("teamFriendList");
        }

        @Bean
        TeamFriendRepository teamFriendRepository() {
            return FRIEND_REPO;
        }

        @Bean
        TeamRepository teamRepository() {
            return TEAM_REPO;
        }

        @Bean
        AccessControlService accessControlService() {
            return ACCESS_CONTROL;
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private TeamFriendQueryService service;
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        reset(CacheSliceConfig.FRIEND_REPO, CacheSliceConfig.TEAM_REPO, CacheSliceConfig.ACCESS_CONTROL);
        ctx = new AnnotationConfigApplicationContext(CacheSliceConfig.class);
        service = ctx.getBean(TeamFriendQueryService.class);
        cacheManager = ctx.getBean(CacheManager.class);

        TeamFriendEntity friend = buildFriend(TEAM_ID, FRIEND_TEAM_ID, 1L, true);
        when(CacheSliceConfig.FRIEND_REPO.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                anyLong(), anyLong(), any(Pageable.class))).thenReturn(List.of(friend));
        when(CacheSliceConfig.TEAM_REPO.findById(anyLong()))
                .thenReturn(Optional.of(TeamEntity.builder().name("フレンドチーム").build()));
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    // ------------------------------------------------------------------
    // 1. キャッシュが実際に発火すること
    // ------------------------------------------------------------------

    @Test
    @DisplayName("同一条件の2回目はキャッシュHITし DB を引かない（キャッシュが実際に発火している証明）")
    void 同一条件の2回目はキャッシュHITする() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        service.listFriends(TEAM_ID, USER_ID, pageable, false);

        verify(CacheSliceConfig.FRIEND_REPO, times(1))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("listFriendsResponse 経由でもキャッシュが発火する（自己呼び出し解消の実証）")
    void listFriendsResponse経由でもキャッシュが発火する() {
        Pageable pageable = PageRequest.of(0, 20);

        // 旧実装では listFriendsResponse → this.listFriends の自己呼び出しで
        // @Cacheable が素通りし、ここが必ず 2 回になっていた。
        service.listFriendsResponse(TEAM_ID, USER_ID, pageable, false);
        service.listFriendsResponse(TEAM_ID, USER_ID, pageable, false);

        verify(CacheSliceConfig.FRIEND_REPO, times(1))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("キャッシュHIT時も同じ内容が返る（キャッシュ値が壊れていない）")
    void キャッシュHITでも同じ内容が返る() {
        Pageable pageable = PageRequest.of(0, 20);

        var first = service.listFriends(TEAM_ID, USER_ID, pageable, false);
        var second = service.listFriends(TEAM_ID, USER_ID, pageable, false);

        assertThat(second.getContent()).hasSize(1);
        assertThat(second.getContent().get(0).getFriendTeamId())
                .isEqualTo(first.getContent().get(0).getFriendTeamId());
        assertThat(second.getContent().get(0).isPublic()).isTrue();
    }

    // ------------------------------------------------------------------
    // 2. 認可がキャッシュヒット時にも必ず実行されること（本 issue の核心）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("キャッシュHIT時も認可は毎回実行される（DBは1回でも checkMembership は2回）")
    void キャッシュHITでも認可は毎回実行される() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        service.listFriends(TEAM_ID, USER_ID, pageable, false);

        // DB は 1 回（＝確かにキャッシュHITしている）
        verify(CacheSliceConfig.FRIEND_REPO, times(1))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
        // 認可は 2 回（＝キャッシュHITでも飛ばされていない）
        verify(CacheSliceConfig.ACCESS_CONTROL, times(2))
                .checkMembership(USER_ID, TEAM_ID, SCOPE_TEAM);
    }

    @Test
    @DisplayName("メンバーがキャッシュを温めた後でも、非メンバーは 403 になる（キャッシュ経由の認可バイパスが無い）")
    void 温めた後でも非メンバーは403になる() {
        Pageable pageable = PageRequest.of(0, 20);

        // メンバーがキャッシュを温める
        service.listFriends(TEAM_ID, USER_ID, pageable, false);

        // 非メンバーは所属チェックで弾かれる
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(CacheSliceConfig.ACCESS_CONTROL)
                .checkMembership(NON_MEMBER_ID, TEAM_ID, SCOPE_TEAM);

        assertThatThrownBy(() -> service.listFriends(TEAM_ID, NON_MEMBER_ID, pageable, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("非メンバーが先に叩いてもキャッシュは汚染されない（403 のあと DB は引かれていない）")
    void 非メンバーの試行はキャッシュを汚染しない() {
        Pageable pageable = PageRequest.of(0, 20);
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(CacheSliceConfig.ACCESS_CONTROL)
                .checkMembership(NON_MEMBER_ID, TEAM_ID, SCOPE_TEAM);

        assertThatThrownBy(() -> service.listFriends(TEAM_ID, NON_MEMBER_ID, pageable, false))
                .isInstanceOf(BusinessException.class);

        verify(CacheSliceConfig.FRIEND_REPO, never())
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // 3. キーの構成要素ごとにエントリが分かれること
    // ------------------------------------------------------------------

    @Test
    @DisplayName("行為者(userId)が違えばキャッシュエントリが分かれる（キーに userId が入っていることの実証）")
    void 行為者ごとにキャッシュが分かれる() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        service.listFriends(TEAM_ID, OTHER_USER_ID, pageable, false);

        verify(CacheSliceConfig.FRIEND_REPO, times(2))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
        verify(CacheSliceConfig.ACCESS_CONTROL, times(1)).checkMembership(USER_ID, TEAM_ID, SCOPE_TEAM);
        verify(CacheSliceConfig.ACCESS_CONTROL, times(1)).checkMembership(OTHER_USER_ID, TEAM_ID, SCOPE_TEAM);
    }

    @Test
    @DisplayName("publicOnly が違えばキャッシュエントリが分かれる（SUPPORTER に全件が漏れない）")
    void publicOnlyごとにキャッシュが分かれる() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        service.listFriends(TEAM_ID, USER_ID, pageable, true);

        verify(CacheSliceConfig.FRIEND_REPO, times(2))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("ページ番号・サイズが違えばキャッシュエントリが分かれる")
    void ページングごとにキャッシュが分かれる() {
        service.listFriends(TEAM_ID, USER_ID, PageRequest.of(0, 20), false);
        service.listFriends(TEAM_ID, USER_ID, PageRequest.of(1, 20), false);
        service.listFriends(TEAM_ID, USER_ID, PageRequest.of(0, 50), false);

        verify(CacheSliceConfig.FRIEND_REPO, times(3))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("チームが違えばキャッシュエントリが分かれる")
    void チームごとにキャッシュが分かれる() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        service.listFriends(FRIEND_TEAM_ID, USER_ID, pageable, false);

        verify(CacheSliceConfig.FRIEND_REPO, times(2))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    // ------------------------------------------------------------------
    // 4. 失効
    // ------------------------------------------------------------------

    @Test
    @DisplayName("失効（allEntries 相当のクリア）後は再び DB を引く")
    void 失効後は再取得される() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        // follow / unfollow / setVisibility / updateTeam / deleteTeam の
        // @CacheEvict(allEntries = true) と同じ効果
        cacheManager.getCache("teamFriendList").clear();
        service.listFriends(TEAM_ID, USER_ID, pageable, false);

        verify(CacheSliceConfig.FRIEND_REPO, times(2))
                .findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(anyLong(), anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("失効後はフレンド関係の変更が次回取得に反映される（古い表示が残らない）")
    void 失効後は新しい内容が返る() {
        Pageable pageable = PageRequest.of(0, 20);

        var before = service.listFriends(TEAM_ID, USER_ID, pageable, false);
        assertThat(before.getContent()).hasSize(1);

        // フレンド解除相当（DB からフレンドが消える）
        when(CacheSliceConfig.FRIEND_REPO.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                anyLong(), anyLong(), any(Pageable.class))).thenReturn(List.of());

        // 失効していなければ古い 1 件が返り続ける
        cacheManager.getCache("teamFriendList").clear();

        var after = service.listFriends(TEAM_ID, USER_ID, pageable, false);
        assertThat(after.getContent()).isEmpty();
    }

    @Test
    @DisplayName("失効しない限り古い内容が返る（上のテストが空虚でないことの対照）")
    void 失効しなければ古い内容が残る() {
        Pageable pageable = PageRequest.of(0, 20);

        service.listFriends(TEAM_ID, USER_ID, pageable, false);
        when(CacheSliceConfig.FRIEND_REPO.findByTeamAIdOrTeamBIdOrderByEstablishedAtDesc(
                anyLong(), anyLong(), any(Pageable.class))).thenReturn(List.of());

        // clear() しない → キャッシュされた古い 1 件が返る
        var after = service.listFriends(TEAM_ID, USER_ID, pageable, false);
        assertThat(after.getContent()).hasSize(1);
    }

    // ------------------------------------------------------------------

    private static TeamFriendEntity buildFriend(Long teamAId, Long teamBId, Long id, boolean isPublic) {
        long aId = Math.min(teamAId, teamBId);
        long bId = Math.max(teamAId, teamBId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(aId)
                .teamBId(bId)
                .aFollowId(100L)
                .bFollowId(200L)
                .establishedAt(LocalDateTime.now())
                .isPublic(isPublic)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
