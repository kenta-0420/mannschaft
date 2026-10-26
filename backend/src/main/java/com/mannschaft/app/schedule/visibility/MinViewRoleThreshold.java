package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.RolePriority;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.schedule.MinViewRole;

/**
 * {@code schedules.min_view_role}（閲覧閾値軸）の<strong>単一正準</strong>の写像。
 *
 * <p>設計書 {@code docs/features/F03.1_schedule_shared.md}「{@code min_view_role} の挙動」:</p>
 * <ul>
 *   <li>{@code ANYONE} — 認証済みの全ロール（GUEST 含む）に公開＝閾値なし</li>
 *   <li>{@code SUPPORTER_PLUS} — SUPPORTER 以上</li>
 *   <li>{@code MEMBER_PLUS} — MEMBER 以上（既定）</li>
 *   <li>{@code ADMIN_ONLY} — <strong>DEPUTY_ADMIN 以上</strong>（ADMIN 限定ではない）</li>
 * </ul>
 *
 * <p><strong>ADMIN_ONLY の写像先に注意</strong>: {@link StandardVisibility#ADMINS_AND_ABOVE} は
 * {@code priority <= 2} で DEPUTY_ADMIN（priority=3）を弾くため設計書に反する。必ず
 * {@link StandardVisibility#DEPUTY_ADMINS_AND_ABOVE} を用いる（CMP-017b 第二隊が新設）。</p>
 *
 * <p><strong>本クラスを設ける理由</strong>: CMP-017b の事故は「閾値の写像が
 * {@code GoogleCalendarService} の private メソッドにだけ存在し、閲覧判定はどこからも
 * 読まない」という形で成立していた。写像を二重実装すると片方だけが是正されて再発するため、
 * 閲覧判定（{@link ScheduleVisibilityResolver}）と Google push 判定は本クラスを共有する。</p>
 */
public final class MinViewRoleThreshold {

    private MinViewRoleThreshold() {
    }

    /**
     * 閾値が要求する最小ロール名を返す。
     *
     * @param minViewRole 閾値（{@code null} 可＝閾値なし扱い）
     * @return 要求ロール名。閾値なし（{@code null} / {@code ANYONE}）なら {@code null}
     */
    public static String requiredRoleName(MinViewRole minViewRole) {
        if (minViewRole == null) {
            return null;
        }
        return switch (minViewRole) {
            case ANYONE -> null;
            case SUPPORTER_PLUS -> "SUPPORTER";
            case MEMBER_PLUS -> "MEMBER";
            // 設計書 F03.1「ADMIN_ONLY: DEPUTY_ADMIN・ADMIN のみ閲覧可」。
            case ADMIN_ONLY -> "DEPUTY_ADMIN";
        };
    }

    /**
     * 実効ロール名が閾値を満たすかを判定する（純メモリ・追加クエリなし）。
     *
     * <p>非メンバー（{@code roleName = null}）は最弱扱いとなり、閾値なし以外は満たさない。</p>
     *
     * @param roleName    対象ユーザーの実効ロール名（{@code null} 可）
     * @param minViewRole 閾値
     * @return 満たすなら {@code true}
     */
    public static boolean satisfies(String roleName, MinViewRole minViewRole) {
        String required = requiredRoleName(minViewRole);
        return required == null || RolePriority.isAtLeast(roleName, required);
    }

    /**
     * 閾値に対応する {@link StandardVisibility} の段を返す。
     *
     * @param minViewRole 閾値
     * @return 対応する段。閾値なしなら {@code null}
     */
    public static StandardVisibility toStandard(MinViewRole minViewRole) {
        if (minViewRole == null) {
            return null;
        }
        return switch (minViewRole) {
            case ANYONE -> null;
            case SUPPORTER_PLUS -> StandardVisibility.SUPPORTERS_AND_ABOVE;
            case MEMBER_PLUS -> StandardVisibility.MEMBERS_AND_ABOVE;
            case ADMIN_ONLY -> StandardVisibility.DEPUTY_ADMINS_AND_ABOVE;
        };
    }

    /**
     * scope 軸で解決済みのレベルに、閲覧閾値軸を合成して<strong>狭い方</strong>を返す。
     *
     * <p>合成対象は「コンテンツ所有スコープの直接所属ロールで評価される段」のみである
     * （{@link StandardVisibility#SCOPE_AFFILIATED} と閾値ラダー）。
     * {@link StandardVisibility#ORGANIZATION_WIDE} など「所属拡大軸」の段は、評価すべき
     * スコープが所有スコープではない（親組織である）ため、単一の enum 値では表現できない。
     * これらは {@link ScheduleVisibilityResolver#visibleByAdditionalAxis} 側で
     * 親組織ロールに対して評価する。{@link StandardVisibility#PRIVATE} などスコープ非依存の段は
     * 閾値の対象外（個人予定の所有者を閾値で潰さないため）。</p>
     *
     * @param level       scope 軸で解決済みのレベル（{@code null} 可）
     * @param minViewRole 閾値
     * @return 合成後のレベル
     */
    public static StandardVisibility tighten(StandardVisibility level, MinViewRole minViewRole) {
        StandardVisibility threshold = toStandard(minViewRole);
        if (level == null || threshold == null) {
            return level;
        }
        int levelRank = ownScopeRank(level);
        if (levelRank < 0) {
            // 所有スコープの閾値軸に属さない段（所属拡大軸 / PRIVATE / CUSTOM_TEMPLATE 等）。
            return level;
        }
        return ownScopeRank(threshold) > levelRank ? threshold : level;
    }

    /**
     * 「所有スコープの直接所属ロールで評価される段」の狭さ順位を返す。
     *
     * @param level レベル
     * @return 狭いほど大きい値。当該軸に属さない段は {@code -1}
     */
    private static int ownScopeRank(StandardVisibility level) {
        return switch (level) {
            // SCOPE_AFFILIATED は「ロールを持つ全員」＝閾値ラダーの最下段と同じ広さ。
            case SCOPE_AFFILIATED -> 0;
            case SUPPORTERS_AND_ABOVE -> 1;
            case MEMBERS_AND_ABOVE -> 2;
            case DEPUTY_ADMINS_AND_ABOVE -> 3;
            case ADMINS_AND_ABOVE -> 4;
            default -> -1;
        };
    }
}
