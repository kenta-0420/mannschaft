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
 * {@link ActivitySummaryGenerator} の追加単体テスト。
 * 全ActivityTypeのカバレッジ向上とエッジケースを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivitySummaryGenerator 追加テスト")
class ActivitySummaryGeneratorAdditionalTest {

    @InjectMocks
    private ActivitySummaryGenerator activitySummaryGenerator;

    // ========================================
    // generate - 追加テスト
    // ========================================

    @Nested
    @DisplayName("generate 追加テスト")
    class GenerateAdditional {

        @Test
        @DisplayName("正常系: 全ActivityTypeでnullでなく文字列が返る")
        void generate_全ActivityType_文字列が返る() {
            // When / Then
            for (ActivityType type : ActivityType.values()) {
                String result = activitySummaryGenerator.generate(type);
                assertThat(result)
                        .as("ActivityType %s should return non-null string", type)
                        .isNotNull()
                        .isNotBlank();
            }
        }

        @Test
        @DisplayName("正常系: 同一タイプを複数回呼び出しても同じ結果が返る（冪等性）")
        void generate_同一タイプ複数回_冪等性() {
            // When
            String result1 = activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED);
            String result2 = activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED);
            String result3 = activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED);

            // Then
            assertThat(result1).isEqualTo(result2).isEqualTo(result3);
        }

        @Test
        @DisplayName("正常系: 各ActivityTypeは異なるサマリーテキストを持つ")
        void generate_異なるActivityType_異なるテキスト() {
            // POST_CREATEDとEVENT_CREATEDは異なるテキスト
            assertThat(activitySummaryGenerator.generate(ActivityType.POST_CREATED))
                    .isNotEqualTo(activitySummaryGenerator.generate(ActivityType.EVENT_CREATED));

            // TODO_COMPLETEDとMEMBER_JOINEDは異なるテキスト
            assertThat(activitySummaryGenerator.generate(ActivityType.TODO_COMPLETED))
                    .isNotEqualTo(activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED));
        }

        @Test
        @DisplayName("正常系: FILE_UPLOADEDのサマリーはアップロードを示す")
        void generate_FILE_UPLOADED_アップロード内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.FILE_UPLOADED);

            // Then
            assertThat(result).contains("アップロード");
        }

        @Test
        @DisplayName("正常系: BULLETIN_CREATEDのサマリーはスレッド作成を示す")
        void generate_BULLETIN_CREATED_スレッド内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.BULLETIN_CREATED);

            // Then
            assertThat(result).contains("スレッド");
        }

        @Test
        @DisplayName("正常系: POLL_CREATEDのサマリーはアンケートを示す")
        void generate_POLL_CREATED_アンケート内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.POLL_CREATED);

            // Then
            assertThat(result).contains("アンケート");
        }

        @Test
        @DisplayName("正常系: TODO_COMPLETEDのサマリーはTODOを示す")
        void generate_TODO_COMPLETED_TODO内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.TODO_COMPLETED);

            // Then
            assertThat(result).contains("TODO");
        }

        @Test
        @DisplayName("正常系: MEMBER_JOINEDのサマリーは参加を示す")
        void generate_MEMBER_JOINED_参加内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.MEMBER_JOINED);

            // Then
            assertThat(result).contains("参加");
        }

        @Test
        @DisplayName("正常系: POST_CREATEDのサマリーは投稿を示す")
        void generate_POST_CREATED_投稿内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.POST_CREATED);

            // Then
            assertThat(result).contains("投稿");
        }

        @Test
        @DisplayName("正常系: EVENT_CREATEDのサマリーはイベントを示す")
        void generate_EVENT_CREATED_イベント内容含む() {
            // When
            String result = activitySummaryGenerator.generate(ActivityType.EVENT_CREATED);

            // Then
            assertThat(result).contains("イベント");
        }
    }
}
