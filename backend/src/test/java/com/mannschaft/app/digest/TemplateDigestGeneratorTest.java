package com.mannschaft.app.digest;

import com.mannschaft.app.digest.service.TemplateDigestGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateDigestGenerator 単体テスト")
class TemplateDigestGeneratorTest {

    @InjectMocks
    private TemplateDigestGenerator generator;

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("正常系: 投稿ありでダイジェストが生成される")
        void 生成_正常_ダイジェスト生成() {
            List<Map<String, Object>> posts = List.of(
                    Map.of("id", 1L, "content", "投稿内容1", "authorName", "ユーザーA",
                            "reactionCount", 5, "replyCount", 2),
                    Map.of("id", 2L, "content", "投稿内容2", "authorName", "ユーザーB",
                            "reactionCount", 3, "replyCount", 1)
            );

            TemplateDigestGenerator.TemplateResult result = generator.generate(
                    "テストチーム",
                    LocalDateTime.of(2026, 3, 1, 0, 0),
                    LocalDateTime.of(2026, 3, 7, 23, 59),
                    posts
            );

            assertThat(result.title()).contains("テストチーム");
            assertThat(result.body()).contains("2件");
            assertThat(result.excerpt()).contains("2件");
        }

        @Test
        @DisplayName("正常系: 空投稿リストでもダイジェストが生成される")
        void 生成_空リスト_ダイジェスト生成() {
            TemplateDigestGenerator.TemplateResult result = generator.generate(
                    "テストチーム",
                    LocalDateTime.now().minusDays(7),
                    LocalDateTime.now(),
                    List.of()
            );

            assertThat(result.title()).contains("テストチーム");
            assertThat(result.body()).contains("0件");
        }
    }
}
