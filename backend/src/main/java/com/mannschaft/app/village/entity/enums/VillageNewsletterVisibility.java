package com.mannschaft.app.village.entity.enums;

/**
 * 村ニュースレター号の公開範囲（F17.1 ②-1・案Y の pull 層）。
 *
 * <p>村ドメイン専用の最小ラダー。cms の {@code Visibility}（Long 前提・ペイウォール連結）を
 * import すると案X 相当の結合が生じるため、村ドメインで独立に持つ（設計書 §4.7.2）。
 * 将来 SUPPORTERS 等へ広げる余地は残すが MVP は2値。</p>
 */
public enum VillageNewsletterVisibility {

    /** 村内限定（既定）。村メンバーのみ閲覧可能。 */
    VILLAGE_MEMBERS,

    /** 一般公開。ログイン済みなら誰でも閲覧可能（全村横断の公開一覧に出る）。 */
    PUBLIC
}
