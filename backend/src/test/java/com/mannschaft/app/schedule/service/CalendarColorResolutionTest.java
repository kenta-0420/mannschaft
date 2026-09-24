package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.schedule.CommentOption;
import com.mannschaft.app.schedule.EventType;
import com.mannschaft.app.schedule.MinViewRole;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.CalendarEntryResponse;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03.19 W1-c — 横断カレンダーの色解決（{@link ScheduleQueryService#getMyCalendar}）の単体テスト。
 *
 * <p>設計書 {@code docs/features/F03.19_unified_calendar_view.md} §3.4（色の優先順位）・
 * §3.4.1（適用範囲）・§4.6（enricher の色責務・R14）・§4.7（クエリ形状）。</p>
 *
 * <p>対応する受け入れ条件: AC-08（レイヤー色が最優先）・AC-18（既存フィールドの後方互換）・
 * AC-18b（全エントリで色が非 null）・AC-19（enricher 由来は固定色・enricher 自身は色を設定しない）・
 * AC-08d（TODO・reflection にレイヤー色を適用しない＝陰性対照）。</p>
 *
 * <p><b>陰性対照を必ず含む</b>: 「レイヤー色が効く」だけでなく「効いてはならない対象に効いていない」
 * ことを明示的に固定する。前者だけだと、全部をレイヤー色で塗り潰す実装でも緑になる。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("F03.19 横断カレンダーの色解決（W1-c）")
class CalendarColorResolutionTest {

    private static final Long ME = 1001L;
    private static final Long TEAM_ID = 42L;
    private static final Long ORG_ID = 7L;
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 8, 31, 23, 59);

    /** reflection の固定色（§3.4.1）。想起＝橙 / 記入＝藍。 */
    private static final String REFLECTION_RECALL_COLOR = "#F59E0B";
    private static final String REFLECTION_ENTRY_COLOR = "#6366F1";

    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private NameResolverService nameResolverService;
    @Mock
    private MembershipService membershipService;
    @Mock
    private ScheduleEventCategoryRepository categoryRepository;
    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;
    @Mock
    private ScheduleAttendanceRepository attendanceRepository;
    @Mock
    private ScheduleTargetService scheduleTargetService;
    @Mock
    private CalendarLayerService calendarLayerService;

    private final List<CalendarEntryResponse> enricherOutput = new java.util.ArrayList<>();

    private ScheduleQueryService service;

    @BeforeEach
    void setUp() {
        enricherOutput.clear();
        CalendarEnricher enricher = (userId, from, to) -> List.copyOf(enricherOutput);
        service = new ScheduleQueryService(
                scheduleRepository, nameResolverService, membershipService, categoryRepository,
                contentVisibilityChecker, attendanceRepository, scheduleTargetService,
                calendarLayerService, List.of(enricher));

        // 既定: 所属なし・設定なし・可視性は素通し。
        when(membershipService.getActiveTeamIdsIncludingRoleAssignments(anyLong())).thenReturn(List.of());
        when(membershipService.getActiveOrgIdsIncludingRoleAssignments(anyLong())).thenReturn(List.of());
        when(calendarLayerService.findUserLayerColors(anyLong())).thenReturn(Map.of());
        when(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(anyLong(), any(), any()))
                .thenReturn(List.of());
        when(nameResolverService.resolveScopeName(anyString(), anyLong())).thenReturn("スコープ名");
        when(nameResolverService.resolveIconUrl(anyString(), anyLong())).thenReturn(null);
        when(nameResolverService.resolveScopeSlug(anyString(), anyLong())).thenReturn("slug");
        when(scheduleTargetService.responsesForSchedules(any(), any(Boolean.class))).thenReturn(Map.of());
    }

    // ------------------------------------------------------------------
    // §3.4 優先順位
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§3.4 色の優先順位")
    class Priority {

        @Test
        @DisplayName("AC-08 レイヤー色は予定色より強い（優先1 > 優先2）")
        void layerColorBeatsScheduleColor() {
            givenTeamSchedule(101L, "#00FF00", null);
            when(calendarLayerService.findUserLayerColors(ME)).thenReturn(Map.of("TEAM:42", "#DC2626"));

            CalendarEntryResponse.CalendarContentDto content = teamEntry();

            assertThat(content.color()).isEqualTo("#DC2626");
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
        }

        @Test
        @DisplayName("レイヤー色が無ければ予定色が効く（優先2）")
        void scheduleColorWhenNoLayerColor() {
            givenTeamSchedule(101L, "#00FF00", null);

            CalendarEntryResponse.CalendarContentDto content = teamEntry();

            assertThat(content.color()).isEqualTo("#00FF00");
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.SCHEDULE);
        }

        @Test
        @DisplayName("レイヤー色も予定色も無ければカテゴリ色が効き categoryColor にも出る（優先3）")
        void categoryColorWhenNoLayerAndScheduleColor() {
            givenTeamSchedule(101L, null, 500L);
            when(categoryRepository.findAllById(any()))
                    .thenReturn(List.of(category(500L, "#123456")));

            CalendarEntryResponse.CalendarContentDto content = teamEntry();

            assertThat(content.color()).isEqualTo("#123456");
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.CATEGORY);
            assertThat(content.categoryColor()).isEqualTo("#123456");
        }

        @Test
        @DisplayName("何も無ければ自動色（優先4・スコープキー由来で決定的）")
        void autoColorWhenNothingSet() {
            givenTeamSchedule(101L, null, null);

            CalendarEntryResponse.CalendarContentDto content = teamEntry();

            assertThat(content.color())
                    .isEqualTo(CalendarLayerAutoColor.resolve("TEAM", TEAM_ID));
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.LAYER_AUTO);
        }

        @Test
        @DisplayName("categoryColor は色の採用可否と無関係にカテゴリ色そのものを載せる")
        void categoryColorIsExposedEvenWhenNotWinning() {
            givenTeamSchedule(101L, "#00FF00", 500L);
            when(categoryRepository.findAllById(any()))
                    .thenReturn(List.of(category(500L, "#123456")));

            CalendarEntryResponse.CalendarContentDto content = teamEntry();

            assertThat(content.color()).isEqualTo("#00FF00");
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.SCHEDULE);
            assertThat(content.categoryColor()).isEqualTo("#123456");
        }
    }

    // ------------------------------------------------------------------
    // §4.7 N+1 の不在
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("§4.7 カテゴリ色のバッチ取得")
    class CategoryBatch {

        @Test
        @DisplayName("カテゴリ色は件数によらず 1 回の findAllById でまとめて引く（N+1 を作らない）")
        void categoryColorsFetchedInSingleBatch() {
            List<ScheduleEntity> teamSchedules = List.of(
                    schedule(101L, "予定1", null, 500L),
                    schedule(102L, "予定2", null, 501L),
                    schedule(103L, "予定3", null, 500L));
            givenTeamSchedules(teamSchedules);
            when(categoryRepository.findAllById(any()))
                    .thenReturn(List.of(category(500L, "#111111"), category(501L, "#222222")));

            service.getMyCalendar(ME, FROM, TO);

            // 1 本の IN 句にまとめる（ループ内 Repository 呼び出しは禁止・§4.7）。
            verify(categoryRepository, times(1)).findAllById(any());
            // per-item 取得（findById）は 1 回も呼ばれてはならない（陰性対照）。
            verify(categoryRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("カテゴリ ID がひとつも無ければクエリを発行しない（0回）")
        void noQueryWhenNoCategoryIds() {
            givenTeamSchedule(101L, "#00FF00", null);

            service.getMyCalendar(ME, FROM, TO);

            verify(categoryRepository, never()).findAllById(any());
            verify(categoryRepository, never()).findById(anyLong());
        }
    }

    // ------------------------------------------------------------------
    // AC-18 / AC-18b
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("後方互換と色の埋め忘れ")
    class BackwardCompatibility {

        @Test
        @DisplayName("AC-18 既存フィールドは改修前と同じ形・同じ値で返る")
        void existingFieldsUnchanged() {
            givenTeamSchedule(101L, "#00FF00", null);

            CalendarEntryResponse entry = service.getMyCalendar(ME, FROM, TO).get(0);

            assertThat(entry.getId()).isEqualTo(101L);
            assertThat(entry.getScheduleId()).isEqualTo(101L);
            assertThat(entry.getContent().title()).isEqualTo("予定");
            assertThat(entry.getContent().eventType()).isEqualTo(EventType.PRACTICE.name());
            assertThat(entry.getContent().status()).isEqualTo(ScheduleStatus.SCHEDULED.name());
            assertThat(entry.getContent().referenceUuid()).isNull();
            assertThat(entry.getContent().referenceKind()).isNull();
            assertThat(entry.getTime().startAt()).isEqualTo(FROM.plusDays(1));
            assertThat(entry.getScope().scopeType()).isEqualTo("TEAM");
            assertThat(entry.getScope().scopeId()).isEqualTo(TEAM_ID);
            assertThat(entry.getMyAttendanceStatus()).isNull();
        }

        @Test
        @DisplayName("AC-18b 個人・チーム・組織・enricher 由来の全エントリで color / colorSource が非 null")
        void everyEntryHasColor() {
            when(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(ME), any(), any()))
                    .thenReturn(List.of(personalSchedule(1L, null)));
            givenTeamSchedule(101L, null, null);
            givenOrgSchedule(201L);
            enricherOutput.add(reflectionMark("REFLECTION_ENTRY"));
            enricherOutput.add(reflectionMark("REFLECTION_RECALL"));

            List<CalendarEntryResponse> entries = service.getMyCalendar(ME, FROM, TO);

            assertThat(entries).hasSize(5);
            assertThat(entries).allSatisfy(e -> {
                assertThat(e.getContent().color()).isNotNull();
                assertThat(e.getContent().colorSource()).isNotNull();
            });
        }

        @Test
        @DisplayName("5 引数コンストラクタは色を null のまま構築する（既存呼び出しは壊れない・R8-2）")
        void fiveArgConstructorStillCompilesAndLeavesColorsNull() {
            CalendarEntryResponse.CalendarContentDto five =
                    new CalendarEntryResponse.CalendarContentDto("t", "E", "S", "uuid", "KIND");
            CalendarEntryResponse.CalendarContentDto three =
                    new CalendarEntryResponse.CalendarContentDto("t", "E", "S");

            assertThat(five.referenceUuid()).isEqualTo("uuid");
            assertThat(five.color()).isNull();
            assertThat(five.colorSource()).isNull();
            assertThat(five.categoryColor()).isNull();
            assertThat(three.referenceUuid()).isNull();
            assertThat(three.color()).isNull();
        }
    }

    // ------------------------------------------------------------------
    // AC-19 / AC-08d（enricher・陰性対照）
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("AC-19 / AC-08d enricher 由来エントリ")
    class EnricherEntries {

        @Test
        @DisplayName("AC-19 想起は橙・記入は藍をサービス層が付与し colorSource は SCHEDULE")
        void reflectionFixedColorsAppliedByService() {
            enricherOutput.add(reflectionMark("REFLECTION_RECALL"));
            enricherOutput.add(reflectionMark("REFLECTION_ENTRY"));

            List<CalendarEntryResponse> entries = service.getMyCalendar(ME, FROM, TO);

            assertThat(entries.get(0).getContent().color()).isEqualTo(REFLECTION_RECALL_COLOR);
            assertThat(entries.get(0).getContent().colorSource()).isEqualTo(CalendarColorSource.SCHEDULE);
            assertThat(entries.get(1).getContent().color()).isEqualTo(REFLECTION_ENTRY_COLOR);
            assertThat(entries.get(1).getContent().colorSource()).isEqualTo(CalendarColorSource.SCHEDULE);
        }

        @Test
        @DisplayName("【陰性対照】AC-19 enricher 自身は色を設定していない（色はサービス層が付ける）")
        void enricherItselfSetsNoColor() {
            CalendarEntryResponse raw = reflectionMark("REFLECTION_RECALL");

            // enricher が返した時点の色は必ず null（R14: SPI に色の責務を持ち込まない）。
            assertThat(raw.getContent().color()).isNull();
            assertThat(raw.getContent().colorSource()).isNull();
            assertThat(raw.getContent().categoryColor()).isNull();
        }

        @Test
        @DisplayName("【陰性対照】AC-08d PERSONAL にレイヤー色を設定しても reflection は固定色のまま")
        void personalLayerColorDoesNotOverrideReflection() {
            when(calendarLayerService.findUserLayerColors(ME))
                    .thenReturn(Map.of("PERSONAL:0", "#DC2626"));
            enricherOutput.add(reflectionMark("REFLECTION_RECALL"));
            enricherOutput.add(reflectionMark("REFLECTION_ENTRY"));

            List<CalendarEntryResponse> entries = service.getMyCalendar(ME, FROM, TO);

            assertThat(entries.get(0).getContent().color()).isEqualTo(REFLECTION_RECALL_COLOR);
            assertThat(entries.get(1).getContent().color()).isEqualTo(REFLECTION_ENTRY_COLOR);
            assertThat(entries).noneSatisfy(e ->
                    assertThat(e.getContent().color()).isEqualTo("#DC2626"));
        }

        @Test
        @DisplayName("【陽性対照】同じ設定で個人スケジュールにはレイヤー色が効く（陰性対照が空振りでない証明）")
        void personalLayerColorDoesApplyToPersonalSchedule() {
            when(calendarLayerService.findUserLayerColors(ME))
                    .thenReturn(Map.of("PERSONAL:0", "#DC2626"));
            when(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(eq(ME), any(), any()))
                    .thenReturn(List.of(personalSchedule(1L, "#00FF00")));

            CalendarEntryResponse.CalendarContentDto content =
                    service.getMyCalendar(ME, FROM, TO).get(0).getContent();

            assertThat(content.color()).isEqualTo("#DC2626");
            assertThat(content.colorSource()).isEqualTo(CalendarColorSource.LAYER_USER);
        }

        @Test
        @DisplayName("referenceKind が未知の enricher エントリでも色は非 null（自動色で埋める）")
        void unknownEnricherKindStillGetsColor() {
            enricherOutput.add(CalendarEntryResponse.builder()
                    .content(new CalendarEntryResponse.CalendarContentDto(
                            "未知", "UNKNOWN", null, "uuid", null))
                    .time(new CalendarEntryResponse.CalendarTimeDto(FROM, FROM, Boolean.TRUE))
                    .scope(new CalendarEntryResponse.CalendarScopeDto("PERSONAL", null, null, null))
                    .build());

            CalendarEntryResponse.CalendarContentDto content =
                    service.getMyCalendar(ME, FROM, TO).get(0).getContent();

            assertThat(content.color()).isNotNull();
            assertThat(content.colorSource()).isNotNull();
        }
    }

    // ------------------------------------------------------------------
    // フィクスチャ
    // ------------------------------------------------------------------

    private CalendarEntryResponse.CalendarContentDto teamEntry() {
        List<CalendarEntryResponse> entries = service.getMyCalendar(ME, FROM, TO);
        assertThat(entries).hasSize(1);
        return entries.get(0).getContent();
    }

    private void givenTeamSchedule(Long id, String color, Long categoryId) {
        givenTeamSchedules(List.of(schedule(id, "予定", color, categoryId)));
    }

    private void givenTeamSchedules(List<ScheduleEntity> schedules) {
        when(membershipService.getActiveTeamIdsIncludingRoleAssignments(ME)).thenReturn(List.of(TEAM_ID));
        when(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(eq(TEAM_ID), any(), any()))
                .thenReturn(schedules);
        Set<Long> ids = new java.util.LinkedHashSet<>(schedules.stream().map(ScheduleEntity::getId).toList());
        when(contentVisibilityChecker.filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(ME)))
                .thenReturn(ids);
        when(scheduleTargetService.assignedScheduleIds(any(), eq(ME))).thenReturn(ids);
    }

    private void givenOrgSchedule(Long id) {
        when(membershipService.getActiveOrgIdsIncludingRoleAssignments(ME)).thenReturn(List.of(ORG_ID));
        ScheduleEntity org = ScheduleEntity.builder()
                .id(id)
                .organizationId(ORG_ID)
                .title("組織予定")
                .startAt(FROM.plusDays(2)).endAt(FROM.plusDays(2).plusHours(1)).allDay(false)
                .eventType(EventType.EVENT)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .createdBy(ME)
                .build();
        when(scheduleRepository.findByOrganizationIdAndStartAtBetweenOrderByStartAtAsc(
                eq(ORG_ID), any(), any())).thenReturn(List.of(org));
        // 可視性・割当は team/org 両方の呼び出しに効くよう「渡された ID をそのまま可視」とする。
        when(contentVisibilityChecker.filterAccessible(eq(ReferenceType.SCHEDULE), any(), eq(ME)))
                .thenAnswer(inv -> new java.util.LinkedHashSet<>(inv.getArgument(1, List.class)));
        when(scheduleTargetService.assignedScheduleIds(any(), eq(ME)))
                .thenAnswer(inv -> {
                    java.util.Collection<ScheduleEntity> arg = inv.getArgument(0);
                    return arg.stream().map(ScheduleEntity::getId)
                            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                });
    }

    private ScheduleEntity schedule(Long id, String title, String color, Long categoryId) {
        return ScheduleEntity.builder()
                .id(id)
                .teamId(TEAM_ID)
                .title(title)
                .startAt(FROM.plusDays(1)).endAt(FROM.plusDays(1).plusHours(2)).allDay(false)
                .eventType(EventType.PRACTICE)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .color(color)
                .eventCategoryId(categoryId)
                .createdBy(ME)
                .build();
    }

    private ScheduleEntity personalSchedule(Long id, String color) {
        return ScheduleEntity.builder()
                .id(id)
                .userId(ME)
                .title("個人予定")
                .startAt(FROM.plusHours(1)).endAt(FROM.plusHours(2)).allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .commentOption(CommentOption.OPTIONAL)
                .isException(false)
                .color(color)
                .createdBy(ME)
                .build();
    }

    private ScheduleEventCategoryEntity category(Long id, String color) {
        return ScheduleEventCategoryEntity.builder()
                .id(id)
                .teamId(TEAM_ID)
                .name("カテゴリ")
                .color(color)
                .build();
    }

    private CalendarEntryResponse reflectionMark(String referenceKind) {
        return CalendarEntryResponse.builder()
                .id(null)
                .scheduleId(null)
                .content(new CalendarEntryResponse.CalendarContentDto(
                        "テーマ", referenceKind, null, java.util.UUID.randomUUID().toString(), referenceKind))
                .time(new CalendarEntryResponse.CalendarTimeDto(FROM, FROM, Boolean.TRUE))
                .scope(new CalendarEntryResponse.CalendarScopeDto("PERSONAL", null, null, null))
                .build();
    }
}
