package com.mannschaft.app.village.service;

/**
 * 村の参加数上限に関する定数の唯一の定義場所（CMP-260827-1808）。
 *
 * <p>既定値・DB の天井をここ以外に直書きしない（マジックナンバー禁止）。Provider・枠の割当・起動時の検査・
 * テストはすべてここを参照する。Flyway の SQL は Java 定数を参照できないため、V255 の CHECK の天井と
 * 移行時の超過是正のしきい値がここと一致することは {@code VillageParticipationLimitsMigrationConsistencyTest} が照合する。</p>
 */
public final class VillageParticipationLimits {

    /** 1 USER が現役で所属できる村数の既定の上限（設定 {@code mannschaft.village.participation-hard-limit} の既定）。 */
    public static final int DEFAULT_HARD_LIMIT = 100;

    /**
     * DB が受け入れる USER 枠番号の天井（{@code ck_vm_active_user_slot} の {@code BETWEEN 1 AND} の右辺）。
     * 上限はこの値を超えて設定できない。天井を上げるには移行で CHECK を張り替える。
     */
    public static final int DB_SLOT_CEILING = 1000;

    /** 参加数がこの値を超えたら参加レスポンスに警告フラグを立てる。 */
    public static final int SOFT_WARN_THRESHOLD = 30;

    /** 上限として設定できる最小値。 */
    public static final int MIN_HARD_LIMIT = 1;

    private VillageParticipationLimits() {
    }
}
