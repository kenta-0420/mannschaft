package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.repository.ChatMessageKeywordSearchRepository;
import com.mannschaft.app.chat.repository.ChatMessageKeywordSearchRepository.Condition;
import com.mannschaft.app.chat.repository.ChatMessageKeywordSearchRepository.DateBound;
import com.mannschaft.app.chat.repository.ChatMessageKeywordSearchRepository.ScoredMessage;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.search.SearchContributor;
import com.mannschaft.app.search.SearchCriteria;
import com.mannschaft.app.search.SearchHit;
import com.mannschaft.app.search.SearchQueryText;
import com.mannschaft.app.search.SearchTextHighlighter;
import com.mannschaft.app.search.SearchType;
import com.mannschaft.app.search.SearchViewer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * グローバル検索 MESSAGE（チャットメッセージ）種別の {@link SearchContributor}（F04.6 §6、CMP-261116-0722 PR-3）。
 *
 * <p><b>可視性</b>: 閲覧者本人のチャンネルメンバー行がある、かつチャンネル種別がチーム／組織スコープ
 * （{@code ChannelType#isScopeChannel()}）でそのスコープが閲覧者の所属に含まれる、かつチャンネル・メッセージとも
 * 生存している、かつ通常の本文メッセージ（システム・招待カード・予約未送信を除く）。述語の詳細と件数・取得の
 * 同一 WHERE 句は {@link ChatMessageKeywordSearchRepository} を参照。DM / GROUP_DM は当事者にも出さない
 * （種別とスコープ列の対応で落とすので、DM の除外だけに依存しない）。{@code ChatMessageVisibilityResolver} は
 * チャンネルメンバーシップを見ないため単独ゲートに使わない。</p>
 *
 * <p><b>所属</b>は集約側 {@link SearchViewer}（GUEST のみ・削除済み・アーカイブ済みスコープを除外済み）を使う。
 * チャンネルのメンバー行が残っていても、スコープから外れていれば出さない。<b>スコープ指定時</b>は
 * {@link SearchViewer#narrowTo} で絞った 1 スコープのチャンネルだけを対象にする。</p>
 *
 * <p>{@code authorId} は {@code sender_id} に一致させる。スレッド返信も同一チャンネル内の可視性に従って対象にし、
 * 遷移先は返信を含むメッセージ一覧を表示するチャンネル画面 {@code /chat?channel={channelId}}。</p>
 *
 * <p><b>{@code @Transactional} を付けない理由</b>: 名前の解決は他ドメインの公開 Service（自前の読み取り
 * トランザクション）に委ねる。本クラスにトランザクションを張ると、その先の他ドメイン Repository まで chat の
 * トランザクションが越境する（D-3T）。件数と取得は同一の述語から別々に発行するため、同一スナップショットである
 * 必要はない（集約側も件数と取得を別呼び出しにしている）。</p>
 */
@Component
@RequiredArgsConstructor
public class ChatMessageSearchContributor implements SearchContributor {

    /** {@code CAST(:x AS DATETIME)} に渡す壁時計の書式。 */
    private static final DateTimeFormatter BOUND_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** IN 句を空にしないためのダミー ID（実在しない）。 */
    private static final Long EMPTY_IN_SENTINEL = -1L;

    private final ChatMessageKeywordSearchRepository keywordSearchRepository;
    private final NameResolverService nameResolverService;

    @Override
    public SearchType type() {
        return SearchType.MESSAGE;
    }

    @Override
    public long count(SearchCriteria criteria, SearchViewer viewer) {
        Condition condition = condition(criteria, viewer);
        if (condition == null) {
            return 0L;
        }
        return keywordSearchRepository.count(condition);
    }

    @Override
    public List<SearchHit> find(SearchCriteria criteria, SearchViewer viewer, int offset, int limit) {
        Condition condition = condition(criteria, viewer);
        if (condition == null) {
            return List.of();
        }
        List<ScoredMessage> scored = keywordSearchRepository.find(condition, offset, limit);
        if (scored.isEmpty()) {
            return List.of();
        }
        Set<Long> authorIds = new HashSet<>();
        Set<Long> teamIds = new HashSet<>();
        Set<Long> orgIds = new HashSet<>();
        for (ScoredMessage s : scored) {
            if (s.senderId() != null) {
                authorIds.add(s.senderId());
            }
            if (s.teamId() != null && s.channelType().startsWith("TEAM_")) {
                teamIds.add(s.teamId());
            } else if (s.organizationId() != null) {
                orgIds.add(s.organizationId());
            }
        }
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(authorIds);
        Map<Long, String> avatarUrls = nameResolverService.resolveUserAvatarUrls(authorIds);
        Map<Long, String> teamNames = nameResolverService.resolveTeamNames(teamIds);
        Map<Long, String> orgNames = nameResolverService.resolveOrganizationNames(orgIds);

        List<SearchHit> hits = new ArrayList<>(scored.size());
        for (ScoredMessage s : scored) {
            // 返却列はすべて可視性を判定した同じ SQL の行の値（ID で読み直さない。PR-6 検分 P1 の横展開）
            Long senderId = s.senderId();
            SearchHit.Author author = senderId == null ? null
                    : new SearchHit.Author(senderId, displayNames.get(senderId), avatarUrls.get(senderId));
            hits.add(new SearchHit(SearchType.MESSAGE, s.id(), null,
                    SearchTextHighlighter.snippet(s.body(), criteria.terms()), Map.of(), s.relevance(),
                    author, scopeRef(s, teamNames, orgNames),
                    SearchHit.createdAtOfStoredUtc(s.createdAtUtc()),
                    "/chat?channel=" + s.channelId(), List.of()));
        }
        return hits;
    }

    /**
     * 可視性述語の材料を組み立てる。該当が原理的に無い場合は null（SQL を発行しない）。
     */
    private Condition condition(SearchCriteria criteria, SearchViewer viewer) {
        String booleanQuery = SearchQueryText.toBooleanModeQuery(criteria.terms());
        if (booleanQuery.isBlank()) {
            return null;
        }
        // スコープ指定時は集約側で所属確認済みの 1 スコープだけに絞った母集団を使う（所属外なら空になる）
        SearchViewer population = viewer.narrowTo(criteria);
        List<Long> teamIds = population.teamIds();
        List<Long> orgIds = population.organizationIds();
        if (teamIds.isEmpty() && orgIds.isEmpty()) {
            // スコープチャンネルはいずれかの所属スコープに属するものだけが対象なので、所属が無ければ 0 件
            return null;
        }
        // 日付境界は閲覧者 TZ で作る（リクエストスレッドで解決済みの値。本メソッドは別スレッドで動く）
        ZoneId zone = criteria.viewerZone();
        return new Condition(booleanQuery, viewer.userId(), safe(teamIds), safe(orgIds), criteria.authorId(),
                startOf(criteria.dateFrom(), zone),
                criteria.dateTo() == null ? null : startOf(criteria.dateTo().plusDays(1), zone));
    }

    /** {@code IN} 句へ安全に渡せる集合（空ならダミー値で当該条件を無効化する）。 */
    private static List<Long> safe(List<Long> ids) {
        return ids.isEmpty() ? List.of(EMPTY_IN_SENTINEL) : ids;
    }

    /**
     * 閲覧者 TZ でのその日の開始（壁時計）と、その時点の UTC オフセット。
     *
     * <p>{@link LocalDate#atStartOfDay(ZoneId)} を使うため、0 時が夏時間の切替で存在しない地域でも
     * その日の実在する最初の時刻（例 01:00）を境界にする。</p>
     */
    private static DateBound startOf(LocalDate date, ZoneId zone) {
        if (date == null) {
            return null;
        }
        ZonedDateTime start = date.atStartOfDay(zone);
        ZoneOffset offset = start.getOffset();
        int totalMinutes = offset.getTotalSeconds() / 60;
        String sign = totalMinutes < 0 ? "-" : "+";
        int abs = Math.abs(totalMinutes);
        return new DateBound(start.toLocalDateTime().format(BOUND_FORMAT),
                String.format("%s%02d:%02d", sign, abs / 60, abs % 60));
    }

    private static SearchHit.ScopeRef scopeRef(ScoredMessage s, Map<Long, String> teamNames,
                                               Map<Long, String> orgNames) {
        if (s.channelType().startsWith("TEAM_")) {
            return new SearchHit.ScopeRef(SearchViewer.TEAM, s.teamId(), teamNames.get(s.teamId()));
        }
        return new SearchHit.ScopeRef(SearchViewer.ORGANIZATION, s.organizationId(),
                orgNames.get(s.organizationId()));
    }
}
