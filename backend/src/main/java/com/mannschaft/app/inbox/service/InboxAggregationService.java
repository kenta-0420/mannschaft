package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.InboxState;
import com.mannschaft.app.inbox.dto.InboxItemDto;
import com.mannschaft.app.inbox.dto.InboxPageResponse;
import com.mannschaft.app.inbox.dto.InboxSummaryResponse;
import com.mannschaft.app.inbox.dto.LabelDto;
import com.mannschaft.app.inbox.entity.InboxItemStateEntity;
import com.mannschaft.app.inbox.entity.InboxLabelLinkEntity;
import com.mannschaft.app.inbox.repository.InboxItemStateRepository;
import com.mannschaft.app.inbox.repository.InboxLabelLinkRepository;
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

        List<InboxItemDto> merged = collectMergedItems(userId);

        // フィルタ（state → sourceType → priority → label）
        LocalDateTime now = LocalDateTime.now();
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
        List<InboxItemDto> merged = collectMergedItems(userId);

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

    /** ソート規則: priority DESC（URGENT が先頭）→ occurredAt DESC（新しい順）。 */
    private static final Comparator<InboxItemDto> ITEM_ORDER =
            Comparator.<InboxItemDto>comparingInt(it -> it.priority().ordinal())  // URGENT=0 が先頭
                    .thenComparing(InboxItemDto::occurredAt, Comparator.nullsLast(Comparator.reverseOrder()));

    /**
     * 全アダプタから生項目を集め、triage 状態・ラベルをまとめ取りしてマージした項目リストを返す。
     */
    private List<InboxItemDto> collectMergedItems(Long userId) {
        // 1. 各アダプタを呼び生項目を集約
        List<InboxItemDto> raw = new ArrayList<>();
        for (InboxSourceAdapter adapter : sourceAdapters) {
            raw.addAll(adapter.fetch(userId));
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

            merged.add(new InboxItemDto(
                    r.id(), r.sourceType(), r.sourceId(), r.title(), r.excerpt(),
                    r.priority(), r.scope(), r.actionUrl(), r.occurredAt(),
                    state, snoozedUntil, labels));
        }
        return merged;
    }

    /**
     * ラベルを sourceType ごとにまとめ取りする（N+1 回避）。MVP はラベル機能未提供のため、
     * リンクが存在しなければ空 Map を返す（LabelDto の name/color/icon は将来のラベル本実装で解決）。
     */
    private Map<String, List<LabelDto>> fetchLabelsBounded(
            Long userId, List<InboxItemDto> raw, Set<InboxSourceType> presentTypes) {
        Map<String, List<LabelDto>> result = new HashMap<>();
        // sourceType ごとに sourceId 集合を作り 1 クエリ
        Map<InboxSourceType, List<Long>> idsByType = new EnumMap<>(InboxSourceType.class);
        for (InboxItemDto r : raw) {
            idsByType.computeIfAbsent(r.sourceType(), t -> new ArrayList<>()).add(r.sourceId());
        }
        for (InboxSourceType type : presentTypes) {
            List<Long> ids = idsByType.get(type);
            if (ids == null || ids.isEmpty()) {
                continue;
            }
            List<InboxLabelLinkEntity> links =
                    labelLinkRepository.findByUserIdAndSourceTypeAndSourceIdIn(userId, type, ids);
            for (InboxLabelLinkEntity link : links) {
                // ラベル本体（notification_labels）の解決はラベル機能本実装（Phase 2）で行う。
                // MVP では link が無い限り空のため、ここはリンク存在時のプレースホルダ。
                result.computeIfAbsent(key(link.getSourceType(), link.getSourceId()), x -> new ArrayList<>())
                        .add(new LabelDto(link.getLabelId(), null, null, null, 0));
            }
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
