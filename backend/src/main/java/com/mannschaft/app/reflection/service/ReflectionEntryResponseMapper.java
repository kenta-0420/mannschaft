package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * エントリレスポンスの唯一の生成口（F06.5・§3.2 マスク分離）。
 *
 * <p><b>マスク中は本文をソースから詰めない</b>: {@code isMasked==true} のとき {@code structuredContent=null}、
 * {@code maskedHint}（theme タイトル・target_date・想起予定日）のみを返す。後段で握り潰すのではなく
 * ソースで null にすることで AC-8（マスク中 original 漏れない）を構造的に担保する。判定例外時も
 * fail-closed（本文 null・マスク扱い）。一覧 API も本マッパーを通すため、一覧でもマスク中本文は出ない。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionEntryResponseMapper {

    private final ReflectionMaskEvaluator maskEvaluator;
    private final ReflectionContentSanitizer contentSanitizer;
    private final ReflectionMaskedCueExtractor maskedCueExtractor;

    /**
     * マスク判定を行ったうえでエントリ応答を生成する（一覧・詳細用）。
     *
     * @param entry 対象エントリ
     * @param theme 親テーマ
     * @param today ユーザー TZ の今日
     * @return マスク状態を反映した応答（マスク中は本文 null）
     */
    public ReflectionEntryResponse toResponse(ReflectionEntryEntity entry, ReflectionThemeEntity theme,
                                              LocalDate today) {
        boolean masked;
        try {
            masked = maskEvaluator.isMasked(entry, theme, today);
        } catch (Exception e) {
            log.warn("マスク判定で例外。fail-closed（マスク）: entryId={}", entry.getId(), e);
            masked = true;
        }
        if (masked) {
            return maskedResponse(entry, theme, today);
        }
        return revealedResponse(entry, theme);
    }

    /**
     * マスクを無視して original 本文を開示する応答を生成する（recall 保存＝開示の唯一の遷移点・AC-7）。
     */
    public ReflectionEntryResponse toRevealedResponse(ReflectionEntryEntity entry,
                                                      ReflectionThemeEntity theme) {
        return revealedResponse(entry, theme);
    }

    private ReflectionEntryResponse revealedResponse(ReflectionEntryEntity entry,
                                                     ReflectionThemeEntity theme) {
        JsonNode content;
        try {
            content = contentSanitizer.parse(entry.getStructuredContent());
        } catch (Exception e) {
            // パース不能なら fail-closed（本文を出さない）。
            log.warn("本文パース失敗のため fail-closed: entryId={}", entry.getId(), e);
            return maskedResponse(entry, theme, null);
        }
        return ReflectionEntryResponse.builder()
                .id(entry.getId().toString())
                .themeId(entry.getThemeId().toString())
                .targetDate(entry.getTargetDate())
                .isMasked(false)
                .structuredContent(content)
                .maskedHint(null)
                .visibility(entry.getVisibility())
                .version(entry.getVersion())
                .updatedAt(entry.getUpdatedAt())
                .exportedBlogPostId(entry.getExportedBlogPostId())
                .build();
    }

    private ReflectionEntryResponse maskedResponse(ReflectionEntryEntity entry,
                                                   ReflectionThemeEntity theme, LocalDate today) {
        List<LocalDate> dueDates = today != null
                ? maskEvaluator.dueRecallDates(entry, theme, today)
                : List.of();

        // Phase 4（§13-C-1）: TERM_CARD の cue 側だけを抽出する。
        // fail-closed: today=null（算出不能）・parse 失敗・型不整合では cardQuiz 空・recallDirection=null。
        RecallDirection direction = null;
        List<ReflectionEntryResponse.MaskedCardQuiz> cardQuiz = List.of();
        if (today != null) {
            try {
                direction = maskEvaluator.resolveDirection(entry, theme, today);
                JsonNode content = contentSanitizer.parse(entry.getStructuredContent());
                cardQuiz = maskedCueExtractor.extractCardQuiz(content, direction);
            } catch (Exception e) {
                // cue 抽出で例外 → 答えを絶対に載せない（fail-closed・§13-C-1）。
                log.warn("マスク中の cue 抽出に失敗のため fail-closed（cardQuiz 空）: entryId={}",
                        entry.getId(), e);
                direction = null;
                cardQuiz = List.of();
            }
        }

        ReflectionEntryResponse.MaskedHint hint = ReflectionEntryResponse.MaskedHint.builder()
                .themeTitle(theme != null ? theme.getTitle() : null)
                .targetDate(entry.getTargetDate())
                .dueRecallDates(dueDates)
                .recallDirection(direction)
                .cardQuiz(cardQuiz)
                .build();
        return ReflectionEntryResponse.builder()
                .id(entry.getId().toString())
                .themeId(entry.getThemeId().toString())
                .targetDate(entry.getTargetDate())
                .isMasked(true)
                .structuredContent(null) // ソースで null（§3.2）
                .maskedHint(hint)
                .visibility(entry.getVisibility())
                .version(entry.getVersion())
                .updatedAt(entry.getUpdatedAt())
                .exportedBlogPostId(entry.getExportedBlogPostId())
                .build();
    }
}
