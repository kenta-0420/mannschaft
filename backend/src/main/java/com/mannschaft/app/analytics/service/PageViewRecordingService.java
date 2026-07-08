package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.event.PageViewRecordedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * ページビュー計測ビーコンの受付サービス（F10.8 アクセス解析）。
 *
 * <p>計測ビーコン {@code POST /api/v1/page-views} の同期経路を担う。DB 書き込みは行わず、
 * (a) 匿名 cookie {@code mnsft_vid} の解決補助、(b) {@link PageViewRecordedEvent} の publish のみを行い、
 * Controller は即 {@code 202 Accepted} を返せる（生ログ INSERT は
 * {@link com.mannschaft.app.analytics.event.PageViewRecordListener} が非同期実行・設計書 §5.1）。</p>
 *
 * <h2>匿名 cookie {@code mnsft_vid}（設計書 §7.2）</h2>
 * <ul>
 *   <li>ランダム UUID・個人を特定しない・IP は保存しない（GDPR 配慮）</li>
 *   <li>{@code HttpOnly=true}（SPA が JS で読む必要は無い）</li>
 *   <li>{@code Secure=${mannschaft.cookie.secure}}（認証 cookie と同じ環境変数に揃える）</li>
 *   <li>{@code SameSite=Lax}（計測精度優先・マスター御裁可 2026-07-08）</li>
 *   <li>{@code Max-Age=400 日}・{@code Path=/}・prefix 無し</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PageViewRecordingService {

    /** 匿名訪問者 cookie 名（prefix 無し・既存認証 cookie 規約に揃える）。 */
    public static final String VISITOR_COOKIE_NAME = "mnsft_vid";

    /** cookie の有効期間（秒）。400 日（Chrome の cookie 上限に整合）。 */
    private static final long VISITOR_COOKIE_MAX_AGE_SECONDS = 400L * 24 * 60 * 60;

    /** JST 壁時計で {@code viewedAt} を採る（設計書 §5.5）。 */
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    /** UUID 形式（cookie 詐称・注入を弾く軽量バリデーション）。 */
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final ApplicationEventPublisher eventPublisher;

    @Value("${mannschaft.cookie.secure:false}")
    private boolean cookieSecure;

    /**
     * リクエストの cookie 値から匿名訪問者 ID を解決する。
     *
     * <p>有効な UUID cookie があればそれを再利用し、未発行・不正値なら新規 UUID を採番する。
     * Controller は {@code @CookieValue(name = VISITOR_COOKIE_NAME, required = false)} で受けた値を渡す。</p>
     *
     * @param existingCookieValue 既存 cookie 値（未発行時は {@code null}）
     * @return 解決済みの訪問者 ID（既存 or 新規採番）
     */
    public String resolveVisitorId(String existingCookieValue) {
        if (StringUtils.hasText(existingCookieValue)
                && UUID_PATTERN.matcher(existingCookieValue).matches()) {
            return existingCookieValue;
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 指定した cookie 値が「新規採番された（＝リクエストに無かった）」かを判定する。
     *
     * <p>Controller は本メソッドが {@code true} のときのみ Set-Cookie ヘッダーを付与すればよい
     * （毎回 Set-Cookie を返すと無駄なため。AC-03）。</p>
     *
     * @param existingCookieValue 既存 cookie 値
     * @return 新規採番が必要なら {@code true}
     */
    public boolean isNewVisitor(String existingCookieValue) {
        return !(StringUtils.hasText(existingCookieValue)
                && UUID_PATTERN.matcher(existingCookieValue).matches());
    }

    /**
     * 匿名訪問者 cookie の Set-Cookie ヘッダーを組み立てる（§7.2 の確定属性）。
     *
     * @param visitorId 発行する訪問者 ID
     * @return Set-Cookie に載せる {@link ResponseCookie}
     */
    public ResponseCookie buildVisitorCookie(String visitorId) {
        return ResponseCookie.from(VISITOR_COOKIE_NAME, visitorId)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .sameSite("Lax")
                .maxAge(VISITOR_COOKIE_MAX_AGE_SECONDS)
                .build();
    }

    /**
     * 計測イベントを publish する（DB 書き込みは非同期リスナーが行う）。
     *
     * <p>Controller は body バリデーション（相対パス・ENUM・正の整数）と cookie 解決を済ませたうえで
     * 本メソッドを呼び、直後に {@code 202 Accepted} を返す。{@code viewedAt} は JST 壁時計で採る。</p>
     *
     * @param scopeType   スコープ種別
     * @param scopeId     スコープ ID（数値・slug ではない）
     * @param contentType 閲覧対象種別
     * @param contentId   閲覧対象 ID（ID を持たない種別は 0）
     * @param url         アプリ内相対パス（Controller で検証済み）
     * @param title       表示タイトル（リスナー側で無害化・切詰する）
     * @param userId      ログイン利用者 ID（{@code null} = ゲスト）
     * @param visitorId   解決済み訪問者 ID
     */
    public void record(
            PageViewScopeType scopeType,
            Long scopeId,
            PageViewContentType contentType,
            Long contentId,
            String url,
            String title,
            Long userId,
            String visitorId) {
        PageViewRecordedEvent event = new PageViewRecordedEvent(
                scopeType,
                scopeId,
                contentType,
                contentId != null ? contentId : 0L,
                url,
                title,
                userId,
                visitorId,
                LocalDateTime.now(JST));
        eventPublisher.publishEvent(event);
    }
}
