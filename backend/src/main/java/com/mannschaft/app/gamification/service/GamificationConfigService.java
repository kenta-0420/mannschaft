package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gamification.GamificationErrorCode;
import com.mannschaft.app.gamification.entity.GamificationConfigEntity;
import com.mannschaft.app.gamification.repository.GamificationConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ゲーミフィケーション設定サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationConfigService {

    private final GamificationConfigRepository gamificationConfigRepository;

    /**
     * ゲーミフィケーション設定を取得する。
     * 設定が存在しない場合はデフォルト設定（isEnabled=false）を返す（DBには保存しない）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ゲーミフィケーション設定
     */
    public ApiResponse<GamificationConfigEntity> getConfig(String scopeType, Long scopeId) {
        GamificationConfigEntity config = gamificationConfigRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(GamificationConfigEntity.builder()
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .isEnabled(false)
                        .isRankingEnabled(true)
                        .rankingDisplayCount(10)
                        .build());
        return ApiResponse.of(config);
    }

    /**
     * ゲーミフィケーション設定を更新する。
     * 設定が存在しない場合はINSERT、存在する場合はversionチェック後UPDATE。
     *
     * @param scopeType            スコープ種別
     * @param scopeId              スコープID
     * @param isEnabled            ゲーミフィケーション有効フラグ
     * @param isRankingEnabled     ランキング有効フラグ
     * @param rankingDisplayCount  ランキング表示件数
     * @param pointResetMonth      ポイントリセット月
     * @param version              楽観的ロックバージョン（新規の場合はnull）
     * @return 更新後のゲーミフィケーション設定
     */
    @Transactional
    public ApiResponse<GamificationConfigEntity> updateConfig(
            String scopeType, Long scopeId,
            boolean isEnabled, boolean isRankingEnabled, int rankingDisplayCount,
            Integer pointResetMonth, Long version) {

        GamificationConfigEntity config = gamificationConfigRepository
                .findByScopeTypeAndScopeId(scopeType, scopeId)
                .orElse(null);

        Byte resetMonth = pointResetMonth != null ? pointResetMonth.byteValue() : null;

        if (config == null) {
            // INSERT
            config = GamificationConfigEntity.builder()
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .isEnabled(isEnabled)
                    .isRankingEnabled(isRankingEnabled)
                    .rankingDisplayCount(rankingDisplayCount)
                    .pointResetMonth(resetMonth)
                    .build();
        } else {
            // バージョンチェック
            if (version == null || !version.equals(config.getVersion())) {
                throw new BusinessException(GamificationErrorCode.GAMIFICATION_006);
            }
            config.update(isEnabled, isRankingEnabled, rankingDisplayCount, resetMonth);
        }

        GamificationConfigEntity saved = gamificationConfigRepository.save(config);
        return ApiResponse.of(saved);
    }
}
