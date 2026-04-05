package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.entity.ActivityFeedEntity;
import com.mannschaft.app.dashboard.repository.ActivityFeedRepository;
import com.mannschaft.app.dashboard.service.ActivitySummaryGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
@DisplayName("ActivityFeedEventListener 単体テスト")
class ActivityFeedEventListenerTest {

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
                    ActivityType.POST_CREATED, ScopeType.TEAM, 10L, 1L, TargetType.TIMELINE_POST, 100L);
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
                    ActivityType.EVENT_CREATED, ScopeType.ORGANIZATION, 20L, 2L, TargetType.SCHEDULE, 200L);
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
                    ActivityType.TODO_COMPLETED, ScopeType.TEAM, 10L, 3L, TargetType.TODO, 300L);
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
                    ActivityType.FILE_UPLOADED, ScopeType.TEAM, 10L, 1L, TargetType.FILE, 400L);
            given(summaryGenerator.generate(ActivityType.FILE_UPLOADED)).willThrow(new RuntimeException("生成エラー"));

            // When（例外がスローされないことを確認）
            activityFeedEventListener.handleActivityEvent(event);

            // Then
            verify(activityFeedRepository, never()).save(any());
        }
    }
}
