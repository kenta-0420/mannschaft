package com.mannschaft.app.social.announcement;

import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceEntity;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceRepository;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceTeamEntity;
import com.mannschaft.app.notification.fanout.NotificationFanoutAudienceTeamRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F01.2.1 部隊 1-B — 告知・fan-out 宛先の器（Entity・Repository）の永続化契約（実 MySQL）。
 *
 * <ul>
 *   <li>announcement_feeds の3列（既定値 include_unassigned=false・target_group_ids/target_audience は JSON）</li>
 *   <li>announcement_range_templates の3列</li>
 *   <li>announcement_feed_group_snapshots（一意制約 feed_id+group_id+team_id）</li>
 *   <li>notification_fanout_audiences（見出し。主キー列は audience_snapshot_id）と
 *       notification_fanout_audience_teams（一意制約 audience_snapshot_id+team_id。0行でもよい）</li>
 * </ul>
 *
 * <p>IT のスキーマは Entity から Hibernate が生成するため、Entity 側に既定値・UNIQUE を写していないと
 * 本番（Flyway）と食い違う。本テストはその写しが効いていることを固定する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F01.2.1 1-B 告知・fan-out 宛先の器 永続化契約")
class AnnouncementGroupTargetingPersistenceIT extends AbstractMySqlIntegrationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private AnnouncementFeedRepository feedRepository;

    @Autowired
    private AnnouncementRangeTemplateRepository templateRepository;

    @Autowired
    private AnnouncementFeedGroupSnapshotRepository snapshotRepository;

    @Autowired
    private NotificationFanoutAudienceRepository audienceRepository;

    @Autowired
    private NotificationFanoutAudienceTeamRepository audienceTeamRepository;

    @Test
    @DisplayName("feed: 3列を指定しなければ include_unassigned=false・JSON 2列は NULL。指定すれば往復できる")
    void feedThreeColumnsDefaultsAndRoundTrip() {
        AnnouncementFeedEntity plain = feedRepository.saveAndFlush(newFeed().build());
        AnnouncementFeedEntity withGroups = feedRepository.saveAndFlush(newFeed()
                .targetGroupIds("[\"11111111-1111-1111-1111-111111111111\"]")
                .includeUnassigned(true)
                .targetAudience("{\"groups\":[{\"name\":\"北\"}],\"pushCount\":3}")
                .build());
        em.clear();

        AnnouncementFeedEntity p = feedRepository.findById(plain.getId()).orElseThrow();
        assertThat(p.getIncludeUnassigned()).isFalse();
        assertThat(p.getTargetGroupIds()).isNull();
        assertThat(p.getTargetAudience()).isNull();

        AnnouncementFeedEntity g = feedRepository.findById(withGroups.getId()).orElseThrow();
        assertThat(g.getIncludeUnassigned()).isTrue();
        assertThat(g.getTargetGroupIds()).contains("11111111-1111-1111-1111-111111111111");
        assertThat(g.getTargetAudience()).contains("pushCount");
    }

    @Test
    @DisplayName("template: 3列を指定しなければ include_unassigned=false・JSON 2列は NULL。指定すれば往復できる")
    void templateThreeColumnsDefaultsAndRoundTrip() {
        AnnouncementRangeTemplateEntity plain = templateRepository.saveAndFlush(newTemplate("既定").build());
        AnnouncementRangeTemplateEntity withGroups = templateRepository.saveAndFlush(newTemplate("グループ")
                .targetGroupIds("[\"22222222-2222-2222-2222-222222222222\"]")
                .targetGroupRange("{\"from_group_id\":\"a\",\"to_group_id\":\"b\"}")
                .includeUnassigned(true)
                .build());
        em.clear();

        AnnouncementRangeTemplateEntity p = templateRepository.findById(plain.getId()).orElseThrow();
        assertThat(p.getIncludeUnassigned()).isFalse();
        assertThat(p.getTargetGroupIds()).isNull();
        assertThat(p.getTargetGroupRange()).isNull();

        AnnouncementRangeTemplateEntity g = templateRepository.findById(withGroups.getId()).orElseThrow();
        assertThat(g.getIncludeUnassigned()).isTrue();
        assertThat(g.getTargetGroupIds()).contains("22222222-2222-2222-2222-222222222222");
        assertThat(g.getTargetGroupRange()).contains("from_group_id");
    }

    @Test
    @DisplayName("snapshot: feed_id+group_id+team_id は一意で、同じ組の二重登録は DB が拒否する")
    void snapshotIsUniquePerFeedGroupTeam() {
        AnnouncementFeedEntity feed = feedRepository.saveAndFlush(newFeed().build());
        String groupId = UUID.randomUUID().toString();

        snapshotRepository.saveAndFlush(AnnouncementFeedGroupSnapshotEntity.builder()
                .feedId(feed.getId()).groupId(groupId).teamId(10L).build());
        // 別チーム・別グループは入る（同じチームが 2 グループに属しても行が分かれる）
        snapshotRepository.saveAndFlush(AnnouncementFeedGroupSnapshotEntity.builder()
                .feedId(feed.getId()).groupId(groupId).teamId(11L).build());
        snapshotRepository.saveAndFlush(AnnouncementFeedGroupSnapshotEntity.builder()
                .feedId(feed.getId()).groupId(UUID.randomUUID().toString()).teamId(10L).build());

        assertThat(snapshotRepository.findByFeedId(feed.getId())).hasSize(3);
        assertThat(snapshotRepository.existsByFeedIdAndTeamId(feed.getId(), 10L)).isTrue();
        assertThat(snapshotRepository.existsByFeedIdAndTeamId(feed.getId(), 99L)).isFalse();

        assertThatThrownBy(() -> snapshotRepository.saveAndFlush(AnnouncementFeedGroupSnapshotEntity.builder()
                .feedId(feed.getId()).groupId(groupId).teamId(10L).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("audience: 見出しは呼び出し側が決めた UUID（決定的キー）のまま保存され、宛先チームは一意・0行でもよい")
    void audienceHeaderKeepsDeterministicIdAndTeamsAreUnique() {
        UUID audienceId = UUID.nameUUIDFromBytes(
                "F02.8:broadcast-audience:123".getBytes(StandardCharsets.UTF_8));
        audienceRepository.saveAndFlush(NotificationFanoutAudienceEntity.builder()
                .id(audienceId).organizationId(7L).build());

        UUID emptyAudienceId = UUID.nameUUIDFromBytes(
                "F02.8:broadcast-audience:124".getBytes(StandardCharsets.UTF_8));
        audienceRepository.saveAndFlush(NotificationFanoutAudienceEntity.builder()
                .id(emptyAudienceId).organizationId(7L).build());

        audienceTeamRepository.saveAndFlush(NotificationFanoutAudienceTeamEntity.builder()
                .audienceSnapshotId(audienceId).teamId(1L).build());
        audienceTeamRepository.saveAndFlush(NotificationFanoutAudienceTeamEntity.builder()
                .audienceSnapshotId(audienceId).teamId(2L).build());
        em.clear();

        NotificationFanoutAudienceEntity header = audienceRepository.findById(audienceId).orElseThrow();
        assertThat(header.getId()).isEqualTo(audienceId);
        assertThat(header.getOrganizationId()).isEqualTo(7L);
        assertThat(header.getCreatedAt()).as("createdAt は Instant で自動設定される").isNotNull();

        List<Long> teamIds = audienceTeamRepository.findTeamIdsByAudienceSnapshotId(audienceId);
        assertThat(teamIds).containsExactlyInAnyOrder(1L, 2L);
        assertThat(audienceTeamRepository.findTeamIdsByAudienceSnapshotId(emptyAudienceId)).isEmpty();

        assertThatThrownBy(() -> audienceTeamRepository.saveAndFlush(NotificationFanoutAudienceTeamEntity.builder()
                .audienceSnapshotId(audienceId).teamId(1L).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static AnnouncementFeedEntity.AnnouncementFeedEntityBuilder<?, ?> newFeed() {
        return AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(1L)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(System.nanoTime())
                .titleCache("t");
    }

    private static AnnouncementRangeTemplateEntity.AnnouncementRangeTemplateEntityBuilder<?, ?> newTemplate(String name) {
        return AnnouncementRangeTemplateEntity.builder()
                .scopeType(AnnouncementScopeType.ORGANIZATION)
                .scopeId(1L)
                .name(name);
    }
}
