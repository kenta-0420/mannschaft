package com.mannschaft.app.cms.media;

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
 * <p><b>背景（画像URL根治 Phase 3）</b>: 従来はフロントエンド（{@code BlogMediaUploader.vue}）が
 * 公開ベース URL を連結した絶対 URL を本文へ焼き込んでいたが、そのベース URL が未宣言のため
 * {@code /blog/TEAM/12/xxx.png} という壊れた相対パスが本文へ永続保存されていた。
 * マスター御裁可により配信は署名 URL に統一する。署名 URL には有効期限があるため
 * 本文へ URL を焼き込むことはできない。<b>本文には r2Key を保存し、取得時に本部品で解決する</b>。</p>
 *
 * <p>{@code BulletinAttachmentService} が取得時に都度 presign する方式を範とする
 * （{@link MediaUrlResolver} の Javadoc 参照）。</p>
 *
 * <p><b>実装契約（出陣で満たすこと）</b>:</p>
 * <ul>
 *   <li>本文 1 件につき {@link MediaUrlResolver#resolveAll} を<b>ちょうど 1 回</b>呼ぶこと。
 *       画像ごとに {@code resolve} をループ呼びしてはならない（N+1 presign の防止）。</li>
 *   <li>投稿自身のスコープ（{@code blog/{scopeType}/{scopeId}/}）に属さないキーは
 *       <b>presign 対象へ含めてはならない</b>。本文は利用者が自由に編集できるため、
 *       他人・他スコープのキーを手書きされた場合に署名 URL を発行すると情報漏洩になる。</li>
 *   <li>解決できなかったキーは本文中でそのまま残し、例外を伝播させないこと（記事全体を 500 にしない）。
 *       ただし握りつぶさず {@code log.warn} で記録すること。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogBodyMediaResolver {

    private final MediaUrlResolver mediaUrlResolver;

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
     * <p>投稿自身のスコープに属さないキーは置換せず、presign 対象にも含めない（越境防止）。</p>
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
