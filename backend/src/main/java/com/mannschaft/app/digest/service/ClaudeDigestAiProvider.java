package com.mannschaft.app.digest.service;

import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.DigestStyle;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Claude API を使用したダイジェスト生成プロバイダー。
 * Claude API の tool_use（Structured Output）でタイトル・本文・抜粋を生成する。
 *
 * TODO: Phase 6 実装時に Claude API クライアントを統合する。
 *       現在はプレースホルダー実装。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClaudeDigestAiProvider implements DigestAiProvider {

    private final DigestProperties digestProperties;

    @Override
    public AiDigestResult generate(
            List<Map<String, Object>> posts,
            DigestStyle style,
            String language,
            String customPrompt,
            String previousBody,
            boolean includeReactions,
            boolean includePolls) {

        log.info("Claude API ダイジェスト生成: style={}, posts={}, model={}",
                style, posts.size(), digestProperties.getAiModel());

        // TODO: Claude API 呼び出し実装
        // 1. システムプロンプト構築（スタイル別テンプレート + 差分ハイライト指示）
        // 2. ユーザープロンプト構築（投稿データ + customPrompt）
        // 3. tool_use で generate_digest ツールを定義
        // 4. Claude API 呼び出し（claude-haiku-4-5）
        // 5. tool_use レスポンスから title / body / excerpt を取得
        // 6. AI 出力サニタイズ（HTML タグ除去、Markdown XSS 対策）
        // 7. プロンプトインジェクション対策（禁止ワードフィルタ）

        String titlePrefix = switch (style) {
            case SUMMARY -> "要約: ";
            case NARRATIVE -> "";
            case HIGHLIGHTS -> "ハイライト: ";
            default -> "";
        };

        return new AiDigestResult(
                titlePrefix + "ダイジェスト（プレースホルダー）",
                "## AI 生成本文\n\nこの内容は Claude API 統合後に実際の AI 生成結果に置き換わります。\n\n対象投稿数: " + posts.size(),
                "プレースホルダー抜粋（" + posts.size() + "件の投稿から生成）",
                digestProperties.getAiModel(),
                0,
                0
        );
    }
}
