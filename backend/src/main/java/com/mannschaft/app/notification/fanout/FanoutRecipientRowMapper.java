package com.mannschaft.app.notification.fanout;

import java.util.ArrayList;
import java.util.List;

/**
 * 受信者 keyset クエリの native 戻り値（{@code Object[]{user_id, locale}}）を
 * {@link FanoutRecipient} のリストへ写す共通マッパ（Issue #2871）。
 *
 * <p>4 つの受信者ソース（VILLAGE / TEAM / SCHEDULE_KEEP_TEAM / ORGANIZATION）はいずれも
 * 「1 列目に user_id、2 列目に locale」という同じ形の行を返す。写す処理を各ソースに複製すると、
 * {@code BigInteger} への mismap 対策や null locale の扱いが実装ごとにズレるため、ここへ集約する。
 * locale の正規化そのものは {@link FanoutRecipient} のコンパクトコンストラクタが必ず行う。</p>
 */
public final class FanoutRecipientRowMapper {

    private FanoutRecipientRowMapper() {
    }

    /**
     * {@code [user_id, locale]} の行リストを {@link FanoutRecipient} のリストへ写す。
     *
     * <p>1 列目は native query の戻りが環境により {@code BigInteger} / {@code Long} と揺れるため
     * {@link Number} 経由で受ける（既存の {@code CAST(... AS SIGNED)} と同じ意図の防御）。
     * user_id が null の行は受信者たりえないため落とす（NOT NULL 違反で 1 件ずつ落ちるのを待たない）。</p>
     */
    public static List<FanoutRecipient> toRecipients(List<Object[]> rows) {
        List<FanoutRecipient> recipients = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            Object rawUserId = row[0];
            if (rawUserId == null) {
                continue;
            }
            long userId = ((Number) rawUserId).longValue();
            String locale = row.length > 1 && row[1] != null ? String.valueOf(row[1]) : null;
            recipients.add(new FanoutRecipient(userId, locale));
        }
        return recipients;
    }
}
