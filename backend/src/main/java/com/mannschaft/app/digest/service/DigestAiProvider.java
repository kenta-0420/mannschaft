package com.mannschaft.app.digest.service;

import com.mannschaft.app.digest.DigestStyle;

import java.util.List;
import java.util.Map;

/**
 * AI ダイジェスト生成プロバイダーインターフェース。
 * Claude API / OpenAI 等の AI プロバイダーを抽象化する。
 */
public interface DigestAiProvider {

    /**
     * AI によるダイジェスト生成結果。
     */
    record AiDigestResult(
            String title,
            String body,
            String excerpt,
            String aiModel,
            int inputTokens,
            int outputTokens
    ) {}

    /**
     * タイムライン投稿を AI で要約してダイジェストを生成する。
     *
     * @param posts           要約対象の投稿データリスト
     * @param style           生成スタイル
     * @param language        生成言語（ja / en）
     * @param customPrompt    カスタムプロンプト（nullable）
     * @param previousBody    前回ダイジェストの本文（差分ハイライト用、nullable）
     * @param includeReactions リアクション情報を含めるか
     * @param includePolls    投票結果を含めるか
     * @return AI 生成結果
     */
    AiDigestResult generate(
            List<Map<String, Object>> posts,
            DigestStyle style,
            String language,
            String customPrompt,
            String previousBody,
            boolean includeReactions,
            boolean includePolls
    );
}
