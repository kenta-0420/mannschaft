package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * お知らせ既読エンドポイントのユーザー別レートリミットフィルタ（F02.6 / #2494）。
 *
 * <p>対象エンドポイントと閾値:</p>
 * <ul>
 *   <li>単件既読 {@code POST /api/v1/(teams|organizations)/*&#47;announcements/*&#47;read}
 *       — <b>60 req/分 / ユーザー</b></li>
 *   <li>一括既読 {@code POST /api/v1/(teams|organizations)/*&#47;announcements/read-all}
 *       — <b>5 req/分 / ユーザー</b></li>
 * </ul>
 *
 * <p><b>なぜ必要か（#2494 課題 2）</b>: 既読 EP はいずれも認証さえあれば連打できた。
 * 1 回あたりの書き込みは冪等（既読済みはスキップ）だが、<b>スコープを変えながら</b>回されると
 * {@code announcement_read_status} に行が積まれ続ける。認可の穴ではなく濫用対策・資源保護である
 * （書き込まれるのは #2478 以降「その利用者に実際に可視なお知らせ」だけに限定済み）。</p>
 *
 * <p><b>閾値の根拠（発明していない）</b>: いずれも既存の文書化済みの値から、
 * <b>厳しい方</b>を採っている。</p>
 * <ul>
 *   <li><b>単件 60 req/分</b> — 設計書 F02.6 §6.4 の想定値は 100 req/分だが、
 *       {@code docs/security/06} §4.2 の標準閾値テーブルは「認証済み WRITE 系」を
 *       <b>60 req/分・上限の目安</b>と定めている。標準を超えられないので 60 を採用した。</li>
 *   <li><b>一括 5 req/分</b> — 設計書 F02.6 §6.4 の想定値そのまま。
 *       標準閾値テーブルの「送信系」10 req/分より厳しく、機能固有要件として許容される
 *       （§4.2 の注記「標準閾値より厳しい制限を設けてよい」）。
 *       一括既読は 1 リクエストで最大
 *       {@link AnnouncementReadService#MARK_ALL_BATCH_SIZE} ×
 *       {@link AnnouncementReadService#MARK_ALL_MAX_BATCHES} 件の {@code INSERT} を伴い、
 *       単件（最大 1 行）より桁違いに重いので、厳しい側に倒すのが妥当である。</li>
 * </ul>
 *
 * <p><b>実装の流儀</b>: 同ドメインの {@link BroadcastRateLimitFilter} に揃える。
 * 認証済みユーザーのみ対象（{@link #shouldNotFilter} で未認証を除外）、キーは基底の
 * {@code "u:{userId}"} 形式、閾値超過時の応答は {@link AbstractRateLimitFilter} が返す
 * <b>429 Too Many Requests</b>（{@code Retry-After} + {@code X-RateLimit-*} +
 * {@code {"error":"Too many requests"}}）。カウントは {@link ValkeyRateLimiter} が担うため
 * ECS 複数タスク構成でも実効上限が緩まない（docs/security/06 §4.3）。
 * zone をエンドポイント別に分けているのは、単件の連打が一括の枠を食い潰さないようにするため。</p>
 */
@Component
public class AnnouncementReadRateLimitFilter extends AbstractRateLimitFilter {

    /** 単件既読: {@code /api/v1/(teams|organizations)/{scopeId}/announcements/{id}/read} */
    private static final Pattern SINGLE_READ_PATTERN =
            Pattern.compile("^/api/v1/(teams|organizations)/[^/]+/announcements/[^/]+/read$");

    /** 一括既読: {@code /api/v1/(teams|organizations)/{scopeId}/announcements/read-all} */
    private static final Pattern READ_ALL_PATTERN =
            Pattern.compile("^/api/v1/(teams|organizations)/[^/]+/announcements/read-all$");

    /** 単件既読の上限（1 分あたり / ユーザー）。docs/security/06 §4.2「認証済み WRITE 系」の標準値。 */
    static final int SINGLE_READ_LIMIT = 60;

    /** 一括既読の上限（1 分あたり / ユーザー）。設計書 F02.6 §6.4 の想定値。単件より重いので厳しくする。 */
    static final int READ_ALL_LIMIT = 5;

    /** ウィンドウ長。 */
    private static final Duration WINDOW = Duration.ofMinutes(1);

    static final String ZONE_SINGLE_READ = "announcement:read";
    static final String ZONE_READ_ALL = "announcement:read-all";

    public AnnouncementReadRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // POST 以外は対象外（既読 EP はいずれも POST）
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 認証なしは除外（認証フィルタで処理される）
        if (!isAuthenticated()) {
            return true;
        }

        return resolveRule(request) == null;
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String path = request.getServletPath();

        // 具体度の高い read-all を先に判定する（単件パターンとは排他だが順序を明示しておく）。
        if (READ_ALL_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE_READ_ALL, READ_ALL_LIMIT, WINDOW);
        }
        if (SINGLE_READ_PATTERN.matcher(path).matches()) {
            return new RateLimitRule(ZONE_SINGLE_READ, SINGLE_READ_LIMIT, WINDOW);
        }
        return null;
    }
}
