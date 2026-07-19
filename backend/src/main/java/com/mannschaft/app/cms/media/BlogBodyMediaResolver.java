package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
        // TODO(出陣): 未実装。試練フェーズの骨格スタブ。
        return List.of();
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
        // TODO(出陣): 未実装。試練フェーズの骨格スタブ（本文を素通しする）。
        return body;
    }
}
