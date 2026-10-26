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
 *   <li>{@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer} —
 *       デシリアライズ時に {@link #isResolved()} で「本当にユーザー由来の TZ か」を判定して解釈を切り替える</li>
 * </ul>
 *
 * <h2>「セット済み」と「解決済み」の区別（Issue #2508）</h2>
 *
 * <p>{@link #get()} は未セット時に UTC を返し、{@link UserTimezoneFilter} も未認証時に UTC を
 * 明示セットする。そのため <b>{@code get()} の戻り値だけでは
 * 「{@code users.timezone} から解決された値」と「未認証・バッチの既定 UTC」を区別できない</b>。</p>
 *
 * <p>この区別が無いまま「オフセット無し入力を {@code get()} の TZ の壁時計として解釈する」
 * デシリアライザを足すと、フィルターを通らないバッチスレッド（{@code get()} は UTC）の入力が
 * UTC 壁時計として解釈され、既定値がそのまま −9 時間のデータ破壊に化ける。
 * これを防ぐため、{@link #setResolved(ZoneId)} で積んだ場合のみ {@link #isResolved()} が
 * {@code true} を返す「解決済みの印」を別途保持する。</p>
 *
 * <p>{@link #get()} の意味は従来どおり（未セット＝UTC）で変更していない。
 * よって {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneSerializer} は無改修であり、
 * 出力側の挙動は一切変わらない（影響半径を絞るため）。</p>
 */
public final class TimezoneContextHolder {

    /**
     * inheritable=false 固定。Virtual Threads 環境で InheritableThreadLocal への
     * 伝搬を防ぐため通常の ThreadLocal を使用する。
     */
    private static final ThreadLocal<ZoneId> ZONE = new ThreadLocal<>();

    /**
     * ZONE の値が「{@code users.timezone} から解決された値」であることの印。
     * ZONE と同様に inheritable=false 固定（Virtual Threads 対策）。
     */
    private static final ThreadLocal<Boolean> RESOLVED = new ThreadLocal<>();

    private TimezoneContextHolder() {}

    /**
     * 現在スレッドのユーザータイムゾーンを、<b>解決済みの印を付けずに</b>セットする。
     *
     * <p>「表示のためにこの TZ を使う」だけを意味し、{@link #isResolved()} は {@code false} のままになる。
     * 未認証リクエストの既定 UTC のように、<b>ユーザー由来ではない値</b>を積むときに使う。
     * 既に印が付いていた場合は落とす（同一スレッドで印だけが残る取り違えを防ぐ）。</p>
     *
     * @param zoneId ユーザーの ZoneId（null 禁止）
     */
    public static void set(ZoneId zoneId) {
        ZONE.set(zoneId);
        RESOLVED.remove();
    }

    /**
     * 現在スレッドのユーザータイムゾーンを、<b>解決済みの印を付けて</b>セットする。
     *
     * <p>{@code users.timezone} を実際に引き当てられた認証済みリクエストでのみ呼ぶこと。
     * これを呼んだ場合のみ {@link #isResolved()} が {@code true} を返し、
     * {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer} が
     * オフセット無し入力を「この TZ の壁時計」として解釈する。</p>
     *
     * @param zoneId ユーザーの ZoneId（null 禁止）
     */
    public static void setResolved(ZoneId zoneId) {
        ZONE.set(zoneId);
        RESOLVED.set(Boolean.TRUE);
    }

    /**
     * 現在スレッドのユーザータイムゾーンを返す。
     * セットされていない場合は UTC を返す。
     *
     * <p><b>この戻り値だけで「ユーザー由来か」を判断してはならない</b>
     * （未認証・バッチの既定 UTC と区別できない）。判断が必要なら {@link #isResolved()} を使うこと。</p>
     *
     * @return ユーザーの ZoneId（未セット時は {@link ZoneOffset#UTC}）
     */
    public static ZoneId get() {
        ZoneId z = ZONE.get();
        return z != null ? z : ZoneOffset.UTC;
    }

    /**
     * 現在スレッドの ZoneId が {@code users.timezone} から解決された値かどうかを返す。
     *
     * @return {@link #setResolved(ZoneId)} で積まれている場合のみ {@code true}。
     *         未セット・{@link #set(ZoneId)} で積まれた既定値・{@link #clear()} 済みはいずれも {@code false}
     */
    public static boolean isResolved() {
        return Boolean.TRUE.equals(RESOLVED.get());
    }

    /**
     * 現在スレッドのユーザータイムゾーンと解決済みの印をクリアする。
     * スレッドプール汚染防止のため、リクエスト終了時に必ず呼び出すこと。
     */
    public static void clear() {
        ZONE.remove();
        RESOLVED.remove();
    }
}
