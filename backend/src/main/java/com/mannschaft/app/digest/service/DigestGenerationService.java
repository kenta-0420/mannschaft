package com.mannschaft.app.digest.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.digest.DigestErrorCode;
import com.mannschaft.app.digest.DigestMapper;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.DigestScopeType;
import com.mannschaft.app.digest.DigestStatus;
import com.mannschaft.app.digest.DigestStyle;
import com.mannschaft.app.digest.dto.AiQuotaResponse;
import com.mannschaft.app.digest.dto.DigestDetailResponse;
import com.mannschaft.app.digest.dto.DigestEditRequest;
import com.mannschaft.app.digest.dto.DigestGenerateRequest;
import com.mannschaft.app.digest.dto.DigestGenerateResponse;
import com.mannschaft.app.digest.dto.DigestListResponse;
import com.mannschaft.app.digest.dto.DigestPublishRequest;
import com.mannschaft.app.digest.dto.DigestPublishResponse;
import com.mannschaft.app.digest.dto.DigestRegenerateRequest;
import com.mannschaft.app.digest.dto.DigestSummaryResponse;
import com.mannschaft.app.digest.dto.DigestUsageResponse;
import com.mannschaft.app.digest.dto.StaleWarningResponse;
import com.mannschaft.app.digest.entity.TimelineDigestConfigEntity;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestConfigRepository;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ダイジェスト生成・プレビュー・公開サービス。
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DigestGenerationService {

    private final TimelineDigestRepository digestRepository;
    private final TimelineDigestConfigRepository configRepository;
    private final DigestAiProvider aiProvider;
    private final TemplateDigestGenerator templateGenerator;
    private final DigestMapper digestMapper;
    private final DigestProperties digestProperties;

    private static final int MAX_PERIOD_DAYS = 31;
    private static final int GENERATING_TIMEOUT_MINUTES = 5;

    /**
     * ダイジェストを手動生成する。
     * AI スタイルの場合は非同期で処理し、TEMPLATE の場合は同期で即座に返す。
     *
     * @throws BusinessException 期間不正、権限不足、重複、上限到達等
     */
    @Transactional
    public DigestGenerateResponse generate(DigestGenerateRequest request, Long userId) {
        DigestScopeType scopeType = DigestScopeType.valueOf(request.getScopeType());
        DigestStyle style = request.getDigestStyle() != null
                ? DigestStyle.valueOf(request.getDigestStyle())
                : DigestStyle.SUMMARY;

        // 期間バリデーション
        validatePeriod(request.getPeriodStart(), request.getPeriodEnd());

        // 並行生成チェック
        if (digestRepository.existsByScopeTypeAndScopeIdAndStatus(
                scopeType, request.getScopeId(), DigestStatus.GENERATING)) {
            throw new BusinessException(DigestErrorCode.DIGEST_009);
        }

        // 重複チェック
        if (digestRepository.existsByScopeAndPeriodAndStatusIn(
                scopeType, request.getScopeId(),
                request.getPeriodStart(), request.getPeriodEnd(),
                List.of(DigestStatus.GENERATED, DigestStatus.PUBLISHED))) {
            throw new BusinessException(DigestErrorCode.DIGEST_007);
        }

        // AI スタイルの月次上限チェック
        if (style != DigestStyle.TEMPLATE) {
            checkAiQuota(scopeType, request.getScopeId());
        }

        // config からデフォルト値を取得
        Optional<TimelineDigestConfigEntity> config = configRepository
                .findByScopeTypeAndScopeId(scopeType, request.getScopeId());

        Long configId = config.map(c -> c.getId()).orElse(null);

        // ダイジェストレコード作成
        TimelineDigestEntity digest = TimelineDigestEntity.builder()
                .configId(configId)
                .scopeType(scopeType)
                .scopeId(request.getScopeId())
                .periodStart(request.getPeriodStart())
                .periodEnd(request.getPeriodEnd())
                .digestStyle(style)
                .status(DigestStatus.GENERATING)
                .generatingTimeoutAt(LocalDateTime.now().plusMinutes(GENERATING_TIMEOUT_MINUTES))
                .triggeredBy(userId)
                .build();

        TimelineDigestEntity saved = digestRepository.save(digest);

        if (style == DigestStyle.TEMPLATE) {
            // TEMPLATE: 同期処理
            generateTemplateDigest(saved, config.orElse(null));
        } else {
            // AI スタイル: 非同期処理
            generateAiDigestAsync(saved, config.orElse(null), request.getCustomPromptSuffix());
        }

        // TODO: estimatedPostCount を実際の投稿クエリ結果から設定
        return new DigestGenerateResponse(saved.getId(), saved.getStatus().name(), null);
    }

    /**
     * ダイジェストをブログ下書きとして公開する。
     *
     * @throws BusinessException ダイジェスト不存在、ステータス不正
     */
    @Transactional
    public DigestPublishResponse publish(Long digestId, DigestPublishRequest request) {
        TimelineDigestEntity digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_011));

        if (digest.getStatus() != DigestStatus.GENERATED) {
            throw new BusinessException(DigestErrorCode.DIGEST_012);
        }

        // TODO: F06.1 の blog_posts に DRAFT 状態で INSERT
        // TODO: stale_warning の計算（source_post_ids の投稿の updated_at / deleted_at 検査）
        // TODO: blog_post_id を digest に紐付け

        TimelineDigestEntity published = digest.toBuilder()
                .status(DigestStatus.PUBLISHED)
                .build();
        digestRepository.save(published);

        log.info("ダイジェストを公開しました: id={}, scope={}:{}",
                digestId, digest.getScopeType(), digest.getScopeId());

        // TODO: 実際の blogPostId と slug を返す
        return new DigestPublishResponse(
                digestId,
                null, // blogPostId - F06.1 統合後に設定
                null, // blogPostSlug - F06.1 統合後に設定
                "DRAFT"
        );
    }

    /**
     * ダイジェスト履歴一覧を取得する。
     */
    public DigestListResponse list(String scopeType, Long scopeId, String status, Long cursor, Integer limit) {
        DigestScopeType scope = DigestScopeType.valueOf(scopeType);
        DigestStatus statusFilter = status != null ? DigestStatus.valueOf(status) : null;
        int pageLimit = limit != null ? limit : 20;

        List<TimelineDigestEntity> digests = digestRepository.findByScopeWithCursor(
                scope, scopeId, statusFilter, cursor);

        List<TimelineDigestEntity> page = digests.stream().limit(pageLimit + 1).toList();
        boolean hasNext = page.size() > pageLimit;
        List<TimelineDigestEntity> result = hasNext ? page.subList(0, pageLimit) : page;

        List<DigestSummaryResponse> data = result.stream()
                .map(digestMapper::toSummaryResponse)
                .toList();

        String nextCursor = hasNext && !result.isEmpty()
                ? result.get(result.size() - 1).getId().toString()
                : null;

        CursorPagedResponse.CursorMeta meta = new CursorPagedResponse.CursorMeta(nextCursor, hasNext, pageLimit);
        AiQuotaResponse aiQuota = buildAiQuota(scope, scopeId);

        return new DigestListResponse(data, meta, aiQuota);
    }

    /**
     * ダイジェスト詳細を取得する。
     */
    public DigestDetailResponse getDetail(Long digestId) {
        TimelineDigestEntity digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_011));
        return digestMapper.toDetailResponse(digest);
    }

    /**
     * ダイジェストを破棄する（GENERATED のみ）。
     */
    @Transactional
    public DigestDetailResponse discard(Long digestId) {
        TimelineDigestEntity digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_011));

        if (digest.getStatus() != DigestStatus.GENERATED) {
            throw new BusinessException(DigestErrorCode.DIGEST_012);
        }

        TimelineDigestEntity discarded = digest.toBuilder()
                .status(DigestStatus.DISCARDED)
                .build();
        digestRepository.save(discarded);

        log.info("ダイジェストを破棄しました: id={}", digestId);
        return digestMapper.toDetailResponse(discarded);
    }

    /**
     * ダイジェストを再生成する。元のダイジェストは DISCARDED に遷移する。
     */
    @Transactional
    public DigestGenerateResponse regenerate(Long digestId, DigestRegenerateRequest request, Long userId) {
        TimelineDigestEntity original = digestRepository.findById(digestId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_011));

        if (original.getStatus() != DigestStatus.GENERATED && original.getStatus() != DigestStatus.FAILED) {
            throw new BusinessException(DigestErrorCode.DIGEST_013);
        }

        // 並行生成チェック
        if (digestRepository.existsByScopeTypeAndScopeIdAndStatus(
                original.getScopeType(), original.getScopeId(), DigestStatus.GENERATING)) {
            throw new BusinessException(DigestErrorCode.DIGEST_009);
        }

        DigestStyle newStyle = request.getDigestStyle() != null
                ? DigestStyle.valueOf(request.getDigestStyle())
                : original.getDigestStyle();

        // AI スタイルの月次上限チェック
        if (newStyle != DigestStyle.TEMPLATE) {
            checkAiQuota(original.getScopeType(), original.getScopeId());
        }

        // 元のダイジェストを DISCARDED に遷移
        TimelineDigestEntity discarded = original.toBuilder()
                .status(DigestStatus.DISCARDED)
                .build();
        digestRepository.save(discarded);

        // 新しいダイジェストを作成
        TimelineDigestEntity newDigest = TimelineDigestEntity.builder()
                .configId(original.getConfigId())
                .scopeType(original.getScopeType())
                .scopeId(original.getScopeId())
                .periodStart(original.getPeriodStart())
                .periodEnd(original.getPeriodEnd())
                .digestStyle(newStyle)
                .status(DigestStatus.GENERATING)
                .generatingTimeoutAt(LocalDateTime.now().plusMinutes(GENERATING_TIMEOUT_MINUTES))
                .triggeredBy(userId)
                .build();

        TimelineDigestEntity saved = digestRepository.save(newDigest);

        Optional<TimelineDigestConfigEntity> config = configRepository
                .findByScopeTypeAndScopeId(original.getScopeType(), original.getScopeId());

        if (newStyle == DigestStyle.TEMPLATE) {
            generateTemplateDigest(saved, config.orElse(null));
        } else {
            generateAiDigestAsync(saved, config.orElse(null), request.getCustomPromptSuffix());
        }

        log.info("ダイジェストを再生成しました: originalId={}, newId={}", digestId, saved.getId());
        return new DigestGenerateResponse(saved.getId(), saved.getStatus().name(), null);
    }

    /**
     * ダイジェストのインライン編集（GENERATED のみ）。
     */
    @Transactional
    public DigestDetailResponse edit(Long digestId, DigestEditRequest request) {
        TimelineDigestEntity digest = digestRepository.findById(digestId)
                .orElseThrow(() -> new BusinessException(DigestErrorCode.DIGEST_011));

        if (digest.getStatus() != DigestStatus.GENERATED) {
            throw new BusinessException(DigestErrorCode.DIGEST_012);
        }

        TimelineDigestEntity.TimelineDigestEntityBuilder builder = digest.toBuilder();
        if (request.getGeneratedTitle() != null) {
            if (request.getGeneratedTitle().length() > 200) {
                throw new BusinessException(DigestErrorCode.DIGEST_018);
            }
            builder.generatedTitle(request.getGeneratedTitle());
        }
        if (request.getGeneratedBody() != null) {
            builder.generatedBody(request.getGeneratedBody());
        }
        if (request.getGeneratedExcerpt() != null) {
            if (request.getGeneratedExcerpt().length() > 500) {
                throw new BusinessException(DigestErrorCode.DIGEST_019);
            }
            builder.generatedExcerpt(request.getGeneratedExcerpt());
        }

        TimelineDigestEntity updated = digestRepository.save(builder.build());
        log.info("ダイジェストを編集しました: id={}", digestId);
        return digestMapper.toDetailResponse(updated);
    }

    /**
     * AI API 利用量統計を取得する（SYSTEM_ADMIN 用）。
     */
    public DigestUsageResponse getUsage(String period) {
        // TODO: 実際の集計クエリを実装
        String effectivePeriod = period != null ? period : "30d";
        log.info("AI 利用量統計を取得: period={}", effectivePeriod);

        return new DigestUsageResponse(
                effectivePeriod,
                0,
                Map.of("generated", 0L, "published", 0L, "discarded", 0L, "failed", 0L),
                0,
                0,
                0,
                new ArrayList<>()
        );
    }

    // ========================================
    // Private methods
    // ========================================

    /**
     * 期間バリデーション。
     */
    private void validatePeriod(LocalDateTime periodStart, LocalDateTime periodEnd) {
        if (periodStart.isAfter(periodEnd)) {
            throw new BusinessException(DigestErrorCode.DIGEST_001);
        }
        if (Duration.between(periodStart, periodEnd).toDays() > MAX_PERIOD_DAYS) {
            throw new BusinessException(DigestErrorCode.DIGEST_001);
        }
    }

    /**
     * AI 生成枠チェック。
     */
    private void checkAiQuota(DigestScopeType scopeType, Long scopeId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        long used = digestRepository.countAiDigestsInMonth(
                scopeType, scopeId,
                List.of(DigestStatus.GENERATED, DigestStatus.PUBLISHED, DigestStatus.GENERATING),
                monthStart);

        if (used >= digestProperties.getMonthlyLimitPerScope()) {
            throw new BusinessException(DigestErrorCode.DIGEST_008);
        }
    }

    /**
     * AI クォータレスポンスを構築する。
     */
    private AiQuotaResponse buildAiQuota(DigestScopeType scopeType, Long scopeId) {
        LocalDateTime monthStart = YearMonth.now().atDay(1).atStartOfDay();
        long used = digestRepository.countAiDigestsInMonth(
                scopeType, scopeId,
                List.of(DigestStatus.GENERATED, DigestStatus.PUBLISHED, DigestStatus.GENERATING),
                monthStart);

        int limit = digestProperties.getMonthlyLimitPerScope();
        // TODO: FeatureFlagService.isEnabled("FEATURE_DIGEST_AI") で enabled を判定
        return new AiQuotaResponse(true, used, limit, Math.max(0, limit - used));
    }

    /**
     * TEMPLATE スタイルのダイジェストを同期生成する。
     */
    private void generateTemplateDigest(TimelineDigestEntity digest, TimelineDigestConfigEntity config) {
        // TODO: タイムラインから実際の投稿データを取得する
        List<Map<String, Object>> posts = new ArrayList<>();

        // TODO: スコープ名を取得する
        String scopeName = "スコープ";

        TemplateDigestGenerator.TemplateResult result = templateGenerator.generate(
                scopeName, digest.getPeriodStart(), digest.getPeriodEnd(), posts);

        TimelineDigestEntity generated = digest.toBuilder()
                .status(DigestStatus.GENERATED)
                .generatedTitle(result.title())
                .generatedBody(result.body())
                .generatedExcerpt(result.excerpt())
                .postCount(posts.size())
                .build();
        digestRepository.save(generated);
    }

    /**
     * AI スタイルのダイジェストを非同期生成する。
     */
    @Async
    public void generateAiDigestAsync(TimelineDigestEntity digest, TimelineDigestConfigEntity config, String customPrompt) {
        try {
            // TODO: タイムラインから実際の投稿データを取得する
            List<Map<String, Object>> posts = new ArrayList<>();

            boolean includeReactions = config != null ? config.getIncludeReactions() : true;
            boolean includePolls = config != null ? config.getIncludePolls() : true;
            String language = config != null ? config.getLanguage() : "ja";

            // 差分ハイライト用の前回ダイジェスト
            String previousBody = null;
            if (config != null && config.getIncludeDiffFromPrevious()) {
                List<TimelineDigestEntity> previous = digestRepository.findLatestPublishedByScope(
                        digest.getScopeType(), digest.getScopeId());
                if (!previous.isEmpty()) {
                    previousBody = previous.get(0).getGeneratedBody();
                }
            }

            DigestAiProvider.AiDigestResult result = aiProvider.generate(
                    posts, digest.getDigestStyle(), language, customPrompt,
                    previousBody, includeReactions, includePolls);

            TimelineDigestEntity generated = digest.toBuilder()
                    .status(DigestStatus.GENERATED)
                    .generatedTitle(result.title())
                    .generatedBody(result.body())
                    .generatedExcerpt(result.excerpt())
                    .aiModel(result.aiModel())
                    .aiInputTokens(result.inputTokens())
                    .aiOutputTokens(result.outputTokens())
                    .postCount(posts.size())
                    .build();
            digestRepository.save(generated);

            // TODO: プッシュ通知（「ダイジェストの生成が完了しました」）
            // TODO: WebSocket ステータス通知

            log.info("AI ダイジェスト生成完了: id={}, model={}", digest.getId(), result.aiModel());

        } catch (Exception e) {
            log.error("AI ダイジェスト生成失敗: id={}", digest.getId(), e);
            TimelineDigestEntity failed = digest.toBuilder()
                    .status(DigestStatus.FAILED)
                    .errorMessage(e.getMessage())
                    .build();
            digestRepository.save(failed);

            // TODO: ADMIN にプッシュ通知（「ダイジェスト生成に失敗しました」）
        }
    }
}
