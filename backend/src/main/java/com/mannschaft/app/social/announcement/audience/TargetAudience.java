package com.mannschaft.app.social.announcement.audience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

/**
 * 送信時の宛先指定の記録（{@code announcement_feeds.target_audience} の JSON。F01.2.1 §5.6・AC-H17）。
 *
 * <p>送信履歴の「宛先: G2 以前（送信時 2 チーム）」の表示に使う。グループ名は送信時の名前で残すため、
 * 後でグループを改名・削除しても変わらない。表示判定には使わない。</p>
 *
 * @param mode              {@code TEAMS}（チームを選ぶ）または {@code GROUPS}（チームグループで選ぶ）
 * @param groups            範囲を展開した後のグループ（送信時の名前。TEAMS では空）
 * @param range             範囲指定（無ければ null）
 * @param includeUnassigned 未分類のチームを含めたか
 * @param teamCount         送信時に解決した宛先チーム数
 * @param directMemberCount 送信時の直属メンバー数（送信者本人を除く組織メンバー）
 */
public record TargetAudience(
        String mode,
        List<GroupRef> groups,
        RangeRef range,
        boolean includeUnassigned,
        int teamCount,
        int directMemberCount) {

    /** 「チームを選ぶ」。 */
    public static final String MODE_TEAMS = "TEAMS";

    /** 「チームグループで選ぶ」。 */
    public static final String MODE_GROUPS = "GROUPS";

    /** 保存された JSON の読み取り用（書き込み側が保存した JSON をそのまま読む。未知の項目は無視する）。 */
    private static final ObjectMapper READER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * {@code announcement_feeds.target_audience} の JSON を読む。無ければ null。
     * 壊れていれば黙って捨てず例外にする（お知らせ一覧・ダッシュボードで同じ読み方をするための唯一の入口）。
     *
     * @param json 保存された JSON（null・空可）
     * @return 宛先の記録。記録が無ければ null
     */
    public static TargetAudience fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return READER.readValue(json, TargetAudience.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("announcement_feeds.target_audience の JSON を読めません", e);
        }
    }

    /**
     * グループの参照（送信時の名前）。
     *
     * @param id   グループ ID
     * @param name 送信時の名前
     */
    public record GroupRef(UUID id, String name) {
    }

    /**
     * 範囲指定の記録（送信時の名前）。
     *
     * @param fromGroupId   開始グループ ID（「以前」は null）
     * @param fromGroupName 開始グループの送信時の名前
     * @param toGroupId     終了グループ ID（「以降」は null）
     * @param toGroupName   終了グループの送信時の名前
     */
    public record RangeRef(UUID fromGroupId, String fromGroupName, UUID toGroupId, String toGroupName) {
    }
}
