package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.translation.entity.TranslationConfigEntity;
import com.mannschaft.app.translation.repository.TranslationConfigRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 翻訳設定サービス。
 * スコープ（組織/チーム）ごとの翻訳設定（対応言語・原文言語・自動検出フラグ）を管理する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TranslationConfigService {

    private final TranslationConfigRepository translationConfigRepository;

    // ========================================
    // リクエスト DTO
    // ========================================

    /**
     * 翻訳設定の作成・更新リクエスト。
     */
    @Getter
    @Setter
    public static class UpsertTranslationConfigRequest {
        /** 原文言語コード（ISO 639-1）。例: "ja" */
        private String primaryLanguage;
        /** 有効な翻訳対象言語コードのリスト。例: ["en","ko"] */
        private List<String> enabledLanguages;
        /** 読者の locale から自動言語選択を行うか */
        private boolean autoDetectReaderLanguage;
    }

    // ========================================
    // レスポンス DTO
    // ========================================

    /**
     * 翻訳設定レスポンス。
     */
    @Getter
    public static class TranslationConfigResponse {
        private final Long id;
        private final String scopeType;
        private final Long scopeId;
        private final String primaryLanguage;
        private final List<String> enabledLanguages;
        private final boolean autoDetectReaderLanguage;

        public TranslationConfigResponse(Long id, String scopeType, Long scopeId,
                                         String primaryLanguage, List<String> enabledLanguages,
                                         boolean autoDetectReaderLanguage) {
            this.id = id;
            this.scopeType = scopeType;
            this.scopeId = scopeId;
            this.primaryLanguage = primaryLanguage;
            this.enabledLanguages = enabledLanguages;
            this.autoDetectReaderLanguage = autoDetectReaderLanguage;
        }
    }

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 翻訳設定を作成または更新する（upsert）。
     * 既存レコードがあれば更新、なければ新規作成する。
     *
     * @param scopeType スコープ種別（ORGANIZATION / TEAM）
     * @param scopeId   スコープID
     * @param req       更新内容
     * @return 保存後の翻訳設定レスポンス
     */
    @Transactional
    public ApiResponse<TranslationConfigResponse> upsertConfig(
            String scopeType, Long scopeId, UpsertTranslationConfigRequest req) {

        // 既存設定の検索
        Optional<TranslationConfigEntity> existing =
                translationConfigRepository.findByScopeTypeAndScopeId(scopeType, scopeId);

        TranslationConfigEntity entity;
        if (existing.isPresent()) {
            // 既存設定を更新
            entity = existing.get();
            entity.updatePrimaryLanguage(req.getPrimaryLanguage());
            entity.updateEnabledLanguages(req.getEnabledLanguages());
            entity.updateAutoDetect(req.isAutoDetectReaderLanguage());
            entity = translationConfigRepository.save(entity);
            log.info("翻訳設定更新: scope={}/{}, primaryLanguage={}, enabledLanguages={}",
                    scopeType, scopeId, req.getPrimaryLanguage(), req.getEnabledLanguages());
        } else {
            // 新規作成
            entity = TranslationConfigEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .primaryLanguage(req.getPrimaryLanguage() != null ? req.getPrimaryLanguage() : "ja")
                    .enabledLanguages(req.getEnabledLanguages() != null ? req.getEnabledLanguages() : List.of())
                    .isAutoDetectReaderLanguage(req.isAutoDetectReaderLanguage())
                    .build();
            entity = translationConfigRepository.save(entity);
            log.info("翻訳設定新規作成: scope={}/{}, primaryLanguage={}", scopeType, scopeId, req.getPrimaryLanguage());
        }

        return ApiResponse.of(toResponse(entity));
    }

    /**
     * 翻訳設定を取得する。
     * 未登録の場合はデフォルト設定（DB保存なし）を返す。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 翻訳設定レスポンス
     */
    public ApiResponse<TranslationConfigResponse> getConfig(String scopeType, Long scopeId) {
        Optional<TranslationConfigEntity> config =
                translationConfigRepository.findByScopeTypeAndScopeId(scopeType, scopeId);

        if (config.isPresent()) {
            return ApiResponse.of(toResponse(config.get()));
        }

        // 未設定の場合はデフォルト値で返す（DBには登録しない）
        log.debug("翻訳設定未登録のためデフォルト設定を返す: scope={}/{}", scopeType, scopeId);
        TranslationConfigResponse defaultConfig = new TranslationConfigResponse(
                null, scopeType, scopeId, "ja", List.of(), true);
        return ApiResponse.of(defaultConfig);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * エンティティをレスポンスDTOに変換する。
     *
     * @param entity 翻訳設定エンティティ
     * @return レスポンスDTO
     */
    private TranslationConfigResponse toResponse(TranslationConfigEntity entity) {
        return new TranslationConfigResponse(
                entity.getId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getPrimaryLanguage(),
                entity.getEnabledLanguages(),
                entity.getIsAutoDetectReaderLanguage()
        );
    }
}
