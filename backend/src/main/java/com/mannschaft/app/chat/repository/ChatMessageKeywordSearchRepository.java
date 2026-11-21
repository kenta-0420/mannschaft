package com.mannschaft.app.chat.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * グローバル検索（F04.6 MESSAGE 種別）のチャットメッセージキーワード検索クエリ（CMP-261116-0722 PR-3）。
 *
 * <p><b>可視性述語</b>（件数と取得で同一の WHERE 句を使う）。次の<b>すべて</b>を満たすメッセージだけを返す:</p>
 * <ol>
 *   <li>閲覧者本人が {@code chat_channel_members} に行を持つチャンネル（未参加の公開チャンネルも対象外。
 *       退出・除名はメンバー行の物理削除なので、その時点で出なくなる）。</li>
 *   <li>チャンネル種別が {@code ChannelType#isScopeChannel()} の 4 種で、かつ<b>種別とスコープ列が対応</b>していること:
 *       {@code TEAM_PUBLIC / TEAM_PRIVATE} は {@code team_id} が閲覧者の所属チーム、
 *       {@code ORG_PUBLIC / ORG_PRIVATE} は {@code organization_id} が閲覧者の所属組織。
 *       DM / GROUP_DM・VILLAGE_LOBBY・EVENT_CHAT・TOURNAMENT* は {@code team_id} が付いていてもどの句にも該当しない。
 *       所属は集約側 {@code SearchViewer}（GUEST のみ・削除済み・アーカイブ済みスコープを除外済み）から渡す。</li>
 *   <li>チャンネルが論理削除・アーカイブされていない。</li>
 *   <li>メッセージが論理削除されておらず、{@code is_system = FALSE}、{@code message_type = 'TEXT'}（INVITE_CARD 等を除外）、
 *       予約未送信（{@code scheduled_at IS NOT NULL AND scheduled_sent_at IS NULL}）でない。</li>
 * </ol>
 * <p>スレッド返信（{@code parent_id} あり）は親と同じチャンネルに属し、可視性も同じなので区別せず対象にする
 * （チャンネル画面のメッセージ一覧は返信も含めて表示するため、遷移先 {@code /chat?channel=} から辿れる）。</p>
 *
 * <p><b>時刻列</b>: 期間指定は Java の壁時計を時刻列へ束縛しない（datetime_policy §4.1）。閲覧者 TZ の日付と
 * UTC オフセットを渡し、SQL の {@code CONVERT_TZ} で UTC 格納基準へ変換してから比較する。</p>
 *
 * <p>値はすべてバインドし、所属スコープ数は IN 句のパラメータ 1 個に収まる（所属数に比例した SQL は発行しない）。</p>
 */
@Repository
public class ChatMessageKeywordSearchRepository {

    private static final String MATCH = "MATCH(m.body) AGAINST(:keyword IN BOOLEAN MODE)";

    @PersistenceContext
    private EntityManager em;

    /**
     * 検索条件（可視性の材料は呼び出し側で解決済み。IN 句用の集合は空にしないこと）。
     *
     * @param booleanQuery FULLTEXT BOOLEAN MODE の検索式（空にしないこと）
     * @param viewerUserId 閲覧者（チャンネルメンバー行の照合）
     * @param teamIds      母集団の所属チーム ID（空ならダミー値）
     * @param orgIds       母集団の所属組織 ID（空ならダミー値）
     * @param authorId     送信者（{@code sender_id}。null なら絞らない）
     * @param from         期間開始（null なら絞らない）
     * @param toExclusive  期間終了の翌日開始（null なら絞らない）
     */
    public record Condition(String booleanQuery, Long viewerUserId, List<Long> teamIds, List<Long> orgIds,
                            Long authorId, DateBound from, DateBound toExclusive) {
    }

    /**
     * 閲覧者 TZ の日付境界。
     *
     * @param localDateTime {@code yyyy-MM-dd HH:mm:ss} 形式の閲覧者 TZ の壁時計
     * @param utcOffset     その時点の UTC オフセット（{@code +09:00} 形式）
     */
    public record DateBound(String localDateTime, String utcOffset) {
    }

    /**
     * 1 件分の取得結果（すべて可視性を判定した同じ SQL の行から取った値）。
     *
     * @param id             メッセージ ID
     * @param body           本文
     * @param senderId       送信者（null 可）
     * @param channelId      チャンネル ID
     * @param createdAtUtc   作成日時の DB 格納値（UTC 壁時計、{@code yyyy-MM-dd HH:mm:ss.SSSSSS}。null 可）
     * @param relevance      FULLTEXT の関連度
     * @param channelType    チャンネル種別名
     * @param teamId         チャンネルのチーム ID（チームチャンネル以外は null）
     * @param organizationId チャンネルの組織 ID（組織チャンネル以外は null）
     */
    public record ScoredMessage(Long id, String body, Long senderId, Long channelId, String createdAtUtc,
                                double relevance, String channelType, Long teamId, Long organizationId) {
    }

    /** 可視メッセージの件数（{@link #find} と同一の WHERE 句）。 */
    public long count(Condition condition) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery(
                "SELECT COUNT(*) FROM chat_messages m JOIN chat_channels c ON c.id = m.channel_id" + where);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }

    /**
     * 可視メッセージを関連度→新しい順に 1 ページ分取得する（{@link #count} と同一の WHERE 句）。
     *
     * <p>結果に載せる列は、可視性を判定した<b>同じ SQL の SELECT 列</b>から取る。ID だけを取って別クエリで行を
     * 読み直すと、その間にスコープ移動等と本文の更新が同時にコミットされた場合に、判定時とは別の状態の本文を返して
     * しまう（CMP-261116-0722 PR-6 検分 P1 の横展開）。作成日時は DB の UTC 格納値を文字列で返す。</p>
     */
    public List<ScoredMessage> find(Condition condition, int offset, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = where(condition, params);
        Query query = em.createNativeQuery("SELECT m.id, m.body, m.sender_id, m.channel_id,"
                + " DATE_FORMAT(m.created_at, '%Y-%m-%d %H:%i:%s.%f') AS created_at_utc, " + MATCH
                + " AS relevance, c.channel_type, c.team_id, c.organization_id"
                + " FROM chat_messages m JOIN chat_channels c ON c.id = m.channel_id" + where
                + " ORDER BY relevance DESC, m.created_at DESC, m.id DESC LIMIT :limit OFFSET :offset");
        params.forEach(query::setParameter);
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<ScoredMessage> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new ScoredMessage(toLong(row[0]), text(row[1]), toLong(row[2]), toLong(row[3]),
                    text(row[4]), relevance(row[5]), text(row[6]), toLong(row[7]), toLong(row[8])));
        }
        return result;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static double relevance(Object value) {
        return value == null ? 0d : ((Number) value).doubleValue();
    }

    private static String where(Condition c, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder(" WHERE ").append(MATCH)
                .append(" AND m.deleted_at IS NULL AND m.is_system = FALSE AND m.message_type = 'TEXT'")
                .append(" AND NOT (m.scheduled_at IS NOT NULL AND m.scheduled_sent_at IS NULL)")
                .append(" AND c.deleted_at IS NULL AND c.is_archived = FALSE")
                // 種別とスコープ列の対応まで確かめる（team_id 付きの DM・対象外種別をここで落とす）
                .append(" AND ((c.channel_type IN ('TEAM_PUBLIC', 'TEAM_PRIVATE') AND c.team_id IN (:teamIds))")
                .append(" OR (c.channel_type IN ('ORG_PUBLIC', 'ORG_PRIVATE') AND c.organization_id IN (:orgIds)))")
                // 閲覧者本人のメンバー行（未参加の公開チャンネル・退出／除名後は出さない）
                .append(" AND EXISTS (SELECT 1 FROM chat_channel_members cm")
                .append(" WHERE cm.channel_id = c.id AND cm.user_id = :viewerUserId)");
        params.put("keyword", c.booleanQuery());
        params.put("teamIds", c.teamIds());
        params.put("orgIds", c.orgIds());
        params.put("viewerUserId", c.viewerUserId());
        if (c.authorId() != null) {
            sb.append(" AND m.sender_id = :authorId");
            params.put("authorId", c.authorId());
        }
        if (c.from() != null) {
            sb.append(" AND m.created_at >= CONVERT_TZ(CAST(:fromLocal AS DATETIME), :fromOffset, '+00:00')");
            params.put("fromLocal", c.from().localDateTime());
            params.put("fromOffset", c.from().utcOffset());
        }
        if (c.toExclusive() != null) {
            sb.append(" AND m.created_at < CONVERT_TZ(CAST(:toLocal AS DATETIME), :toOffset, '+00:00')");
            params.put("toLocal", c.toExclusive().localDateTime());
            params.put("toOffset", c.toExclusive().utcOffset());
        }
        return sb.toString();
    }
}
