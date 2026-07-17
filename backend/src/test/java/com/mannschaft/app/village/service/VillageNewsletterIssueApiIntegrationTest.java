package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.controller.AbstractVillageIntegrationTest;
import com.mannschaft.app.village.dto.NewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.NewsletterTagResponse;
import com.mannschaft.app.village.dto.PublicNewsletterIssuePageResponse;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F17.1 ②-4 — 村ニュースレター号 API（公開一覧・タグ絞り込み・IDOR）の統合テスト（実 MySQL Testcontainers）。
 *
 * <p>Service〜Repository〜DB を実 Bean で通し、モック UT では偽 green になりうる箇所を非ザルに検証する:</p>
 * <ul>
 *   <li>AC-16: 公開一覧クエリ（{@code visibility=PUBLIC AND status=PUBLISHED}）が
 *       VILLAGE_MEMBERS 号を<b>絶対に含まない</b>（実 SQL の述語が効いていること）</li>
 *   <li>AC-15: HEADMAN がタグ作成→号にタグ付け→{@code listIssues(tagId=)} で絞り込み一覧が返る
 *       （中間表の逆引き＋村スコープ IN 絞り込みの実クエリ）</li>
 *   <li>AC-17: 非メンバーの村外ユーザーが VILLAGE_MEMBERS 号を公開詳細で引くと 404 秘匿</li>
 * </ul>
 *
 * <p>日時は文字列でなく {@link LocalDateTime} を bind する（TZ 境界事故回避・
 * memory {@code feedback_it_fixture_datetime_tz_bind}）。</p>
 */
@DisplayName("F17.1 ②-4 村ニュースレター号 API 統合テスト")
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class VillageNewsletterIssueApiIntegrationTest extends AbstractVillageIntegrationTest {

    @Autowired
    private VillageNewsletterIssueService issueService;
    @Autowired
    private VillageNewsletterIssueRepository issueRepository;
    @Autowired
    private VillageRepository villageRepository;
    @Autowired
    private VillageMembershipRepository membershipRepository;

    private static final Long HEADMAN_USER_ID = 501L;
    private static final Long OUTSIDER_USER_ID = 999L;
    private static final LocalDateTime PUBLISHED_AT = LocalDateTime.of(2026, 7, 1, 18, 0);

    // ========================================================================
    // AC-16 — 公開一覧は PUBLIC×PUBLISHED のみ（VILLAGE_MEMBERS を絶対に混ぜない）
    // ========================================================================

    @Test
    @DisplayName("AC-16: listPublicIssues は PUBLIC×PUBLISHED 号のみ返し VILLAGE_MEMBERS 号を含めない")
    void listPublicIssues_returnsOnlyPublic() {
        UUID villageId = persistVillage().getId();

        VillageNewsletterIssueEntity publicIssue = persistIssue(villageId,
                VillageNewsletterVisibility.PUBLIC, VillageNewsletterIssueStatus.PUBLISHED, "公開号");
        // 混ぜてはいけない号たち
        persistIssue(villageId, VillageNewsletterVisibility.VILLAGE_MEMBERS,
                VillageNewsletterIssueStatus.PUBLISHED, "村内限定号");
        persistIssue(villageId, VillageNewsletterVisibility.PUBLIC,
                VillageNewsletterIssueStatus.FROZEN, "未配信の公開予定号");

        PublicNewsletterIssuePageResponse page = issueService.listPublicIssues(
                OUTSIDER_USER_ID, PageRequest.of(0, 20));

        assertThat(page.content()).extracting("id").containsExactly(publicIssue.getId());
        assertThat(page.content()).extracting("title").containsExactly("公開号");
        assertThat(page.content()).extracting("title").doesNotContain("村内限定号", "未配信の公開予定号");
    }

    // ========================================================================
    // AC-17 — VILLAGE_MEMBERS 号の公開直アクセスは 404 秘匿・PUBLIC は取得可
    // ========================================================================

    @Test
    @DisplayName("AC-17: 非メンバーが VILLAGE_MEMBERS 号を公開詳細で引くと 404 秘匿・PUBLIC 号は取得可")
    void getPublicIssue_idorHiding() {
        UUID villageId = persistVillage().getId();
        VillageNewsletterIssueEntity members = persistIssue(villageId,
                VillageNewsletterVisibility.VILLAGE_MEMBERS, VillageNewsletterIssueStatus.PUBLISHED, "村内限定号");
        VillageNewsletterIssueEntity pub = persistIssue(villageId,
                VillageNewsletterVisibility.PUBLIC, VillageNewsletterIssueStatus.PUBLISHED, "公開号");

        assertThatThrownBy(() -> issueService.getPublicIssue(members.getId(), OUTSIDER_USER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND);

        assertThat(issueService.getPublicIssue(pub.getId(), OUTSIDER_USER_ID).id())
                .isEqualTo(pub.getId());
    }

    // ========================================================================
    // AC-15 — タグ作成 → タグ付け → タグ絞り込み一覧（村ドメイン UUIDv7・Long 壁なし）
    // ========================================================================

    @Test
    @DisplayName("AC-15: HEADMAN がタグ作成→号にタグ付け→listIssues(tagId=) で絞り込み一覧が返る")
    void tagCreateAssignAndFilter() {
        VillageEntity village = persistVillage();
        UUID villageId = village.getId();
        persistHeadman(villageId, HEADMAN_USER_ID);

        VillageNewsletterIssueEntity tagged = persistIssue(villageId,
                VillageNewsletterVisibility.VILLAGE_MEMBERS, VillageNewsletterIssueStatus.FROZEN, "タグ付き号");
        VillageNewsletterIssueEntity untagged = persistIssue(villageId,
                VillageNewsletterVisibility.VILLAGE_MEMBERS, VillageNewsletterIssueStatus.FROZEN, "タグ無し号");

        NewsletterTagResponse tag = issueService.createTag(
                villageId, HEADMAN_USER_ID, "お祭り", "#FF8800", 1);
        issueService.setIssueTags(villageId, tagged.getId(), HEADMAN_USER_ID,
                List.of(tag.id()), tagged.getVersion());

        // 絞り込み: タグ付き号のみが返る（bulletinVisibility=PUBLIC なので閲覧認可は通過）
        NewsletterIssuePageResponse filtered = issueService.listIssues(
                villageId, HEADMAN_USER_ID, tag.id(), PageRequest.of(0, 20));
        assertThat(filtered.content()).extracting("id").containsExactly(tagged.getId());
        assertThat(filtered.content()).extracting("id").doesNotContain(untagged.getId());
        assertThat(filtered.content().get(0).tags()).extracting("name").containsExactly("お祭り");

        // 絞り込み無し: 2 号とも返る
        NewsletterIssuePageResponse all = issueService.listIssues(
                villageId, HEADMAN_USER_ID, null, PageRequest.of(0, 20));
        assertThat(all.content()).extracting("id")
                .containsExactlyInAnyOrder(tagged.getId(), untagged.getId());
    }

    // ========================================================================
    // フィクスチャ
    // ========================================================================

    private VillageEntity persistVillage() {
        return villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("nl-api-" + UUID.randomUUID().toString().substring(0, 8))
                .name("お便りAPIテスト村" + System.nanoTime())
                .description("ニュースレター号 API テスト用")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                // 閲覧認可（掲示板流用）が非メンバーでも通るよう PUBLIC にする
                .bulletinVisibility(VillageBulletinVisibility.PUBLIC)
                .category("業種")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_USER_ID)
                .build());
    }

    private void persistHeadman(UUID villageId, Long userId) {
        membershipRepository.saveAndFlush(VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(VillageRole.HEADMAN)
                .joinedAt(LocalDateTime.now())
                .version(0L)
                .build());
    }

    private VillageNewsletterIssueEntity persistIssue(
            UUID villageId, VillageNewsletterVisibility visibility,
            VillageNewsletterIssueStatus status, String title) {
        return issueRepository.saveAndFlush(VillageNewsletterIssueEntity.builder()
                .villageId(villageId)
                .frequency(VillageNewsletterFrequency.MONTHLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(status)
                .title(title)
                .visibility(visibility)
                .periodStart(LocalDateTime.of(2026, 6, 1, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 7, 1, 0, 0))
                .publishedAt(status == VillageNewsletterIssueStatus.PUBLISHED ? PUBLISHED_AT : null)
                .digestPostCount(10)
                .digestNewMemberCount(2)
                .digestFestivalCount(1)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Name("夏祭り")
                .digestTopic1Count(3)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .version(0L)
                .build());
    }
}
