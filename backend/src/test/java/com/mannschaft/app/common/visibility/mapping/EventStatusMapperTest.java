package com.mannschaft.app.common.visibility.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.event.EventStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link EventStatusMapper} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5
 * 「各機能 status の正規化」表。
 */
@DisplayName("EventStatusMapper")
class EventStatusMapperTest {

    @ParameterizedTest
    @EnumSource(EventStatus.class)
    @DisplayName("全 enum 値が non-null な ContentStatus にマップされる（exhaustive）")
    void every_value_maps_to_non_null(EventStatus s) {
        assertThat(EventStatusMapper.toStandard(s)).isNotNull();
    }

    @Test
    @DisplayName("DRAFT → ContentStatus.DRAFT")
    void draft_maps_to_DRAFT() {
        assertThat(EventStatusMapper.toStandard(EventStatus.DRAFT))
                .isEqualTo(ContentStatus.DRAFT);
    }

    @Test
    @DisplayName("PUBLISHED → ContentStatus.PUBLISHED")
    void published_maps_to_PUBLISHED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.PUBLISHED))
                .isEqualTo(ContentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("REGISTRATION_OPEN → ContentStatus.PUBLISHED")
    void registration_open_maps_to_PUBLISHED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.REGISTRATION_OPEN))
                .isEqualTo(ContentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("REGISTRATION_CLOSED → ContentStatus.PUBLISHED")
    void registration_closed_maps_to_PUBLISHED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.REGISTRATION_CLOSED))
                .isEqualTo(ContentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("IN_PROGRESS → ContentStatus.PUBLISHED")
    void in_progress_maps_to_PUBLISHED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.IN_PROGRESS))
                .isEqualTo(ContentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("COMPLETED → ContentStatus.PUBLISHED（完了済イベントも閲覧可能）")
    void completed_maps_to_PUBLISHED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.COMPLETED))
                .isEqualTo(ContentStatus.PUBLISHED);
    }

    @Test
    @DisplayName("CANCELLED → ContentStatus.ARCHIVED（SystemAdmin のみ可視）")
    void cancelled_maps_to_ARCHIVED() {
        assertThat(EventStatusMapper.toStandard(EventStatus.CANCELLED))
                .isEqualTo(ContentStatus.ARCHIVED);
    }
}
