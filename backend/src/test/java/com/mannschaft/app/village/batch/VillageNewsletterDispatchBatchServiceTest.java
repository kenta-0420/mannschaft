package com.mannschaft.app.village.batch;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.service.VillageNewsletterBodyComposer;
import com.mannschaft.app.village.service.VillageNewsletterPublishService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link VillageNewsletterDispatchBatchService} 単体テスト（F17.1 ②-3・設計書 §11.3）。
 *
 * <p>号（issue）駆動の配信で、<b>手間ゼロ既定</b>（コメント無しでもダイジェスト単体で自動配信）・
 * opt-out 除外・1 件失敗しても継続（fault isolation）・型の壁回避（scopeType=SYSTEM / sourceId=null /
 * actionUrl に村UUID・号UUID）を検証する。本文組み立ては実物の {@link VillageNewsletterBodyComposer}
 * （{@link StaticMessageSource}）を通し、通知に渡る body を {@link ArgumentCaptor} で確認する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-10: コメント無し FROZEN 号 → ダイジェスト単体で自動配信・号は PUBLISHED 化（publishIssue 委譲）</li>
 *   <li>AC-11: コメント有り → body に「ダイジェスト＋コメント」を含む</li>
 *   <li>AC-12: opt-out 済みユーザーが受信者から除外される</li>
 *   <li>AC-13: 受信者 1 名の配信例外でも継続し、号は PUBLISHED 化・failure_count が加算される</li>
 *   <li>AC-14: 通知の scopeType=SYSTEM・sourceId=null・actionUrl が /villages/{村UUID}/newsletter/issues/{号UUID}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNewsletterDispatchBatchService 単体テスト（F17.1 ②-3）")
class VillageNewsletterDispatchBatchServiceTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000e01");
    private static final UUID ISSUE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000e02");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 19, 18, 0);

    private static final Long USER_1 = 1001L;
    private static final Long USER_2 = 1002L;
    private static final Long USER_3 = 1003L;

    @Mock
    private VillageNewsletterIssueRepository issueRepository;
    @Mock
    private VillageNewsletterOptOutRepository optOutRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private VillageNewsletterPublishService publishService;

    private VillageNewsletterDispatchBatchService batchService;

    @BeforeEach
    void setUp() {
        // 本文組み立ては実物を通す（メッセージは StaticMessageSource で ja 既定を再現）。
        StaticMessageSource ms = new StaticMessageSource();
        ms.addMessage("village.newsletter.body.digest.post", Locale.JAPANESE, "投稿 {0}件");
        ms.addMessage("village.newsletter.body.digest.newMember", Locale.JAPANESE, "新しい仲間 {0}人");
        ms.addMessage("village.newsletter.body.digest.festival", Locale.JAPANESE, "お祭り {0}件");
        ms.addMessage("village.newsletter.body.digest.meetup", Locale.JAPANESE, "寄合 {0}件");
        ms.addMessage("village.newsletter.body.digest.recruit", Locale.JAPANESE, "募集 {0}件");
        ms.addMessage("village.newsletter.body.topic", Locale.JAPANESE, "話題「{0}」{1}件");
        ms.addMessage("village.newsletter.body.quiet", Locale.JAPANESE, "静かな一週間でした。");
        ms.addMessage("village.newsletter.body.commentHeading", Locale.JAPANESE, "村長より");
        VillageNewsletterBodyComposer bodyComposer = new VillageNewsletterBodyComposer(ms);

        batchService = new VillageNewsletterDispatchBatchService(
                issueRepository, optOutRepository, membershipRepository,
                notificationHelper, bodyComposer, publishService);
    }

    // ========================================================================
    // AC-10 — コメント無し FROZEN 号がダイジェスト単体で自動配信され PUBLISHED 化
    // ========================================================================

    @Test
    @DisplayName("AC-10: コメント無しの配信期到来 FROZEN 号 → ダイジェスト単体で通知・号を PUBLISHED 化")
    void dispatchForDate_commentless_digestOnly_published() {
        VillageNewsletterIssueEntity issue = frozenIssue(5, null);
        givenDueIssues(issue);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(USER_1));
        given(optOutRepository.findByVillageId(VILLAGE_ID)).willReturn(List.of());

        int published = batchService.dispatchForDate(NOW);

        assertThat(published).isEqualTo(1);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notifyPreAuthorized(
                eq(USER_1), eq("VILLAGE_NEWSLETTER"), eq(NotificationPriority.NORMAL),
                anyString(), bodyCaptor.capture(),
                eq("VILLAGE_NEWSLETTER"), any(),
                eq(NotificationScopeType.SYSTEM), any(),
                anyString(), any());
        // ダイジェスト単体（コメント見出しを含まない）
        assertThat(bodyCaptor.getValue()).contains("投稿 5件");
        assertThat(bodyCaptor.getValue()).doesNotContain("村長より");

        // 号は PUBLISHED 化・send_log は publishIssue へ委譲（recipient=1/success=1/failure=0）
        verify(publishService).publishIssue(eq(issue), eq(1), eq(1), eq(0));
    }

    // ========================================================================
    // AC-11 — コメント有りは「ダイジェスト＋コメント」を本文に含む
    // ========================================================================

    @Test
    @DisplayName("AC-11: コメント有りの号 → body に「ダイジェスト＋コメント」を含む")
    void dispatchForDate_withComment_bodyIncludesComment() {
        VillageNewsletterIssueEntity issue = frozenIssue(5, "今週もお疲れさまでした");
        givenDueIssues(issue);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(USER_1));
        given(optOutRepository.findByVillageId(VILLAGE_ID)).willReturn(List.of());

        batchService.dispatchForDate(NOW);

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notifyPreAuthorized(
                eq(USER_1), anyString(), any(),
                anyString(), bodyCaptor.capture(),
                anyString(), any(), any(), any(), anyString(), any());
        String body = bodyCaptor.getValue();
        assertThat(body).contains("投稿 5件");        // ダイジェスト
        assertThat(body).contains("村長より");          // コメント見出し
        assertThat(body).contains("今週もお疲れさまでした"); // コメント本体
    }

    // ========================================================================
    // AC-12 — opt-out 済みユーザーが受信者から除外される
    // ========================================================================

    @Test
    @DisplayName("AC-12: opt-out 済みユーザーは受信者から除外される")
    void dispatchForDate_optedOutExcluded() {
        VillageNewsletterIssueEntity issue = frozenIssue(3, null);
        givenDueIssues(issue);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(USER_1, USER_2, USER_3));
        given(optOutRepository.findByVillageId(VILLAGE_ID)).willReturn(List.of(optOut(USER_2)));

        batchService.dispatchForDate(NOW);

        // USER_2 には通知しない
        verify(notificationHelper, never()).notifyPreAuthorized(
                eq(USER_2), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
        // 受信者は 2 名（USER_1, USER_3）
        ArgumentCaptor<Long> userCaptor = ArgumentCaptor.forClass(Long.class);
        verify(notificationHelper, times(2)).notifyPreAuthorized(
                userCaptor.capture(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
        assertThat(userCaptor.getAllValues()).containsExactlyInAnyOrder(USER_1, USER_3);
        verify(publishService).publishIssue(eq(issue), eq(2), eq(2), eq(0));
    }

    // ========================================================================
    // AC-13 — 1 名失敗でも継続し PUBLISHED 化・failure_count 加算
    // ========================================================================

    @Test
    @DisplayName("AC-13: 受信者 1 名の配信例外でも継続し、号は PUBLISHED 化・failure_count=1")
    void dispatchForDate_oneRecipientFails_continuesAndCountsFailure() {
        VillageNewsletterIssueEntity issue = frozenIssue(3, null);
        givenDueIssues(issue);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(USER_1, USER_2, USER_3));
        given(optOutRepository.findByVillageId(VILLAGE_ID)).willReturn(List.of());
        // USER_2 への通知だけ例外。lenient で strict-stubs の PotentialStubbingProblem を回避する
        // （非マッチの USER_1/USER_3 呼び出しがサービスの広域 catch に飲み込まれ余計な failure が
        //  加算される現象を防ぐ。第1引数の eq(USER_2) は維持し USER_2 のみ throw させる）。
        lenient().doThrow(new RuntimeException("配信失敗（テスト）")).when(notificationHelper).notifyPreAuthorized(
                eq(USER_2), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        int published = batchService.dispatchForDate(NOW);

        assertThat(published).isEqualTo(1);
        // 3 名全員に配信を試みる（USER_2 で止まらない）
        verify(notificationHelper, times(3)).notifyPreAuthorized(
                any(), anyString(), any(), anyString(), anyString(),
                anyString(), any(), any(), any(), anyString(), any());
        // 号は PUBLISHED 化され failure_count=1・success_count=2
        verify(publishService).publishIssue(eq(issue), eq(3), eq(2), eq(1));
    }

    // ========================================================================
    // AC-14 — 型の壁回避の契約（scopeType=SYSTEM / sourceId=null / actionUrl）
    // ========================================================================

    @Test
    @DisplayName("AC-14: 通知は scopeType=SYSTEM・sourceId=null・actionUrl=/villages/{村UUID}/newsletter/issues/{号UUID}")
    void dispatchForDate_notifyContract_typeWallAvoidance() {
        VillageNewsletterIssueEntity issue = frozenIssue(1, null);
        givenDueIssues(issue);
        given(membershipRepository.findActiveUserSubjectIdsByVillageId(VILLAGE_ID))
                .willReturn(List.of(USER_1));
        given(optOutRepository.findByVillageId(VILLAGE_ID)).willReturn(List.of());

        batchService.dispatchForDate(NOW);

        ArgumentCaptor<Long> sourceIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<NotificationScopeType> scopeCaptor =
                ArgumentCaptor.forClass(NotificationScopeType.class);
        ArgumentCaptor<String> actionUrlCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationHelper).notifyPreAuthorized(
                eq(USER_1), anyString(), any(), anyString(), anyString(),
                anyString(), sourceIdCaptor.capture(),
                scopeCaptor.capture(), any(),
                actionUrlCaptor.capture(), any());

        assertThat(sourceIdCaptor.getValue()).isNull();                       // UUID を Long に載せない
        assertThat(scopeCaptor.getValue()).isEqualTo(NotificationScopeType.SYSTEM); // VILLAGE 不在の回避
        assertThat(actionUrlCaptor.getValue())
                .isEqualTo("/villages/" + VILLAGE_ID + "/newsletter/issues/" + ISSUE_ID);
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private void givenDueIssues(VillageNewsletterIssueEntity... issues) {
        given(issueRepository.findByStatusAndScheduledPublishAtLessThanEqualAndDeletedAtIsNull(
                eq(VillageNewsletterIssueStatus.FROZEN), any(LocalDateTime.class)))
                .willReturn(List.of(issues));
    }

    /** 配信期が到来した FROZEN 号を作る（コメントは null 可）。 */
    private VillageNewsletterIssueEntity frozenIssue(int postCount, String comment) {
        VillageNewsletterIssueEntity issue = VillageNewsletterIssueEntity.builder()
                .villageId(VILLAGE_ID)
                .newsletterId(UUID.fromString("01956c00-0000-7000-8000-000000000e03"))
                .frequency(VillageNewsletterFrequency.WEEKLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(VillageNewsletterIssueStatus.FROZEN)
                .title("2026年06月15日週 村だより")
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(LocalDateTime.of(2026, 6, 8, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 6, 15, 0, 0))
                .scheduledPublishAt(LocalDateTime.of(2026, 6, 19, 18, 0))
                .digestPostCount(postCount)
                .digestNewMemberCount(0)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .headmanComment(comment)
                .version(0L)
                .build();
        issue.setId(ISSUE_ID);
        return issue;
    }

    private VillageNewsletterOptOutEntity optOut(Long userId) {
        return VillageNewsletterOptOutEntity.builder()
                .villageId(VILLAGE_ID)
                .userId(userId)
                .optedOutAt(LocalDateTime.of(2026, 6, 1, 0, 0))
                .build();
    }
}
