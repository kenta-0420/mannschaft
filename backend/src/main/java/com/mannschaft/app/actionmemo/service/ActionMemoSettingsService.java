package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoErrorCode;
import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.dto.ActionMemoSettingsResponse;
import com.mannschaft.app.actionmemo.dto.UpdateActionMemoSettingsRequest;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F02.5 ユーザー別 行動メモ設定サービス。
 *
 * <p>レコード未作成のユーザーに対しては「デフォルト値（mood_enabled = false）」と等価に扱う。
 * 設定変更時は UPSERT（1回目の PATCH で INSERT、2回目以降は UPDATE）。</p>
 *
 * <p><b>Phase 3 拡張</b>: default_post_team_id / default_category の管理を追加。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoSettingsService {

    private final UserActionMemoSettingsRepository settingsRepository;
    private final UserRoleRepository userRoleRepository;
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
     * 指定ユーザーの設定エンティティを取得する（存在しない場合は null を返す）。
     *
     * @param userId ユーザー ID
     * @return 設定エンティティ (Optional)
     */
    public Optional<UserActionMemoSettingsEntity> findSettings(Long userId) {
        return settingsRepository.findById(userId);
    }

    /**
     * 自分の設定を取得する（GET /api/v1/action-memo-settings）。
     * レコード未作成時はデフォルト値で返す。
     */
    public ActionMemoSettingsResponse getSettings(Long userId) {
        return settingsRepository.findById(userId)
                .map(s -> ActionMemoSettingsResponse.builder()
                        .moodEnabled(Boolean.TRUE.equals(s.getMoodEnabled()))
                        .defaultPostTeamId(s.getDefaultPostTeamId())
                        .defaultCategory(s.getDefaultCategory() != null
                                ? s.getDefaultCategory()
                                : ActionMemoCategory.PRIVATE)
                        .build())
                .orElseGet(() -> ActionMemoSettingsResponse.builder()
                        .moodEnabled(false)
                        .defaultPostTeamId(null)
                        .defaultCategory(ActionMemoCategory.PRIVATE)
                        .build());
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
                        .defaultCategory(ActionMemoCategory.PRIVATE)
                        .build());

        if (request.getMoodEnabled() != null) {
            entity.setMoodEnabled(request.getMoodEnabled());
        }

        // Phase 3: デフォルト投稿先チームの更新
        if (request.getDefaultPostTeamId() != null) {
            // 所属チームかどうかを検証（IDOR 対策: 非所属チームは 400）
            if (!userRoleRepository.existsByUserIdAndTeamId(userId, request.getDefaultPostTeamId())) {
                throw new BusinessException(ActionMemoErrorCode.ACTION_MEMO_INVALID_DEFAULT_TEAM);
            }
            entity.setDefaultPostTeamId(request.getDefaultPostTeamId());
        }

        // Phase 3: デフォルトカテゴリの更新
        if (request.getDefaultCategory() != null) {
            entity.setDefaultCategory(request.getDefaultCategory());
        }

        UserActionMemoSettingsEntity saved = settingsRepository.save(entity);

        log.info("行動メモ設定更新: userId={}, moodEnabled={}, defaultPostTeamId={}, defaultCategory={}",
                userId, saved.getMoodEnabled(), saved.getDefaultPostTeamId(), saved.getDefaultCategory());

        // mood_enabled ユーザー数 gauge を再計算
        metrics.refreshMoodEnabledUserCount();

        return ActionMemoSettingsResponse.builder()
                .moodEnabled(Boolean.TRUE.equals(saved.getMoodEnabled()))
                .defaultPostTeamId(saved.getDefaultPostTeamId())
                .defaultCategory(saved.getDefaultCategory() != null
                        ? saved.getDefaultCategory()
                        : ActionMemoCategory.PRIVATE)
                .build();
    }
}
