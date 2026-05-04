package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F09.8 Phase A-1: URL カード作成時に元ページの OGP メタ
 * （og:title / og:image / og:description）を非同期取得する。
 *
 * <p>挙動:</p>
 * <ul>
 *   <li>{@code @Async("event-pool")} でメインスレッドから切り離して実行</li>
 *   <li>HTTP 接続タイムアウト 5 秒、リトライ無し</li>
 *   <li>取得失敗（タイムアウト / 4xx / 5xx / パースエラー）時は OGP 無しのまま保存</li>
 *   <li>Jsoup を使って {@code <meta property="og:*">} と
 *       {@code <meta name="og:*">}（互換）を抽出</li>
 *   <li>{@code og:title} 不在時は HTML {@code <title>} タグをフォールバックに採用</li>
 * </ul>
 *
 * <p>{@link #fetchAndUpdate(Long, String)} は新規トランザクション境界で動き、
 * カード作成直後の {@code afterCommit} フェーズから安全に呼び出せる
 * （= カード作成トランザクションがコミットされる前にレースしない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OgpFetchService {

    /** HTTP タイムアウト（ミリ秒）。設計書 §A-1: 5 秒。 */
    private static final int TIMEOUT_MS = 5_000;

    /** OGP タイトル DB 列長（200）。 */
    private static final int OG_TITLE_MAX = 200;

    /** OGP 画像 URL DB 列長（500）。 */
    private static final int OG_IMAGE_URL_MAX = 500;

    /** OGP 説明 DB 列長（500）。 */
    private static final int OG_DESCRIPTION_MAX = 500;

    /** 偽装 UA（一部サイトはデフォルト Java UA を弾くため）。 */
    private static final String USER_AGENT =
            "Mozilla/5.0 (compatible; MannschaftBot/1.0; +https://mannschaft.example.com/bot)";

    private final CorkboardCardRepository cardRepository;

    /**
     * 非同期で URL の OGP を取得しカードに反映する。失敗時はログのみ。
     *
     * @param cardId 対象カードID
     * @param url    取得対象 URL
     */
    @Async("event-pool")
    @Transactional
    public void fetchAndUpdate(Long cardId, String url) {
        if (cardId == null || url == null || url.isBlank()) {
            return;
        }
        OgpMeta meta = fetch(url);
        if (meta == null) {
            log.info("OGP 取得失敗（無視）: cardId={}, url={}", cardId, url);
            return;
        }
        cardRepository.findById(cardId).ifPresent(card -> {
            card.updateOgpMeta(meta.title, meta.imageUrl, meta.description);
            cardRepository.save(card);
            log.info("OGP 更新: cardId={}, title={}", cardId, meta.title);
        });
    }

    /**
     * 同期版の取得処理（テスト・内部呼び出し用に public）。
     * 失敗時は {@code null}。
     */
    public OgpMeta fetch(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .followRedirects(true)
                    .ignoreContentType(false)
                    .ignoreHttpErrors(false)
                    .get();
            String title = pickMeta(doc, "og:title");
            if (title == null) {
                Element titleEl = doc.selectFirst("title");
                title = titleEl != null ? titleEl.text() : null;
            }
            String image = pickMeta(doc, "og:image");
            String description = pickMeta(doc, "og:description");
            return new OgpMeta(
                    truncate(title, OG_TITLE_MAX),
                    truncate(image, OG_IMAGE_URL_MAX),
                    truncate(description, OG_DESCRIPTION_MAX)
            );
        } catch (Exception e) {
            log.warn("OGP 取得失敗: url={}, cause={}", url, e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * {@code <meta property="og:*">} もしくは {@code <meta name="og:*">} から content を抽出する。
     */
    private String pickMeta(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "]");
        if (el == null) {
            el = doc.selectFirst("meta[name=" + property + "]");
        }
        if (el == null) {
            return null;
        }
        String content = el.attr("content");
        return content.isBlank() ? null : content;
    }

    /**
     * DB 列長を超えないように切り詰める（null セーフ）。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /**
     * OGP 抽出結果。
     */
    public record OgpMeta(String title, String imageUrl, String description) {
    }
}
