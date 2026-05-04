package com.mannschaft.app.corkboard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.corkboard.dto.PinnedCardListResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardReferenceResponse;
import com.mannschaft.app.corkboard.dto.PinnedCardResponse;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F09.8.1 Phase 3 横断ピン止めカード取得サービス。
 *
 * <p>{@code GET /api/v1/users/me/corkboards/pinned-cards} のビジネスロジック。
 * 個人ボード横断でピン止めカードを取得し、参照先（REFERENCE 系カード）の閲覧権限と
 * 論理削除をバッチ判定して、N+1 を避けつつ完成品の DTO 一式を返す。</p>
 *
 * <h3>処理フロー</h3>
 * <ol>
 *   <li>cursor をデコード</li>
 *   <li>{@link CorkboardCardRepository#findPinnedCardsForUser} で limit + 1 件取得（次ページ判定用）</li>
 *   <li>カード集合からボード ID をユニーク化し {@code findAllById} でボード名一括取得</li>
 *   <li>{@code reference_type} 別に ID をグループ化</li>
 *   <li>{@link AccessControlDispatcher#filterAccessibleByType} で type 別バッチ閲覧権限判定</li>
 *   <li>{@link AccessControlDispatcher#filterDeletedByType} で type 別バッチ論理削除判定</li>
 *   <li>各カードを {@link ReferenceTypeResolver#resolve} で DTO 化</li>
 *   <li>件数が limit + 1 なら最後の 1 件を切り落として next_cursor 生成</li>
 * </ol>
 *
 * <h3>SQL 数（設計書 §10.4 目標 8 以内）</h3>
 * <ul>
 *   <li>1: ピン止めカード一覧取得</li>
 *   <li>1: ボード名バッチ取得</li>
 *   <li>1: 総件数取得</li>
 *   <li>0〜N: 参照先閲覧権限・論理削除判定（MVP では DB アクセスなし、将来 type 別に最大 5）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MyPinnedCardsService {

    /** デフォルト取得件数。 */
    public static final int DEFAULT_LIMIT = 20;

    /** 最大取得件数。設計書 §4.3 の上限。 */
    public static final int MAX_LIMIT = 50;

    private static final String SCOPE_PERSONAL = "PERSONAL";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CorkboardCardRepository cardRepository;
    private final CorkboardRepository boardRepository;
    private final ReferenceTypeResolver referenceTypeResolver;
    private final AccessControlDispatcher accessControlDispatcher;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * 横断ピン止めカード一覧を取得する。
     *
     * @param userId 認証ユーザーID
     * @param limit  取得件数（null や 0 以下は {@link #DEFAULT_LIMIT}、{@link #MAX_LIMIT} 超過は上限に丸め）
     * @param cursor 前回レスポンスの {@code next_cursor}（初回は null）
     * @return 取得結果リスト + 次ページカーソル + 総件数
     */
    public PinnedCardListResponse list(Long userId, Integer limit, String cursor) {
        int effectiveLimit = normalizeLimit(limit);
        CursorPosition cursorPos = decodeCursor(cursor);

        // 1) limit + 1 件取得（次ページ判定）
        Pageable pageable = PageRequest.of(0, effectiveLimit + 1);
        List<CorkboardCardEntity> rows = cardRepository.findPinnedCardsForUser(
                userId, cursorPos.pinnedAt(), cursorPos.id(), pageable);

        // 2) 総件数（ページネーションに依存しない）
        long totalCount = cardRepository.countPinnedByOwnerIdAndScopePersonal(userId);

        // 3) 次ページ判定 + 切り落とし
        boolean hasNext = rows.size() > effectiveLimit;
        List<CorkboardCardEntity> pageRows = hasNext ? rows.subList(0, effectiveLimit) : rows;

        if (pageRows.isEmpty()) {
            return new PinnedCardListResponse(List.of(), null, totalCount);
        }

        // 4) ボード名バッチ取得（N+1 防止）
        Set<Long> boardIds = pageRows.stream()
                .map(CorkboardCardEntity::getCorkboardId)
                .collect(Collectors.toSet());
        Map<Long, String> boardNameMap = new HashMap<>();
        for (CorkboardEntity board : boardRepository.findAllById(boardIds)) {
            // 念のため scope/owner 防衛（@SQLRestriction で deletedAt は自動除外される）
            if (SCOPE_PERSONAL.equals(board.getScopeType()) && userId.equals(board.getOwnerId())) {
                boardNameMap.put(board.getId(), board.getName());
            }
        }

        // 5) reference_type 別に ID リストをグループ化（URL カードは除外: 権限二重チェック対象外）
        Map<String, Set<Long>> idsByType = new HashMap<>();
        for (CorkboardCardEntity card : pageRows) {
            String refType = card.getReferenceType();
            Long refId = card.getReferenceId();
            if (refType == null || "URL".equals(refType) || refId == null) {
                continue;
            }
            idsByType.computeIfAbsent(refType, k -> new HashSet<>()).add(refId);
        }

        // 6) type 別バッチ閲覧権限・論理削除判定
        Map<String, Set<Long>> accessibleIdsByType =
                accessControlDispatcher.filterAccessibleByType(userId, idsByType);
        Map<String, Set<Long>> deletedIdsByType =
                accessControlDispatcher.filterDeletedByType(idsByType);

        // 6.5) CHAT_MESSAGE 用 channelId バッチ取得（N+1 防止）
        Map<Long, Long> chatChannelIdMap = resolveChatChannelIds(idsByType);

        // 7) DTO 化
        List<PinnedCardResponse> items = new ArrayList<>(pageRows.size());
        for (CorkboardCardEntity card : pageRows) {
            items.add(toResponse(card, boardNameMap, accessibleIdsByType, deletedIdsByType, chatChannelIdMap));
        }

        // 8) next_cursor 生成
        String nextCursor = null;
        if (hasNext && !pageRows.isEmpty()) {
            CorkboardCardEntity last = pageRows.get(pageRows.size() - 1);
            nextCursor = encodeCursor(last.getPinnedAt(), last.getId());
        }

        log.debug("ピン止めカード横断取得: userId={}, returned={}, hasNext={}, totalCount={}",
                userId, items.size(), hasNext, totalCount);
        return new PinnedCardListResponse(items, nextCursor, totalCount);
    }

    private PinnedCardResponse toResponse(CorkboardCardEntity card,
                                           Map<Long, String> boardNameMap,
                                           Map<String, Set<Long>> accessibleIdsByType,
                                           Map<String, Set<Long>> deletedIdsByType,
                                           Map<Long, Long> chatChannelIdMap) {
        String refType = card.getReferenceType();
        Long refId = card.getReferenceId();

        PinnedCardReferenceResponse reference;
        if (refType == null || "MEMO".equals(card.getCardType())
                || "SECTION_HEADER".equals(card.getCardType())) {
            // 純メモ/セクション見出しは reference 自体が null（設計書 §4.3 表）
            reference = null;
        } else if ("URL".equals(refType)) {
            reference = referenceTypeResolver.resolve(card, true, false, Collections.emptyMap());
        } else {
            boolean accessible = accessibleIdsByType
                    .getOrDefault(refType, Collections.emptySet())
                    .contains(refId);
            boolean deleted = deletedIdsByType
                    .getOrDefault(refType, Collections.emptySet())
                    .contains(refId);
            reference = referenceTypeResolver.resolve(card, accessible, deleted, chatChannelIdMap);
        }

        return new PinnedCardResponse(
                card.getId(),
                card.getCorkboardId(),
                boardNameMap.get(card.getCorkboardId()),
                card.getCardType(),
                card.getColorLabel(),
                card.getTitle(),
                card.getBody(),
                card.getUserNote(),
                card.getPinnedAt(),
                reference);
    }

    /**
     * CHAT_MESSAGE 参照を持つカードについて、{@code messageId -> channelId} のマップを
     * バッチ取得する（N+1 防止）。CHAT_MESSAGE 参照が存在しない場合は空マップを返す。
     * 該当メッセージが論理削除されている場合は {@link ChatMessageRepository#findAllById}
     * の結果に含まれず、マップに entry が無いため、Resolver 側で {@code is_deleted=true} 扱いとなる。
     */
    private Map<Long, Long> resolveChatChannelIds(Map<String, Set<Long>> idsByType) {
        Set<Long> chatMessageIds = idsByType.get("CHAT_MESSAGE");
        if (chatMessageIds == null || chatMessageIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Long> map = new HashMap<>();
        for (ChatMessageEntity msg : chatMessageRepository.findAllById(chatMessageIds)) {
            map.put(msg.getId(), msg.getChannelId());
        }
        return map;
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * cursor 文字列をデコードする。Base64(JSON {@code {"p":"ISO","i":12345}})。
     * 不正な cursor は {@link CommonErrorCode#COMMON_001} (BAD_REQUEST) を投げる。
     */
    CursorPosition decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new CursorPosition(null, null);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            ObjectNode node = (ObjectNode) MAPPER.readTree(decoded);
            String pinnedAtStr = node.has("p") ? node.get("p").asText(null) : null;
            Long id = node.has("i") ? node.get("i").asLong() : null;
            LocalDateTime pinnedAt = pinnedAtStr == null ? null : LocalDateTime.parse(pinnedAtStr);
            return new CursorPosition(pinnedAt, id);
        } catch (RuntimeException | java.io.IOException ex) {
            log.warn("不正な cursor: {}", cursor);
            throw new BusinessException(CommonErrorCode.COMMON_001);
        }
    }

    /**
     * cursor 文字列を生成する。Base64URL(JSON {@code {"p":"ISO","i":12345}})。
     */
    String encodeCursor(LocalDateTime pinnedAt, Long id) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("p", pinnedAt == null ? null : pinnedAt.toString());
            node.put("i", id);
            byte[] json = MAPPER.writeValueAsBytes(node);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException ex) {
            // 実用上発生しない（書き込み失敗）。ログのみ残し null を返す。
            log.error("cursor エンコード失敗", ex);
            return null;
        }
    }

    /** カーソル位置（pinnedAt, cardId）。両者 null は先頭から取得を意味する。 */
    record CursorPosition(LocalDateTime pinnedAt, Long id) {
    }
}
