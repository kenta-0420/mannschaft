package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * fan-out ジョブの文面を <b>enqueue 時に 6 配信ロケールぶんまとめて描画</b>するレンダラ（Issue #2871）。
 *
 * <h2>なぜ enqueue 時に全ロケール描画するのか</h2>
 * <p>受信者は enqueue 時点では未確定だが、<b>配信ロケールは 6 種しかない</b>。よって受信者スナップショットを
 * 取らずとも「起こりうる文面」は 6 通りで尽きる。これを子表 {@code notification_fanout_job_messages} に
 * 保存しておくと、
 * <ul>
 *   <li>ジョブ処理中に翻訳がデプロイされても、1 つのイベント内で文面が前半と後半で食い違わない</li>
 *   <li>切り詰め（title 200 / body 1000）も enqueue 時に確定するため、リトライやデプロイをまたいでも
 *       同じ文面が再現される</li>
 * </ul>
 * が同時に得られる。</p>
 *
 * <h2>欠落キーは握り潰さない（AC-6）</h2>
 * <p>{@link MessageSource} は {@code useCodeAsDefaultMessage(false)} で構成してあるため、
 * どのバンドルにも無いキーは {@link org.springframework.context.NoSuchMessageException} を投げる。
 * 本レンダラはこれを<b>捕捉しない</b>。キー文字列をそのまま本文として配ってしまうと、
 * 「利用者には意味不明な文字列が届いたが、ログにもメトリクスにも何も残らない」という
 * 最悪の握り潰しになるためである。例外は呼び出し元（enqueue）まで伝播し、
 * 通知の欠落として log.error に残る。</p>
 */
@Slf4j
@Component
public class FanoutMessageRenderer {

    /** {@code notifications.title} は VARCHAR(200)。 */
    public static final int TITLE_MAX_CODE_POINTS = 200;
    /** {@code notifications.body} は VARCHAR(1000)。 */
    public static final int BODY_MAX_CODE_POINTS = 1000;

    /** ロケール別の描画件数（可観測性）。 */
    static final String METRIC_RENDERED = "mannschaft.notification.fanout.message.rendered";
    /** 描画失敗（欠落キー等）カウンタ。 */
    static final String METRIC_RENDER_FAILED = "mannschaft.notification.fanout.message.render_failed";
    /** 切り詰め発生カウンタ。 */
    static final String METRIC_TRUNCATED = "mannschaft.notification.fanout.message.truncated";

    private final MessageSource messageSource;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public FanoutMessageRenderer(MessageSource messageSource,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.messageSource = messageSource;
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * 6 配信ロケールぶんの文面を描画して返す。
     *
     * @param kind 文面テンプレート種別
     * @param args 利用者が書いた中身（アンケート名・行事名 等）。<b>翻訳せずそのまま差し込む</b>。
     *             {@code null} 要素は空文字として扱う（MessageFormat に {@code null} を渡すと
     *             リテラル {@code "null"} が本文に出てしまうため）
     * @return locale タグ（{@link DeliveryLocales#TAGS}）→ 描画済み文面
     */
    public Map<String, RenderedMessage> renderAllLocales(FanoutMessageKind kind, String... args) {
        Object[] safeArgs = normalizeArgs(args);
        Map<String, RenderedMessage> byLocale = new LinkedHashMap<>();
        for (String tag : DeliveryLocales.TAGS) {
            Locale locale = DeliveryLocales.toLocale(tag);
            String title;
            String body;
            try {
                title = messageSource.getMessage(kind.titleKey(), safeArgs, locale);
                body = messageSource.getMessage(kind.bodyKey(), safeArgs, locale);
            } catch (RuntimeException e) {
                // 握り潰さない。キー欠落は恒久的な設定不備であり、無音のフォールバックは事故を隠す。
                incrementCounter(METRIC_RENDER_FAILED);
                log.error("fan-out 文面の描画に失敗（キー欠落等）: kind={} locale={} titleKey={} bodyKey={}",
                        kind, tag, kind.titleKey(), kind.bodyKey(), e);
                throw e;
            }
            RenderedMessage rendered = new RenderedMessage(
                    truncateByCodePoints(title, TITLE_MAX_CODE_POINTS),
                    truncateByCodePoints(body, BODY_MAX_CODE_POINTS));
            if (!rendered.title().equals(title) || !rendered.body().equals(body)) {
                incrementCounter(METRIC_TRUNCATED);
            }
            incrementCounter(METRIC_RENDERED);
            byLocale.put(tag, rendered);
        }
        return byLocale;
    }

    /**
     * <b>コードポイント境界</b>で切り詰める（AC-8）。
     *
     * <p>素の {@code substring(0, max)} は UTF-16 コードユニット単位で切るため、絵文字などの
     * サロゲートペアの<b>途中</b>で切れて壊れた文字（lone surrogate）を作り、DB へ書いた瞬間に
     * 不正なバイト列として弾かれる／文字化けする。コードポイント単位で数えて切ることで、
     * ペアを分断しない。MySQL の {@code VARCHAR(n)} は文字数（＝コードポイント数）で数えるため、
     * コードポイント {@code n} 以内なら列にも必ず収まる。</p>
     */
    public static String truncateByCodePoints(String value, int maxCodePoints) {
        if (value == null) {
            return null;
        }
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maxCodePoints);
        return value.substring(0, endIndex);
    }

    private static Object[] normalizeArgs(String[] args) {
        if (args == null || args.length == 0) {
            return new Object[0];
        }
        Object[] normalized = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            normalized[i] = args[i] == null ? "" : args[i];
        }
        return normalized;
    }

    private void incrementCounter(String name) {
        if (meterRegistryProvider == null) {
            return;
        }
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            return;
        }
        registry.counter(name).increment();
    }

    /** 描画済みの 1 ロケールぶんの文面。 */
    public record RenderedMessage(String title, String body) {
    }
}
