package com.mannschaft.app.timeline.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineFeedResponseTest {

    @Test
    void of_marksExactLimitAsLastPage() {
        TimelineFeedResponse response = TimelineFeedResponse.of(
                List.of(), List.of(reply(3L), reply(2L)), 2);

        assertThat(response.getData().getPosts()).extracting(PostResponse::getId)
                .containsExactly(3L, 2L);
        assertThat(response.getMeta().isHasNext()).isFalse();
        assertThat(response.getMeta().getNextCursor()).isNull();
    }

    @Test
    void of_trimsExtraRowAndUsesLastReturnedPostAsCursor() {
        TimelineFeedResponse response = TimelineFeedResponse.of(
                List.of(), List.of(reply(3L), reply(2L), reply(1L)), 2);

        assertThat(response.getData().getPosts()).extracting(PostResponse::getId)
                .containsExactly(3L, 2L);
        assertThat(response.getMeta().isHasNext()).isTrue();
        assertThat(response.getMeta().getNextCursor()).isEqualTo(2L);
    }

    @Test
    void ofReplies_marksExactLimitAsLastPage() {
        TimelineFeedResponse response = TimelineFeedResponse.ofReplies(
                List.of(reply(1L), reply(2L)), 2);

        assertThat(response.getData().getPosts()).extracting(PostResponse::getId)
                .containsExactly(1L, 2L);
        assertThat(response.getMeta().isHasNext()).isFalse();
        assertThat(response.getMeta().getNextCursor()).isNull();
    }

    @Test
    void ofReplies_trimsExtraRowAndUsesLastReturnedReplyAsCursor() {
        TimelineFeedResponse response = TimelineFeedResponse.ofReplies(
                List.of(reply(1L), reply(2L), reply(3L)), 2);

        assertThat(response.getData().getPosts()).extracting(PostResponse::getId)
                .containsExactly(1L, 2L);
        assertThat(response.getMeta().isHasNext()).isTrue();
        assertThat(response.getMeta().getNextCursor()).isEqualTo(2L);
    }

    private PostResponse reply(Long id) {
        return PostResponse.builder().id(id).build();
    }
}
