package com.mannschaft.app.digest.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * TEMPLATE スタイルのダイジェスト生成器。
 * AI を使用せず、統計データをテンプレートに流し込んでダイジェストを生成する。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class TemplateDigestGenerator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * テンプレートベースのダイジェスト生成結果。
     */
    public record TemplateResult(
            String title,
            String body,
            String excerpt
    ) {}

    /**
     * 統計データを基にテンプレートでダイジェストを生成する。
     *
     * @param scopeName   スコープ名（チーム名/組織名）
     * @param periodStart 期間開始
     * @param periodEnd   期間終了
     * @param posts       対象投稿データ
     * @return テンプレート生成結果
     */
    public TemplateResult generate(
            String scopeName,
            LocalDateTime periodStart,
            LocalDateTime periodEnd,
            List<Map<String, Object>> posts) {

        int totalPosts = posts.size();
        long uniqueAuthors = posts.stream()
                .map(p -> p.get("authorId"))
                .distinct()
                .count();
        long totalReactions = posts.stream()
                .mapToLong(p -> ((Number) p.getOrDefault("reactionCount", 0)).longValue())
                .sum();
        long totalReplies = posts.stream()
                .mapToLong(p -> ((Number) p.getOrDefault("replyCount", 0)).longValue())
                .sum();

        // エンゲージメント上位3投稿
        List<Map<String, Object>> topPosts = posts.stream()
                .sorted((a, b) -> {
                    long engA = ((Number) a.getOrDefault("reactionCount", 0)).longValue()
                              + ((Number) a.getOrDefault("replyCount", 0)).longValue();
                    long engB = ((Number) b.getOrDefault("reactionCount", 0)).longValue()
                              + ((Number) b.getOrDefault("replyCount", 0)).longValue();
                    return Long.compare(engB, engA);
                })
                .limit(3)
                .toList();

        String startDate = periodStart.format(DATE_FORMATTER);
        String endDate = periodEnd.format(DATE_FORMATTER);

        String title = scopeName + " ダイジェスト（" + startDate + " 〜 " + endDate + "）";

        StringBuilder body = new StringBuilder();
        body.append("## 期間の概要\n");
        body.append("- 投稿数: ").append(totalPosts).append("件（").append(uniqueAuthors).append("名が参加）\n");
        body.append("- リアクション: ").append(totalReactions).append("件 / 返信: ").append(totalReplies).append("件\n");
        body.append("\n## 注目の投稿\n");

        int rank = 1;
        for (Map<String, Object> post : topPosts) {
            String authorName = (String) post.getOrDefault("authorName", "不明");
            long reactionCount = ((Number) post.getOrDefault("reactionCount", 0)).longValue();
            long replyCount = ((Number) post.getOrDefault("replyCount", 0)).longValue();
            String content = (String) post.getOrDefault("content", "");
            String preview = content.length() > 100 ? content.substring(0, 100) + "..." : content;

            body.append("### ").append(rank).append(". ").append(authorName)
                    .append(" さんの投稿（").append(reactionCount).append(" ").append(replyCount).append("）\n");
            body.append("> ").append(preview).append("\n\n");
            rank++;
        }

        String excerpt = totalPosts + "件の投稿から注目トピックをピックアップ";

        log.info("TEMPLATE ダイジェスト生成完了: scope={}, posts={}", scopeName, totalPosts);
        return new TemplateResult(title, body.toString(), excerpt);
    }
}
