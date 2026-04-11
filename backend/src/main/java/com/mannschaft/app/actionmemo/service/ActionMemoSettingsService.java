package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F02.5 ユーザー別 行動メモ設定サービス。
 *
 * <p>レコード未作成のユーザーに対しては「デフォルト値（mood_enabled = false）」と等価に扱う。
 * 設定変更時は UPSERT（1回目の PATCH で INSERT、2回目以降は UPDATE）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoSettingsService {

    private final UserActionMemoSettingsRepository settingsRepository;
    private final ActionMemoMetrics metrics;

    /**
     * 指定ユーザーの mood_enabled 値を取得する。
     * レコードが存在しない場合は false（デフォルト = ドライモード）を返す。
     *
     * @param userId ユーザー ID
     * @return mood_enabled の値
     */
    public boolean getMoodEnabled(Long userId) {
        return settingsRepository.findById(userId)
                .map(UserActionMemoSettingsEntity::getMoodEnabled)
                .orElse(false);
    }

    /**
     * 自分の設定を取得する（GET /api/v1/action-memo-settings）。
     * レコード未作成時はデフォルト値で返す。
     */
    public ActionMemoSettingsResponse getSettings(Long userId) {
        boolean moodEnabled = getMoodEnabled(userId);
        return new ActionMemoSettingsResponse(moodEnabled);
    }

    /**
     * 自分の設定を UPSERT する（PATCH /api/v1/action-memo-settings）。
     *
     * @param userId  ユーザー ID
     * @param request 更新リクエスト（送信された項目のみ更新）
     * @return 更新後の設定
     */
    @Transactional
    public ActionMemoSettingsResponse updateSettings(Long userId, UpdateActionMemoSettingsRequest request) {
        UserActionMemoSettingsEntity entity = settingsRepository.findById(userId)
                .orElseGet(() -> UserActionMemoSettingsEntity.builder()
                        .userId(userId)
                        .moodEnabled(false)
                        .build());

        if (request.getMoodEnabled() != null) {
            entity.setMoodEnabled(request.getMoodEnabled());
        }

        UserActionMemoSettingsEntity saved = settingsRepository.save(entity);

        log.info("行動メモ設定更新: userId={}, moodEnabled={}", userId, saved.getMoodEnabled());

        // mood_enabled ユーザー数 gauge を再計算
        metrics.refreshMoodEnabledUserCount();

        return new ActionMemoSettingsResponse(saved.getMoodEnabled());
    }
}
