package com.mannschaft.app.publicview.visibility;

import com.mannschaft.app.publicview.enums.NameDisclosureMode;

import java.util.Objects;

/**
 * スコープ（チーム / 組織）の表示モード設定。
 *
 * <p>F19.1 §7.1 で {@link IdentityVisibilityResolver} に渡される、対象スコープの
 * {@code supporter_name_disclosure} 等のモード設定を不変的に保持する DTO。</p>
 *
 * <p>Phase 1 では実機能活性化なしのため値は常に {@link NameDisclosureMode#DISPLAY_NAME} で渡される。
 * Phase 2 で {@code teams.supporter_name_disclosure} の取得経路が確定したら、
 * {@code PublicTeamQueryService} / {@code PublicOrganizationQueryService} が
 * Entity から本値を取り出して渡す。</p>
 *
 * @param supporterNameDisclosure サポーター向け氏名表示モード（{@code null} 不可）
 */
public record ScopeSettings(NameDisclosureMode supporterNameDisclosure) {

    public ScopeSettings {
        Objects.requireNonNull(supporterNameDisclosure, "supporterNameDisclosure must not be null");
    }

    /**
     * 既定値（{@link NameDisclosureMode#DISPLAY_NAME}）の ScopeSettings を生成する。
     *
     * <p>Phase 1 では全スコープがこの既定値で動作する。</p>
     *
     * @return 既定値設定
     */
    public static ScopeSettings defaultDisplayName() {
        return new ScopeSettings(NameDisclosureMode.DISPLAY_NAME);
    }
}
