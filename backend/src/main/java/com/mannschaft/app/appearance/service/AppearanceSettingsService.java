package com.mannschaft.app.appearance.service;

import com.mannschaft.app.appearance.dto.AppearanceResponse;
import com.mannschaft.app.appearance.dto.UpdateAppearanceRequest;
import com.mannschaft.app.appearance.entity.AppearanceSettingsEntity;
import com.mannschaft.app.appearance.entity.ThemeMode;
import com.mannschaft.app.appearance.repository.AppearanceSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F11.4 外観テーマ設定 — サービス層。
 *
 * <p>1ユーザー1行の upsert 方式で外観設定を管理する。
 * DB 未登録時はデフォルト値を返し、DB への保存はしない（getOrDefault は readonly）。</p>
 */
@Service
@RequiredArgsConstructor
public class AppearanceSettingsService {

    /** デフォルトテーマ。 */
    private static final ThemeMode DEFAULT_THEME = ThemeMode.LIGHT;

    /** デフォルト背景色（温かみのあるオフホワイト）。 */
    private static final String DEFAULT_BG_COLOR = "#f3efe0";

    private final AppearanceSettingsRepository repository;

    /**
     * 指定ユーザーの外観設定を取得する。未登録の場合はデフォルト値を返す（DB保存なし）。
     *
     * @param userId ユーザー ID
     * @return 外観設定レスポンス（DB登録値またはデフォルト）
     */
    @Transactional(readOnly = true)
    public AppearanceResponse getOrDefault(Long userId) {
        return repository.findByUserId(userId)
                .map(this::toResponse)
                .orElseGet(this::defaultResponse);
    }

    /**
     * 指定ユーザーの外観設定を保存する（upsert）。
     * UNIQUE KEY uq_appearance_settings_user_id により既存行があれば更新、なければ挿入する。
     *
     * @param userId  ユーザー ID
     * @param request 更新リクエスト
     * @return 保存後の外観設定レスポンス
     */
    @Transactional
    public AppearanceResponse save(Long userId, UpdateAppearanceRequest request) {
        AppearanceSettingsEntity entity = repository.findByUserId(userId)
                .map(existing -> existing.toBuilder()
                        .theme(request.getTheme())
                        .bgColor(request.getBgColor())
                        .seasonalThemeId(request.getSeasonalThemeId())
                        .hideChatPreview(Boolean.TRUE.equals(request.getHideChatPreview()))
                        .build())
                .orElseGet(() -> AppearanceSettingsEntity.builder()
                        .userId(userId)
                        .theme(request.getTheme())
                        .bgColor(request.getBgColor())
                        .seasonalThemeId(request.getSeasonalThemeId())
                        .hideChatPreview(Boolean.TRUE.equals(request.getHideChatPreview()))
                        .build());

        return toResponse(repository.save(entity));
    }

    // ─────────────────────────────────────────────────────────────────────
    // プライベートヘルパー
    // ─────────────────────────────────────────────────────────────────────

    private AppearanceResponse toResponse(AppearanceSettingsEntity entity) {
        return AppearanceResponse.builder()
                .theme(entity.getTheme())
                .bgColor(entity.getBgColor())
                .seasonalThemeId(entity.getSeasonalThemeId())
                .hideChatPreview(entity.isHideChatPreview())
                .build();
    }

    private AppearanceResponse defaultResponse() {
        return AppearanceResponse.builder()
                .theme(DEFAULT_THEME)
                .bgColor(DEFAULT_BG_COLOR)
                .seasonalThemeId(null)
                .hideChatPreview(false)
                .build();
    }
}
