package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * ブログ記事本文（Markdown）に埋め込まれた生の R2 オブジェクトキーを、
 * 表示用の署名付き GET URL へ解決する共通部品。
 *
 * <p><b>背景（画像URL根治）</b>: 従来はフロントエンド（{@code BlogMediaUploader.vue}）が
 * 公開ベース URL を連結した絶対 URL を本文へ焼き込んでいたが、そのベース URL が未宣言のため
 * {@code /blog/TEAM/12/xxx.png} という壊れた相対パスが本文へ保存されていた。
 * マスター御裁可により配信は署名 URL に統一する。署名 URL には有効期限があるため
 * 本文へ URL を焼き込むことはできない。<b>本文には r2Key を保存し、取得時に本部品で解決する</b>。</p>
 *
 * <p>{@code BulletinAttachmentService} が取得時に都度 presign する方式を範とする
 * （{@link MediaUrlResolver} の Javadoc 参照）。</p>
 *
 * <p><b>本番未稼働のため後方互換は不要</b>: 本文には常に r2Key（{@code blog/} 始まり）が
 * 入っている前提でよい。旧形式（絶対 URL・先頭スラッシュ付き相対パス）を読める互換処理は設けない。</p>
 *
 * <h2>実装契約（出陣で満たすこと）</h2>
 *
 * <p><b>1. 越境防止（最重要）</b>: 本文は利用者が自由に編集できる。他チーム・他組織・他ユーザーの
 * r2Key を手書きされた場合に署名 URL を発行すると、他人の画像を盗み見できる情報漏洩になる。
 * 以下の <b>二段の関門を両方</b>通過したキーのみ presign 対象とすること:</p>
 * <ol>
 *   <li><b>プレフィックス照合</b>（安価・DB 不要）— キーを正規化したうえで
 *       {@code blog/{scopeType}/{scopeId}/} 配下に厳密に属すること。
 *       {@code ../} による脱出、{@code blog/TEAM/12} が {@code blog/TEAM/123} に前方一致する
 *       取りこぼしを許さないこと。</li>
 *   <li><b>台帳照合</b>（防御の二段目）— {@link BlogMediaUploadRepository} に当該 s3Key の
 *       行が実在すること。存在しないキー（＝アップロード経路を通っていない手書き文字列）は
 *       presign しない。照会は <b>1 本文につき 1 クエリ</b>（{@code findByS3KeyIn}）で行い、
 *       キーごとにループ照会してはならない。</li>
 * </ol>
 *
 * <p><b>2. 性能</b>: 本文 1 件につき {@link MediaUrlResolver#resolveAll} を<b>ちょうど 1 回</b>
 * 呼ぶこと。画像ごとに {@code resolve} をループ呼びしてはならない（N+1 presign の防止）。</p>
 *
 * <p><b>3. 縮退</b>: 解決できなかったキーは本文中でそのまま残し、例外を伝播させないこと
 * （記事全体を 500 にしない）。ただし握りつぶさず {@code log.warn} で記録すること。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogBodyMediaResolver {

    /**
     * 本文中の R2 キー出現箇所にマッチする正規表現。
     *
     * <ul>
     *   <li>group 1: 画像記法 <code>![alt](blog/...)</code>（任意のタイトル付きも許容）</li>
     *   <li>group 2: src 属性 <code>&lt;video src="blog/..."&gt;</code> / <code>&lt;img src='blog/...'&gt;</code></li>
     * </ul>
     *
     * <p>抽出と置換で同一のパターンを使うことで「抽出できたが置換されない」ずれを構造的に防ぐ。
     * {@code blog/} 始まりのみを対象とするため、外部 URL（{@code https://...}）は自然に対象外となる。</p>
     */
    private static final Pattern MEDIA_KEY_PATTERN = Pattern.compile(
            "!\\[[^\\]]*\\]\\(\\s*(blog/[^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)"
                    + "|src\\s*=\\s*[\"'](blog/[^\"']+)[\"']");

    private final MediaUrlResolver mediaUrlResolver;
    private final BlogMediaUploadRepository blogMediaUploadRepository;

    /**
     * 本文中に出現する R2 オブジェクトキー（{@code blog/} で始まるもの）を抽出する。
     *
     * <p>対象記法:</p>
     * <ul>
     *   <li>画像: <code>![alt](blog/TEAM/12/xxx.png)</code></li>
     *   <li>動画: <code>&lt;video src="blog/TEAM/12/xxx.mp4"&gt;</code></li>
     * </ul>
     *
     * @param body 記事本文（null 可）
     * @return 出現順・重複排除済みのキー一覧（本文が null/空なら空リスト）
     */
    public List<String> extractR2Keys(String body) {
        if (body == null || body.isEmpty()) {
            return List.of();
        }
        // LinkedHashSet で「出現順・重複排除」を同時に満たす。
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = MEDIA_KEY_PATTERN.matcher(body);
        while (matcher.find()) {
            String key = extractKeyGroup(matcher);
            if (key != null && !key.isBlank()) {
                keys.add(key);
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 本文中の R2 キーを署名付き表示 URL へ置換して返す。
     *
     * <p>投稿自身のスコープに属し、かつ {@code blog_media_uploads} に実在するキーのみを
     * presign 対象とする（クラス Javadoc の「二段の関門」参照）。</p>
     *
     * @param body      記事本文（null 可）
     * @param scopeType 投稿のスコープ種別（TEAM / ORGANIZATION / PERSONAL）
     * @param scopeId   投稿のスコープ ID
     * @return 署名 URL へ置換済みの本文。body が null なら null
     */
    public String resolveBody(String body, StorageScopeType scopeType, Long scopeId) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        List<String> keys = extractR2Keys(body);
        if (keys.isEmpty()) {
            return body;
        }

        // 関門1: スコーププレフィックス照合（DB 不要・安価なので先に通す）。
        List<String> ownScopeKeys = keys.stream()
                .filter(key -> belongsToScope(key, scopeType, scopeId))
                .toList();
        int rejectedByScope = keys.size() - ownScopeKeys.size();
        if (rejectedByScope > 0) {
            log.warn("本文メディア: 投稿スコープ外の r2Key を presign 対象から除外: scope={}/{}, 除外={}件",
                    scopeType, scopeId, rejectedByScope);
        }
        if (ownScopeKeys.isEmpty()) {
            return body;
        }

        // 関門2: 台帳（blog_media_uploads）照合。照会失敗は fail-closed（presign しない）。
        Set<String> registeredKeys;
        try {
            registeredKeys = blogMediaUploadRepository.findByS3KeyIn(ownScopeKeys).stream()
                    .map(BlogMediaUploadEntity::getS3Key)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (Exception e) {
            // 検証できない以上 presign しない。本文は失わずそのまま返す（記事全体は 500 にしない）。
            log.warn("本文メディア: 台帳照合に失敗したため presign を見送る（fail-closed）: scope={}/{}",
                    scopeType, scopeId, e);
            return body;
        }

        // 重要: ここで Set.contains（Java の case-sensitive 比較）により再度絞り込むことは
        // 「無駄な二度絞り」ではなく意図的な防壁である。MySQL の既定照合順序は
        // case-insensitive のため、findByS3KeyIn に "blog/TEAM/12/AAAA.PNG" を渡すと
        // 台帳の "blog/team/12/aaaa.png" 行（大文字小文字違いの他人のキー）が誤ってヒットし
        // registeredKeys に含まれてしまう可能性がある。このフィルタが無いと、大文字小文字を
        // 変えたキーを本文に手書きするだけで他人のファイルの署名 URL を引ける穴になる。
        // 削除禁止（DB 照合順序に依存せず Java 側で厳密一致を担保する最後の砦）。
        List<String> verifiedKeys = ownScopeKeys.stream()
                .filter(registeredKeys::contains)
                .toList();
        if (verifiedKeys.size() < ownScopeKeys.size()) {
            log.warn("本文メディア: 台帳に存在しない r2Key を presign 対象から除外: scope={}/{}, 除外={}件",
                    scopeType, scopeId, ownScopeKeys.size() - verifiedKeys.size());
        }
        if (verifiedKeys.isEmpty()) {
            return body;
        }

        // 性能: 本文 1 件につき resolveAll をちょうど 1 回だけ呼ぶ（N+1 presign の防止）。
        Map<String, String> resolvedUrls;
        try {
            resolvedUrls = mediaUrlResolver.resolveAll(verifiedKeys);
        } catch (Exception e) {
            log.warn("本文メディア: 署名URLの一括解決に失敗したため本文を素通しする: scope={}/{}",
                    scopeType, scopeId, e);
            return body;
        }
        if (resolvedUrls == null || resolvedUrls.isEmpty()) {
            log.warn("本文メディア: 署名URLが 1 件も解決できなかった: scope={}/{}, 対象={}件",
                    scopeType, scopeId, verifiedKeys.size());
            return body;
        }
        if (resolvedUrls.size() < verifiedKeys.size()) {
            // 解決できなかったキーは本文中にそのまま残す（黙って消さない）。
            log.warn("本文メディア: 一部の署名URLが解決できなかった: scope={}/{}, 未解決={}件",
                    scopeType, scopeId, verifiedKeys.size() - resolvedUrls.size());
        }

        return replaceKeys(body, resolvedUrls);
    }

    /**
     * 関門1: キーが {@code blog/{scopeType}/{scopeId}/...} 配下へ<b>厳密に</b>属するかを判定する。
     *
     * <p>前方一致（{@code startsWith}）ではなく<b>セグメント単位の完全一致</b>で判定する。
     * 前方一致だと {@code blog/TEAM/12} が {@code blog/TEAM/123/x.png} に一致してしまい、
     * 隣接 ID のチームの画像が漏洩する。</p>
     *
     * <p>{@code ../} などの相対セグメントを含むキーは正規化前後で表現が変わるため一律に拒否する
     * （アップロード経路が発行する正規キーには相対セグメントが現れない）。
     * これにより {@code blog/TEAM/12/../99/x.png} での脱出を防ぐ。</p>
     *
     * <p><b>パーセントエンコードも同様に一律拒否する</b>（例: {@code %2e%2e} は文字列としては
     * {@code ".."} と一致しないため、上記の {@code ".."} 完全一致チェックだけでは素通りする）。
     * アップロード経路が発行する正規キー（{@code blog/{scopeType}/{scopeId}/{uuid}.{ext}}）には
     * {@code %} が現れないため、正当なキーを巻き添えにしない。
     * 「デコードして正規化してから比較する」方式は正規化ロジック自体にバグがあれば即座に穴になるため
     * 採らない。あくまで<b>正規形でないものを拒否する</b>という fail-closed の方針を維持する
     * （関門2 が将来 S3 側で {@code %2e%2e} を正規化する実装に差し替わった場合でも、
     * 関門1 単独でこの経路を防ぐため）。</p>
     */
    private boolean belongsToScope(String key, StorageScopeType scopeType, Long scopeId) {
        if (key == null || scopeType == null || scopeId == null) {
            return false;
        }
        String[] segments = key.split("/", -1);
        // blog / {scopeType} / {scopeId} / {ファイル名...} の最低 4 セグメントが必要。
        if (segments.length < 4) {
            return false;
        }
        for (String segment : segments) {
            // 空セグメント（"//"・先頭スラッシュ）と相対セグメントを含むキーは正規形ではない。
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return false;
            }
            // パーセントエンコード（%2e%2e 等）を含むキーも正規形ではないため一律拒否する。
            if (segment.indexOf('%') >= 0) {
                return false;
            }
        }
        return "blog".equals(segments[0])
                && scopeType.name().equals(segments[1])
                && String.valueOf(scopeId).equals(segments[2]);
    }

    /** 本文中のキー出現箇所（画像記法・src 属性）を署名 URL へ置換する。 */
    private String replaceKeys(String body, Map<String, String> resolvedUrls) {
        Matcher matcher = MEDIA_KEY_PATTERN.matcher(body);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String key = extractKeyGroup(matcher);
            String url = key == null ? null : resolvedUrls.get(key);
            if (url == null) {
                // 未解決キーは元のまま残す。
                matcher.appendReplacement(out, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            // マッチ全体のうちキー部分だけを署名 URL へ差し替える（記法の外側は保持）。
            int matchStart = matcher.start();
            int keyGroup = matcher.group(1) != null ? 1 : 2;
            String replaced = matcher.group().substring(0, matcher.start(keyGroup) - matchStart)
                    + url
                    + matcher.group().substring(matcher.end(keyGroup) - matchStart);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replaced));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** 画像記法（group 1）と src 属性（group 2）のどちらでマッチしたかを吸収してキーを返す。 */
    private static String extractKeyGroup(Matcher matcher) {
        return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    }
}
