package com.mannschaft.app.succession.entity;

/**
 * 5 段階エスカレーションのステージ定義（F09.15 §5.7）。
 *
 * <p>滞納開始日（D+0）からの経過日数に応じて自動進行する:
 * <ul>
 *   <li>D+30  → {@link #STAGE_1_REMINDER}（督促）</li>
 *   <li>D+60  → {@link #STAGE_2_EMERGENCY_CONTACT}（緊急連絡先への連絡）</li>
 *   <li>D+90  → {@link #STAGE_3_WATCHER_VISIT}（見守り員の訪問）</li>
 *   <li>D+120 → {@link #STAGE_4_DEATH_SUSPECTED}（死亡疑い・行政連携）</li>
 *   <li>D+150 → {@link #STAGE_5_LEGAL_PREP}（法的手続き準備）</li>
 * </ul>
 *
 * <p>{@link DelinquencyEscalationEntity#getCurrentStage()} は String カラムで保存するが、
 * バリデーション・バッチ処理は本 enum で型安全に扱う。
 */
public enum DelinquencyEscalationStage {

    /** D+30: 督促状・電話連絡を開始する段階。 */
    STAGE_1_REMINDER,

    /** D+60: 緊急連絡先（事前登録）に連絡を試みる段階。 */
    STAGE_2_EMERGENCY_CONTACT,

    /** D+90: 見守り員（MonitoringCommittee）が現地訪問する段階。 */
    STAGE_3_WATCHER_VISIT,

    /** D+120: 長期連絡不通のため死亡・行方不明を疑い、行政への照会を行う段階。 */
    STAGE_4_DEATH_SUSPECTED,

    /** D+150: 弁護士への依頼・法的手続き準備を行う最終段階。これ以上は自動昇格しない。 */
    STAGE_5_LEGAL_PREP;

    /**
     * String 値から変換するユーティリティ（DB 文字列との相互変換用）。
     *
     * @param value DB に保存された文字列
     * @return 対応する enum 定数
     * @throws IllegalArgumentException 未知の値の場合
     */
    public static DelinquencyEscalationStage fromString(String value) {
        for (DelinquencyEscalationStage stage : values()) {
            if (stage.name().equals(value)) {
                return stage;
            }
        }
        throw new IllegalArgumentException("不明なエスカレーションステージ: " + value);
    }
}
