package com.mannschaft.app.common.timezone;

import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * リクエスト単位のユーザータイムゾーンを ThreadLocal で保持するホルダー。
 *
 * <p>Virtual Threads 環境では InheritableThreadLocal を使用しないこと。
 * 通常の ThreadLocal のみ使用する（{@link UserLocaleFilter} と同様の方針）。</p>
 *
 * <p>利用箇所:</p>
 * <ul>
 *   <li>{@link UserTimezoneFilter} — リクエスト開始時にユーザーの ZoneId をセット・終了時にクリア</li>
 *   <li>{@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneSerializer} —
 *       シリアライズ時にここから ZoneId を取得して LocalDateTime を変換</li>
 * </ul>
 */
public final class TimezoneContextHolder {

    /**
     * inheritable=false 固定。Virtual Threads 環境で InheritableThreadLocal への
     * 伝搬を防ぐため通常の ThreadLocal を使用する。
     */
    private static final ThreadLocal<ZoneId> ZONE = new ThreadLocal<>();

    private TimezoneContextHolder() {}

    /**
     * 現在スレッドのユーザータイムゾーンをセットする。
     *
     * @param zoneId ユーザーの ZoneId（null 禁止）
     */
    public static void set(ZoneId zoneId) {
        ZONE.set(zoneId);
    }

    /**
     * 現在スレッドのユーザータイムゾーンを返す。
     * セットされていない場合は UTC を返す。
     *
     * @return ユーザーの ZoneId（未セット時は {@link ZoneOffset#UTC}）
     */
    public static ZoneId get() {
        ZoneId z = ZONE.get();
        return z != null ? z : ZoneOffset.UTC;
    }

    /**
     * 現在スレッドのユーザータイムゾーンをクリアする。
     * スレッドプール汚染防止のため、リクエスト終了時に必ず呼び出すこと。
     */
    public static void clear() {
        ZONE.remove();
    }
}
