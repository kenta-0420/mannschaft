package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.dto.ActivityFeedPageResponse;
import com.mannschaft.app.dashboard.dto.ActivityFeedResponse;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivityFeedService;
import com.mannschaft.app.schedule.visibility.ScheduleVisibilityResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.18 第三隊 — {@code ActivityFeedService} の可視性フィルタ／ページ送りの単体テスト。
 *
 * <p>受け入れ条件 AC-14（fail-closed）・AC-15（削除イベント例外）・AC-18（nextCursor）・
 * AC-19（追加フェッチ上限）・AC-23 / AC-24（判定のバッチ化＝N+1 の不在）を、
 * {@link ScheduleVisibilityResolver} をモックした純メモリの単体テストで固定する。</p>
 *
 * <p>可視性の «意味» そのもの（誰に見えるか）は Resolver の責務であり、実 MySQL を使う
 * {@code ActivityFeedVisibilityIT} が検証する。本テストが測るのは
 * 「Service が Resolver の答えに正しく従い、まとめて1回だけ問い合わせるか」である。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ActivityFeedService 可視性フィルタ・ページ送り（F03.18）")
class ActivityFeedVisibilityFilterTest {

    @Mock private ActivityFeedRepository activityFeedRepository;
    @Mock private DashboardMapper dashboardMapper;
    @Mock private NameResolverService nameResolverService;
    @Mock private ScheduleVisibilityResolver scheduleVisibilityResolver;

    @InjectMocks
    private ActivityFeedService activityFeedService;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long ACTOR_ID = 99L;
    private static final List<Long> TEAM_IDS = List.of(TEAM_ID);
    private static final List<Long> ORG_IDS = List.of(ORG_ID);

    /** id を明示できる行ビルダ（ページ送りの検証で id の連続性が意味を持つため）。 */
    private static ActivityFeedEntity row(Long id, ActivityType activityType,
                                          TargetType targetType, Long targetId) {
        ActivityFeedEntity e = ActivityFeedEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(TEAM_ID)
                .actorId(ACTOR_ID)
                .activityType(activityType)
                .targetType(targetType)
                .targetId(targetId)
                .summary("テスト")
                .createdAt(LocalDateTime.now())
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private static ActivityFeedEntity scheduleRow(Long id, Long scheduleId) {
        return row(id, ActivityType.SCHEDULE_UPDATED, TargetType.SCHEDULE, scheduleId);
    }

    private static ActivityFeedEntity cancelledRow(Long id, Long scheduleId) {
        return row(id, ActivityType.SCHEDULE_CANCELLED, TargetType.SCHEDULE, scheduleId);
    }

    private static ActivityFeedEntity postRow(Long id) {
        return row(id, ActivityType.POST_CREATED, TargetType.TIMELINE_POST, 500L + id);
    }

    /**
     * 名前解決とマッパーを「素通し」に固定する。可視性の検証に無関係なノイズを消すため。
     */
    private void stubPassthroughMapping() {
        given(nameResolverService.resolveUserDisplayNames(any())).willReturn(Map.of());
        given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of());
        given(nameResolverService.resolveOrganizationNames(any())).willReturn(Map.of());
        given(dashboardMapper.toActivityFeedResponse(any(), any(), any(), any()))
                .willAnswer(inv -> {
                    ActivityFeedEntity e = inv.getArgument(0);
                    return new ActivityFeedResponse(
                            e.getId(), e.getActivityType().name(),
                            inv.getArgument(1),
                            e.getScopeType().name(), e.getScopeId(), inv.getArgument(2),
                            e.getTargetType().name(), e.getTargetId(), e.getSummary(),
                            inv.getArgument(3), e.getCreatedAt());
                });
    }

    /** 先頭ページ（カーソルなし）に返す行を仕込む。 */
    private void stubFirstPage(List<ActivityFeedEntity> rows) {
        given(activityFeedRepository.findByScopesAndExcludeActor(
                any(), any(), any(), any(Pageable.class)))
                .willReturn(rows);
    }

    // ==================================================================
    // AC-14 / AC-15 — fail-closed と削除イベントの例外
    // ==================================================================

    @Nested
    @DisplayName("可視性フィルタ")
    class VisibilityFilter {

        @Test
        @DisplayName("AC-14: Resolver から見えない予定の SCHEDULE_UPDATED 行は結果に含まれない（fail-closed）")
        void ac14_invisibleScheduleRowIsExcluded() {
            stubPassthroughMapping();
            stubFirstPage(List.of(scheduleRow(100L, 777L)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of()); // 1件も見えない

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems())
                    .as("Resolver が不可視と判定した予定のフィード行が残っている（情報漏洩）")
                    .isEmpty();
        }

        @Test
        @DisplayName("AC-14 陽性対照: Resolver が可視と判定した予定の行は残る")
        void ac14_visibleScheduleRowIsKept() {
            stubPassthroughMapping();
            stubFirstPage(List.of(scheduleRow(100L, 777L)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of(777L));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getTargetId()).isEqualTo(777L);
        }

        @Test
        @DisplayName("AC-14: SCHEDULE 以外の既存7種別は Resolver を通さずそのまま残る")
        void ac14_nonScheduleRowsBypassResolver() {
            stubPassthroughMapping();
            stubFirstPage(List.of(postRow(100L), postRow(99L)));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(2);
            // SCHEDULE 行が1件も無いページでは Resolver を呼ばない（無駄な SQL を増やさない）。
            verify(scheduleVisibilityResolver, never()).filterAccessible(anyCollection(), anyLong());
        }

        @Test
        @DisplayName("AC-15: SCHEDULE_CANCELLED は Resolver に渡されず、所属スコープの行として残る")
        void ac15_cancelledRowIsExemptFromResolver() {
            stubPassthroughMapping();
            // 削除済み予定は @SQLRestriction により Resolver の射影に載らない＝空集合が返る状況を再現。
            stubFirstPage(List.of(cancelledRow(100L, 777L), scheduleRow(99L, 888L)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of());

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            // 削除の «事実» はスコープ所属者に残す。中身を伴う SCHEDULE_UPDATED は消える。
            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getType()).isEqualTo("SCHEDULE_CANCELLED");

            // 削除済み予定の ID を Resolver へ渡してはならない（渡せば必ず fail-closed で消える）。
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(scheduleVisibilityResolver).filterAccessible(captor.capture(), anyLong());
            assertThat(captor.getValue())
                    .as("SCHEDULE_CANCELLED の対象予定IDが可視性判定へ渡っている")
                    .doesNotContain(777L)
                    .contains(888L);
        }

        @Test
        @DisplayName("AC-15: SCHEDULE_CANCELLED しか無いページでも Resolver は呼ばれない")
        void ac15_onlyCancelledRows_resolverNotCalled() {
            stubPassthroughMapping();
            stubFirstPage(List.of(cancelledRow(100L, 777L)));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(1);
            verify(scheduleVisibilityResolver, never()).filterAccessible(anyCollection(), anyLong());
        }

        @Test
        @DisplayName("所属スコープが空なら1本もクエリを発行せず空ページを返す")
        void noScopes_returnsEmptyWithoutQuery() {
            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, List.of(), List.of());

            assertThat(result.getItems()).isEmpty();
            assertThat(result.getNextCursor()).isNull();
            verify(activityFeedRepository, never())
                    .findByScopesAndExcludeActor(any(), any(), any(), any(Pageable.class));
        }
    }

    // ==================================================================
    // AC-18 / AC-19 — nextCursor と追加フェッチ上限
    // ==================================================================

    @Nested
    @DisplayName("ページ送り")
    class Paging {

        @Test
        @DisplayName("AC-18: nextCursor は «フィルタ前» の最終行 id（除外された行の id を含む）")
        void ac18_nextCursorIsLastRawIdBeforeFilter() {
            stubPassthroughMapping();
            // limit=2。2件取得し、最後の1件（id=99）は不可視で除外される。
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(100L), scheduleRow(99L, 777L)));
            given(activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    any(), any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of()); // 続きは無い
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of());

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 2, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).extracting(ActivityFeedResponse::getId)
                    .containsExactly(100L);
            // 追加フェッチが空を返した＝母集団が尽きた → nextCursor は null。
            assertThat(result.getNextCursor()).isNull();

            // 追加フェッチのカーソルは «除外された» id=99 でなければならない。
            // フィルタ後の id=100 を使うと、次回が id<100 となり id=99 を読み直してしまう。
            ArgumentCaptor<Long> cursorCaptor = ArgumentCaptor.forClass(Long.class);
            verify(activityFeedRepository).findByScopeAndExcludeActorWithCursor(
                    any(), any(), any(), cursorCaptor.capture(), any(Pageable.class));
            assertThat(cursorCaptor.getValue()).isEqualTo(99L);
        }

        @Test
        @DisplayName("AC-18: 満たされたページでは nextCursor が «フィルタ前» 最終行 id で非 null")
        void ac18_fullPageReturnsNonNullCursor() {
            stubPassthroughMapping();
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(100L), postRow(99L)));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 2, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(2);
            assertThat(result.getNextCursor()).isEqualTo("99");
        }

        @Test
        @DisplayName("AC-19 枯渇: Repository が要求件数未満を返したら nextCursor は null")
        void ac19_exhaustedReturnsNullCursor() {
            stubPassthroughMapping();
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(100L)));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getNextCursor()).isNull();
        }

        @Test
        @DisplayName("AC-19: 全件が可視性で除外され続けても追加フェッチは5周を超えず、nextCursor は非 null")
        void ac19_loopIsCappedAtFive() {
            stubPassthroughMapping();
            // 常に「要求件数ちょうど」を返し、可視性は常に全滅させる＝理論上の無限ループ条件。
            AtomicInteger issued = new AtomicInteger();
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willAnswer(inv -> {
                        int n = issued.incrementAndGet();
                        return List.of(scheduleRow(1000L - n, 777L), scheduleRow(999L - n, 778L));
                    });
            given(activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    any(), any(), any(), any(), any(Pageable.class)))
                    .willAnswer(inv -> {
                        int n = issued.incrementAndGet();
                        return List.of(scheduleRow(1000L - n, 777L), scheduleRow(999L - n, 778L));
                    });
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of());

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 2, TEAM_IDS, ORG_IDS);

            // SQL 意図数で測る: フェッチ回数（初回 + 追加）は合計5回で打ち切られる。
            assertThat(issued.get())
                    .as("追加フェッチのループが上限 5 周を超えている（無限ループの危険）")
                    .isEqualTo(5);
            assertThat(result.getItems()).isEmpty();
            assertThat(result.getNextCursor())
                    .as("上限打ち切り時は «まだ続きがある» ことを表すため nextCursor は非 null")
                    .isNotNull();
        }

        @Test
        @DisplayName("AC-19: 不足分は追加フェッチで埋められ、要求件数を超えて返さない")
        void ac19_refillsUpToLimitAndNoMore() {
            stubPassthroughMapping();
            // 1周目: 3件中2件が不可視 → 可視1件。2周目: 3件すべて可視 → 合計4件だが limit=3 で切る。
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(100L), scheduleRow(99L, 777L), scheduleRow(98L, 778L)));
            given(activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    any(), any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(97L), postRow(96L), postRow(95L)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of());

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 3, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).extracting(ActivityFeedResponse::getId)
                    .containsExactly(100L, 97L, 96L);
            // 切り詰めが起きた（可視4件中3件だけ返した）ため、nextCursor は
            // «実際に返した最後の行» の id。«フィルタ前» 最終 id（95）を返すと、
            // 切り捨てた id=95 の行が次ページ（id<95）に現れず恒久的に欠落する。
            assertThat(result.getNextCursor()).isEqualTo("96");
        }

        @Test
        @DisplayName("回帰(P1-2): 切り詰めで捨てた可視行が次ページで必ず取得でき、重複も欠落も出ない")
        void truncatedVisibleRowIsNotLostAcrossPages() {
            stubPassthroughMapping();
            // limit=3。1周目=可視1件（100）、2周目=可視3件（97/96/95）＝可視4件。
            // 1ページ目は 100/97/96 を返し、95 は切り詰められる。
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(List.of(postRow(100L), scheduleRow(99L, 777L), scheduleRow(98L, 778L)));
            given(activityFeedRepository.findByScopeAndExcludeActorWithCursor(
                    any(), any(), any(), any(), any(Pageable.class)))
                    .willAnswer(inv -> {
                        long cursor = inv.getArgument(3);
                        // 実 Repository と同じく「id < cursor を id DESC で最大 size 件」を再現する。
                        List<ActivityFeedEntity> all = List.of(
                                postRow(97L), postRow(96L), postRow(95L));
                        return all.stream().filter(r -> r.getId() < cursor).limit(3).toList();
                    });
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of());

            ActivityFeedPageResponse page1 =
                    activityFeedService.getActivityFeed(USER_ID, null, 3, TEAM_IDS, ORG_IDS);
            assertThat(page1.getItems()).extracting(ActivityFeedResponse::getId)
                    .containsExactly(100L, 97L, 96L);
            assertThat(page1.getNextCursor())
                    .as("切り詰め時は «返した最後の行» の id を返さないと、捨てた行が二度と取れない")
                    .isEqualTo("96");

            ActivityFeedPageResponse page2 = activityFeedService.getActivityFeed(
                    USER_ID, Long.valueOf(page1.getNextCursor()), 3, TEAM_IDS, ORG_IDS);

            List<Long> page1Ids = page1.getItems().stream().map(ActivityFeedResponse::getId).toList();
            List<Long> page2Ids = page2.getItems().stream().map(ActivityFeedResponse::getId).toList();

            assertThat(page2Ids)
                    .as("1ページ目で切り捨てられた id=95 が2ページ目に現れない（恒久的な欠落）")
                    .contains(95L);
            assertThat(page2Ids)
                    .as("2ページ目に1ページ目と同じ行が混ざっている（重複）")
                    .doesNotContainAnyElementsOf(page1Ids);

            // 2ページ通して可視4件が過不足なく現れる。
            List<Long> all = new ArrayList<>(page1Ids);
            all.addAll(page2Ids);
            assertThat(all).containsExactly(100L, 97L, 96L, 95L);
        }
    }

    // ==================================================================
    // 回帰(P1-1) — detail は JSON 文字列でなくパース済み object で返る
    // ==================================================================

    @Nested
    @DisplayName("detail のパース（F03.18 §3.3）")
    class DetailParsing {

        private static final String DETAIL_JSON =
                "{\"scheduleId\":777,\"title\":\"定例会議\",\"fields\":["
                        + "{\"field\":\"startAt\",\"before\":\"2026-08-10T19:00:00\",\"after\":\"2026-08-17T19:00:00\"}"
                        + "],\"affectedCount\":1}";

        private ActivityFeedEntity scheduleRowWithDetail(Long id, Long scheduleId, String detail) {
            ActivityFeedEntity e = scheduleRow(id, scheduleId);
            org.springframework.test.util.ReflectionTestUtils.setField(e, "detail", detail);
            return e;
        }

        @Test
        @DisplayName("回帰(P1-1): detail は JSON 文字列のままでなく構造化オブジェクトとして返る")
        void detailIsParsedIntoObject() {
            stubPassthroughMapping();
            stubFirstPage(List.of(scheduleRowWithDetail(100L, 777L, DETAIL_JSON)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of(777L));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            Object detail = result.getItems().get(0).getDetail();
            assertThat(detail)
                    .as("detail が String のまま。Jackson がエスケープ済み文字列として出力し、"
                            + "FE から detail.fields を読めない")
                    .isNotInstanceOf(String.class)
                    .isInstanceOf(com.mannschaft.app.dashboard.dto.ScheduleFeedDetail.class);

            var parsed = (com.mannschaft.app.dashboard.dto.ScheduleFeedDetail) detail;
            assertThat(parsed.title()).isEqualTo("定例会議");
            assertThat(parsed.scheduleId()).isEqualTo(777L);
            assertThat(parsed.fields()).hasSize(1);
            assertThat(parsed.fields().get(0).field()).isEqualTo("startAt");
            assertThat(parsed.fields().get(0).after()).isEqualTo("2026-08-17T19:00:00");
            assertThat(parsed.affectedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("回帰(P1-1): detail のパース失敗でも行は落とさず detail=null で返す")
        void brokenDetailDoesNotDropTheRow() {
            stubPassthroughMapping();
            stubFirstPage(List.of(
                    scheduleRowWithDetail(100L, 777L, "{壊れたJSON"),
                    scheduleRowWithDetail(99L, 778L, DETAIL_JSON)));
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(Set.of(777L, 778L));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems())
                    .as("壊れた1行がフィード一覧全体を壊してはならない")
                    .hasSize(2);
            assertThat(result.getItems().get(0).getDetail()).isNull();
            assertThat(result.getItems().get(1).getDetail()).isNotNull();
        }

        @Test
        @DisplayName("既存7種別（detail 列が null）は detail=null のまま返る")
        void existingTypesKeepNullDetail() {
            stubPassthroughMapping();
            stubFirstPage(List.of(postRow(100L)));

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 10, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems().get(0).getDetail()).isNull();
        }
    }

    // ==================================================================
    // AC-23 / AC-24 — 判定のバッチ化（N+1 の不在）
    // ==================================================================

    @Nested
    @DisplayName("バッチ判定（N+1 の不在）")
    class BatchResolution {

        @Test
        @DisplayName("AC-23: SCHEDULE 系20件を1回取得したとき、可視性判定の問い合わせ意図は 1 回")
        void ac23_twentyScheduleRows_singleResolverCall() {
            stubPassthroughMapping();
            List<ActivityFeedEntity> rows = new ArrayList<>();
            Set<Long> visible = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                long scheduleId = 700L + i;
                rows.add(scheduleRow(200L - i, scheduleId));
                visible.add(scheduleId);
            }
            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(rows);
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willReturn(visible);

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, 20, TEAM_IDS, ORG_IDS);

            assertThat(result.getItems()).hasSize(20);
            // 件数に依らず 1 回。20 回呼べば schedules への SQL が 20 本になる（N+1）。
            verify(scheduleVisibilityResolver, times(1)).filterAccessible(anyCollection(), anyLong());

            // かつ、20件の予定IDが «まとめて» 1回で渡っていること。
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(scheduleVisibilityResolver).filterAccessible(captor.capture(), anyLong());
            assertThat(captor.getValue()).hasSize(20);
        }

        @Test
        @DisplayName("AC-24: 件数を10→50に増やしても可視性判定の問い合わせ意図数は増えない")
        void ac24_scalingRowCountDoesNotIncreaseIntentCount() {
            int callsFor10 = measureResolverCalls(10);
            int callsFor50 = measureResolverCalls(50);

            assertThat(callsFor10).as("10件時の判定回数").isEqualTo(1);
            assertThat(callsFor50)
                    .as("50件へ増やしたら判定回数が増えた（N+1）。10件時=%d, 50件時=%d",
                            callsFor10, callsFor50)
                    .isEqualTo(callsFor10);
        }

        /**
         * 指定件数の SCHEDULE 行を1ページで返したときの、可視性判定の呼び出し回数を測る。
         */
        private int measureResolverCalls(int rowCount) {
            org.mockito.Mockito.reset(activityFeedRepository, scheduleVisibilityResolver,
                    dashboardMapper, nameResolverService);
            stubPassthroughMapping();

            List<ActivityFeedEntity> rows = new ArrayList<>();
            for (int i = 0; i < rowCount; i++) {
                rows.add(scheduleRow(1000L - i, 700L + i));
            }
            Set<Long> visible = rows.stream()
                    .map(ActivityFeedEntity::getTargetId).collect(Collectors.toSet());

            given(activityFeedRepository.findByScopesAndExcludeActor(
                    any(), any(), any(), any(Pageable.class)))
                    .willReturn(rows);

            // 実呼び出しだけを数える（スタブ登録そのものを数に含めない）。
            AtomicInteger resolverCalls = new AtomicInteger();
            given(scheduleVisibilityResolver.filterAccessible(anyCollection(), anyLong()))
                    .willAnswer(inv -> {
                        resolverCalls.incrementAndGet();
                        return visible;
                    });

            ActivityFeedPageResponse result =
                    activityFeedService.getActivityFeed(USER_ID, null, rowCount, TEAM_IDS, ORG_IDS);
            assertThat(result.getItems()).hasSize(rowCount);

            return resolverCalls.get();
        }
    }
}
