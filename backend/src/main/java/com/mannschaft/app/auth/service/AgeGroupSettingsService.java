package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import com.mannschaft.app.auth.repository.AgeGroupSettingsRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢区分設定管理サービス。
 *
 * <p>age_group_settings マスタテーブルの参照・更新を管理する。
 * 更新は管理者（SYSTEM_ADMIN）のみ許可し、SecurityConfig で制御する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AgeGroupSettingsService {

    private final AgeGroupSettingsRepository ageGroupSettingsRepository;
    private final ObjectMapper objectMapper;

    /**
     * すべての年齢区分設定を取得する。
     *
     * @return 年齢区分設定リスト
     */
    public List<AgeGroupSettingsEntity> getAll() {
        return ageGroupSettingsRepository.findAll();
    }

    /**
     * 指定した年齢区分の設定を更新する。
     *
     * @param ageGroup        更新対象の年齢区分識別子
     * @param featuresEnabled 機能有効フラグ（Object → JSON 文字列に変換して保存。null の場合は変更しない）
     * @param themeConfig     UI テーマ設定（Object → JSON 文字列に変換して保存。null の場合は変更しない）
     * @return 更新後のエンティティ
     * @throws BusinessException 対象の年齢区分が存在しない場合
     */
    @Transactional
    public AgeGroupSettingsEntity update(String ageGroup, Object featuresEnabled, Object themeConfig) {
        AgeGroupSettingsEntity entity = ageGroupSettingsRepository.findById(ageGroup)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.COMMON_999));

        String featuresEnabledJson = toJsonString(featuresEnabled);
        String themeConfigJson = toJsonString(themeConfig);

        entity.update(featuresEnabledJson, themeConfigJson);
        AgeGroupSettingsEntity saved = ageGroupSettingsRepository.save(entity);

        log.info("年齢区分設定を更新しました: ageGroup={}", ageGroup);
        return saved;
    }

    /**
     * Object を JSON 文字列に変換する。
     * null の場合は null を返す（更新対象外として扱う）。
     *
     * @param value JSON シリアライズ対象のオブジェクト
     * @return JSON 文字列、または null
     */
    private String toJsonString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            // すでに文字列の場合はそのまま返す
            return s;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("JSON シリアライズに失敗しました: value={}", value, e);
            throw new BusinessException(CommonErrorCode.COMMON_999);
        }
    }
}
