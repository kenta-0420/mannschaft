package com.mannschaft.app.social.announcement.audience;

import com.mannschaft.app.organization.teamgroup.service.OrgTeamGroupService;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedGroupSnapshotRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository.OrgFeedCursor;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService.TeamOrgGroupAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F01.2.1 部隊 6-C — {@link AnnouncementAudienceMatcher#findVisibleOrgFeeds} の読み進め（Codex 検分 2 巡目 中）。
 *
 * <p>ページの間にピン留めが変わると、既に読んだ行が次のページにもう一度現れうる（実 DB での再現は
 * {@code AnnouncementAudiencePagingConsistencyIT}）。ここでは 1 回の呼び出しの中でその状況を作り、
 * 重複した行を件数に数えないこと、位置が取得時のピン留めで渡ること、位置の行が消えたときに
 * 存在を確かめて打ち切ることを固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("F01.2.1 6-C 組織告知の読み進め: 重複を数えない・位置・位置の行の削除")
class AnnouncementAudienceMatcherPagingTest {

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Set<String> VIS = Set.of("MEMBERS_AND_ABOVE");
    private static final int PAGE = AnnouncementAudienceMatcher.PAGE_SIZE;

    @Mock private TeamOrgMembershipQueryService membershipQueryService;
    @Mock private OrgTeamGroupService orgTeamGroupService;
    @Mock private AnnouncementFeedGroupSnapshotRepository snapshotRepository;
    @Mock private AnnouncementFeedQueryRepository feedQueryRepository;
    @Mock private AnnouncementFeedRepository feedRepository;

    @InjectMocks
    private AnnouncementAudienceMatcher matcher;

    @BeforeEach
    void setUp() {
        given(membershipQueryService.findActiveOrgGroupAssignments(TEAM_ID))
                .willReturn(List.of(new TeamOrgGroupAssignment(ORG_ID, null)));
    }

    @Test
    @DisplayName("次のページに既読の行が再び現れても件数に数えず、本来の続きの行で上限に達する")
    void rereadRowIsNotCounted() {
        // 1 ページ目: id 1..50（全チーム宛て。最後の行はピン留め）
        List<AnnouncementFeedEntity> page1 = feeds(1, PAGE);
        page1.set(PAGE - 1, feed(PAGE, true));
        // 2 ページ目: 既読の id 50 がもう一度現れ、その後ろに 51・52
        List<AnnouncementFeedEntity> page2 = new ArrayList<>(List.of(feed(PAGE, false)));
        page2.addAll(feeds(PAGE + 1, PAGE + 2));
        given(feedQueryRepository.findOrgScopePageForTeamDashboard(
                eq(ORG_ID), eq(VIS), eq(TEAM_ID), isNull(), isNull(), anyInt())).willReturn(page1);
        given(feedQueryRepository.findOrgScopePageForTeamDashboard(
                eq(ORG_ID), eq(VIS), eq(TEAM_ID), isNull(), eq(new OrgFeedCursor(true, (long) PAGE)), anyInt()))
                .willReturn(page2);

        List<Long> ids = matcher.findVisibleOrgFeeds(TEAM_ID, VIS, PAGE + 2).stream()
                .map(AnnouncementFeedEntity::getId).toList();

        assertThat(ids).as("重複を数えず、本来の 51・52 まで読む")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, PAGE + 2).boxed().toList());
    }

    @Test
    @DisplayName("位置の行が消えて次のページが空になったら、行の存在を確かめて打ち切る（行が尽きた場合と区別する）")
    void deletedCursorRowIsDetected() {
        given(feedQueryRepository.findOrgScopePageForTeamDashboard(
                eq(ORG_ID), eq(VIS), eq(TEAM_ID), isNull(), isNull(), anyInt())).willReturn(feeds(1, PAGE));
        given(feedQueryRepository.findOrgScopePageForTeamDashboard(
                eq(ORG_ID), eq(VIS), eq(TEAM_ID), isNull(), any(OrgFeedCursor.class), anyInt())).willReturn(List.of());
        given(feedRepository.existsById((long) PAGE)).willReturn(false);

        assertThat(matcher.findVisibleOrgFeeds(TEAM_ID, VIS, PAGE + 10)).hasSize(PAGE);
        verify(feedRepository).existsById((long) PAGE);
    }

    @Test
    @DisplayName("行が 1 ページに収まって尽きたときは、位置の行の存在を確かめない（SQL を増やさない）")
    void exhaustedWithinPageDoesNotCheckCursor() {
        given(feedQueryRepository.findOrgScopePageForTeamDashboard(
                eq(ORG_ID), eq(VIS), eq(TEAM_ID), isNull(), isNull(), anyInt())).willReturn(feeds(1, 3));

        assertThat(matcher.findVisibleOrgFeeds(TEAM_ID, VIS, 20)).hasSize(3);
        verify(feedRepository, never()).existsById(any());
    }

    private static List<AnnouncementFeedEntity> feeds(long fromId, long toId) {
        List<AnnouncementFeedEntity> list = new ArrayList<>();
        for (long id = fromId; id <= toId; id++) {
            list.add(feed(id, false));
        }
        return list;
    }

    private static AnnouncementFeedEntity feed(long id, boolean pinned) {
        return AnnouncementFeedEntity.builder()
                .id(id)
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(ORG_ID)
                .sourceType(AnnouncementSourceType.BULLETIN_THREAD)
                .sourceId(id)
                .titleCache("告知" + id)
                .isPinned(pinned)
                .build();
    }
}
