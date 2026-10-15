package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.PointCardUserSettingsResponse;
import com.mannschaft.app.pointcard.dto.UpdateUserSettingsRequest;
import com.mannschaft.app.pointcard.entity.PointCardUserSettingsEntity;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.repository.PointCardUserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * ポイントカードウォレットのユーザー設定サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §6.1 / §7.1
 *
 * <p>オプトイン状態と規約同意の管理を担当する。設定が存在しない場合は
 * {@code is_enabled=false} のデフォルト値で自動作成して返却する。
 * 後続陣（2B / 3）から呼び出される検証ヘルパー
 * {@link #assertTermsAcceptedAndCurrent(Long, String)} を公開する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PointCardUserSettingsService {

    /**
     * ポイントカードウォレットの現行規約バージョン。
     * カード作成・グループ作成・提示モードなど規約検証を行う全クラスはこの定数を参照すること（バージョンドリフト防止）。
     *
     * <p>設計書 §13 未解決事項として application.yml 化は後付け可（運用判断）。
     */
    public static final String CURRENT_TERMS_VERSION = "v1.0.0";

    private final PointCardUserSettingsRepository settingsRepository;

    /**
     * 指定ユーザーの設定を取得する。存在しない場合はデフォルト設定（オプトアウト状態）を
     * 作成して返却する。
     *
     * @param userId ユーザー ID
     * @return ユーザー設定レスポンス
     */
    @Transactional
    public PointCardUserSettingsResponse getOrCreateSettings(Long userId) {
        PointCardUserSettingsEntity settings = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));
        return PointCardUserSettingsResponse.from(settings);
    }

    /**
     * 指定ユーザーの設定を更新する。
     *
     * <ul>
     *   <li>{@code isEnabled} が指定されていればその値を採用する</li>
     *   <li>{@code termsVersion} が指定されていれば {@code termsAcceptedAt} を
     *       現在時刻で更新し、規約バージョンを記録する</li>
     *   <li>{@code requireBiometricOnShow} が指定されていればその値を採用する</li>
     * </ul>
     *
     * <p>null フィールドは既存値を維持する（差分適用）。
     */
    @Transactional
    public PointCardUserSettingsResponse updateSettings(Long userId, UpdateUserSettingsRequest req) {
        PointCardUserSettingsEntity current = settingsRepository.findByUserId(userId)
                .orElseGet(() -> createDefaultSettings(userId));

        PointCardUserSettingsEntity.PointCardUserSettingsEntityBuilder builder = current.toBuilder();
        if (req.isEnabled() != null) {
            builder.enabled(req.isEnabled());
        }
        if (req.termsVersion() != null && !req.termsVersion().isBlank()) {
            builder.termsVersion(req.termsVersion());
            builder.termsAcceptedAt(OffsetDateTime.now());
        }
        if (req.requireBiometricOnShow() != null) {
            builder.requireBiometricOnShow(req.requireBiometricOnShow());
        }
        PointCardUserSettingsEntity updated = settingsRepository.save(builder.build());
        log.info("ポイントカードウォレット設定を更新: userId={}, enabled={}, termsVersion={}",
                userId, updated.getEnabled(), updated.getTermsVersion());
        return PointCardUserSettingsResponse.from(updated);
    }

    /**
     * 規約同意済みかつ現行バージョンであることを検証する。
     * 後続陣（カード作成・提示モード等）から呼ばれる前提条件チェッカ。
     *
     * <p>以下のいずれかに該当する場合は {@link PointCardErrorCode#WALLET_NOT_ENABLED}
     * で {@link BusinessException} を投げる:
     * <ul>
     *   <li>設定レコードが存在しない（オプトイン未実施）</li>
     *   <li>{@code is_enabled=false}</li>
     *   <li>{@code terms_accepted_at} が null</li>
     *   <li>同意済みバージョンが {@code currentVersion} と不一致</li>
     * </ul>
     *
     * @param userId         ユーザー ID
     * @param currentVersion 現行の規約バージョン
     */
    public void assertTermsAcceptedAndCurrent(Long userId, String currentVersion) {
        PointCardUserSettingsEntity settings = settingsRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED));

        if (!Boolean.TRUE.equals(settings.getEnabled())) {
            throw new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED);
        }
        if (settings.getTermsAcceptedAt() == null) {
            throw new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED);
        }
        if (currentVersion != null && !currentVersion.equals(settings.getTermsVersion())) {
            throw new BusinessException(PointCardErrorCode.WALLET_NOT_ENABLED);
        }
    }

    /**
     * デフォルト設定を新規作成する（オプトアウト状態）。
     */
    private PointCardUserSettingsEntity createDefaultSettings(Long userId) {
        PointCardUserSettingsEntity defaults = PointCardUserSettingsEntity.builder()
                .userId(userId)
                .enabled(Boolean.FALSE)
                .requireBiometricOnShow(Boolean.FALSE)
                .build();
        return settingsRepository.save(defaults);
    }
}
