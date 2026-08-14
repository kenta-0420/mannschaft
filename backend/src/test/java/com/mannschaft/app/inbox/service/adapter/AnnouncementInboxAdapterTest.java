package com.mannschaft.app.inbox.service.adapter;

import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.social.announcement.AnnouncementReadStatusEntity;
import com.mannschaft.app.social.announcement.AnnouncementReadStatusRepository;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * F04.11 {@link AnnouncementInboxAdapter} 単体テスト（Mockito）。
 *
 * <p>設計書 03_business_logic.md §2・01_data_model.md §3.2・04_security_operations.md §1.2 から、
 * DashboardService と同じスコープ解決（所属チーム/組織 + RoleResolver の visibility 解決）に基づく fetch の
 * マッピング（title_cache/excerpt_cache・created_at・ANNOUNCEMENT 優先度写像・announcement_read_status の既読反映）
 * と、{@code isVisibleTo} のスコープ所属＋role visibility 判定（他人/未所属スコープは false＝IDOR）を受け入れ条件化する。</p>
 *
 * <p><b>注</b>: エンティティ mock の構築（{@code given} を含む）を別の {@code given} の引数内でネストすると
 * Mockito の UnfinishedStubbingException になるため、先にローカル変数へ組み立ててからリポジトリ stub に渡す。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnnouncementInboxAdapter 単体テスト")
class AnnouncementInboxAdapterTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final RoleResolver roleResolver = mock(RoleResolver.class);
    private final AnnouncementFeedQueryRepository feedQueryRepository =
            mock(AnnouncementFeedQueryRepository.class);
    private final AnnouncementFeedRepository feedRepository = mock(AnnouncementFeedRepository.class);
    private final AnnouncementReadStatusRepository readStatusRepository =
            mock(AnnouncementReadStatusRepository.class);
    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    private final AnnouncementInboxAdapter adapter = new AnnouncementInboxAdapter(
            userRoleRepository, roleResolver, feedQueryRepository, feedRepository,
            readStatusRepository, normalizer,
            new com.mannschaft.app.inbox.service.InboxDedupeKeyResolver());


    private AnnouncementFeedEntity feed(Long id, AnnouncementScopeType scopeType, Long scopeId,
                                        String priority, String visibility, String titleCache,
                                        String excerptCache, LocalDateTime createdAt,
                                        LocalDateTime expiresAt, LocalDateTime sourceDeletedAt) {
        AnnouncementFeedEntity feed = mock(AnnouncementFeedEntity.class);
        given(feed.getId()).willReturn(id);
        given(feed.getScopeType()).willReturn(scopeType);
        given(feed.getScopeId()).willReturn(scopeId);
        given(feed.getPriority()).willReturn(priority);
        given(feed.getVisibility()).willReturn(visibility);
        given(feed.getTitleCache()).willReturn(titleCache);
        given(feed.getExcerptCache()).willReturn(excerptCache);
        given(feed.getCreatedAt()).willReturn(createdAt);
        given(feed.getExpiresAt()).willReturn(expiresAt);
        given(feed.getSourceDeletedAt()).willReturn(sourceDeletedAt);
        return feed;
    }

    private AnnouncementReadStatusEntity readStatus(Long feedId) {
        AnnouncementReadStatusEntity r = mock(AnnouncementReadStatusEntity.class);
        given(r.getAnnouncementFeedId()).willReturn(feedId);
        return r;
    }

    /** 所属なし・既読なしの既定スタブ。 */
    private void noScopes() {
        given(userRoleRepository.findTeamIdsByUserId(USER_ID)).willReturn(List.of());
        given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
        given(readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(eq(USER_ID), any()))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("sourceType は ANNOUNCEMENT を返す")
    void sourceTypeIsAnnouncement() {
        assertThat(adapter.sourceType()).isEqualTo(InboxSourceType.ANNOUNCEMENT);
    }

    @Nested
    @DisplayName("fetch のマッピング")
    class Fetch {

        @Test
        @DisplayName("所属がなければ空を返す（findByScope を呼ばない）")
        void emptyWhenNoScopes() {
            noScopes();

            assertThat(adapter.fetch(USER_ID, 50)).isEmpty();
        }

        @Test
        @DisplayName("title_cache/excerpt_cache/created_at を写像し actionUrl と sourceId を導出する")
        void mapsFields() {
            LocalDateTime now = LocalDateTime.now();
            AnnouncementFeedEntity f = feed(
                    30L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL", "MEMBERS_AND_ABOVE",
                    "お知らせタイトル", "抜粋", now, null, null);
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), anyInt()))
                    .willReturn(List.of(f));
            given(readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(eq(USER_ID), any()))
                    .willReturn(List.of());

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).hasSize(1);
            InboxItemDto dto = items.get(0);
            assertThat(dto.id()).isEqualTo("ANNOUNCEMENT:30");
            assertThat(dto.sourceId()).isEqualTo(30L);
            assertThat(dto.title()).isEqualTo("お知らせタイトル");
            assertThat(dto.excerpt()).isEqualTo("抜粋");
            assertThat(dto.occurredAt()).isEqualTo(now);
            assertThat(dto.actionUrl()).isEqualTo("/announcements/30");
            assertThat(dto.scope().type()).isEqualTo("TEAM");
            assertThat(dto.scope().id()).isEqualTo(TEAM_ID);
            assertThat(dto.state()).isEqualTo(InboxState.UNREAD);
        }

        @Test
        @DisplayName("ANNOUNCEMENT の priority 写像: URGENT→URGENT / IMPORTANT→HIGH / NORMAL→NORMAL")
        void mapsPriority() {
            LocalDateTime now = LocalDateTime.now();
            AnnouncementFeedEntity u = feed(31L, AnnouncementScopeType.TEAM, TEAM_ID, "URGENT",
                    "MEMBERS_AND_ABOVE", "u", "e", now, null, null);
            AnnouncementFeedEntity i = feed(32L, AnnouncementScopeType.TEAM, TEAM_ID, "IMPORTANT",
                    "MEMBERS_AND_ABOVE", "i", "e", now, null, null);
            AnnouncementFeedEntity n = feed(33L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "n", "e", now, null, null);
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), anyInt()))
                    .willReturn(List.of(u, i, n));
            given(readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(eq(USER_ID), any()))
                    .willReturn(List.of());

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).extracting(InboxItemDto::sourceId, InboxItemDto::priority)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(31L, InboxPriority.URGENT),
                            org.assertj.core.groups.Tuple.tuple(32L, InboxPriority.HIGH),
                            org.assertj.core.groups.Tuple.tuple(33L, InboxPriority.NORMAL));
        }

        @Test
        @DisplayName("announcement_read_status に既読があれば READ、なければ UNREAD")
        void mapsReadState() {
            LocalDateTime now = LocalDateTime.now();
            AnnouncementFeedEntity read = feed(40L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "read", "e", now, null, null);
            AnnouncementFeedEntity unread = feed(41L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "unread", "e", now, null, null);
            AnnouncementReadStatusEntity rs = readStatus(40L);
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), anyInt()))
                    .willReturn(List.of(read, unread));
            given(readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(eq(USER_ID), any()))
                    .willReturn(List.of(rs));

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).extracting(InboxItemDto::sourceId, InboxItemDto::state)
                    .containsExactlyInAnyOrder(
                            org.assertj.core.groups.Tuple.tuple(40L, InboxState.READ),
                            org.assertj.core.groups.Tuple.tuple(41L, InboxState.UNREAD));
        }

        @Test
        @DisplayName("チームと組織の両スコープから集約し、重複 feed.id は 1 件に集約する")
        void aggregatesTeamAndOrgDeduped() {
            LocalDateTime now = LocalDateTime.now();
            AnnouncementFeedEntity teamFeed = feed(50L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", now, null, null);
            AnnouncementFeedEntity orgFeed = feed(51L, AnnouncementScopeType.ORGANIZATION, ORG_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "o", "e", now, null, null);
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID))
                    .willReturn(List.of(ORG_ID));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(roleResolver.resolveViewerRole(USER_ID, "ORGANIZATION", ORG_ID)).willReturn(ViewerRole.MEMBER);
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.TEAM), eq(TEAM_ID), any(), any(), anyInt()))
                    .willReturn(List.of(teamFeed));
            given(feedQueryRepository.findByScope(
                    eq(AnnouncementScopeType.ORGANIZATION), eq(ORG_ID), any(), any(), anyInt()))
                    .willReturn(List.of(orgFeed));
            given(readStatusRepository.findByUserIdAndAnnouncementFeedIdIn(eq(USER_ID), any()))
                    .willReturn(List.of());

            List<InboxItemDto> items = adapter.fetch(USER_ID, 50);

            assertThat(items).extracting(InboxItemDto::sourceId)
                    .containsExactlyInAnyOrder(50L, 51L);
        }
    }

    @Nested
    @DisplayName("isVisibleTo（IDOR）")
    class Visibility {

        @Test
        @DisplayName("本人の所属スコープかつ role visibility に収まる feed は true")
        void ownScopeVisible() {
            AnnouncementFeedEntity f = feed(60L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            given(feedRepository.findById(60L)).willReturn(Optional.of(f));
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);

            assertThat(adapter.isVisibleTo(USER_ID, 60L)).isTrue();
        }

        @Test
        @DisplayName("未所属スコープの feed は false")
        void otherScopeInvisible() {
            AnnouncementFeedEntity f = feed(61L, AnnouncementScopeType.TEAM, 999L, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            given(feedRepository.findById(61L)).willReturn(Optional.of(f));
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);

            assertThat(adapter.isVisibleTo(USER_ID, 61L)).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER に MEMBERS_AND_ABOVE の feed は false（漏洩根治・正準: SUPPORTER は内輪お知らせ不可）")
        void supporterCannotSeeMembersOnly() {
            // 正準: SUPPORTER が見られる集合は {PUBLIC, SUPPORTERS_AND_ABOVE}。MEMBERS_AND_ABOVE は含めない。
            // 従来はここを true としていた（漏洩を仕様固定していた）が、設計書 F02.6 §6.2 に反するため反転。
            AnnouncementFeedEntity f = feed(62L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            given(feedRepository.findById(62L)).willReturn(Optional.of(f));
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.SUPPORTER);

            assertThat(adapter.isVisibleTo(USER_ID, 62L)).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER は SUPPORTERS_AND_ABOVE / PUBLIC の feed を閲覧できる")
        void supporterCanSeeSupportersAndPublic() {
            AnnouncementFeedEntity sup = feed(66L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "SUPPORTERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            AnnouncementFeedEntity pub = feed(67L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "PUBLIC", "t", "e", LocalDateTime.now(), null, null);
            given(feedRepository.findById(66L)).willReturn(Optional.of(sup));
            given(feedRepository.findById(67L)).willReturn(Optional.of(pub));
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.SUPPORTER);

            assertThat(adapter.isVisibleTo(USER_ID, 66L)).isTrue();
            assertThat(adapter.isVisibleTo(USER_ID, 67L)).isTrue();
        }

        @Test
        @DisplayName("MEMBER は MEMBERS_AND_ABOVE / SUPPORTERS_AND_ABOVE / PUBLIC の feed を全て閲覧できる（取りこぼし解消）")
        void memberCanSeeAllVisibilities() {
            AnnouncementFeedEntity members = feed(70L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            AnnouncementFeedEntity sup = feed(71L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "SUPPORTERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, null);
            AnnouncementFeedEntity pub = feed(72L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "PUBLIC", "t", "e", LocalDateTime.now(), null, null);
            given(feedRepository.findById(70L)).willReturn(Optional.of(members));
            given(feedRepository.findById(71L)).willReturn(Optional.of(sup));
            given(feedRepository.findById(72L)).willReturn(Optional.of(pub));
            given(userRoleRepository.findTeamIdsByUserId(USER_ID))
                    .willReturn(List.of(TEAM_ID));
            given(userRoleRepository.findOrganizationIdsByUserId(USER_ID)).willReturn(List.of());
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);

            assertThat(adapter.isVisibleTo(USER_ID, 70L)).isTrue();
            assertThat(adapter.isVisibleTo(USER_ID, 71L)).isTrue();
            assertThat(adapter.isVisibleTo(USER_ID, 72L)).isTrue();
        }

        @Test
        @DisplayName("元コンテンツ削除済み（source_deleted_at != null）は false")
        void sourceDeletedInvisible() {
            AnnouncementFeedEntity f = feed(63L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), null, LocalDateTime.now());
            given(feedRepository.findById(63L)).willReturn(Optional.of(f));

            assertThat(adapter.isVisibleTo(USER_ID, 63L)).isFalse();
        }

        @Test
        @DisplayName("失効済み（expires_at <= now）は false")
        void expiredInvisible() {
            AnnouncementFeedEntity f = feed(64L, AnnouncementScopeType.TEAM, TEAM_ID, "NORMAL",
                    "MEMBERS_AND_ABOVE", "t", "e", LocalDateTime.now(), LocalDateTime.now().minusHours(1), null);
            given(feedRepository.findById(64L)).willReturn(Optional.of(f));

            assertThat(adapter.isVisibleTo(USER_ID, 64L)).isFalse();
        }

        @Test
        @DisplayName("存在しない feed は false")
        void missingInvisible() {
            given(feedRepository.findById(65L)).willReturn(Optional.empty());

            assertThat(adapter.isVisibleTo(USER_ID, 65L)).isFalse();
        }
    }
}
