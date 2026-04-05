package com.mannschaft.app.dashboard;

import com.mannschaft.app.dashboard.service.ActivitySummaryGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ActivitySummaryGenerator} の単体テスト。
 * アクティビティ種別ごとのサマリーテキスト生成を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivitySummaryGenerator 単体テスト")
class ActivitySummaryGeneratorTest {

    @InjectMocks
    private ActivitySummaryGenerator activitySummaryGenerator;

    // ========================================
    // generate
    // ========================================

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("正常系: POST_CREATEDで投稿作成テキストが返却される")
        void generate_POST_CREATED_投稿作成() {
            assertThat(activitySummaryGenerator.generate(ActivityType.POST_CREATED))
                    .isEqualTo("新しい投稿を作成しました");
        }

        @Test
        @DisplayName("正常系: EVENT_CREATEDでイベント作成テキストが返却される")
        void generate_EVENT_CREATED_イベント作成() {
            assertThat(activitySummaryGenerator.generate(ActivityType.EVENT_CREATED))
                    .isEqualTo("新しいイベントを作成しました");
        }

        @Test
        @DisplayName("正常系: MEMBER_JOINEDで参加テキストが返却される")
        void generate_MEMBER_JOINED_参加() {
            assertThat(activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED))
                    .isEqualTo("メンバーとして参加しました");
        }

        @Test
        @DisplayName("正常系: TODO_COMPLETEDで完了テキストが返却される")
        void generate_TODO_COMPLETED_完了() {
            assertThat(activitySummaryGenerator.generate(ActivityType.TODO_COMPLETED))
                    .isEqualTo("TODOを完了しました");
        }

        @Test
        @DisplayName("正常系: BULLETIN_CREATEDでスレッド作成テキストが返却される")
        void generate_BULLETIN_CREATED_スレッド作成() {
            assertThat(activitySummaryGenerator.generate(ActivityType.BULLETIN_CREATED))
                    .isEqualTo("新しいスレッドを作成しました");
        }

        @Test
        @DisplayName("正常系: POLL_CREATEDでアンケート作成テキストが返却される")
        void generate_POLL_CREATED_アンケート作成() {
            assertThat(activitySummaryGenerator.generate(ActivityType.POLL_CREATED))
                    .isEqualTo("新しいアンケートを作成しました");
        }

        @Test
        @DisplayName("正常系: FILE_UPLOADEDでアップロードテキストが返却される")
        void generate_FILE_UPLOADED_アップロード() {
            assertThat(activitySummaryGenerator.generate(ActivityType.FILE_UPLOADED))
                    .isEqualTo("ファイルをアップロードしました");
        }
    }
}
