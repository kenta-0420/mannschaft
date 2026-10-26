package com.mannschaft.app.notification.dto;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 通知スヌーズリクエストDTO。
 *
 * <p><b>TZ 根治</b>: フロントは {@code .toISOString()}（UTC・{@code Z} 付き）で送るため、
 * {@code LocalDateTime} だと Jackson がオフセットを捨て、JST 固定 JVM の壁時計とずれる（約 9 時間）。
 * そのため絶対時刻を保持できる {@link OffsetDateTime} で受け、Service 層で JST 壁時計へ変換して保存する。</p>
 */
@Getter
@RequiredArgsConstructor
public class SnoozeRequest {

    /** スヌーズ解除日時（絶対時刻・ISO8601・オフセット必須／未来必須） */
    @NotNull
    @Future
    private final OffsetDateTime snoozedUntil;
}
