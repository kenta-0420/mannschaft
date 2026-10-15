package com.mannschaft.app.gamification.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.gamification.entity.GamificationUserSettingEntity;
import com.mannschaft.app.gamification.repository.GamificationUserSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ゲーミフィケーション・ユーザー設定サービス。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class GamificationSettingService {

    private final GamificationUserSettingRepository gamificationUserSettingRepository;

    /**
     * ユーザーのゲーミフィケーション設定を取得する。
     * 設定が存在しない場合はデフォルト（show_in_ranking=true, show_badges=true）を返す（DBには保存しない）。
     *
     * @param userId    ユーザーID
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ユーザーゲーミフィケーション設定
     */
    public ApiResponse<GamificationUserSettingEntity> getMySetting(
            Long userId, String scopeType, Long scopeId) {

        GamificationUserSettingEntity setting = gamificationUserSettingRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId)
                .orElse(GamificationUserSettingEntity.builder()
                        .userId(userId)
                        .scopeType(scopeType)
                        .scopeId(scopeId)
                        .showInRanking(true)
                        .showBadges(true)
                        .build());

        return ApiResponse.of(setting);
    }

    /**
     * ユーザーのゲーミフィケーション設定をUPSERTする。
     * 存在しない場合はINSERT、存在する場合はUPDATEを行う。
     *
     * @param userId        ユーザーID
     * @param scopeType     スコープ種別
     * @param scopeId       スコープID
     * @param showInRanking ランキング表示フラグ
     * @param showBadges    バッジ表示フラグ
     * @return 更新後のユーザーゲーミフィケーション設定
     */
    @Transactional
    public ApiResponse<GamificationUserSettingEntity> updateMySetting(
            Long userId, String scopeType, Long scopeId,
            boolean showInRanking, boolean showBadges) {

        GamificationUserSettingEntity setting = gamificationUserSettingRepository
                .findByUserIdAndScopeTypeAndScopeId(userId, scopeType, scopeId)
                .orElse(null);

        if (setting == null) {
            // INSERT
            setting = GamificationUserSettingEntity.builder()
                    .userId(userId)
                    .scopeType(scopeType)
                    .scopeId(scopeId)
                    .showInRanking(showInRanking)
                    .showBadges(showBadges)
                    .build();
        } else {
            // UPDATE
            setting.update(showInRanking, showBadges);
        }

        GamificationUserSettingEntity saved = gamificationUserSettingRepository.save(setting);
        return ApiResponse.of(saved);
    }
}
