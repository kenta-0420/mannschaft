package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.service.BlogFeedService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlogFeedService 単体テスト")
class BlogFeedServiceTest {

    @InjectMocks
    private BlogFeedService service;

    private BlogPostResponse createPost(String title, String slug, Long teamId) {
        return BlogPostResponse.builder()
                .scope(new BlogPostResponse.BlogPostScopeDto(teamId, null, null, null))
                .content(new BlogPostResponse.BlogPostContentDto(title, slug, null, null, null))
                .audit(new BlogPostResponse.BlogPostAuditDto(LocalDateTime.now(), null, null, null))
                .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                .build();
    }

    @Nested
    @DisplayName("generateFeedXml")
    class GenerateFeedXml {

        @Test
        @DisplayName("正常系: RSS形式のフィードXMLが生成される")
        void RSS形式_正常_XML生成() {
            List<BlogPostResponse> posts = List.of(createPost("記事1", "article-1", 1L));
            String xml = service.generateFeedXml(posts, "rss", 1L, null);
            assertThat(xml).contains("rss");
            assertThat(xml).contains("記事1");
        }

        @Test
        @DisplayName("正常系: Atom形式のフィードXMLが生成される")
        void Atom形式_正常_XML生成() {
            List<BlogPostResponse> posts = List.of(createPost("記事2", "article-2", null));
            String xml = service.generateFeedXml(posts, "atom", null, 1L);
            assertThat(xml).containsIgnoringCase("atom");
        }

        @Test
        @DisplayName("正常系: 空のリストでもXMLが生成される")
        void 空リスト_正常_XML生成() {
            String xml = service.generateFeedXml(List.of(), "rss", 1L, null);
            assertThat(xml).isNotBlank();
        }

        @Test
        @DisplayName("AC-14: 有料本文（body）はフィードXMLに含めない（title/excerpt/slug のみ出力）")
        void AC14_本文はフィードに含めない() {
            // body に本文があっても、generateFeedXml は title/excerpt/slug/tags/audit のみを出力する。
            BlogPostResponse post = BlogPostResponse.builder()
                    .scope(new BlogPostResponse.BlogPostScopeDto(1L, null, null, null))
                    .content(new BlogPostResponse.BlogPostContentDto(
                            "記事タイトル", "article-1", "秘密の有料本文フルテキスト", "公開抜粋", null))
                    .audit(new BlogPostResponse.BlogPostAuditDto(LocalDateTime.now(), null, null, null))
                    .stats(new BlogPostResponse.BlogPostStatisticsDto(null, null, false, 0))
                    .build();

            String xml = service.generateFeedXml(List.of(post), "rss", 1L, null);

            assertThat(xml).contains("記事タイトル");
            assertThat(xml).contains("公開抜粋");
            // 有料本文はフィードに漏れない
            assertThat(xml).doesNotContain("秘密の有料本文フルテキスト");
        }
    }
}
