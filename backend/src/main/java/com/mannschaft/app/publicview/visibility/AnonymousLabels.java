package com.mannschaft.app.publicview.visibility;

/**
 * 未ログイン / 非メンバー向け汎用ラベルの定数。
 *
 * <p>F19.1 §3 用語定義 / §4.6.1 開示マトリクスで規定される「汎用ラベル」。
 * 投稿種別に応じて「投稿者」「メンバー」「主催者」を使い分ける運用想定だが、Phase 1 では
 * 投稿系の最も一般的な「投稿者」のみ提供する（イベント主催者識別は Phase 4 で拡張）。</p>
 *
 * <p>i18n はフロントエンド側で行うため、本定数はサーバー側では検索キー的に扱う識別子として
 * 日本語固定値を返す（フロントは表示時にロケール辞書から引き直す前提）。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §3 / §4.6.1</p>
 */
public final class AnonymousLabels {

    /** 投稿者用の汎用ラベル。 */
    public static final String POSTER = "投稿者";

    /** メンバー用の汎用ラベル。 */
    public static final String MEMBER = "メンバー";

    /** 主催者用の汎用ラベル（Phase 4 で実利用予定）。 */
    public static final String HOST = "主催者";

    private AnonymousLabels() {
        // ユーティリティクラス: インスタンス化禁止
    }
}
