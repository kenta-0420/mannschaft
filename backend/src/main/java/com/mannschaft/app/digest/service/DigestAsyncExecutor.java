package com.mannschaft.app.digest.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ダイジェスト非同期生成の実行サービス。
 * {@link DigestGenerationService} から分離し、Spring の @Async プロキシが正しく動作するようにする。
 *
 * <p>Issue #2997 / CMP-260827-1152 G7-a: 本クラスは<b>トランザクションを持たない</b>。是正前はメソッド全体
 * （AI プロバイダへのネットワーク呼び出しを含む）を 1 つの {@code @Transactional} で包み、その内側で完了／失敗通知を
 * 同一TXで作っていたため、通知の DB 例外が生成成功の GENERATED ごと巻き戻して FAILED に落としていた。
 * 現在は GENERATED／FAILED の確定と通知配送要求の publish を {@link DigestGenerationRunner}
 * （{@code REQUIRES_NEW}）に任せ、通知は commit 後に {@code DigestGenerationNotificationListener} が配送する。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DigestAsyncExecutor {

    private final TimelineDigestRepository digestRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final DigestAiProvider aiProvider;
    private final NameResolverService nameResolverService;
    private final DigestProperties digestProperties;
    private final DigestGenerationRunner generationRunner;

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

            // AI 呼び出しはトランザクションの外で行う（長時間のネットワーク呼び出しで接続を握らない）。
            DigestAiProvider.AiDigestResult result = aiProvider.generate(
                    posts, digest.getDigestStyle(), lang, customPrompt,
                    previousBody, reactions, polls);

            // GENERATED の確定と完了通知の配送要求は別Bean（REQUIRES_NEW）で行う。
            generationRunner.complete(digestId, result, sourcePostIds, posts.size(), scopeType, scopeId);

            log.info("AI ダイジェスト生成完了: id={}, model={}", digestId, result.aiModel());

        } catch (Exception e) {
            log.error("AI ダイジェスト生成処理が失敗: id={}", digestId, e);
            try {
                // GENERATING のときだけ FAILED に確定する（確定済みの結果は上書きしない）。
                generationRunner.fail(digestId, e.getMessage(), scopeType, scopeId);
            } catch (Exception saveEx) {
                // 握りつぶさず ERROR で観測可能にする。GENERATING のまま残り、
                // generating_timeout_at 経過後の既存タイムアウト回収経路が拾う。
                log.error("ダイジェスト失敗ステータスの保存にも失敗: id={}", digestId, saveEx);
            }
        }
    }
}
