package com.mannschaft.app.dashboard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivitySummaryGenerator;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ActivityFeedEventListener} の単体テスト。
 * アクティビティイベントの受信と書き込み処理を検証する。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ActivityFeedEventListener 単体テスト")
class ActivityFeedEventListenerTest {

    /** マージ後の detail JSON を «構造» で照合するための Mapper（文字列一致に依存しない）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Mock
    private ActivityFeedRepository activityFeedRepository;

    @Mock
    private ActivitySummaryGenerator summaryGenerator;

    @InjectMocks
    private ActivityFeedEventListener activityFeedEventListener;

    // ========================================
    // handleActivityEvent
    // ========================================

    @Nested
    @DisplayName("handleActivityEvent")
    class HandleActivityEvent {

        @Test
        @DisplayName("正常系: アクティビティイベントがフィードに書き込まれる")
        void handleActivityEvent_正常_フィードに書き込み() {
            // Given
            ActivityEvent event = new ActivityEvent(
                    ActivityType.POST_CREATED, ScopeType.TEAM, 10L, 1L, TargetType.TIMELINE_POST, 100L, null);
            given(summaryGenerator.generate(ActivityType.POST_CREATED)).willReturn("新しい投稿を作成しました");

            // When
            activityFeedEventListener.handleActivityEvent(event);

            // Then
            verify(activityFeedRepository).save(any(ActivityFeedEntity.class));
            verify(summaryGenerator).generate(ActivityType.POST_CREATED);
        }

        @Test
        @DisplayName("異常系: 書き込み失敗時は例外をキャッチしてログのみ出力")
        void handleActivityEvent_書き込み失敗_例外キャッチ() {
            // Given
            ActivityEvent event = new ActivityEvent(
                    ActivityType.EVENT_CREATED, ScopeType.ORGANIZATION, 20L, 2L, TargetType.SCHEDULE, 200L, null);
            given(summaryGenerator.generate(ActivityType.EVENT_CREATED)).willReturn("新しいイベントを作成しました");
            doThrow(new RuntimeException("DB接続エラー")).when(activityFeedRepository).save(any(ActivityFeedEntity.class));

            // When（例外がスローされないことを確認）
            activityFeedEventListener.handleActivityEvent(event);

            // Then
            verify(activityFeedRepository).save(any(ActivityFeedEntity.class));
        }

        @Test
        @DisplayName("正常系: 異なるアクティビティタイプで正しいサマリーが生成される")
        void handleActivityEvent_TODOComplete_正しいサマリー() {
            // Given
            ActivityEvent event = new ActivityEvent(
                    ActivityType.TODO_COMPLETED, ScopeType.TEAM, 10L, 3L, TargetType.TODO, 300L, null);
            given(summaryGenerator.generate(ActivityType.TODO_COMPLETED)).willReturn("TODOを完了しました");

            // When
            activityFeedEventListener.handleActivityEvent(event);

            // Then
            verify(summaryGenerator).generate(ActivityType.TODO_COMPLETED);
            verify(activityFeedRepository).save(any(ActivityFeedEntity.class));
        }

        @Test
        @DisplayName("異常系: サマリー生成で失敗した場合も例外をキャッチ")
        void handleActivityEvent_サマリー生成失敗_例外キャッチ() {
            // Given
            ActivityEvent event = new ActivityEvent(
                    ActivityType.FILE_UPLOADED, ScopeType.TEAM, 10L, 1L, TargetType.FILE, 400L, null);
            given(summaryGenerator.generate(ActivityType.FILE_UPLOADED)).willThrow(new RuntimeException("生成エラー"));

            // When（例外がスローされないことを確認）
            activityFeedEventListener.handleActivityEvent(event);

            // Then
            verify(activityFeedRepository, never()).save(any());
        }
    }

    // ========================================
    // AC-09: 5分以内の連続編集マージ（F03.18 §5.4）
    // ========================================

    @Nested
    @DisplayName("連続編集のマージ（AC-09）")
    class MergeRecentEdit {

        private static final Long ACTOR_ID = 1L;
        private static final Long SCHEDULE_ID = 555L;

        private ActivityFeedEntity existingRow(ActivityType type, LocalDateTime createdAt, String detail) {
            ActivityFeedEntity e = ActivityFeedEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(10L)
                    .actorId(ACTOR_ID)
                    .activityType(type)
                    .targetType(TargetType.SCHEDULE)
                    .targetId(SCHEDULE_ID)
                    .summary("予定を更新しました")
                    .detail(detail)
                    .build();
            ReflectionTestUtils.setField(e, "id", 42L);
            ReflectionTestUtils.setField(e, "createdAt", createdAt);
            return e;
        }

        private ActivityEvent updateEvent(ActivityType type, String detail) {
            return new ActivityEvent(type, ScopeType.TEAM, 10L, ACTOR_ID,
                    TargetType.SCHEDULE, SCHEDULE_ID, detail);
        }

        private static final String FIRST_DETAIL =
                "{\"scheduleId\":555,\"title\":\"定例会議\",\"fields\":["
                        + "{\"field\":\"title\",\"before\":\"旧タイトル\",\"after\":\"中間タイトル\"}],"
                        + "\"affectedCount\":1}";
        private static final String SECOND_DETAIL =
                "{\"scheduleId\":555,\"title\":\"最新タイトル\",\"fields\":["
                        + "{\"field\":\"title\",\"before\":\"中間タイトル\",\"after\":\"最新タイトル\"},"
                        + "{\"field\":\"startAt\",\"before\":\"2026-08-10T19:00:00\",\"after\":\"2026-08-17T19:00:00\"}],"
                        + "\"affectedCount\":1}";

        @Test
        @DisplayName("AC-09: 5分以内の2回目の編集は新規行を作らず直近行へマージされる（行数1・createdAt 不変・before は初回値）")
        void withinFiveMinutes_mergesIntoExistingRow() throws Exception {
            LocalDateTime firstAt = LocalDateTime.now().minusMinutes(2);
            ActivityFeedEntity existing = existingRow(ActivityType.SCHEDULE_UPDATED, firstAt, FIRST_DETAIL);
            given(activityFeedRepository.findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                    ACTOR_ID, SCHEDULE_ID, TargetType.SCHEDULE)).willReturn(Optional.of(existing));
            given(summaryGenerator.generate(any(ActivityType.class))).willReturn("予定の日程を変更しました");

            activityFeedEventListener.handleActivityEvent(
                    updateEvent(ActivityType.SCHEDULE_RESCHEDULED, SECOND_DETAIL));

            ArgumentCaptor<ActivityFeedEntity> captor = ArgumentCaptor.forClass(ActivityFeedEntity.class);
            verify(activityFeedRepository).save(captor.capture());
            ActivityFeedEntity saved = captor.getValue();

            // 行数1（＝新規 INSERT ではなく既存 id への UPDATE）
            assertThat(saved.getId()).as("新規行が作られている（マージされていない）").isEqualTo(42L);
            // createdAt 据え置き
            assertThat(saved.getCreatedAt()).isEqualTo(firstAt);
            // 種別は UPDATED → RESCHEDULED へ昇格
            assertThat(saved.getActivityType()).isEqualTo(ActivityType.SCHEDULE_RESCHEDULED);

            JsonNode merged = OBJECT_MAPPER.readTree(saved.getDetail());
            Map<String, JsonNode> byField = new LinkedHashMap<>();
            merged.get("fields").forEach(f -> byField.put(f.get("field").asText(), f));

            // title は before=初回値・after=最新値にまとめる
            assertThat(byField.get("title").get("before").asText()).isEqualTo("旧タイトル");
            assertThat(byField.get("title").get("after").asText()).isEqualTo("最新タイトル");
            // 2回目で初めて変わったフィールドは追加される
            assertThat(byField.get("startAt").get("after").asText()).isEqualTo("2026-08-17T19:00:00");
            assertThat(merged.get("title").asText()).isEqualTo("最新タイトル");
        }

        @Test
        @DisplayName("AC-09: 5分を超えていれば新規行として INSERT される（2行になる）")
        void beyondFiveMinutes_insertsNewRow() {
            ActivityFeedEntity existing = existingRow(
                    ActivityType.SCHEDULE_UPDATED, LocalDateTime.now().minusMinutes(6), FIRST_DETAIL);
            given(activityFeedRepository.findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                    ACTOR_ID, SCHEDULE_ID, TargetType.SCHEDULE)).willReturn(Optional.of(existing));
            given(summaryGenerator.generate(any(ActivityType.class))).willReturn("予定を更新しました");

            activityFeedEventListener.handleActivityEvent(
                    updateEvent(ActivityType.SCHEDULE_UPDATED, SECOND_DETAIL));

            ArgumentCaptor<ActivityFeedEntity> captor = ArgumentCaptor.forClass(ActivityFeedEntity.class);
            verify(activityFeedRepository).save(captor.capture());
            assertThat(captor.getValue().getId())
                    .as("5分超なのに既存行へマージされている（別の編集セッションが1行に潰れる）")
                    .isNull();
            assertThat(captor.getValue().getDetail()).isEqualTo(SECOND_DETAIL);
        }

        @Test
        @DisplayName("AC-09: 種別は降格しない（直近が RESCHEDULED なら UPDATED を受けても RESCHEDULED のまま）")
        void neverDemotesActivityType() {
            ActivityFeedEntity existing = existingRow(
                    ActivityType.SCHEDULE_RESCHEDULED, LocalDateTime.now().minusMinutes(1), FIRST_DETAIL);
            given(activityFeedRepository.findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                    ACTOR_ID, SCHEDULE_ID, TargetType.SCHEDULE)).willReturn(Optional.of(existing));
            given(summaryGenerator.generate(any(ActivityType.class))).willReturn("予定の日程を変更しました");

            activityFeedEventListener.handleActivityEvent(
                    updateEvent(ActivityType.SCHEDULE_UPDATED, SECOND_DETAIL));

            ArgumentCaptor<ActivityFeedEntity> captor = ArgumentCaptor.forClass(ActivityFeedEntity.class);
            verify(activityFeedRepository).save(captor.capture());
            assertThat(captor.getValue().getActivityType()).isEqualTo(ActivityType.SCHEDULE_RESCHEDULED);
        }

        @Test
        @DisplayName("AC-09: 作成・削除イベントはマージ対象外（直近行があっても新規 INSERT）")
        void createAndCancelAreNotMerged() {
            ActivityFeedEntity existing = existingRow(
                    ActivityType.SCHEDULE_UPDATED, LocalDateTime.now(), FIRST_DETAIL);
            given(activityFeedRepository.findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                    ACTOR_ID, SCHEDULE_ID, TargetType.SCHEDULE)).willReturn(Optional.of(existing));
            given(summaryGenerator.generate(any(ActivityType.class))).willReturn("予定を削除しました");

            activityFeedEventListener.handleActivityEvent(
                    updateEvent(ActivityType.SCHEDULE_CANCELLED, null));

            ArgumentCaptor<ActivityFeedEntity> captor = ArgumentCaptor.forClass(ActivityFeedEntity.class);
            verify(activityFeedRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isNull();
            assertThat(captor.getValue().getActivityType()).isEqualTo(ActivityType.SCHEDULE_CANCELLED);
        }

        @Test
        @DisplayName("AC-09: 直近行が無ければ従来どおり新規 INSERT")
        void noRecentRow_insertsNewRow() {
            given(activityFeedRepository.findTopByActorIdAndTargetIdAndTargetTypeOrderByIdDesc(
                    ACTOR_ID, SCHEDULE_ID, TargetType.SCHEDULE)).willReturn(Optional.empty());
            given(summaryGenerator.generate(any(ActivityType.class))).willReturn("予定を更新しました");

            activityFeedEventListener.handleActivityEvent(
                    updateEvent(ActivityType.SCHEDULE_UPDATED, FIRST_DETAIL));

            ArgumentCaptor<ActivityFeedEntity> captor = ArgumentCaptor.forClass(ActivityFeedEntity.class);
            verify(activityFeedRepository).save(captor.capture());
            assertThat(captor.getValue().getId()).isNull();
        }
    }
}
