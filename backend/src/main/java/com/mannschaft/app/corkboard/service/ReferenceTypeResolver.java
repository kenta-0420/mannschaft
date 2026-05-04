package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.dto.PinnedCardReferenceResponse;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * F09.8.1 Phase 3 参照先タイプ別の {@code navigate_to} 生成と参照先メタデータ整形を担う Resolver。
 *
 * <p>設計書 §4.3 / §5.2 / D-8 に基づき、{@code reference_type} ごとのナビゲート先 URL 生成と
 * {@link PinnedCardReferenceResponse} 構築を集約する。新タイプ追加時はここの switch / マップに
 * ケースを追加するのみで、API スキーマや Service 層は変更不要。</p>
 *
 * <h3>対応タイプ（10 種）</h3>
 * <ul>
 *   <li>TIMELINE_POST &rarr; /timeline/posts/{id}</li>
 *   <li>BULLETIN_THREAD &rarr; /bulletin/threads/{id}</li>
 *   <li>BLOG_POST &rarr; /blog/{id}</li>
 *   <li>CHAT_MESSAGE &rarr; /chat/messages/{id}（チャネル ID は Phase 3 では取得せず id 単独）</li>
 *   <li>FILE &rarr; /files/{id}</li>
 *   <li>TEAM &rarr; /teams/{id}</li>
 *   <li>ORGANIZATION &rarr; /organizations/{id}</li>
 *   <li>EVENT &rarr; /schedules/{id}</li>
 *   <li>DOCUMENT &rarr; /documents/{id}</li>
 *   <li>URL &rarr; カードの url カラム値そのもの</li>
 * </ul>
 *
 * <p>未対応 type は {@code is_accessible = false} + {@code navigate_to = null} へフォールバック
 * （設計書 §4.3 末尾の注記参照）。</p>
 */
@Component
public class ReferenceTypeResolver {

    /** Phase 3 で取り扱う参照タイプの集合。{@code MEMO} / {@code SECTION_HEADER} は含めない。 */
    public static final Set<String> SUPPORTED_TYPES = Set.of(
            "TIMELINE_POST", "BULLETIN_THREAD", "BLOG_POST", "CHAT_MESSAGE",
            "FILE", "TEAM", "ORGANIZATION", "EVENT", "DOCUMENT", "URL"
    );

    /** タイプ別のナビゲート先テンプレート。{@code {id}} を ID で置換する。URL は別扱い。 */
    private static final Map<String, String> NAVIGATE_TEMPLATES = Map.ofEntries(
            Map.entry("TIMELINE_POST", "/timeline/posts/{id}"),
            Map.entry("BULLETIN_THREAD", "/bulletin/threads/{id}"),
            Map.entry("BLOG_POST", "/blog/{id}"),
            Map.entry("CHAT_MESSAGE", "/chat/messages/{id}"),
            Map.entry("FILE", "/files/{id}"),
            Map.entry("TEAM", "/teams/{id}"),
            Map.entry("ORGANIZATION", "/organizations/{id}"),
            Map.entry("EVENT", "/schedules/{id}"),
            Map.entry("DOCUMENT", "/documents/{id}")
    );

    /**
     * ピン止めカードから参照先 DTO を組み立てる。
     *
     * <p>判定ルール:</p>
     * <ol>
     *   <li>カードに {@code reference_type} が無い（MEMO 等）&rarr; 呼び出し側で {@code reference = null} とする想定。本メソッドは呼ばれない</li>
     *   <li>{@code reference_type = URL} &rarr; カードの url カラムをそのまま navigate_to に設定</li>
     *   <li>未対応 type &rarr; {@code is_accessible = false} + {@code navigate_to = null} のフォールバック（snapshot は表示）</li>
     *   <li>対応 type だが閲覧権限なし &rarr; {@code is_accessible = false} + {@code navigate_to = null}</li>
     *   <li>対応 type かつ閲覧権限あり &rarr; テンプレートから navigate_to 生成</li>
     * </ol>
     *
     * @param card         カードエンティティ
     * @param isAccessible 参照先への閲覧権限チェック結果（呼び出し側でバッチ判定済み）
     * @param isDeleted    参照先が論理削除されているか（同様にバッチ判定済み）
     * @return 参照先 DTO（カードが {@code reference_type} を持たない場合は呼び出し側で null を返すこと）
     */
    public PinnedCardReferenceResponse resolve(CorkboardCardEntity card,
                                                boolean isAccessible,
                                                boolean isDeleted) {
        String type = card.getReferenceType();
        if (type == null) {
            // 呼び出し側で除外済みの想定だが防御的に null を返す
            return null;
        }

        // URL カードは特別扱い（ID なし、url カラム参照）
        if ("URL".equals(type)) {
            String url = card.getUrl();
            return new PinnedCardReferenceResponse(
                    type,
                    null,
                    null, // URL カードは snapshot を持たない
                    null,
                    Boolean.TRUE, // URL は外部 URL のため常にアクセス可能扱い（権限二重チェック対象外）
                    Boolean.FALSE, // URL カードは論理削除概念なし
                    url, // navigate_to = url 値
                    url,
                    card.getOgTitle(),
                    card.getOgImageUrl());
        }

        // 未対応 type のフォールバック
        if (!SUPPORTED_TYPES.contains(type)) {
            return new PinnedCardReferenceResponse(
                    type,
                    card.getReferenceId(),
                    extractSnapshotTitle(card),
                    extractSnapshotExcerpt(card),
                    Boolean.FALSE,
                    Boolean.FALSE,
                    null,
                    null, null, null);
        }

        String navigateTo = isAccessible
                ? NAVIGATE_TEMPLATES.get(type).replace("{id}", String.valueOf(card.getReferenceId()))
                : null;

        return new PinnedCardReferenceResponse(
                type,
                card.getReferenceId(),
                extractSnapshotTitle(card),
                extractSnapshotExcerpt(card),
                isAccessible,
                isDeleted,
                navigateTo,
                null, null, null);
    }

    /**
     * カードの {@code content_snapshot} からタイトル相当を抽出する（v1.0 では先頭 1 行を採用）。
     * snapshot が null/空なら null を返す。
     */
    private String extractSnapshotTitle(CorkboardCardEntity card) {
        String snapshot = card.getContentSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            // フォールバックとしてカードの title を採用
            return card.getTitle();
        }
        int newlineIdx = snapshot.indexOf('\n');
        return newlineIdx < 0 ? snapshot : snapshot.substring(0, newlineIdx);
    }

    /**
     * カードの {@code content_snapshot} から本文抜粋（最大 200 文字）を抽出する。
     */
    private String extractSnapshotExcerpt(CorkboardCardEntity card) {
        String snapshot = card.getContentSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        return snapshot.length() <= 200 ? snapshot : snapshot.substring(0, 200);
    }
}
