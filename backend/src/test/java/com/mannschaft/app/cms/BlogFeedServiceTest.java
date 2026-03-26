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
        return new BlogPostResponse(
                null, teamId, null, null, null, title, slug, null, null, null,
                null, null, null, null, LocalDateTime.now(), null, null, null,
                null, null, null, null, null, null, null);
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
    }
}
