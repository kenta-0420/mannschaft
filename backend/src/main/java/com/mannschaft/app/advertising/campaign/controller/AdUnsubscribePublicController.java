package com.mannschaft.app.advertising.campaign.controller;

import com.mannschaft.app.advertising.campaign.dto.UnsubscribeRequest;
import com.mannschaft.app.advertising.campaign.dto.UnsubscribeResultResponse;
import com.mannschaft.app.advertising.campaign.event.AdOpenPixelTrackingEvent;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.service.AdOpenPixelJwtService;
import com.mannschaft.app.advertising.campaign.service.AdUnsubscribeJwtService;
import com.mannschaft.app.advertising.campaign.service.UserAdPreferenceService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.AuthorizedInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * F09.17 Phase 11-b — 認証不要の公開エンドポイント
 * （ワンクリック unsubscribe / メール開封ピクセル）。
 *
 * <p>SecurityConfig の {@code /api/v1/public/**} ではなく {@code /api/v1/ads/}
 * 直下に置く理由は、メーラー / 受信者の URL が極力短く・正規であるべきため
 * （RFC 8058 List-Unsubscribe ヘッダ表記との整合）。
 * 同パスは SecurityConfig で明示的に {@code permitAll} 列挙されており、認証不要で到達できる。</p>
 *
 * <p>レート制限は {@link com.mannschaft.app.advertising.campaign.filter.AdPublicEndpointRateLimitFilter}
 * が IP 単位の Bucket4j で行う（unsubscribe=60/分、open-pixel=600/分）。</p>
 *
 * <p>第三陣 η（Tracking Listener）の実装は触らない — Event 発行のみで停止する。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/ads")
@RequiredArgsConstructor
@Tag(name = "広告メール unsubscribe / 開封ピクセル", description = "F09.17 Phase 11-b 公開 API")
public class AdUnsubscribePublicController {

    /** 1x1 透明 GIF（GIF89a, 43 バイト）。base64 ではなく原バイト列で持つ。 */
    private static final byte[] TRANSPARENT_GIF_1X1 = new byte[] {
            (byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38, (byte) 0x39, (byte) 0x61,
            (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x80, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, (byte) 0x21, (byte) 0xF9, (byte) 0x04, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x2C, (byte) 0x00, (byte) 0x00,
            (byte) 0x00, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00,
            (byte) 0x00, (byte) 0x02, (byte) 0x02, (byte) 0x44, (byte) 0x01, (byte) 0x00,
            (byte) 0x3B
    };

    private final AdUnsubscribeJwtService unsubscribeJwtService;
    private final AdOpenPixelJwtService openPixelJwtService;
    private final UserAdPreferenceService preferenceService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * メール footer からのワンクリック解除エンドポイント。
     *
     * <p>JWT 検証 → 該当 channel を {@code accept_*_ads=false} に切り替える。
     * 結果はシンプル HTML で返却（第四陣 κ が i18n 化）。</p>
     *
     * <ul>
     *   <li>JWT 期限切れ → 410 AD_UNSUBSCRIBE_TOKEN_EXPIRED</li>
     *   <li>JWT 改竄 / 形式不正 → 400 AD_UNSUBSCRIBE_TOKEN_INVALID</li>
     *   <li>token_version 不一致 → 410 AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH</li>
     * </ul>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 認可は署名付き capability トークン（JWT）で実施する。
     * {@code AdUnsubscribeJwtService.java:110-135}（{@code verify}）が
     * {@code Jwts.parser().verifyWith(signingKey).requireIssuer(ISSUER).parseSignedClaims(token)} で
     * <b>署名・issuer・有効期限</b>を検証し、改竄/形式不正は {@code AD_UNSUBSCRIBE_TOKEN_INVALID}、
     * 期限切れは {@code AD_UNSUBSCRIBE_TOKEN_EXPIRED} を throw して中断する。
     * 操作対象の {@code userId} は<b>リクエストではなく JWT クレームから取得</b>するため
     * （{@code :128}）、他人の受信設定を操作することはできない。
     * さらに {@code tokenVersion} 照合で失効済みトークンを弾く。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @GetMapping(value = "/unsubscribe", produces = "text/html;charset=UTF-8")
    @Operation(summary = "ワンクリック解除（認証不要）",
            description = "メール footer の解除リンクから呼ばれる。JWT 検証後、当該 channel の受信設定を OFF にする")
    public ResponseEntity<String> unsubscribe(@RequestParam("token") String token) {
        AdUnsubscribeJwtService.UnsubscribeTokenClaims claims = unsubscribeJwtService.verify(token);
        preferenceService.unsubscribe(claims.userId(), claims.channel(), claims.tokenVersion());
        log.info("ad unsubscribe processed userId={} channel={}", claims.userId(), claims.channel());
        return ResponseEntity.ok()
                .header("Content-Type", "text/html;charset=UTF-8")
                .body(buildSuccessHtml(claims.channel()));
    }

    /**
     * F09.17 残課題 4 — 公開 unsubscribe SPA からの POST 受信エンドポイント。
     *
     * <p>{@code GET /api/v1/ads/unsubscribe} は単一クリックで JWT 内 {@code ch}
     * 1 チャネルのみを OFF にする後方互換用フローだが、設計書 §9.3 のタップ動線では
     * 「リンクから JWT 受領 → SPA でチャネル選択 → POST で確定 → 完了画面」の
     * 3 ステップ UX を要求している。本メソッドはその 2 ステップ目に対応する。</p>
     *
     * <p>JWT 検証 → 受信 channels 一覧 (1〜4 件) を冪等に OFF にし、確定後の状態を返す。</p>
     *
     * <ul>
     *   <li>JWT 期限切れ → 410 AD_UNSUBSCRIBE_TOKEN_EXPIRED</li>
     *   <li>JWT 改竄 / 形式不正 → 400 AD_UNSUBSCRIBE_TOKEN_INVALID</li>
     *   <li>token_version 不一致 → 410 AD_UNSUBSCRIBE_TOKEN_VERSION_MISMATCH</li>
     *   <li>channels 空 → 400 (Validation により先に弾く)</li>
     * </ul>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@link #unsubscribe} と同一の
     * 署名付き capability トークン（JWT）で認可する。
     * {@code AdUnsubscribeJwtService.java:110-135}（{@code verify}）が署名・issuer・有効期限を検証し、
     * 失敗時は {@code AD_UNSUBSCRIBE_TOKEN_INVALID} / {@code AD_UNSUBSCRIBE_TOKEN_EXPIRED} で中断する。
     * 操作対象の {@code userId} は<b>リクエストボディではなく JWT クレーム由来</b>
     * （本メソッド内の {@code claims.userId()}）であり、リクエストから受け取るのは
     * OFF にする channels のみのため、他人の受信設定は操作できない。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping(value = "/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "公開 unsubscribe SPA からのチャネル選択 OFF（認証不要）",
            description = "メール末尾リンクから SPA に遷移したユーザーがチェックボックスで選択した複数チャネルを OFF にする")
    public UnsubscribeResultResponse unsubscribePost(@Valid @RequestBody UnsubscribeRequest request) {
        AdUnsubscribeJwtService.UnsubscribeTokenClaims claims =
                unsubscribeJwtService.verify(request.token());
        UnsubscribeResultResponse response =
                preferenceService.applyChannelUnsubscribe(
                        claims.userId(), request.channels(), claims.tokenVersion());
        log.info("ad unsubscribe SPA processed userId={} disabledChannels={}",
                claims.userId(), response.disabledChannels());
        return response;
    }

    /**
     * 開封ピクセル取得エンドポイント。
     *
     * <p>JWT 検証成功時は {@link AdOpenPixelTrackingEvent} を発行する。
     * 実際の delivery テーブル更新は第三陣 η の Listener が担当する。</p>
     *
     * <p>JWT 失敗時もログを残すのみで、レスポンスは常に 200 + 1x1 GIF を返す。
     * メーラーに警告を出さない・キャンペーン側でクロール避けに使われ得るため。</p>
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 認可は署名付き capability トークン（JWT）で実施する。
     * {@code AdOpenPixelJwtService.java:97-114}（{@code verify}）が
     * {@code Jwts.parser().verifyWith(signingKey).requireIssuer(ISSUER).parseSignedClaims(token)} で
     * 署名・issuer・有効期限を検証し、失敗時は {@code AD_OPEN_PIXEL_TOKEN_INVALID} を throw する。</p>
     *
     * <p><b>本 EP のみの特殊性（明記）</b>: 検証失敗時も HTTP は常に 200 + 1x1 GIF を返す
     * （本メソッド内の {@code catch (BusinessException e)} / {@code catch (Exception e)} で例外を捕捉）。
     * すなわち<b>レスポンス自体は fail-open</b> である。ただしこれが安全なのは、
     * レスポンスが<b>ユーザーデータを一切含まない固定バイト列</b>（定数 {@code TRANSPARENT_GIF_1X1}）
     * だからであり、<b>副作用（{@code AdOpenPixelTrackingEvent} の発行＝開封計上）は
     * JWT 検証成功時のみ</b>に限定される（fail-closed）。
     * 集計対象の {@code deliveryId} はリクエストではなく JWT クレーム由来のため、
     * 他キャンペーンの開封数を偽装することはできない。
     * 認可根治戦役 Wave5 監査済。</p>
     */
    @AuthorizedInService
    @GetMapping(value = "/pixels/open", produces = MediaType.IMAGE_GIF_VALUE)
    @Operation(summary = "メール開封ピクセル（認証不要）",
            description = "JWT 検証成功時のみ AdOpenPixelTrackingEvent を発行。常に 200 + 1x1 GIF を返す")
    public ResponseEntity<byte[]> openPixel(@RequestParam("token") String token) {
        try {
            AdOpenPixelJwtService.OpenPixelClaims claims = openPixelJwtService.verify(token);
            eventPublisher.publishEvent(new AdOpenPixelTrackingEvent(
                    claims.deliveryId(), claims.type(), Instant.now()));
        } catch (BusinessException e) {
            // ピクセル本体は常に 200 で返す。集計だけ落とす。
            if (e.getErrorCode() == AdCampaignErrorCode.AD_OPEN_PIXEL_TOKEN_INVALID) {
                log.warn("ad open pixel JWT invalid: {}", e.getMessage());
            } else {
                log.warn("ad open pixel verify failed code={}", e.getErrorCode().getCode());
            }
        } catch (Exception e) {
            // 想定外の例外もピクセルを 200 で返すために握り潰すが、ログは必ず残す
            log.error("ad open pixel unexpected error", e);
        }
        return ResponseEntity.status(HttpStatus.OK)
                .contentType(MediaType.IMAGE_GIF)
                .header("Cache-Control", "no-store, no-cache, must-revalidate, private")
                .header("Pragma", "no-cache")
                .body(TRANSPARENT_GIF_1X1);
    }

    /**
     * 解除成功時の HTML。文言は第四陣 κ が i18n 化する前提で最低限の英日併記とする。
     */
    private static String buildSuccessHtml(String channel) {
        return """
                <!DOCTYPE html>
                <html lang="ja">
                <head>
                  <meta charset="UTF-8">
                  <title>配信停止</title>
                </head>
                <body style="font-family: sans-serif; padding: 2em; text-align: center;">
                  <h1>広告メール配信を停止しました</h1>
                  <p>%s の広告配信を OFF に設定しました。</p>
                  <p>You have unsubscribed from %s ads.</p>
                </body>
                </html>
                """.formatted(channel, channel);
    }
}
