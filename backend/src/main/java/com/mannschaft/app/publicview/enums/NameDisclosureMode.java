package com.mannschaft.app.publicview.enums;

/**
 * サポーター向け氏名表示モード。
 *
 * <p>F19.1 公開ページ氏名開示制御で用いる、{@code teams.supporter_name_disclosure} および
 * {@code organizations.supporter_name_disclosure} の表示モード列挙。</p>
 *
 * <ul>
 *   <li>{@link #DISPLAY_NAME}: サポーター閲覧時は {@code users.display_name} を表示する（既定）</li>
 *   <li>{@link #REAL_NAME}: サポーター閲覧時は {@code users.last_name + ' ' + users.first_name}
 *       による本名を表示する（locale により語順変動。詳細は設計書 §8.4）</li>
 * </ul>
 *
 * <p>Phase 1 ではカラム追加のみで本 enum は未参照。実機能活性化は Phase 2 の
 * {@code IdentityVisibilityResolver} 実装時に行う。</p>
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §3 用語定義 / §7.2</p>
 */
public enum NameDisclosureMode {

    /** 表示名（{@code users.display_name}）を公開する。 */
    DISPLAY_NAME,

    /** 本名を公開する（locale により語順変動）。 */
    REAL_NAME
}
