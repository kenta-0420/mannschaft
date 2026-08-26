package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TeamFriendQueryService} の単体テスト（リファクタリング第4弾 Phase 4-B で分離）。
 *
 * <p>フレンド一覧取得の振る舞い（ADMIN 全件 / SUPPORTER 公開のみ）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFriendQueryService 単体テスト")
class TeamFriendQueryServiceTest {

    @Mock
    private TeamFriendRepository teamFriendRepository;

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AccessControlService accessControlService;

    private TeamFriendQueryService teamFriendQueryService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long TARGET_TEAM_ID = 20L;

    /**
     * {@link TeamFriendQueryService} は {@code @Cacheable} の自己呼び出しを避けるため
     * 自分自身（プロキシ）を {@code self} として注入する（issue #2496）。
     * 単体テストでは AOP プロキシが介在しないため、{@code self} に同じ実インスタンスを渡して
     * {@code listFriends → listFriendViews} の経路を直接通す
     * （{@code WidgetVisibilityResolverTest} と同型）。
     */
    @BeforeEach
    void setUp() {
        teamFriendQueryService = new TeamFriendQueryService(
                teamFriendRepository, teamRepository, accessControlService, null);
        ReflectionTestUtils.setField(teamFriendQueryService, "self", teamFriendQueryService);
    }

    @Test
    @DisplayName("正常系: ADMIN(publicOnly=false) には全件返る")
    void ADMIN_には全件返る() {
        // given
        TeamFriendEntity publicFriend = buildTeamFriendWithPublic(TEAM_ID, TARGET_TEAM_ID, 1L, true);
        TeamFriendEntity privateFriend = buildTeamFriendWithPublic(TEAM_ID, 30L, 2L, false);
        TeamEntity friendTeamA = TeamEntity.builder().name("公開チーム").build();
        TeamEntity friendTeamB = TeamEntity.builder().name("非公開チーム").build();
        Pageable pageable = PageRequest.of(0, 20);

        given(teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                TEAM_ID, TEAM_ID, false, pageable))
                .willReturn(new PageImpl<>(List.of(publicFriend, privateFriend), pageable, 2));
        given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(friendTeamA));
        given(teamRepository.findById(30L)).willReturn(Optional.of(friendTeamB));

        // when
        var result = teamFriendQueryService.listFriends(TEAM_ID, USER_ID, pageable, false);

        // then
        assertThat(result.getContent()).hasSize(2);
        assertThat(result.getTotalElements()).isEqualTo(2);
        verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
    }

    /**
     * SUPPORTER 向けの is_public 絞り込みは CMP-028 Phase D で SQL の WHERE に降りた。
     * 単体テストでは「publicOnly=true が正しく Repository の引数へ渡ること」を検証する
     * （行の除外そのものはメモリフィルタではなく実 DB が担保するため、単体で行数を検証しても
     * 同語反復にしかならない。実データでの検証は
     * {@code TeamFriendRepositoryVisibilityInTest} が担う）。
     */
    @Test
    @DisplayName("正常系: SUPPORTER(publicOnly=true) は Repository へ publicOnly=true が渡る")
    void SUPPORTER_はpublicOnly引数がSQLへ渡る() {
        // given: publicOnly=true で呼んだ場合、SQL 側で絞り込み済みの1件のみが返る想定
        TeamFriendEntity publicFriend = buildTeamFriendWithPublic(TEAM_ID, TARGET_TEAM_ID, 1L, true);
        TeamEntity friendTeam = TeamEntity.builder().name("公開チーム").build();
        Pageable pageable = PageRequest.of(0, 20);

        given(teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                TEAM_ID, TEAM_ID, true, pageable))
                .willReturn(new PageImpl<>(List.of(publicFriend), pageable, 1));
        given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(friendTeam));

        // when
        var result = teamFriendQueryService.listFriends(TEAM_ID, USER_ID, pageable, true);

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getFriendTeamId()).isEqualTo(TARGET_TEAM_ID);
        assertThat(result.getTotalElements()).isEqualTo(1);
        // publicOnly=true が Repository へそのまま渡っていることの実証
        verify(teamFriendRepository).findVisibleByTeamAIdOrTeamBId(TEAM_ID, TEAM_ID, true, pageable);
    }

    @Test
    @DisplayName("listFriendsResponse: 整形済みレスポンスが正しく返る")
    void listFriendsResponse_整形() {
        // given
        TeamFriendEntity publicFriend = buildTeamFriendWithPublic(TEAM_ID, TARGET_TEAM_ID, 1L, true);
        TeamEntity friendTeam = TeamEntity.builder().name("公開チーム").build();
        Pageable pageable = PageRequest.of(0, 20);

        given(teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                TEAM_ID, TEAM_ID, false, pageable))
                .willReturn(new PageImpl<>(List.of(publicFriend), pageable, 1));
        given(teamRepository.findById(TARGET_TEAM_ID)).willReturn(Optional.of(friendTeam));

        // when
        var response = teamFriendQueryService.listFriendsResponse(TEAM_ID, USER_ID, pageable, false);

        // then
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getPagination().getPage()).isZero();
        assertThat(response.getPagination().getSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("認可はキャッシュ層より先に実行される: 非メンバーは 403 になり DB を一切引かない（issue #2496）")
    void 非メンバーは所属チェックで弾かれDBを引かない() {
        // given: 所属チェックが 403 を投げる
        doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .when(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        Pageable pageable = PageRequest.of(0, 20);

        // when / then
        assertThatThrownBy(() -> teamFriendQueryService.listFriends(TEAM_ID, USER_ID, pageable, false))
                .isInstanceOf(BusinessException.class);

        // 認可がキャッシュ対象メソッドの外側にあるため、DB 取得まで到達しない
        verifyNoInteractions(teamFriendRepository);
    }

    private TeamFriendEntity buildTeamFriendWithPublic(Long teamAId, Long teamBId, Long id, boolean isPublic) {
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
