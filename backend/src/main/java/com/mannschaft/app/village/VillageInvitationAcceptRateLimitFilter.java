package com.mannschaft.app.village;

import com.mannschaft.app.common.ratelimit.AbstractRateLimitFilter;
import com.mannschaft.app.common.ratelimit.RateLimitRule;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 村招待の受諾エンドポイントのレートリミットフィルタ（依頼書 §5.5）。
 *
 * <p>{@code POST /api/v1/village-invitations/{token}/accept} を
 * <strong>制限主体（認証済みなら userId）単位</strong>で 1 分間に 10 回に制限する。</p>
 *
 * <p><b>なぜ必要か</b>: 受諾は失敗理由を一切返さず、すべて 404（不在）へ畳む
 * （{@link com.mannschaft.app.village.service.VillageInvitationService} 参照）。
 * 応答からは何も漏れないが、<strong>当たりを引くまで叩き続ける総当たり</strong>そのものは
 * 応答内容と無関係に成立する。トークンは 256 ビット乱数であり現実的には当たらないものの、
 * 「叩き放題」を残すこと自体が秘匿系エンドポイントの設計上の穴であり、
 * かつ DB への悲観ロック検索を無制限に誘発できる（可用性の的にもなる）。
 * よって回数そのものを絞る。</p>
 *
 * <p><b>なぜキーにトークンを含めないか</b>: 総当たりは毎回異なるトークンを試すため、
 * トークンをキーに含めると攻撃者は永久に上限へ到達しない（カウンタが毎回新規になる）。
 * 基底の {@code u:{userId}}（未認証時は {@code ip:{ip}}）をそのまま使う。</p>
 *
 * <p>登録方式: {@link com.mannschaft.app.config.SecurityConfig} が Bean 定義と
 * サーブレット自動登録の無効化を行い、{@code addFilterAfter(・, JwtAuthenticationFilter.class)}
 * 経由でのみ動作する（受諾は認証必須のため、確定した SecurityContext から userId を解決する）。
 * カウント・§4.3 標準ヘッダー・429 応答は {@link AbstractRateLimitFilter} が担う。</p>
 */
public class VillageInvitationAcceptRateLimitFilter extends AbstractRateLimitFilter {

    /**
     * 対象: POST /api/v1/village-invitations/{token}/accept。
     *
     * <p>{@code [^/]+} で 1 階層だけを捕捉する（{@code .*} のような再帰マッチは、
     * 意図しない下位パスまで巻き込むため使わない）。</p>
     */
    private static final Pattern TARGET_PATH =
            Pattern.compile("^/api/v1/village-invitations/([^/]+)/accept/?$");

    /** 1 分間あたりの上限回数。正規の利用は「招待リンクを踏んで 1 回」であり、10 回でも十分に緩い。 */
    private static final int CAPACITY_PER_MINUTE = 10;

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private static final String ZONE = "village:invitation-accept";

    public VillageInvitationAcceptRateLimitFilter(ObjectProvider<ValkeyRateLimiter> rateLimiterProvider) {
        super(rateLimiterProvider);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isTarget(request);
    }

    @Override
    protected RateLimitRule resolveRule(HttpServletRequest request) {
        if (!isTarget(request)) {
            return null;
        }
        return new RateLimitRule(ZONE, CAPACITY_PER_MINUTE, WINDOW);
    }

    private boolean isTarget(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && TARGET_PATH.matcher(request.getServletPath()).matches();
    }
}
