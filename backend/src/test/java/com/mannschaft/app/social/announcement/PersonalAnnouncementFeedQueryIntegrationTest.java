package com.mannschaft.app.social.announcement;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@Transactional
@DisplayName("個人横断お知らせ検索 MySQL 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalAnnouncementFeedQueryIntegrationTest extends AbstractMySqlIntegrationTest {

    private static final long USER_ID = 91001L;
    private static final long TEAM_ID = 92001L;
    private static final long OTHER_TEAM_ID = 92002L;

    @Autowired
    private AnnouncementFeedQueryRepository queryRepository;

    @Autowired
    private MembershipRepository membershipRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("現役所属だけをjoinedAt,id降順で取得し退会・他ユーザーを除外する")
    void activeMembershipsAreStableAndIsolated() {
        LocalDateTime joinedAt = LocalDateTime.of(2026, 9, 1, 12, 0);
        MembershipEntity olderId = membership(USER_ID, TEAM_ID, joinedAt, null);
        MembershipEntity newerId = membership(USER_ID, OTHER_TEAM_ID, joinedAt, null);
        membership(USER_ID, 92003L, joinedAt.plusDays(1), joinedAt.plusDays(2));
        membership(99999L, 92004L, joinedAt.plusDays(2), null);
        em.flush();
        em.clear();

        List<MembershipEntity> result = membershipRepository.findRecentActiveByUser(
                USER_ID, Set.of(ScopeType.TEAM, ScopeType.ORGANIZATION), PageRequest.of(0, 20));

        assertThat(result).extracting(MembershipEntity::getId)
                .containsExactly(newerId.getId(), olderId.getId());
    }

    @Test
    @DisplayName("visibility・既読・別scopeをDBで除外し同時刻はid降順、offset/limitを適用する")
    void personalQueryAppliesAllDbFiltersAndStableOrder() {
        AnnouncementFeedEntity memberOlder = feed(TEAM_ID, "MEMBERS_AND_ABOVE", null);
        AnnouncementFeedEntity memberNewer = feed(TEAM_ID, "MEMBERS_AND_ABOVE", null);
        AnnouncementFeedEntity supporter = feed(TEAM_ID, "SUPPORTERS_AND_ABOVE", null);
        AnnouncementFeedEntity otherScope = feed(OTHER_TEAM_ID, "PUBLIC", null);
        AnnouncementFeedEntity deleted = feed(TEAM_ID, "PUBLIC", LocalDateTime.now());
        em.flush();
        LocalDateTime sameTime = LocalDateTime.of(2026, 9, 20, 10, 0);
        em.createNativeQuery("UPDATE announcement_feeds SET created_at = :createdAt WHERE id IN (:ids)")
                .setParameter("createdAt", sameTime)
                .setParameter("ids", List.of(memberOlder.getId(), memberNewer.getId()))
                .executeUpdate();
        em.persist(AnnouncementReadStatusEntity.builder()
                .announcementFeedId(supporter.getId()).userId(USER_ID).build());
        em.flush();
        em.clear();

        List<AnnouncementFeedEntity> unreadMemberOnly = queryRepository.findPersonalFeed(
                List.of(new AnnouncementFeedQueryRepository.PersonalScopeAccess(
                        AnnouncementScopeType.TEAM, TEAM_ID, Set.of("PUBLIC", "MEMBERS_AND_ABOVE"))),
                USER_ID, false, 0, 10);
        assertThat(unreadMemberOnly).extracting(AnnouncementFeedEntity::getId)
                .containsExactly(memberNewer.getId(), memberOlder.getId());

        List<AnnouncementFeedEntity> secondRow = queryRepository.findPersonalFeed(
                List.of(new AnnouncementFeedQueryRepository.PersonalScopeAccess(
                        AnnouncementScopeType.TEAM, TEAM_ID,
                        Set.of("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_AND_ABOVE"))),
                USER_ID, true, 1, 1);
        assertThat(secondRow).extracting(AnnouncementFeedEntity::getId)
                .containsExactly(memberNewer.getId());
        assertThat(otherScope.getId()).isNotIn(
                unreadMemberOnly.stream().map(AnnouncementFeedEntity::getId).toList());
        assertThat(deleted.getId()).isNotIn(
                unreadMemberOnly.stream().map(AnnouncementFeedEntity::getId).toList());
    }

    private MembershipEntity membership(long userId, long scopeId, LocalDateTime joinedAt, LocalDateTime leftAt) {
        MembershipEntity entity = MembershipEntity.builder()
                .userId(userId).scopeType(ScopeType.TEAM).scopeId(scopeId)
                .roleKind(RoleKind.MEMBER).joinedAt(joinedAt).leftAt(leftAt).build();
        em.persist(entity);
        return entity;
    }

    private AnnouncementFeedEntity feed(long scopeId, String visibility, LocalDateTime deletedAt) {
        AnnouncementFeedEntity entity = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM).scopeId(scopeId)
                .sourceType(AnnouncementSourceType.BLOG_POST).sourceId(System.nanoTime())
                .titleCache("test").visibility(visibility).sourceDeletedAt(deletedAt).build();
        em.persist(entity);
        return entity;
    }
}
