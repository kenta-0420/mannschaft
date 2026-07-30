package com.mannschaft.app.common.timezone;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Optional;

/**
 * ユーザーの timezone を各リクエストの {@link TimezoneContextHolder} にセットするフィルター。
 *
 * <p>実行順序:</p>
 * <p>Spring Security の DelegatingFilterProxy（@Order = -100）より後に実行されるため、
 * JwtAuthenticationFilter が SecurityContextHolder にセット済みの状態で動作する。
 * {@link com.mannschaft.app.common.i18n.UserLocaleFilter}（LOWEST_PRECEDENCE - 10）と同じ優先度帯で、
 * ロケールフィルターの直後（LOWEST_PRECEDENCE - 9）に実行させる。</p>
 *
 * <p>timezone 決定ロジック:</p>
 * <ol>
 *   <li>ログイン済み（SecurityContext に Authentication あり）
 *       → {@link UserTimezoneCache#getTimezone(Long)} でキャッシュ参照（TTL 5分）
 *       → 不正な timezone 文字列は catch して "Asia/Tokyo" にフォールバック</li>
 *   <li>未ログイン、またはキャッシュ未利用時（@WebMvcTest スライス等）→ UTC</li>
 * </ol>
 *
 * <h2>「解決済み」の印を付ける条件（Issue #2508）</h2>
 *
 * <p>{@link TimezoneContextHolder#setResolved(ZoneId)}（＝解決済みの印あり）を使うのは
 * <b>{@code users.timezone} を実際に引き当てられた場合に限る</b>。
 * 印の無い {@link TimezoneContextHolder#set(ZoneId)} を使う経路は「ユーザー由来の TZ が不明」を意味し、
 * {@link com.mannschaft.app.config.jackson.LocalDateTimeTimezoneDeserializer} は
 * オフセット無し入力をサーバー既定 TZ（Asia/Tokyo）の壁時計として解釈する（＝旧挙動と一致）。</p>
 *
 * <table border="1">
 *   <caption>経路ごとの扱い</caption>
 *   <tr><th>経路</th><th>積む ZoneId</th><th>解決済みの印</th><th>理由</th></tr>
 *   <tr>
 *     <td>認証済み ＋ キャッシュから正常な TZ を取得</td><td>その TZ</td><td><b>付ける</b></td>
 *     <td>{@code users.timezone} 由来の確かな値</td>
 *   </tr>
 *   <tr>
 *     <td>認証済み ＋ TZ が null/空/不正文字列</td><td>Asia/Tokyo</td><td><b>付ける</b></td>
 *     <td>ユーザー行には到達できている。{@code users.timezone} は {@code NOT NULL DEFAULT 'Asia/Tokyo'}
 *         なので Asia/Tokyo フォールバックは DB 既定値と一致し、未解決時の解釈（Asia/Tokyo）とも一致するため
 *         印の有無でデシリアライズ結果は変わらない。ここで印を落として UTC を積むと
 *         <b>シリアライザの出力が +09:00 から Z へ変わってしまう</b>ため、既存挙動を保つ側に寄せる</td>
 *   </tr>
 *   <tr>
 *     <td>未ログイン / 匿名 / principal が userId でない</td><td>UTC</td><td>付けない</td>
 *     <td>ユーザーが特定できず TZ は不明。出力は従来どおり UTC</td>
 *   </tr>
 *   <tr>
 *     <td>{@code userTimezoneCache} が null（@WebMvcTest スライス等）</td><td>UTC</td><td>付けない</td>
 *     <td>参照先が無く TZ を解決できない。javadoc どおり UTC に落とす
 *         （従来は null ガードが無く NPE になり得たのを塞いだ）</td>
 *   </tr>
 * </table>
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 9)
public class UserTimezoneFilter extends OncePerRequestFilter {

    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    /**
     * ユーザー TZ を解決できなかった場合に印なしで積む ZoneId。
     * {@link TimezoneContextHolder#get()} の未セット時の戻り値と同じ UTC で、既存の出力挙動を変えない。
     */
    private static final ZoneId UNRESOLVED_ZONE = ZoneId.of("UTC");

    /** @WebMvcTest スライスではキャッシュ Bean が存在しないため required = false で注入する */
    @Autowired(required = false)
    private UserTimezoneCache userTimezoneCache;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 解決できた場合のみ「解決済みの印」を付けて積む（クラス javadoc の表を参照）
            resolveUserZone().ifPresentOrElse(
                    TimezoneContextHolder::setResolved,
                    () -> TimezoneContextHolder.set(UNRESOLVED_ZONE));
            filterChain.doFilter(request, response);
        } finally {
            // スレッドプール汚染防止: 必ず finally でクリアする（解決済みの印も一緒に落ちる）
            TimezoneContextHolder.clear();
        }
    }

    /**
     * {@code users.timezone} からユーザーの ZoneId を解決する。
     *
     * @return 解決できた場合はその ZoneId（不正値は Asia/Tokyo にフォールバックした値）。
     *         ユーザーを特定できない・キャッシュ Bean が無いなど<b>解決できない場合は空</b>
     */
    private Optional<ZoneId> resolveUserZone() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        // ログイン済みの場合は DB（キャッシュ経由）から timezone を取得
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof String principal) {
            // @WebMvcTest スライス等ではキャッシュ Bean が存在しない。
            // 参照先が無い＝TZ を解決できないため「未解決」として扱う（NPE を出さない）
            if (userTimezoneCache == null) {
                log.debug("userTimezoneCache が未注入のため timezone を解決できない（未解決として扱う）");
                return Optional.empty();
            }
            try {
                Long userId = Long.parseLong(principal);
                String timezoneStr = userTimezoneCache.getTimezone(userId);
                return Optional.of(parseZoneId(timezoneStr));
            } catch (NumberFormatException e) {
                log.debug("userId のパース失敗: {}", auth.getPrincipal());
            }
        }

        // 未ログイン等: ユーザーを特定できないため未解決。
        // TimezoneContextHolder.get() が UTC を返すのと同じ値（UNRESOLVED_ZONE）を印なしで積む
        return Optional.empty();
    }

    /**
     * タイムゾーン文字列を ZoneId に変換する。
     * 不正な文字列の場合は Asia/Tokyo にフォールバックする。
     *
     * @param timezoneStr タイムゾーン文字列（例: "Asia/Tokyo"）
     * @return ZoneId
     */
    private ZoneId parseZoneId(String timezoneStr) {
        if (timezoneStr == null || timezoneStr.isBlank()) {
            return SERVER_ZONE;
        }
        try {
            return ZoneId.of(timezoneStr);
        } catch (Exception e) {
            log.warn("不正なタイムゾーン文字列: {} → Asia/Tokyo にフォールバック", timezoneStr);
            return SERVER_ZONE;
        }
    }
}
