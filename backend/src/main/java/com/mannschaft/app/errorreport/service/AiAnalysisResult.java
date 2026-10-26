package com.mannschaft.app.errorreport.service;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F12.5 Phase 2-C — Claude AI 分析結果 DTO。
 *
 * <p>{@link ErrorReportClaudeAiProvider#analyze(SanitizedErrorContext)} の戻り値。</p>
 */
@Builder
@Getter
public class AiAnalysisResult {

    /** 推定原因（最大2000文字）。 */
    private String estimatedCause;

    /** 修正案（最大2000文字）。 */
    private String fixProposal;

    /** 影響評価（最大1000文字）。 */
    private String impactAssessment;

    /** 関連ファイル候補（最大10件）。 */
    private List<String> suggestedFiles;

    /** Claude API usage の入力トークン数。 */
    private int promptTokens;

    /** Claude API usage の出力トークン数。 */
    private int completionTokens;

    /** 生 JSON レスポンス（デバッグ用、30日後に NULL 化される）。 */
    private String rawResponse;
}
