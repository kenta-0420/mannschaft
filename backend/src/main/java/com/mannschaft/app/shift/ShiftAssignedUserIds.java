package com.mannschaft.app.shift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

/**
 * {@code shift_slots.assigned_user_ids}（JSON 配列）の読み書きの唯一の定義（CMP-260908-2117）。
 *
 * <p><b>本列が「現在の割当状態の正本」である。</b> {@code shift_assignments} は
 * 「誰がいつどの戦略で割り当てたか」を記録する<b>履歴表</b>であり、現在の割当を問い合わせる
 * 読み出し経路の参照先ではない（設計 {@code docs/features/F03.5_shift/01_db_design.md} §割当の二表）。</p>
 *
 * <p>従来はこの JSON の直列化・復元が {@code ShiftSlotService} と
 * {@code ShiftAutoAssignService} にそれぞれ private メソッドとして重複しており、
 * 参照側（サマリー集計）が同じ処理を持てないために {@code shift_assignments} を
 * 見に行く実装になっていた。読み書きを本クラスへ一本化することで、
 * 「正本はどちらか」がコード上でも一意になる。</p>
 */
@Slf4j
public final class ShiftAssignedUserIds {

    /** ステートレスかつスレッドセーフな用途（List&lt;Long&gt; のみ）に限定した共有インスタンス。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<Long>> LIST_OF_LONG = new TypeReference<>() {
    };

    private ShiftAssignedUserIds() {
    }

    /**
     * JSON 配列文字列から割当ユーザー ID リストを復元する。
     *
     * <p>壊れた JSON は空リストとして扱う（fail-closed）。ここで例外を投げると
     * シフト表 1 枠の破損で一覧全体が 500 になるため、除外して先へ進める。</p>
     *
     * @param json JSON 配列文字列（{@code null} / 空文字可）
     * @return 割当ユーザー ID リスト（不変ではないが呼び出し側で変更しないこと）
     */
    public static List<Long> parse(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Long> parsed = MAPPER.readValue(json, LIST_OF_LONG);
            return parsed != null ? parsed : Collections.emptyList();
        } catch (JsonProcessingException e) {
            log.warn("assigned_user_ids の復元に失敗したため空として扱う: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 割当ユーザー ID リストを JSON 配列文字列へ直列化する。
     *
     * <p>空リストは {@code null} を返す（列の意味を「割当なし」に揃えるため。
     * 既存データにも {@code NULL} と {@code []} が混在しうるので、
     * 読み出し側は {@link #parse(String)} で両方を空として受ける）。</p>
     *
     * @param userIds 割当ユーザー ID リスト（{@code null} 可）
     * @return JSON 配列文字列、または割当なしを表す {@code null}
     */
    public static String serialize(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(userIds);
        } catch (JsonProcessingException e) {
            // Long のリストが直列化できないことは実質ありえないが、握りつぶすと
            // 「割当したのに保存されない」無言の欠落になるため、明示的に失敗させる。
            throw new IllegalStateException("assigned_user_ids の直列化に失敗しました", e);
        }
    }
}
