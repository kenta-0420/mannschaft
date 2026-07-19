package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.cms.repository.BlogPostRevisionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 既存の破損した記事本文（絶対パス化した R2 キー）を相対キーへ補正するバックフィル。
 *
 * <p><b>背景</b>: 公開ベース URL が未宣言だったため、フロントエンドが空文字を連結して
 * {@code ![](/blog/TEAM/12/xxx.png)} という先頭スラッシュ付きの壊れたパスを本文へ焼き込んでいた。
 * これを {@code ![](blog/TEAM/12/xxx.png)} へ補正し、{@link BlogBodyMediaResolver} が
 * 取得時に署名 URL へ解決できる状態に戻す。</p>
 *
 * <p><b>実装契約（出陣で満たすこと）</b>:</p>
 * <ul>
 *   <li>{@code blog_posts.body} だけでなく <b>{@code blog_post_revisions.body} も同時に補正</b>すること。
 *       版管理で本文が複製されているため、片方だけ直すと「版を戻した瞬間に破損が復活する」。</li>
 *   <li>補正後の r2Key が {@code blog_media_uploads.s3_key} に実在するか検証し、
 *       実在しないキーは {@link BackfillResult#unknownKeys()} で報告すること（黙って捨てない）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlogBodyMediaBackfillService {

    private final BlogPostRepository blogPostRepository;
    private final BlogPostRevisionRepository blogPostRevisionRepository;
    private final BlogMediaUploadRepository blogMediaUploadRepository;

    /**
     * 本文中の絶対パス化したキー（{@code ](/blog/} ・ {@code src="/blog/}）を相対キーへ補正する。
     *
     * @param body 記事本文（null 可）
     * @return 補正後の本文。body が null なら null
     */
    public static String normalizeLegacyKeys(String body) {
        // TODO(出陣): 未実装。試練フェーズの骨格スタブ（本文を素通しする）。
        return body;
    }

    /**
     * 全記事・全リビジョンを走査し、破損した本文を補正する。
     *
     * @return 補正件数と、実在しなかったキーの一覧
     */
    @Transactional
    public BackfillResult backfillLegacyAbsoluteKeys() {
        // TODO(出陣): 未実装。試練フェーズの骨格スタブ。
        return new BackfillResult(0, 0, List.of());
    }

    /**
     * バックフィル結果。
     *
     * @param postsFixed     補正した blog_posts の件数
     * @param revisionsFixed 補正した blog_post_revisions の件数
     * @param unknownKeys    blog_media_uploads.s3_key に実在しなかったキー一覧
     */
    public record BackfillResult(int postsFixed, int revisionsFixed, List<String> unknownKeys) {}
}
