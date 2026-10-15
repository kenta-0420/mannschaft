package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.entity.ErrorReportActivityEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportActivityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * F12.5 Phase 2 — {@code error_report_activities} への追記を一元化する薄いラッパーサービス。
 *
 * <p>{@code metadata} は JSON 化して {@code metadata_json} カラム（最大2000文字）に保存する。
 * 超過時は切り詰め + ログ警告。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorReportActivityService {

    /** {@code metadata_json} の最大長。 */
    private static final int METADATA_MAX_LENGTH = 2000;

    /** {@code content} の最大長。 */
    private static final int CONTENT_MAX_LENGTH = 2000;

    private final ErrorReportActivityRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * 操作履歴を記録する。
     *
     * @param errorReportId エラーレポート ID
     * @param actorId       操作者ユーザー ID（NULL = 退会済み or システム自動）
     * @param type          操作種別
     * @param content       本文（COMMENT_ADDED 用）
     * @param metadata      追加メタデータ（種別ごとに付与、JSON 化される）
     */
    @Transactional
    public void record(Long errorReportId, Long actorId, ErrorReportActivityType type,
                       String content, Map<String, Object> metadata) {
        String metadataJson = serializeMetadata(metadata);

        ErrorReportActivityEntity entity = ErrorReportActivityEntity.builder()
                .errorReportId(errorReportId)
                .actorId(actorId)
                .activityType(type)
                .content(truncate(content, CONTENT_MAX_LENGTH))
                .metadataJson(metadataJson)
                .build();
        repository.save(entity);
    }

    /**
     * システム自動操作として記録する。{@code metadata} に {@code system=true} を自動付与する。
     *
     * @param errorReportId エラーレポート ID
     * @param type          操作種別
     * @param metadata      追加メタデータ（NULL の場合は {@code system=true} のみ）
     */
    @Transactional
    public void recordSystemActivity(Long errorReportId, ErrorReportActivityType type,
                                     Map<String, Object> metadata) {
        Map<String, Object> merged = new HashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.put("system", true);
        record(errorReportId, null, type, null, merged);
    }

    /**
     * {@code metadata} を JSON 文字列に変換する。長すぎる場合は切り詰め + 警告ログ。
     */
    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > METADATA_MAX_LENGTH) {
                log.warn("ErrorReportActivity metadata_json が上限超過: length={}, truncated={}",
                        json.length(), METADATA_MAX_LENGTH);
                return json.substring(0, METADATA_MAX_LENGTH);
            }
            return json;
        } catch (JsonProcessingException e) {
            log.warn("ErrorReportActivity metadata の JSON 変換に失敗", e);
            return null;
        }
    }

    /**
     * 文字列を指定長に切り詰める。
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return null;
        return str.length() <= maxLength ? str : str.substring(0, maxLength);
    }
}
