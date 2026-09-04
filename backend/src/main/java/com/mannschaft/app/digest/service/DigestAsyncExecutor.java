package com.mannschaft.app.digest.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ダイジェスト非同期生成の実行サービス。
 * {@link DigestGenerationService} から分離し、Spring の @Async プロキシが正しく動作するようにする。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DigestAsyncExecutor {

    private final TimelineDigestRepository digestRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final DigestAiProvider aiProvider;
    private final NotificationHelper notificationHelper;
    private final NameResolverService nameResolverService;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;
    private final DigestProperties digestProperties;

    /**
     * AI スタイルのダイジェストを非同期生成する。
     * 別クラスに切り出すことで Spring AOP プロキシ経由の @Async 呼び出しを保証する。
     *
     * <p>Issue #2990 L4: executor を {@code job-pool} に明示する（是正前は無指定で
     * {@code @Primary} の {@code event-pool} へ載っていた）。本メソッドは AI プロバイダへの
     * ネットワーク呼び出しを含む長時間処理であり、{@code event-pool}（core=2/max=5）を占有すると
     * 他ドメインの AFTER_COMMIT 通知配送リスナーが軒並み詰まる（Issue #2953 で問題化した
     * event-pool の自己飽和と同じ経路）。{@code job-pool} は「定期実行タスクや重い処理」用であり、
     * 通知配送と実行資源を分離できる。</p>
     */
    @Async("job-pool")
    @Transactional
    public void generateAiDigestAsync(Long digestId, String scopeType, Long scopeId,
                                       String digestStyle, String customPrompt,
                                       Boolean includeReactions, Boolean includePolls,
                                       Boolean includeDiffFromPrevious, String language) {
        try {
            TimelineDigestEntity digest = digestRepository.findById(digestId)
                    .orElseThrow(() -> new IllegalStateException("Digest not found: " + digestId));

            // タイムラインから投稿データを取得し、期間内でフィルタ
            int maxPosts = digestProperties.getDefaults().getMaxPostsPerDigest();
            List<TimelinePostEntity> timelinePosts = timelinePostRepository.findFeedByScopeType(
                    PostScopeType.valueOf(scopeType), scopeId, PageRequest.of(0, maxPosts));
            List<TimelinePostEntity> filteredPosts = timelinePosts.stream()
                    .filter(p -> p.getCreatedAt() != null
                            && !p.getCreatedAt().isBefore(digest.getPeriodStart())
                            && !p.getCreatedAt().isAfter(digest.getPeriodEnd()))
                    .toList();

            // ユーザー名をバッチ解決
            Set<Long> userIds = filteredPosts.stream()
                    .map(TimelinePostEntity::getUserId)
                    .collect(Collectors.toSet());
            Map<Long, String> userNames = nameResolverService.resolveUserDisplayNames(userIds);

            // 投稿データを Map に変換（AI プロバイダーへの入力）
            List<Map<String, Object>> posts = new ArrayList<>();
            List<Long> sourcePostIds = new ArrayList<>();
            for (TimelinePostEntity post : filteredPosts) {
                Map<String, Object> postMap = new HashMap<>();
                postMap.put("id", post.getId());
                postMap.put("content", post.getContent());
                postMap.put("createdAt", post.getCreatedAt());
                postMap.put("userId", post.getUserId());
                postMap.put("authorName", userNames.getOrDefault(post.getUserId(), "不明"));
                postMap.put("reactionCount", post.getReactionCount());
                postMap.put("replyCount", post.getReplyCount());
                posts.add(postMap);
                sourcePostIds.add(post.getId());
            }

            boolean reactions = includeReactions != null ? includeReactions : true;
            boolean polls = includePolls != null ? includePolls : true;
            String lang = language != null ? language : "ja";

            // 差分ハイライト用の前回ダイジェスト
            String previousBody = null;
            if (includeDiffFromPrevious != null && includeDiffFromPrevious) {
                List<TimelineDigestEntity> previous = digestRepository.findLatestPublishedByScope(
                        digest.getScopeType(), digest.getScopeId());
                if (!previous.isEmpty()) {
                    previousBody = previous.get(0).getGeneratedBody();
                }
            }

            DigestAiProvider.AiDigestResult result = aiProvider.generate(
                    posts, digest.getDigestStyle(), lang, customPrompt,
                    previousBody, reactions, polls);

            digest.markGenerated(result.title(), result.body(), result.excerpt(),
                    result.aiModel(), result.inputTokens(), result.outputTokens(), posts.size());
            // ソース投稿 ID を JSON 文字列として保存
            try {
                com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
                digest.setSourcePostIds(om.writeValueAsString(sourcePostIds));
            } catch (Exception e) {
                log.warn("sourcePostIds のシリアライズに失敗: {}", e.getMessage());
            }
            digestRepository.save(digest);

            // ダイジェスト生成完了通知（作成者に送信）
            NotificationScopeType notifScope = "TEAM".equals(scopeType)
                    ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
            Locale completedLocale = Locale.forLanguageTag(userLocaleCache.getLocale(digest.getTriggeredBy()));
            notificationHelper.notify(digest.getTriggeredBy(), "DIGEST_COMPLETED",
                    messageSource.getMessage(
                            "notification.digest.completed.title", null,
                            "ダイジェスト生成完了", completedLocale),
                    messageSource.getMessage(
                            "notification.digest.completed.body", null,
                            "AIダイジェストの生成が完了しました。", completedLocale),
                    "DIGEST", digestId, notifScope, scopeId,
                    "/digests/" + digestId, null);

            log.info("AI ダイジェスト生成完了: id={}, model={}", digestId, result.aiModel());

        } catch (Exception e) {
            log.error("AI ダイジェスト生成失敗: id={}", digestId, e);
            try {
                TimelineDigestEntity digest = digestRepository.findById(digestId).orElse(null);
                if (digest != null) {
                    digest.markFailed(e.getMessage());
                    digestRepository.save(digest);
                }
            } catch (Exception saveEx) {
                log.error("ダイジェスト失敗ステータスの保存にも失敗: id={}", digestId, saveEx);
            }

            // ダイジェスト生成失敗を作成者に通知
            TimelineDigestEntity failedDigest = digestRepository.findById(digestId).orElse(null);
            if (failedDigest != null && failedDigest.getTriggeredBy() != null) {
                NotificationScopeType notifScope = "TEAM".equals(scopeType)
                        ? NotificationScopeType.TEAM : NotificationScopeType.ORGANIZATION;
                Locale failedLocale = Locale.forLanguageTag(
                        userLocaleCache.getLocale(failedDigest.getTriggeredBy()));
                notificationHelper.notify(failedDigest.getTriggeredBy(), "DIGEST_FAILED",
                        NotificationPriority.HIGH,
                        messageSource.getMessage(
                                "notification.digest.failed.title", null,
                                "ダイジェスト生成失敗", failedLocale),
                        messageSource.getMessage(
                                "notification.digest.failed.body", null,
                                "AIダイジェストの生成に失敗しました。再試行してください。", failedLocale),
                        "DIGEST", digestId, notifScope, scopeId,
                        "/digests/" + digestId, null);
            }
        }
    }
}
