package com.mannschaft.app.digest.service;

import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ダイジェスト非同期生成の実行サービス。
 * {@link DigestGenerationService} から分離し、Spring の @Async プロキシが正しく動作するようにする。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DigestAsyncExecutor {

    private final TimelineDigestRepository digestRepository;
    private final DigestAiProvider aiProvider;
    private final TemplateDigestGenerator templateGenerator;

    /**
     * AI スタイルのダイジェストを非同期生成する。
     * 別クラスに切り出すことで Spring AOP プロキシ経由の @Async 呼び出しを保証する。
     */
    @Async
    @Transactional
    public void generateAiDigestAsync(Long digestId, String scopeType, Long scopeId,
                                       String digestStyle, String customPrompt,
                                       Boolean includeReactions, Boolean includePolls,
                                       Boolean includeDiffFromPrevious, String language) {
        try {
            TimelineDigestEntity digest = digestRepository.findById(digestId)
                    .orElseThrow(() -> new IllegalStateException("Digest not found: " + digestId));

            // TODO: タイムラインから実際の投稿データを取得する
            List<Map<String, Object>> posts = new ArrayList<>();

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
            digestRepository.save(digest);

            // TODO: プッシュ通知（「ダイジェストの生成が完了しました」）
            // TODO: WebSocket ステータス通知

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

            // TODO: ADMIN にプッシュ通知（「ダイジェスト生成に失敗しました」）
        }
    }
}
