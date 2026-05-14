package com.mannschaft.app.village.entity.enums;

/**
 * 村内ロール。
 *
 * <ul>
 *   <li>{@link #HEADMAN}: 村長（最高権限・1村1名）</li>
 *   <li>{@link #ELDER}: 長老（モデレーター・複数可）</li>
 *   <li>{@link #VILLAGER}: 村人（一般メンバー）</li>
 *   <li>{@link #VISITOR}: 旅人（閲覧のみ）</li>
 * </ul>
 */
public enum VillageRole {
    HEADMAN,
    ELDER,
    VILLAGER,
    VISITOR
}
