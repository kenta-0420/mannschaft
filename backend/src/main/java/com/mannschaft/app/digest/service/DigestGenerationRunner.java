package com.mannschaft.app.digest.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.event.DigestGenerationNotificationEvent;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * AI ダイジェスト生成結果（GENERATED／FAILED）を独立トランザクションで確定する実行 Bean
 * （Issue #2997 / CMP-260827-1152 G7-a。金型: {@code ShiftPreferenceReminderRunner}）。
 *
 * <h2>是正前の欠陥</h2>
 * <p>是正前の {@code DigestAsyncExecutor#generateAiDigestAsync} は、AI プロバイダへのネットワーク呼び出しまで含めて
 * メソッド全体を 1 つの {@code @Transactional} で包み、その内側で完了／失敗通知を同一TXで作っていた。通知の DB 例外が
 * rollback-only を残すと、生成成功の GENERATED への遷移ごと巻き戻り、外側の catch が FAILED に落とした
 * （生成は成功したのに「失敗」になる）。また成功側には {@code triggeredBy == null} の検査が無く、NPE で同じ経路に落ちた。</p>
 *
 * <h2>現在の契約</h2>
 * <ul>
 *   <li>executor は TX を持たない（AI 呼び出しも TX の外）。状態確定だけを本 Bean の {@link Propagation#REQUIRES_NEW}
 *       で行い、通知配送要求は<b>その確定TXの内側で publish するだけ</b>にする。
 *       配送は commit 後（{@code AFTER_COMMIT}）に非同期リスナーが行うので、確定TXが rollback すれば通知は作られず、
 *       通知の失敗が GENERATED／FAILED の確定を巻き戻すこともない。</li>
 *   <li>{@code triggeredBy == null} のときは配送先が無いため publish しない（確定だけを行う）。</li>
 *   <li>{@link #fail} は GENERATING のダイジェストだけを FAILED にする。すでに GENERATED 等へ確定済みなら何もしない
 *       （確定後に起きた後続処理の例外が、確定済みの結果を FAILED で上書きしない）。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigestGenerationRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final TimelineDigestRepository digestRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 生成結果を GENERATED として確定し、完了通知の配送要求を publish する。
     *
     * @param digestId      対象ダイジェストID
     * @param result        AI 生成結果
     * @param sourcePostIds ソース投稿ID（JSON 文字列として保存する）
     * @param postCount     対象投稿数
     * @param scopeType     スコープ種別の enum 名（通知の遷移先スコープ用）
     * @param scopeId       スコープID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long digestId, DigestAiProvider.AiDigestResult result, List<Long> sourcePostIds,
                         int postCount, String scopeType, Long scopeId) {
        TimelineDigestEntity digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new IllegalStateException("Digest not found: " + digestId));
        digest.markGenerated(result.title(), result.body(), result.excerpt(),
                result.aiModel(), result.inputTokens(), result.outputTokens(), postCount);
        try {
            digest.setSourcePostIds(OBJECT_MAPPER.writeValueAsString(sourcePostIds));
        } catch (Exception e) {
            log.warn("sourcePostIds のシリアライズに失敗: {}", e.getMessage());
        }
        digestRepository.save(digest);
        publishIfRecipientExists(digest, DigestGenerationNotificationEvent.Kind.COMPLETED, scopeType, scopeId);
    }

    /**
     * ダイジェストを FAILED として確定し、失敗通知の配送要求を publish する。
     *
     * <p>GENERATING でなければ（すでに確定済み・不存在）何もしない。</p>
     *
     * @return FAILED へ遷移させた場合 {@code true}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(Long digestId, String errorMessage, String scopeType, Long scopeId) {
        TimelineDigestEntity digest = digestRepository.findById(digestId).orElse(null);
        if (digest == null || digest.getStatus() != DigestStatus.GENERATING) {
            return false;
        }
        digest.markFailed(errorMessage);
        digestRepository.save(digest);
        publishIfRecipientExists(digest, DigestGenerationNotificationEvent.Kind.FAILED, scopeType, scopeId);
        return true;
    }

    private void publishIfRecipientExists(TimelineDigestEntity digest,
                                          DigestGenerationNotificationEvent.Kind kind,
                                          String scopeType, Long scopeId) {
        if (digest.getTriggeredBy() == null) {
            log.info("起動者不明のため AI ダイジェスト通知は配送しない: id={}, kind={}", digest.getId(), kind);
            return;
        }
        eventPublisher.publishEvent(new DigestGenerationNotificationEvent(
                digest.getId(), kind, digest.getTriggeredBy(), scopeType, scopeId));
    }
}
