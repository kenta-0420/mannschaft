package com.mannschaft.app.member;

import com.mannschaft.app.dashboard.MinRole;

import java.util.EnumMap;
import java.util.Map;

/**
 * CMP-260919-1140 Phase 1: サブタブごとのデフォルト最低必要ロール定義。
 *
 * <p>DB にレコードがない場合に使われる推奨デフォルト値。両サブタブとも
 * {@link MinRole#MEMBER} を既定とする（既存の名簿API・紹介APIの現状挙動と等価）。</p>
 *
 * <p>一覧（{@link MemberSubtabKey#MEMBER_LIST}）は氏名・役割等の個人情報を含むため、
 * PUBLIC への変更は許可しない（{@link #isPublicAllowed}）。紹介
 * （{@link MemberSubtabKey#MEMBER_PROFILES}）は連絡先を含まないため PUBLIC を許可する。</p>
 *
 * <p>設計書: docs/features/F06.6_member_subtab_visibility.md §3</p>
 */
public final class MemberSubtabDefaultMinRoleMap {

    private static final Map<MemberSubtabKey, MinRole> DEFAULTS = new EnumMap<>(MemberSubtabKey.class);

    static {
        DEFAULTS.put(MemberSubtabKey.MEMBER_LIST, MinRole.MEMBER);
        DEFAULTS.put(MemberSubtabKey.MEMBER_PROFILES, MinRole.MEMBER);
    }

    private MemberSubtabDefaultMinRoleMap() {
    }

    public static MinRole getDefault(MemberSubtabKey key) {
        MinRole minRole = DEFAULTS.get(key);
        if (minRole == null) {
            throw new IllegalArgumentException("Unknown MemberSubtabKey: " + key);
        }
        return minRole;
    }

    public static Map<MemberSubtabKey, MinRole> getDefaults() {
        return new EnumMap<>(DEFAULTS);
    }

    /**
     * 指定サブタブに PUBLIC 設定を許可するかどうか。
     * 一覧タブのみ不可（氏名・役割等の個人情報を含むため）。
     */
    public static boolean isPublicAllowed(MemberSubtabKey key) {
        return key != MemberSubtabKey.MEMBER_LIST;
    }
}
