package com.mannschaft.app.line.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.line.dto.SnsFeedPreviewResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

/**
 * SNS フィード取得 API クライアント。Instagram Graph API を使用する。
 */
@Slf4j
@Component
public class SnsFeedApiClient {

    private static final String INSTAGRAM_GRAPH_API = "https://graph.instagram.com/me/media";

    private final RestClient restClient;

    public SnsFeedApiClient() {
        this.restClient = RestClient.create();
    }

    /**
     * Instagram Graph API からフィードを取得する。
     *
     * @param accessToken Instagram アクセストークン
     * @param limit       取得件数
     * @return フィードアイテムリスト
     */
    public List<SnsFeedPreviewResponse.FeedItem> fetchInstagramFeed(String accessToken, int limit) {
        try {
            InstagramMediaResponse response = restClient.get()
                    .uri(INSTAGRAM_GRAPH_API + "?fields=id,caption,media_url,permalink,timestamp&limit={limit}&access_token={token}",
                            limit, accessToken)
                    .retrieve()
                    .body(InstagramMediaResponse.class);

            if (response == null || response.getData() == null) {
                return Collections.emptyList();
            }

            return response.getData().stream()
                    .map(item -> new SnsFeedPreviewResponse.FeedItem(
                            item.getId(),
                            item.getMediaUrl(),
                            item.getCaption(),
                            item.getPermalink(),
                            parseTimestamp(item.getTimestamp())))
                    .toList();
        } catch (Exception e) {
            log.warn("Instagram フィード取得失敗", e);
            return Collections.emptyList();
        }
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        if (timestamp == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(timestamp).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class InstagramMediaResponse {
        private List<InstagramMediaItem> data;
    }

    @Getter
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class InstagramMediaItem {
        private String id;
        private String caption;
        @JsonProperty("media_url")
        private String mediaUrl;
        private String permalink;
        private String timestamp;
    }
}
