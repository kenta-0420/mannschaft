package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpointRegistry;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timeline.dto.PostResponse;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.timeline.service.TimelinePostService;
import com.mannschaft.app.village.dto.CalendarEventCreateRequest;
import com.mannschaft.app.village.dto.FestivalCreateRequest;
import com.mannschaft.app.village.dto.MeetupCandidateDateInput;
import com.mannschaft.app.village.dto.MeetupConfirmRequest;
import com.mannschaft.app.village.dto.MeetupCreateRequest;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageFestivalEntity;
import com.mannschaft.app.village.entity.VillageFestivalRsvpEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageEventArchiveSourceType;
import com.mannschaft.app.village.entity.enums.VillageEventNotificationType;
import com.mannschaft.app.village.entity.enums.VillageFestivalRsvpStatus;
import com.mannschaft.app.village.entity.enums.VillageFestivalStatus;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageEventArchiveRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMeetupCandidateDateRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageCalendarService;
import com.mannschaft.app.village.service.VillageFestivalService;
import com.mannschaft.app.village.service.VillageMeetupService;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * F17.2 Wave2 ①行事→村フィード自動還流 ＋ ③祭の村史編纂 — サービス/バッチ層 結合テスト（試練 / red 先行）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>red テスト</strong>。骨格のみの現時点では、行事作成・確定・状態遷移バッチに
 * <b>自動投稿（{@code createSystemVillagePost}）・村史編纂の発火が未配線</b>のため、
 * 「システム投稿が 1 件できる」「村史が 1 件編纂される」を突きつける本テストは必ず赤くなる。
 * 出陣（実装）で各注入点に発火を結線すると green 化する（設計書 §3.3.1／§5.5）。</p>
 *
 * <p>方式: {@code AbstractMySqlIntegrationTest} 直継承の実 MySQL 結合テスト。
 * 行事の作成/確定は実 {@code Village*Service} を直接呼び、状態遷移は実
 * {@link VillageFestivalStateTransitionBatchService#runBatch()} を呼ぶ（金型:
 * {@code TeamMemberCountBackfillBatchServiceIntegrationTest}。{@code @SchedulerLock} 用の
 * {@link LockProvider} は no-op モックに差し替える）。DB 検証は骨格 Repository 越しに行う。</p>
 *
 * <p>受け入れ条件（設計書 §11.1/§11.3）: AC-01・AC-02・AC-03・AC-04・AC-05・AC-17。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.2 Wave2 ①フィード還流＋③村史編纂 結合テスト")
class VillageEventRefluxIntegrationIT extends AbstractMySqlIntegrationTest {

    // 注: 本クラスは @Transactional を付けない（ロールバックしない）。
    // 行事作成・確定の還流は afterCommit 同期で発火するため、テスト側もコミットさせる必要がある
    // （@Transactional だと本体がロールバックされ afterCommit が発火しない）。
    // 各テストは System.nanoTime() で一意な村を作るためテスト間のデータ汚染は起きない。

    /** {@code @SchedulerLock} 経由の shedlock テーブル INSERT を回避する no-op スタブ。 */
    @MockitoBean
    private LockProvider lockProvider;

    /** 署名 URL 計算は外部境界のため決定論のためモック化。 */
    @MockitoBean
    private R2StorageService r2StorageService;

    @Autowired
    private VillageFestivalService festivalService;

    @Autowired
    private VillageMeetupService meetupService;

    @Autowired
    private VillageCalendarService calendarService;

    @Autowired
    private VillageFestivalStateTransitionBatchService festivalBatch;

    @Autowired
    private TimelinePostService timelinePostService;

    @Autowired
    private BatchEndpointRegistry batchEndpointRegistry;

    @Autowired
    private VillageRepository villageRepository;

    @Autowired
    private VillageMembershipRepository membershipRepository;

    @Autowired
    private VillageFestivalRepository festivalRepository;

    @Autowired
    private VillageMeetupCandidateDateRepository candidateDateRepository;

    @Autowired
    private VillageEventArchiveRepository eventArchiveRepository;

    @Autowired
    private com.mannschaft.app.village.repository.VillageFestivalRsvpRepository rsvpRepository;

    @Autowired
    private TimelinePostRepository timelinePostRepository;

    private static final Long HEADMAN_ID = 17_270_001L;
    private static final Long ORGANIZER_ID = 17_270_002L;
    private static final Long VILLAGER_ID = 17_270_003L;

    @BeforeEach
    void setUp() {
        when(lockProvider.lock(any())).thenReturn(Optional.of(mock(SimpleLock.class)));
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-01: 行事作成 → EVENT_CREATED システム投稿が 1 件（user_id NULL・source_event_uuid=行事UUID）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-01 行事作成でフィード還流（EVENT_CREATED システム投稿）")
    class EventCreatedReflux {

        @Test
        @DisplayName("祭を作成すると EVENT_CREATED のシステム投稿が 1 件できる（user_id NULL・source_event_uuid=祭UUID）")
        void createFestival_emitsEventCreatedSystemPost() {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            LocalDateTime starts = LocalDateTime.now().plusDays(7);
            UUID festivalId = festivalService.createFestival(v.getId(),
                    new FestivalCreateRequest("夏祭り", null, starts, starts.plusDays(2), null, null),
                    HEADMAN_ID).id();

            List<TimelinePostEntity> systemPosts =
                    systemPosts(v.getId(), VillageEventNotificationType.EVENT_CREATED, festivalId);
            assertThat(systemPosts).hasSize(1);
            assertThat(systemPosts.get(0).getUserId()).isNull();
            assertThat(systemPosts.get(0).getSourceEventUuid()).isEqualTo(festivalId);
        }

        @Test
        @DisplayName("寄合を作成すると EVENT_CREATED のシステム投稿が 1 件できる")
        void createMeetup_emitsEventCreatedSystemPost() {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            UUID meetupId = meetupService.createMeetup(v.getId(),
                    new MeetupCreateRequest("寄合", null, null,
                            List.of(new MeetupCandidateDateInput(LocalDate.now().plusDays(10), null))),
                    ORGANIZER_ID).id();

            assertThat(systemPosts(v.getId(), VillageEventNotificationType.EVENT_CREATED, meetupId))
                    .hasSize(1);
        }

        @Test
        @DisplayName("歳時記を作成すると EVENT_CREATED のシステム投稿が 1 件できる")
        void createCalendarEvent_emitsEventCreatedSystemPost() {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), HEADMAN_ID, VillageRole.HEADMAN);

            UUID eventId = calendarService.createEvent(v.getId(),
                    new CalendarEventCreateRequest("七夕", null, LocalDate.of(2026, 7, 7),
                            null, true, null, null),
                    HEADMAN_ID).id();

            assertThat(systemPosts(v.getId(), VillageEventNotificationType.EVENT_CREATED, eventId))
                    .hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-02: user_id NULL のシステム投稿がフィード GET で NPE なく返る（enrich null ガード）
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-02 システム投稿のフィード読取（user_id NULL でも NPE なし）")
    class SystemPostFeedRead {

        @Test
        @DisplayName("createSystemVillagePost した投稿が getFeed で NPE なく返り、systemPostType が付き postedAs は null")
        void systemPost_returnedByFeed_withoutNpe() {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), VILLAGER_ID, VillageRole.VILLAGER);
            UUID sourceEventUuid = UUID.randomUUID();

            // 骨格で実装済みの村行事システム投稿 API（user_id NULL）
            timelinePostService.createSystemVillagePost(
                    v.getId(), VillageEventNotificationType.EVENT_CREATED, sourceEventUuid, "村の行事案内");

            // 村メンバーとしてフィード取得（enrichUser/enrichPostedAs の null ガードを踏む）
            List<PostResponse> feed = timelinePostService.getFeed("VILLAGE", 0L, v.getId(), 20, VILLAGER_ID);

            assertThat(feed)
                    .anySatisfy(p -> {
                        assertThat(p.getSystemPostType()).isEqualTo(VillageEventNotificationType.EVENT_CREATED);
                        assertThat(p.getPostedAs()).isNull();
                    });
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-03: 祭バッチ SCHEDULED→ACTIVE で FESTIVAL_STARTED 1 回・再実行で二重発火なし
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-03 祭 ACTIVE 化で FESTIVAL_STARTED（冪等）")
    class FestivalStartedReflux {

        @Test
        @DisplayName("バッチで SCHEDULED→ACTIVE 化した祭は FESTIVAL_STARTED 投稿が 1 件・再実行しても 1 件のまま")
        void batch_emitsFestivalStartedOnce() {
            VillageEntity v = persistVillage();
            // startsAt が過去の SCHEDULED 祭 → runBatch で ACTIVE 化される
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.SCHEDULED,
                    LocalDateTime.now().minusMinutes(5), LocalDateTime.now().plusDays(1));

            festivalBatch.runBatch();
            assertThat(systemPosts(v.getId(), VillageEventNotificationType.FESTIVAL_STARTED, f.getId()))
                    .hasSize(1);

            // 再実行しても二重発火しない（既に ACTIVE のため SCHEDULED 走査に載らない）
            festivalBatch.runBatch();
            assertThat(systemPosts(v.getId(), VillageEventNotificationType.FESTIVAL_STARTED, f.getId()))
                    .hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-04: confirmMeetup で MEETUP_CONFIRMED 発火
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-04 寄合 CONFIRMED でフィード還流（MEETUP_CONFIRMED）")
    class MeetupConfirmedReflux {

        @Test
        @DisplayName("寄合を CONFIRMED に確定すると MEETUP_CONFIRMED のシステム投稿が 1 件できる")
        void confirmMeetup_emitsMeetupConfirmedSystemPost() {
            VillageEntity v = persistVillage();
            persistMembership(v.getId(), ORGANIZER_ID, VillageRole.VILLAGER);

            UUID meetupId = meetupService.createMeetup(v.getId(),
                    new MeetupCreateRequest("確定寄合", null, null,
                            List.of(new MeetupCandidateDateInput(LocalDate.now().plusDays(14), null))),
                    ORGANIZER_ID).id();
            UUID candidateDateId = candidateDateRepository
                    .findByMeetupIdOrderBySortOrderAscCandidateDateAscCandidateTimeAsc(meetupId)
                    .get(0).getId();

            meetupService.confirmMeetup(v.getId(), meetupId, new MeetupConfirmRequest(candidateDateId), ORGANIZER_ID);

            assertThat(systemPosts(v.getId(), VillageEventNotificationType.MEETUP_CONFIRMED, meetupId))
                    .hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-05: 接近通知バッチ（EVENT_UPCOMING）— 未実装ゆえ @BatchEndpoint 未登録で red
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-05 接近通知バッチ（EVENT_UPCOMING・前日1回・冪等）")
    class UpcomingNoticeBatch {

        @Test
        @DisplayName("接近通知バッチが @BatchEndpoint で登録され、翌日開催の祭に UPCOMING を 1 回だけ投稿する")
        void upcomingBatch_registeredAndEmitsOncePerEvent() {
            VillageEntity v = persistVillage();
            // 翌日開催（前日=今日）の SCHEDULED 祭
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.SCHEDULED,
                    LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(2));

            // 未実装なら @BatchEndpoint 未登録 → find が空で red（クラス不在をバッチ名経由で突きつける）
            assertThat(batchEndpointRegistry.find("village-event-upcoming-notice"))
                    .as("接近通知バッチが @BatchEndpoint(name=\"village-event-upcoming-notice\") で登録されている")
                    .isPresent();

            batchEndpointRegistry.invoke("village-event-upcoming-notice");
            assertThat(systemPosts(v.getId(), VillageEventNotificationType.EVENT_UPCOMING, f.getId()))
                    .hasSize(1);

            // 再走しても冪等（source_event_uuid 存在チェックで二重送信しない）
            batchEndpointRegistry.invoke("village-event-upcoming-notice");
            assertThat(systemPosts(v.getId(), VillageEventNotificationType.EVENT_UPCOMING, f.getId()))
                    .hasSize(1);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // AC-17: transitionToEnded で村史 1 件（RSVP 数が summary に反映）・再実行で二重編纂なし
    // ══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-17 祭 ENDED で村史編纂（冪等・RSVP集計）")
    class FestivalEndedArchiving {

        @Test
        @DisplayName("バッチで ACTIVE→ENDED 化した祭は village_event_archives に 1 件編纂され、RSVP 数が summary に載る")
        void batch_archivesFestivalOnceWithRsvpCount() {
            VillageEntity v = persistVillage();
            // endsAt が過去の ACTIVE 祭 → runBatch で ENDED 化される
            VillageFestivalEntity f = persistFestival(v.getId(), VillageFestivalStatus.ACTIVE,
                    LocalDateTime.now().minusDays(2), LocalDateTime.now().minusMinutes(5));
            persistRsvp(f.getId(), VILLAGER_ID, VillageFestivalRsvpStatus.GOING);
            persistRsvp(f.getId(), ORGANIZER_ID, VillageFestivalRsvpStatus.MAYBE);

            festivalBatch.runBatch();

            assertThat(eventArchiveRepository.findBySourceTypeAndSourceId(
                    VillageEventArchiveSourceType.FESTIVAL, f.getId()))
                    .as("祭 ENDED で村史が 1 件編纂される")
                    .isPresent()
                    .get()
                    .satisfies(a -> {
                        assertThat(a.getSourceId()).isEqualTo(f.getId());
                        // RSVP 2 件（GOING/MAYBE）が summary に反映される
                        assertThat(a.getSummary()).isNotNull();
                        assertThat(a.getSummary()).contains("2");
                    });

            // 再実行しても (source_type, source_id) UNIQUE により二重編纂されない（Optional が単一を返せる）
            festivalBatch.runBatch();
            assertThat(eventArchiveRepository.findBySourceTypeAndSourceId(
                    VillageEventArchiveSourceType.FESTIVAL, f.getId()))
                    .isPresent();
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    /** 指定村・種別・対象行事のシステム投稿を村フィードから取り出す（source_event_uuid 一致で機械判定）。 */
    private List<TimelinePostEntity> systemPosts(UUID villageId, VillageEventNotificationType type,
                                                 UUID sourceEventUuid) {
        return timelinePostRepository.findFeedByVillageId(villageId, PageRequest.of(0, 100)).stream()
                .filter(p -> type.name().equals(p.getSystemPostType()))
                .filter(p -> sourceEventUuid.equals(p.getSourceEventUuid()))
                .toList();
    }

    private VillageEntity persistVillage() {
        VillageEntity v = VillageEntity.builder()
                .slug("rfx-" + Long.toHexString(System.nanoTime()))
                .name("還流村" + System.nanoTime())
                .description("フィード還流テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build();
        return villageRepository.saveAndFlush(v);
    }

    private VillageMembershipEntity persistMembership(UUID villageId, Long userId, VillageRole role) {
        VillageMembershipEntity m = VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
        return membershipRepository.saveAndFlush(m);
    }

    private VillageFestivalEntity persistFestival(UUID villageId, VillageFestivalStatus status,
                                                  LocalDateTime starts, LocalDateTime ends) {
        VillageFestivalEntity f = VillageFestivalEntity.builder()
                .villageId(villageId)
                .title("祭" + System.nanoTime())
                .startsAt(starts)
                .endsAt(ends)
                .status(status)
                .createdByUserId(HEADMAN_ID)
                .build();
        return festivalRepository.saveAndFlush(f);
    }

    private VillageFestivalRsvpEntity persistRsvp(UUID festivalId, Long userId, VillageFestivalRsvpStatus status) {
        VillageFestivalRsvpEntity r = VillageFestivalRsvpEntity.builder()
                .festivalId(festivalId)
                .userId(userId)
                .status(status)
                .build();
        return rsvpRepository.saveAndFlush(r);
    }
}
