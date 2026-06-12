package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.entity.UserAdPreference;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.directmail.service.DirectMailService;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * F09.17 Phase 11-b ε-B メールチャネル配信サービス。
 *
 * <p>処理の流れ:</p>
 * <ol>
 *   <li>受信者 user の email を取得（未取得 / blank ならスキップ）</li>
 *   <li>HTML 本文に unsubscribe リンクと開封ピクセルを埋め込む</li>
 *   <li>{@link DirectMailService#sendSystemAdMail} に委譲して SES 送信 + direct_mail_logs/recipients 書き込み</li>
 *   <li>{@code ad_email_deliveries} に履歴を 1 行追加（{@code direct_mail_recipient_id} を転記）</li>
 * </ol>
 *
 * <p>件名 {@code [PR] } プレフィックス付与は {@link DirectMailService#sendSystemAdMail} 内で強制。
 * 開封ピクセル JWT は {@link AdOpenPixelJwtService}、unsubscribe JWT は {@link AdUnsubscribeJwtService}。</p>
 */
@Service
@Slf4j
public class AdEmailChannelService {

    /** YYYY-MM (パーティショニング用 month_key) */
    private static final DateTimeFormatter MONTH_KEY_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    /**
     * Unsubscribe SPA のパス (F09.17 残課題 4)。
     *
     * <p>旧: {@code /api/v1/ads/unsubscribe?token=...} (バックエンド HTML 直接返却)<br>
     * 新: {@code /ads/unsubscribe?token=...} (Nuxt SPA でチャネル選択 → POST)</p>
     *
     * <p>旧 URL は古いメール救済のため Controller 側で後方互換維持されている。
     * 本サービス（新規送信）は SPA URL を採用する。</p>
     */
    private static final String UNSUBSCRIBE_SPA_PATH = "/ads/unsubscribe?token=";

    /** 開封ピクセルのパス。 */
    private static final String OPEN_PIXEL_PATH = "/api/v1/ads/pixels/open?token=";

    private final DirectMailService directMailService;
    private final UserRepository userRepository;
    private final UserAdPreferenceService userAdPreferenceService;
    private final AdUnsubscribeJwtService unsubscribeJwtService;
    private final AdOpenPixelJwtService openPixelJwtService;
    private final AdEmailDeliveryRepository deliveryRepository;

    /** SPA 配信元のベース URL。{@code app.base-url}（環境変数 {@code APP_BASE_URL}）から取得。 */
    private final String appBaseUrl;

    public AdEmailChannelService(
            DirectMailService directMailService,
            UserRepository userRepository,
            UserAdPreferenceService userAdPreferenceService,
            AdUnsubscribeJwtService unsubscribeJwtService,
            AdOpenPixelJwtService openPixelJwtService,
            AdEmailDeliveryRepository deliveryRepository,
            @Value("${app.base-url}") String appBaseUrl) {
        this.directMailService = directMailService;
        this.userRepository = userRepository;
        this.userAdPreferenceService = userAdPreferenceService;
        this.unsubscribeJwtService = unsubscribeJwtService;
        this.openPixelJwtService = openPixelJwtService;
        this.deliveryRepository = deliveryRepository;
        this.appBaseUrl = normalizeBaseUrl(appBaseUrl);
    }

    /**
     * 末尾の "/" を除去して {@code base + path} 結合時の "//" 発生を防ぐ。
     */
    private static String normalizeBaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * 1 ユーザーに 1 通のメールを配信する。
     *
     * <p>email が取得できないユーザーは黙ってスキップして false を返す。
     * （広告の場合、配信失敗を可視化する必要は薄く、FreqCap ロールバックは dispatcher 側で判断する）</p>
     *
     * @param campaign キャンペーン本体
     * @param channel  EMAIL チャネル設定（locale 解決済の単一行）
     * @param userId   受信者
     * @return 配信に成功したら true、email 取得不能等でスキップした場合 false
     */
    @Transactional
    public boolean deliver(AdMessagingCampaign campaign,
                           AdMessagingCampaignChannel channel,
                           Long userId) {
        if (campaign == null || channel == null || userId == null) {
            throw new IllegalArgumentException("campaign, channel, userId は必須です");
        }

        // 1) email 取得
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty() || userOpt.get().getEmail() == null
                || userOpt.get().getEmail().isBlank()) {
            log.debug("AD_EMAIL_SKIPPED reason=NO_EMAIL userId={}", userId);
            return false;
        }
        String email = userOpt.get().getEmail();

        // 2) JWT トークン発行
        UserAdPreference pref = userAdPreferenceService.getOrCreateEntityForUser(userId);
        Integer tokenVersion = pref.getUnsubscribeTokenVersion() != null
                ? pref.getUnsubscribeTokenVersion()
                : 0;
        String unsubscribeJwt = unsubscribeJwtService.generate(userId, tokenVersion, "EMAIL");

        // 3) HTML 本文構築（unsubscribe リンク + 開封ピクセルを後で footer に埋め込む）
        //    開封ピクセルは delivery_id に依存するが、delivery_id は ad_email_deliveries.id 採番後にしか
        //    確定しないため、ここでは「先に delivery を作って id を得てから token を埋める」二段構えに
        //    する。実装単純化のため pixel token は ad_email_deliveries.id を採番後に再構築し、
        //    メール本体は送信済（SES の messageId は未取得）として記録する。
        //    ※開封ピクセルは「メール本文埋め込み時」が本来の最適点。ad_email_deliveries.id 採番
        //      前にトークンを作るには UUIDv7 を事前生成する手もあるが、現状の builder 経由では
        //      JPA が PrePersist で振るためここでは「unsubscribe のみ埋め込んで SES 送信 → delivery 行作成」
        //      のフローを採用する。pixel JWT は ε-C で生成方式を refactor 予定。

        String subject = channel.getSubject() != null ? channel.getSubject() : "(広告)";
        String body = channel.getBodyMarkdown() != null ? channel.getBodyMarkdown() : "";
        String htmlBody = buildHtmlBody(body, unsubscribeJwt, /* openPixelToken= */ null);

        // 4) SES 送信 + direct_mail_logs/recipients 作成
        // F09.18 Phase 18-f: 双方向トレース用に delivery UUID を先行生成
        // （enqueue 時の source_event_id として渡すため sendSystemAdMail() 呼び出し前に生成する）
        UUID deliveryId = UUID.randomUUID();

        DirectMailService.AdMailSendResult result = directMailService.sendSystemAdMail(
                campaign.getAdvertiserAccountId(),
                userId,
                email,
                subject,
                htmlBody,
                deliveryId);  // 先行生成した delivery UUID を渡す

        // 5) ad_email_deliveries に履歴を残す
        LocalDateTime now = LocalDateTime.now();
        AdEmailDelivery delivery = AdEmailDelivery.builder()
                .campaignId(campaign.getId())
                .userId(userId)
                .directMailRecipientId(result.recipient().getId())
                .emailOutboxId(result.outboxId())     // F09.18 outbox UUID を記録（双方向トレース）
                .sentAt(now)
                .monthKey(now.format(MONTH_KEY_FMT))
                .build();
        delivery.setId(deliveryId);  // 先行生成した UUID を entity に設定
        deliveryRepository.save(delivery);

        log.info("AD_EMAIL_DELIVERED campaignId={} userId={} recipientId={} deliveryId={} outboxId={}",
                campaign.getId(), userId, result.recipient().getId(), delivery.getId(), result.outboxId());
        return true;
    }

    /**
     * HTML 本文を組み立てる。広告 footer に unsubscribe リンクと開封ピクセル {@code <img>} を埋め込む。
     */
    String buildHtmlBody(String bodyMarkdownOrHtml, String unsubscribeJwt, String openPixelJwt) {
        // Markdown 変換は ε-B では割愛し、改行のみ HTML 化する簡素な変換を行う。
        // 本格的な Markdown レンダリングは ε-C で MarkdownConverter 統合予定。
        String escapedBody = bodyMarkdownOrHtml == null
                ? ""
                : bodyMarkdownOrHtml.replace("\n", "<br>");

        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        html.append(escapedBody);
        html.append("<hr>");
        html.append("<p style=\"font-size:12px;color:#666;\">");
        html.append("この広告メールは F09.17 広告配信ネットワークから送信されました。");
        if (unsubscribeJwt != null && !unsubscribeJwt.isBlank()) {
            html.append("<br><a href=\"")
                    .append(appBaseUrl)
                    .append(UNSUBSCRIBE_SPA_PATH)
                    .append(unsubscribeJwt)
                    .append("\">配信停止</a>");
        }
        html.append("</p>");
        if (openPixelJwt != null && !openPixelJwt.isBlank()) {
            html.append("<img src=\"")
                    .append(OPEN_PIXEL_PATH)
                    .append(openPixelJwt)
                    .append("\" width=\"1\" height=\"1\" alt=\"\" style=\"display:none;\">");
        }
        html.append("</body></html>");
        return html.toString();
    }
}
