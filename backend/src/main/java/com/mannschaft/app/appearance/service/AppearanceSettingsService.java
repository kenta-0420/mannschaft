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
        // 新規時は builder で userId のみ持たせ、既存時は取得済み managed entity をそのまま使う。
        AppearanceSettingsEntity entity = repository.findByUserId(userId)
                .orElseGet(() -> AppearanceSettingsEntity.builder()
                        .userId(userId)
                        .build());

        // 値の真実の源を setter に一本化する（新規・既存とも同一経路。builder と二重設定しない＝保守時の片側更新ミスを防ぐ）。
        // toBuilder().build() は UuidV7Entity 継承クラスで id が引き継がれず新インスタンス＝INSERT になるため使わない。
        entity.setTheme(request.getTheme());
        entity.setBgColor(request.getBgColor());
        entity.setSeasonalThemeId(request.getSeasonalThemeId());
        entity.setHideChatPreview(Boolean.TRUE.equals(request.getHideChatPreview()));

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
