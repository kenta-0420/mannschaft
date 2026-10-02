package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftScheduleStatus;

import java.time.LocalDateTime;

/**
 * シフト表の可視性判定の唯一の定義（CMP-260826-2127）。
 *
 * <p>正本設計: {@code docs/features/F03.5_shift/05_unpublished_visibility.md} §3.6 / G-1。
 * 一覧・単体・枠一覧・PDF・グローバル検索の全経路は、ステータス条件式を書き写さず
 * <b>本クラスだけ</b>を参照すること。</p>
 *
 * <p>隔ての軸は「情報の層 × ステータス」であり、ステータス単独で API を開閉しない。
 * 非管理者から見た可視性は次の 3 分類になる:</p>
 *
 * <ul>
 *   <li>{@link Visibility#HIDDEN} — 存在ごと秘匿（一覧から除外・単体404・枠404・PDF404・検索ヒットせず）。
 *       {@code DRAFT}、{@code ARCHIVED} かつ {@code publishedAt} が NULL、および status が NULL（fail-closed）。</li>
 *   <li>{@link Visibility#MASKED} — メタと枠の骨格は開き、割当だけ伏せる。{@code COLLECTING} / {@code ADJUSTING}。</li>
 *   <li>{@link Visibility#FULL} — 全量。{@code PUBLISHED}（{@code publishedAt} は見ない）、
 *       および {@code ARCHIVED} かつ {@code publishedAt} が非 NULL。</li>
 * </ul>
 *
 * <p><b>publishedAt の扱いが非対称である理由</b>: {@code shift_schedules.published_at}（V3.070）には
 * status との整合制約が無く、{@code PUBLISHED} かつ {@code publishedAt IS NULL} の行が実在しうる
 *（{@code ShiftMapperTest} / {@code ShiftSwapScopeContractIT} が実際に作る）。よって PUBLISHED は
 * status だけで公開扱いとする。一方 {@code ARCHIVED} は「PUBLISHED を経たもの」と
 * 「DRAFT から直接アーカイブされたもの」の両方を含みうる（{@code transitionStatus} に遷移元ガードが無い）ため、
 * 判別できない側を閉じる方向に倒す（fail-closed）。</p>
 *
 * <p><b>閲覧者の判定は本クラスに含めない。</b> 管理者（SYSTEM_ADMIN／当該チームの ADMIN・DEPUTY_ADMIN）判定は
 * 各サービスが {@code AccessControlService} を直接呼ぶ。ArchUnit の認可番人が委譲を 2 ホップまでしか
 * 追跡できず、認可呼び出しを別クラスへ隠すと番人から見えなくなるためである
 *（{@code ShiftScheduleService#checkScheduleAdminAccess} の既存コメントと同じ事情）。</p>
 *
 * <p>検索クエリ（JPQL）側の同値な述語は
 * {@link com.mannschaft.app.shift.entity.ShiftScheduleEntity#NOT_HIDDEN_JPQL} が持つ
 *（{@code @Query} のアノテーション値に埋め込むためコンパイル時定数である必要がある）。</p>
 */
public final class ShiftScheduleVisibilityPolicy {

    private ShiftScheduleVisibilityPolicy() {
    }

    /** 非管理者から見た可視性の 3 分類。 */
    public enum Visibility {
        /** 存在ごと秘匿。 */
        HIDDEN,
        /** メタと枠の骨格は開き、割当のみ伏せる。 */
        MASKED,
        /** 全量。 */
        FULL;

        /** 存在ごと秘匿すべきか。 */
        public boolean isHidden() {
            return this == HIDDEN;
        }

        /** 割当を伏せるべきか。 */
        public boolean isAssignmentMasked() {
            return this == MASKED;
        }
    }

    /**
     * 非管理者から見たシフト表の可視性を分類する（§3.6 の 3 分類）。
     *
     * @param status      ステータス（{@code null} は fail-closed で HIDDEN）
     * @param publishedAt 公開日時
     * @return 可視性
     */
    public static Visibility classify(ShiftScheduleStatus status, LocalDateTime publishedAt) {
        if (status == null) {
            return Visibility.HIDDEN;
        }
        return switch (status) {
            case COLLECTING, ADJUSTING -> Visibility.MASKED;
            // PUBLISHED は publishedAt を見ない（published_at に整合制約が無いため）。
            case PUBLISHED -> Visibility.FULL;
            // ARCHIVED は publishedAt が唯一の手がかり。NULL は fail-closed。
            case ARCHIVED -> publishedAt != null ? Visibility.FULL : Visibility.HIDDEN;
            case DRAFT -> Visibility.HIDDEN;
        };
    }

    /**
     * DTO 経由（ステータスが文字列）での可視性分類。PDF が使う。
     *
     * <p>DTO は {@code status} を設定しないまま組み立てられる経路があるため、
     * <b>{@code null} および未知の値は未公開扱い（fail-closed）</b>とする。
     * {@code null} を公開扱いにすると、DTO を部分的にしか組み立てない経路から遮断がまるごと抜ける。</p>
     *
     * @param statusName  ステータス名（{@code null} / 未知の値は HIDDEN）
     * @param publishedAt 公開日時
     * @return 可視性
     */
    public static Visibility classifyByStatusName(String statusName, LocalDateTime publishedAt) {
        if (statusName == null) {
            return Visibility.HIDDEN;
        }
        try {
            return classify(ShiftScheduleStatus.valueOf(statusName), publishedAt);
        } catch (IllegalArgumentException e) {
            return Visibility.HIDDEN;
        }
    }
}
