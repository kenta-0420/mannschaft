package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxItemRef;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.InboxLabelSuggestion;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.dto.SuggestedLabelDto;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.entity.NotificationLabelEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
import com.mannschaft.app.inbox.repository.NotificationLabelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F04.11 統合通知インボックス：集約サービス。
 *
 * <p>各ソースアダプタを読み取り集約し {@code InboxItem} に正規化、triage 状態/ラベルのオーバーレイを
 * <b>まとめ取り</b>してマージする（手本: {@code DashboardService.getPersonalDashboard}）。
 * 設計書: 03_business_logic.md §1・§4。MVP は NOTIFICATION + TODO_DUE の 2 アダプタ前提。</p>
 *
 * <p><b>N+1 回避の肝</b>: オーバーレイ状態は {@code findByUserIdAndSourceTypeIn} で 1 回、ラベルは
 * 出現した sourceType ごとに {@code findByUserIdAndSourceTypeAndSourceIdIn} で 1 回ずつ取得する
 * （item 件数に依らず定数回＝最大ソース種別数）。</p>
 */
@Service
@RequiredArgsConstructor
public class InboxAggregationService {

    private final List<InboxSourceAdapter> sourceAdapters;
    private final InboxPriorityNormalizer priorityNormalizer;
    private final InboxItemStateRepository itemStateRepository;
    private final InboxLabelLinkRepository labelLinkRepository;
    private final NotificationLabelRepository labelRepository;
    private final InboxLabelSuggestionRules suggestionRules;

    /**
     * インボックス一覧を集約取得する（フィルタ・ページング）。
     */
    public InboxPageResponse getInbox(
            Long userId,
            String stateFilter,
            List<InboxPriority> priorities,
            List<InboxSourceType> sourceTypes,
            UUID labelId,
            int page,
            int size) {

        // 境界付きウィンドウ（Phase3 ③）: 当該ページまでに必要な上位件数 + 安全マージン。
        // 各アダプタはこのウィンドウ件数までしか取得しない（無制限 fetch を根絶）。
        int window = requiredWindow(page, size);
        List<InboxItemDto> merged = collectMergedItems(userId, window);

        // フィルタ（state → sourceType → priority → label）
        List<InboxItemDto> filtered = merged.stream()
                .filter(it -> matchesState(it, stateFilter))
                .filter(it -> sourceTypes == null || sourceTypes.isEmpty()
                        || sourceTypes.contains(it.sourceType()))
                .filter(it -> priorities == null || priorities.isEmpty()
                        || priorities.contains(it.priority()))
                .filter(it -> labelId == null || hasLabel(it, labelId))
                .sorted(ITEM_ORDER)
                .toList();

        long totalEstimated = filtered.size();
        int from = Math.min(page * size, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<InboxItemDto> pageItems = new ArrayList<>(filtered.subList(from, to));
        boolean hasMore = to < filtered.size();

        return new InboxPageResponse(pageItems, page, size, totalEstimated, hasMore);
    }

    /**
     * 状態別・緊急度別・種類別の件数サマリを取得する（タブ/バッジ用）。
     */
    public InboxSummaryResponse getSummary(Long userId) {
        // サマリは概算（タブ/バッジ用）。件数の上ぶれを抑えるためサマリ専用の広めウィンドウで取得する。
        List<InboxItemDto> merged = collectMergedItems(userId, SUMMARY_WINDOW);

        Map<String, Long> byState = new LinkedHashMap<>();
        Map<String, Long> byPriority = new LinkedHashMap<>();
        Map<String, Long> bySourceType = new LinkedHashMap<>();

        for (InboxItemDto it : merged) {
            byState.merge(it.state().name(), 1L, Long::sum);
            byPriority.merge(it.priority().name(), 1L, Long::sum);
            bySourceType.merge(it.sourceType().name(), 1L, Long::sum);
        }
        return new InboxSummaryResponse(byState, byPriority, bySourceType);
    }

    // ─────────────────────────────────────────────────────────────────
    // 集約 + オーバーレイマージ（N+1 回避のまとめ取り）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 安全マージン（Phase3 ③）。名寄せ畳み込みで件数が減る・境界での同着を吸収するための余裕。
     * 当該ページの直前直後に同 priority・同 occurredAt の項目が連なっても取りこぼさないようにする。
     */
    private static final int SAFETY_MARGIN = 20;

    /** サマリ集計用の広めウィンドウ（件数バッジは概算で十分・暴走防止に上限を設ける）。 */
    private static final int SUMMARY_WINDOW = 500;

    /**
     * ソート規則（<b>完全な全順序</b>・Phase3 ③）: priority DESC（URGENT が先頭）→ occurredAt DESC（新しい順）
     * → sourceType 名 → sourceId のタイブレーク。
     *
     * <p>同 priority・同 occurredAt の同着を sourceType/sourceId で決定的に解消することで、ページ境界での
     * 重複/欠落（同着順序がリクエスト毎に揺れて隣接ページに項目が漏れる事故）を防ぐ。これが境界付きウィンドウの
     * 決定性を支える不変条件。occurredAt は nullsLast（時刻なしは末尾）。</p>
     */
    private static final Comparator<InboxItemDto> ITEM_ORDER =
            Comparator.<InboxItemDto>comparingInt(it -> it.priority().ordinal())  // URGENT=0 が先頭
                    .thenComparing(InboxItemDto::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(it -> it.sourceType().name())   // タイブレーク1: ソース種別名（昇順）
                    .thenComparing(InboxItemDto::sourceId,
                            Comparator.nullsLast(Comparator.naturalOrder()));  // タイブレーク2: sourceId（昇順）

    /**
     * 当該ページまでに必要な取得ウィンドウ件数を算出する（Phase3 ③）。
     *
     * <p>{@code (page+1)*size} が「当該ページ末尾までに表示しうる上位件数」。これに {@link #SAFETY_MARGIN} を
     * 足したものをウィンドウとし、各ソースはこのウィンドウ件数までしか取得しない。オーバーフロー回避のため
     * {@code int} 上限でクランプする。</p>
     */
    private static int requiredWindow(int page, int size) {
        long needed = (long) (page + 1) * size + SAFETY_MARGIN;
        return (int) Math.min(needed, Integer.MAX_VALUE);
    }

    /**
     * 全アダプタから生項目を集め、triage 状態・ラベルをまとめ取りしてマージした項目リストを返す。
     *
     * @param window 各アダプタの取得上限件数（境界付きウィンドウ・Phase3 ③）
     */
    private List<InboxItemDto> collectMergedItems(Long userId, int window) {
        // 1. 各アダプタを境界付きウィンドウで呼び生項目を集約（無制限 fetch を根絶）
        List<InboxItemDto> raw = new ArrayList<>();
        for (InboxSourceAdapter adapter : sourceAdapters) {
            raw.addAll(adapter.fetch(userId, window));
        }

        // 2-a. オーバーレイ状態を user_id でまとめ取り（item 件数に依らず 1 回）
        Set<InboxSourceType> presentTypes = raw.stream()
                .map(InboxItemDto::sourceType)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, InboxItemStateEntity> stateByKey = new HashMap<>();
        if (!presentTypes.isEmpty()) {
            List<InboxItemStateEntity> states =
                    itemStateRepository.findByUserIdAndSourceTypeIn(userId, presentTypes);
            for (InboxItemStateEntity s : states) {
                stateByKey.put(key(s.getSourceType(), s.getSourceId()), s);
            }
        }

        // 2-b. ラベルを sourceType ごとにまとめ取り（item 毎には引かない＝最大ソース種別数回）
        Map<String, List<LabelDto>> labelsByKey =
                fetchLabelsBounded(userId, raw, presentTypes);

        // 3. 各生項目に state/labels を被せて確定
        List<InboxItemDto> merged = new ArrayList<>(raw.size());
        for (InboxItemDto r : raw) {
            String k = key(r.sourceType(), r.sourceId());
            InboxItemStateEntity overlay = stateByKey.get(k);
            InboxState state = resolveState(r, overlay);
            LocalDateTime snoozedUntil = overlay != null ? overlay.getSnoozedUntil() : r.snoozedUntil();
            List<LabelDto> labels = labelsByKey.getOrDefault(k, List.of());

            // canonicalRef / groupCount / groupMembers はアダプタ計算値を保持する（畳み込みは次段で行う）。
            merged.add(new InboxItemDto(
                    r.id(), r.sourceType(), r.sourceId(), r.title(), r.excerpt(),
                    r.priority(), r.scope(), r.actionUrl(), r.occurredAt(),
                    state, snoozedUntil, labels,
                    r.canonicalRef(), r.groupCount(), r.groupMembers()));
        }

        // 4. 名寄せ（Phase 3 ①）：canonicalRef でグルーピングし 2 件以上のみ 1 代表へ畳む。
        List<InboxItemDto> folded = foldByCanonicalRef(merged);

        // 5. 自動ラベリング提案（案C・Phase 4・非永続）：静的ルールで各カードへ提案を被せる。
        return applySuggestions(userId, folded);
    }

    /**
     * 各カードへ自動ラベリング提案（{@link SuggestedLabelDto}）を被せる（案C・非永続・読み取り時導出）。
     *
     * <p>提案は {@link InboxLabelSuggestionRules} の静的ルールで {@code (sourceType, priority)} から導出する。
     * <b>重複提案の抑制</b>: ユーザーが既に同義ラベル（既定名一致）を手作成済みなら、その {@code labelId} を
     * {@code existingLabelId} に詰める。さらにそのラベルが <b>当該カードに既に付与済み</b>なら提案自体を外す
     * （設計書 03 §10）。名寄せできない提案は {@code existingLabelId=null}（FE が find-or-create に倒す）。</p>
     *
     * <p>N+1 回避: ユーザーの現役ラベルは <b>1 回だけ</b>まとめ取りして名前→ID の写像を作る
     * （カード件数に依らず定数回）。提案が 1 件も出ないケースに備え、ラベルが無ければクエリも省く。</p>
     */
    private List<InboxItemDto> applySuggestions(Long userId, List<InboxItemDto> cards) {
        // 既定名 → labelId 写像（重複提案抑制・existingLabelId 解決用）。提案が出るカードがあるときだけ引く。
        Map<String, UUID> labelIdByDefaultName = null;

        List<InboxItemDto> result = new ArrayList<>(cards.size());
        for (InboxItemDto card : cards) {
            List<InboxLabelSuggestion> keys = suggestionRules.suggest(card.sourceType(), card.priority());
            if (keys.isEmpty()) {
                result.add(card);  // 提案なし＝そのまま（suggestedLabels は空リスト既定）
                continue;
            }
            if (labelIdByDefaultName == null) {
                labelIdByDefaultName = loadDefaultNameToLabelId(userId);
            }

            List<SuggestedLabelDto> suggestions = new ArrayList<>(keys.size());
            for (InboxLabelSuggestion key : keys) {
                UUID existingLabelId = labelIdByDefaultName.get(key.defaultName());
                // 既に同義ラベルが当該カードに付与済みなら重複提案を抑制する
                if (existingLabelId != null && cardHasLabel(card, existingLabelId)) {
                    continue;
                }
                suggestions.add(new SuggestedLabelDto(key, key.defaultColor(), existingLabelId));
            }
            result.add(card.withSuggestedLabels(suggestions));
        }
        return result;
    }

    /**
     * ユーザーの現役ラベル名（既定名と突合する小文字キー）→ labelId 写像を 1 クエリで作る。
     * 既定名と一致する手作成ラベルがあれば existingLabelId を埋め、重複提案抑制に使う。
     */
    private Map<String, UUID> loadDefaultNameToLabelId(Long userId) {
        Map<String, UUID> map = new HashMap<>();
        for (NotificationLabelEntity label : labelRepository.findByUserIdOrderBySortOrderAsc(userId)) {
            if (label.getName() != null) {
                // 後勝ちを避けるため最初の一致のみ採用（表示順が安定）
                map.putIfAbsent(label.getName(), label.getId());
            }
        }
        return map;
    }

    /** カードに指定 labelId のラベルが既に付与されているか。 */
    private boolean cardHasLabel(InboxItemDto card, UUID labelId) {
        return card.labels() != null
                && card.labels().stream().anyMatch(l -> labelId.equals(l.id()));
    }

    /**
     * {@code canonicalRef} で項目をグルーピングし、2 件以上のグループを 1 代表へ畳む（Phase 3 ① 名寄せ）。
     *
     * <p><b>誤突合の安全弁</b>: 畳むのは「正規化成功かつ同一 EntityRef（canonicalRef 一致）」のときのみ。
     * 正規化不能な項目は各アダプタが自分自身キー（{@code "{sourceType}:{sourceId}"} 等の固有値）を
     * canonicalRef に詰めているため、決して他項目と同一グループにならない（設計書 §8）。</p>
     *
     * <ul>
     *   <li><b>代表</b>: グループ内で {@link #ITEM_ORDER} 最上位（priority 最優先・新着）。</li>
     *   <li><b>groupCount</b>: 構成メンバー件数（単一は 1）。</li>
     *   <li><b>groupMembers</b>: 全構成メンバーの {@code (sourceType, sourceId)}（FE が bulk triage で一括適用）。</li>
     *   <li><b>state</b>: 最も未処理側（UNREAD &gt; READ &gt; SNOOZED &gt; ARCHIVED を優先）。</li>
     *   <li><b>labels</b>: 全メンバーのラベル和集合（labelId 重複排除）。</li>
     * </ul>
     */
    private List<InboxItemDto> foldByCanonicalRef(List<InboxItemDto> merged) {
        // 出現順を保ちつつ canonicalRef でグルーピング（null は理論上起きないが安全側で自分自身 id 扱い）。
        Map<String, List<InboxItemDto>> groups = new LinkedHashMap<>();
        for (InboxItemDto it : merged) {
            String ref = it.canonicalRef() != null ? it.canonicalRef() : it.id();
            groups.computeIfAbsent(ref, k -> new ArrayList<>()).add(it);
        }

        List<InboxItemDto> result = new ArrayList<>(groups.size());
        for (List<InboxItemDto> group : groups.values()) {
            if (group.size() == 1) {
                // 単一＝畳まない。アダプタ既定（groupCount=1・members 自分 1 件）をそのまま使う。
                result.add(group.get(0));
                continue;
            }

            // 代表＝ITEM_ORDER 最上位。
            InboxItemDto representative = group.stream().min(ITEM_ORDER).orElse(group.get(0));

            // groupMembers＝全メンバーの参照（重複排除・出現順）。
            List<InboxItemRef> members = group.stream()
                    .map(it -> new InboxItemRef(it.sourceType(), it.sourceId()))
                    .distinct()
                    .toList();

            // state＝最も未処理側。labels＝和集合（labelId で重複排除）。
            InboxState mergedState = group.stream()
                    .map(InboxItemDto::state)
                    .min(Comparator.comparingInt(InboxAggregationService::stateUnprocessedRank))
                    .orElse(representative.state());

            Map<UUID, LabelDto> unionLabels = new LinkedHashMap<>();
            for (InboxItemDto it : group) {
                if (it.labels() == null) {
                    continue;
                }
                for (LabelDto label : it.labels()) {
                    unionLabels.putIfAbsent(label.id(), label);
                }
            }

            result.add(new InboxItemDto(
                    representative.id(), representative.sourceType(), representative.sourceId(),
                    representative.title(), representative.excerpt(), representative.priority(),
                    representative.scope(), representative.actionUrl(), representative.occurredAt(),
                    mergedState, representative.snoozedUntil(),
                    new ArrayList<>(unionLabels.values()),
                    representative.canonicalRef(), members.size(), members));
        }
        return result;
    }

    /**
     * 状態の「未処理度」ランク（小さいほど未処理＝畳み込み時に優先する）。
     * UNREAD(0) &gt; READ(1) &gt; SNOOZED(2) &gt; ARCHIVED(3)。
     */
    private static int stateUnprocessedRank(InboxState state) {
        return switch (state) {
            case UNREAD -> 0;
            case READ -> 1;
            case SNOOZED -> 2;
            case ARCHIVED -> 3;
        };
    }

    /**
     * ラベルを sourceType ごとにまとめ取りし、ラベル本体（name/color/icon/sortOrder）を解決する。
     *
     * <p>N+1 回避: リンクは sourceType ごとに 1 クエリ（最大ソース種別数回）、ラベル本体は
     * 出現した {@code labelId} 集合を {@link NotificationLabelRepository#findByIdIn} で <b>1 回だけ</b>
     * まとめ取りする（item 件数・リンク件数に依らず定数回）。{@code @SQLRestriction("deleted_at IS NULL")}
     * により論理削除済みラベルは findByIdIn で自動脱落し、孤児リンクは表示から外れる（設計書 §2.3）。</p>
     */
    private Map<String, List<LabelDto>> fetchLabelsBounded(
            Long userId, List<InboxItemDto> raw, Set<InboxSourceType> presentTypes) {
        // sourceType ごとに sourceId 集合を作り 1 クエリでリンクを集める
        Map<InboxSourceType, List<Long>> idsByType = new EnumMap<>(InboxSourceType.class);
        for (InboxItemDto r : raw) {
            idsByType.computeIfAbsent(r.sourceType(), t -> new ArrayList<>()).add(r.sourceId());
        }

        List<InboxLabelLinkEntity> allLinks = new ArrayList<>();
        Set<UUID> labelIds = new LinkedHashSet<>();
        for (InboxSourceType type : presentTypes) {
            List<Long> ids = idsByType.get(type);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            List<InboxLabelLinkEntity> links =
                    labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(userId, type, ids);
            allLinks.addAll(links);
            for (InboxLabelLinkEntity link : links) {
                labelIds.add(link.getLabelId());
            }
        }

        if (labelIds.isEmpty()) {
            return new HashMap<>();
        }

        // ラベル本体を 1 回でまとめ取り（論理削除済みは @SQLRestriction で脱落）
        Map<UUID, NotificationLabelEntity> labelById = new HashMap<>();
        for (NotificationLabelEntity label : labelRepository.findByIdIn(labelIds)) {
            labelById.put(label.getId(), label);
        }

        // 各通知（source キー）へ解決済みラベルを束ねる
        Map<String, List<LabelDto>> result = new HashMap<>();
        for (InboxLabelLinkEntity link : allLinks) {
            NotificationLabelEntity label = labelById.get(link.getLabelId());
            if (label == null) {
                // 論理削除済み（孤児リンク）→ 表示から除外（設計書 §2.3）
                continue;
            }
            result.computeIfAbsent(key(link.getSourceType(), link.getSourceId()), x -> new ArrayList<>())
                    .add(new LabelDto(label.getId(), label.getName(), label.getColor(),
                            label.getIcon(), label.getSortOrder()));
        }
        return result;
    }

    /**
     * オーバーレイ＋ソース既読から最終状態を導出する（ARCHIVED &gt; SNOOZED &gt; READ &gt; UNREAD）。
     */
    private InboxState resolveState(InboxItemDto raw, InboxItemStateEntity overlay) {
        LocalDateTime now = LocalDateTime.now();
        if (overlay != null) {
            if (overlay.getArchivedAt() != null) {
                return InboxState.ARCHIVED;
            }
            if (overlay.getSnoozedUntil() != null && overlay.getSnoozedUntil().isAfter(now)) {
                return InboxState.SNOOZED;
            }
        }
        // オーバーレイなし or 期限切れスヌーズ → ソース既読を反映
        return raw.state() == InboxState.READ ? InboxState.READ : InboxState.UNREAD;
    }

    /**
     * state クエリ（INBOX/SNOOZED/ARCHIVED/ALL）に対する項目の合致判定。
     */
    private boolean matchesState(InboxItemDto it, String stateFilter) {
        String f = stateFilter == null ? "INBOX" : stateFilter.toUpperCase();
        return switch (f) {
            case "ALL" -> true;
            case "ARCHIVED" -> it.state() == InboxState.ARCHIVED;
            case "SNOOZED" -> it.state() == InboxState.SNOOZED;
            // INBOX = 受信箱（アーカイブでもスヌーズ中でもない＝期限切れスヌーズは復帰済み）
            default -> it.state() != InboxState.ARCHIVED && it.state() != InboxState.SNOOZED;
        };
    }

    private boolean hasLabel(InboxItemDto it, UUID labelId) {
        return it.labels() != null
                && it.labels().stream().anyMatch(l -> labelId.equals(l.id()));
    }

    private String key(InboxSourceType type, Long sourceId) {
        return type.name() + ":" + sourceId;
    }
}
