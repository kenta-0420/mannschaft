package com.mannschaft.app.notification.confirmable.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConfirmableNotificationCreateRequest#getDeadlineAtAsJst()} 単体テスト。
 *
 * <p>フロントエンドから任意のオフセット付き日時が来た場合に、
 * JST の LocalDateTime に正しく変換されることを検証する。</p>
 */
@DisplayName("ConfirmableNotificationCreateRequest.getDeadlineAtAsJst() テスト")
class ConfirmableNotificationCreateRequestTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /**
     * テスト用にフィールドを直接セットするヘルパー（リフレクション使用）。
     */
    private ConfirmableNotificationCreateRequest buildWithDeadline(OffsetDateTime deadlineAt) {
        try {
            ConfirmableNotificationCreateRequest req = new ConfirmableNotificationCreateRequest();
            java.lang.reflect.Field f = ConfirmableNotificationCreateRequest.class.getDeclaredField("deadlineAt");
            f.setAccessible(true);
            f.set(req, deadlineAt);
            return req;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("deadlineAtがnullの場合_getDeadlineAtAsJstはnullを返す")
    void deadlineAtがNull_nullを返す() {
        ConfirmableNotificationCreateRequest req = buildWithDeadline(null);
        assertThat(req.getDeadlineAtAsJst()).isNull();
    }

    @Test
    @DisplayName("JSTオフセット付き日時_そのままJST LocalDateTimeに変換される")
    void jstOffsetDateTime_jstLocalDateTimeに変換() {
        // 2026-06-10 15:00:00 +09:00
        OffsetDateTime input = OffsetDateTime.of(2026, 6, 10, 15, 0, 0, 0, ZoneOffset.ofHours(9));
        ConfirmableNotificationCreateRequest req = buildWithDeadline(input);

        LocalDateTime result = req.getDeadlineAtAsJst();

        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 10, 15, 0, 0));
    }

    @Test
    @DisplayName("UTCオフセット付き日時_JST（UTC+9）に変換される")
    void utcOffsetDateTime_jstに変換() {
        // UTC 2026-06-10 06:00:00 = JST 2026-06-10 15:00:00
        OffsetDateTime input = OffsetDateTime.of(2026, 6, 10, 6, 0, 0, 0, ZoneOffset.UTC);
        ConfirmableNotificationCreateRequest req = buildWithDeadline(input);

        LocalDateTime result = req.getDeadlineAtAsJst();

        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 10, 15, 0, 0));
    }

    @Test
    @DisplayName("UTC-5オフセット付き日時_JST（UTC+9）に変換される_日付が進む")
    void utcMinus5OffsetDateTime_jstに変換_翌日() {
        // UTC-5: 2026-06-10 23:00:00 -05:00 → UTC 2026-06-11 04:00:00 → JST 2026-06-11 13:00:00
        OffsetDateTime input = OffsetDateTime.of(2026, 6, 10, 23, 0, 0, 0, ZoneOffset.ofHours(-5));
        ConfirmableNotificationCreateRequest req = buildWithDeadline(input);

        LocalDateTime result = req.getDeadlineAtAsJst();

        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 11, 13, 0, 0));
    }

    @Test
    @DisplayName("JST日付変わり目付近_UTC+9_00:00_前日23:00UTCからの変換")
    void jstMidnight_UTC前日から変換() {
        // UTC 2026-06-09 15:00:00 = JST 2026-06-10 00:00:00
        OffsetDateTime input = OffsetDateTime.of(2026, 6, 9, 15, 0, 0, 0, ZoneOffset.UTC);
        ConfirmableNotificationCreateRequest req = buildWithDeadline(input);

        LocalDateTime result = req.getDeadlineAtAsJst();

        assertThat(result).isEqualTo(LocalDateTime.of(2026, 6, 10, 0, 0, 0));
    }
}
