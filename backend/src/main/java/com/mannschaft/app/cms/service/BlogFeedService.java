package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndContentImpl;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndEntryImpl;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndFeedImpl;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedOutput;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * ブログ RSS/Atom フィード生成サービス。
 * ROME ライブラリを使用して RSS 2.0 / Atom 1.0 の XML を生成する。
 */
@Slf4j
@Service
public class BlogFeedService {

    private static final String BASE_URL = "https://mannschaft.app";
    private static final String FEED_TITLE = "Mannschaft Blog";
    private static final String FEED_DESCRIPTION = "Mannschaft ブログの最新記事";

    /**
     * RSS/Atom フィード XML を生成する。
     *
     * @param posts    公開記事一覧
     * @param format   "rss" または "atom"
     * @param teamId   チームID（任意）
     * @param orgId    組織ID（任意）
     * @return XML 文字列
     */
    public String generateFeedXml(List<BlogPostResponse> posts, String format,
                                   Long teamId, Long orgId) {
        SyndFeed feed = new SyndFeedImpl();

        if ("atom".equalsIgnoreCase(format)) {
            feed.setFeedType("atom_1.0");
        } else {
            feed.setFeedType("rss_2.0");
        }

        feed.setTitle(FEED_TITLE);
        feed.setDescription(FEED_DESCRIPTION);
        feed.setLink(buildFeedLink(teamId, orgId));
        feed.setLanguage("ja");

        if (!posts.isEmpty()) {
            LocalDateTime latest = posts.stream()
                    .map(BlogPostResponse::getPublishedAt)
                    .filter(p -> p != null)
                    .max(LocalDateTime::compareTo)
                    .orElse(LocalDateTime.now());
            feed.setPublishedDate(toDate(latest));
        }

        List<SyndEntry> entries = posts.stream()
                .map(this::toSyndEntry)
                .toList();
        feed.setEntries(entries);

        try {
            SyndFeedOutput output = new SyndFeedOutput();
            return output.outputString(feed);
        } catch (FeedException e) {
            log.error("フィード XML 生成失敗", e);
            throw new RuntimeException("フィード生成に失敗しました", e);
        }
    }

    private SyndEntry toSyndEntry(BlogPostResponse post) {
        SyndEntry entry = new SyndEntryImpl();
        entry.setTitle(post.getTitle());
        entry.setLink(buildPostLink(post));
        entry.setUri(buildPostLink(post));

        if (post.getPublishedAt() != null) {
            entry.setPublishedDate(toDate(post.getPublishedAt()));
            entry.setUpdatedDate(toDate(
                    post.getUpdatedAt() != null ? post.getUpdatedAt() : post.getPublishedAt()));
        }

        if (post.getExcerpt() != null) {
            SyndContent description = new SyndContentImpl();
            description.setType("text/html");
            description.setValue(post.getExcerpt());
            entry.setDescription(description);
        }

        if (post.getTags() != null && !post.getTags().isEmpty()) {
            entry.setCategories(post.getTags().stream()
                    .map(tag -> {
                        com.rometools.rome.feed.synd.SyndCategoryImpl cat =
                                new com.rometools.rome.feed.synd.SyndCategoryImpl();
                        cat.setName(tag.getName());
                        return (com.rometools.rome.feed.synd.SyndCategory) cat;
                    })
                    .toList());
        }

        return entry;
    }

    private String buildFeedLink(Long teamId, Long orgId) {
        if (teamId != null) {
            return BASE_URL + "/teams/" + teamId + "/blog/feed";
        }
        if (orgId != null) {
            return BASE_URL + "/organizations/" + orgId + "/blog/feed";
        }
        return BASE_URL + "/blog/feed";
    }

    private String buildPostLink(BlogPostResponse post) {
        if (post.getTeamId() != null) {
            return BASE_URL + "/teams/" + post.getTeamId() + "/blog/" + post.getSlug();
        }
        if (post.getOrganizationId() != null) {
            return BASE_URL + "/organizations/" + post.getOrganizationId() + "/blog/" + post.getSlug();
        }
        return BASE_URL + "/blog/" + post.getSlug();
    }

    private Date toDate(LocalDateTime ldt) {
        return Date.from(ldt.atZone(ZoneId.of("Asia/Tokyo")).toInstant());
    }
}
