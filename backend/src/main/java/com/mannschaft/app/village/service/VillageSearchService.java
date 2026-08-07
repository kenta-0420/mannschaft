package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.VillageInternalSearchItemResponse;
import com.mannschaft.app.village.dto.VillageInternalSearchResponse;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * F17.1 Phase 1 B10 — 村内検索サービス。
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET /api/v1/villages/{villageId}/search?q=&type=&page=&size=} （§4.12）</li>
 * </ul>
 *
 * <h2>検索対象</h2>
 * <ul>
 *   <li>{@code POST}: {@code bulletin_threads} + {@code timeline_posts} の {@code scope_village_id} 一致行</li>
 *   <li>{@code MESSAGE}: 当該村ロビーチャネルの {@code chat_messages}</li>
 *   <li>{@code MEMBER}: 当該村の現役メンバー {@code user_village_nicknames}（{@code userId} は秘匿）</li>
 *   <li>{@code ALL}: 上記すべて（type が未指定 / "ALL" の場合のデフォルト）</li>
 * </ul>
 *
 * <h2>権限</h2>
 * <ul>
 *   <li>認証必須（{@code SecurityUtils.getCurrentUserId()} で取得）</li>
 *   <li>村人（{@code village_memberships} に行あり）のみ村内検索可</li>
 *   <li>非村人は {@link VillageErrorCode#NOT_MEMBER} → IDOR 対策で 404</li>
 *   <li>削除 / 凍結村は {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404）</li>
 * </ul>
 *
 * <h2>個人特定情報の保護（§6.1）</h2>
 * <p>MEMBER 検索結果でも {@code userId} は <b>絶対に</b> 返さない。
 * 返却するのは {@code nickname} と {@code avatarR2Key} のみ。
 * id フィールドには公開可能な {@code user_village_nicknames.id}（UUIDv7）を入れる。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 他ドメイン（bulletin/timeline/chat）の Repository は <b>読み取り専用</b> で呼ぶ</li>
 *   <li>原則5: {@code @Transactional(readOnly = true)} で読み取り限定。書き込みなし</li>
 * </ul>
 *
 * <h2>既知の制約（打ち切り上限、利用者向けドキュメントは
 * {@code docs/features/F17.1_village_community.md} §4.12 に記載）</h2>
 * <p>タイプ（POST / MESSAGE / MEMBER）ごとに {@link #PER_TYPE_FETCH_HARD_CAP} 件までしか
 * 取得しない。あるタイプのヒット件数がこれを超える場合、超過分は検索結果に一切現れない
 * （ElasticSearch 等への移行を待つ Phase 1 の簡易実装としての意図的な割り切り）。
 * {@code total} はこの打ち切り後に実際に取得できた件数を上限とするよう補正しており、
 * ページ送りが空配列で行き詰まることは無いが、打ち切られたヒットそのものへは到達できない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageSearchService {

    /** 検索クエリの最低文字数（短すぎる q は DB 負荷増のため拒否）。 */
    public static final int MIN_QUERY_LENGTH = 2;

    /** ページサイズの上限（DoS 対策）。 */
    public static final int MAX_PAGE_SIZE = 50;

    /** 4 種類のタイプ集合に対する 1 タイプあたりの最大取得件数（簡易プール用）。 */
    private static final int PER_TYPE_FETCH_HARD_CAP = 50;

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserVillageNicknameRepository nicknameRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatChannelRepository chatChannelRepository;

    // ============================================================
    // 公開メソッド
    // ============================================================

    /**
     * 村内横断検索を実行する。
     *
     * @param villageId 検索対象の村 ID
     * @param q         検索キーワード（最低 {@value #MIN_QUERY_LENGTH} 文字）
     * @param type      {@code POST}/{@code MESSAGE}/{@code MEMBER}/{@code ALL} のいずれか（null は ALL）
     * @param page      ページ番号（0 始まり）
     * @param size      ページサイズ（{@value #MAX_PAGE_SIZE} で頭打ち）
     * @param actorUserId 検索リクエストを発行したユーザー ID（認証済み必須）
     */
    public VillageInternalSearchResponse search(
            UUID villageId, String q, String type, int page, int size, Long actorUserId) {

        validateQuery(q);
        SearchType resolvedType = resolveType(type);
        loadActiveVillage(villageId);
        requireVillageMember(villageId, actorUserId);

        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));

        // 横断検索は「タイプごとに少しずつ取って結合 → ページングして返す」シンプル方式（Phase 1）。
        // 大規模化したら ElasticSearch 等への切替を検討する。
        List<VillageInternalSearchItemResponse> all = new ArrayList<>();
        long totalEstimate = 0L;

        if (resolvedType == SearchType.ALL || resolvedType == SearchType.POST) {
            List<VillageInternalSearchItemResponse> posts = searchPosts(villageId, q);
            all.addAll(posts);
            totalEstimate += countPosts(villageId, q);
        }
        if (resolvedType == SearchType.ALL || resolvedType == SearchType.MESSAGE) {
            List<VillageInternalSearchItemResponse> messages = searchLobbyMessages(villageId, q);
            all.addAll(messages);
            totalEstimate += countLobbyMessages(villageId, q);
        }
        if (resolvedType == SearchType.ALL || resolvedType == SearchType.MEMBER) {
            List<VillageInternalSearchItemResponse> members = searchMembers(villageId, q);
            all.addAll(members);
            totalEstimate += countMembers(villageId, q);
        }

        // 横断結果は createdAt 降順（MEMBER は createdAt を持たないため最後尾へ）でソート。
        all.sort(Comparator.comparing(
                (VillageInternalSearchItemResponse i) -> i.createdAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));

        // ページング切り出し
        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<VillageInternalSearchItemResponse> pageItems = all.subList(from, to);

        // total の是正: countXxx() はタイプごとの実件数（キャップ無し）の合算だが、
        // 実際に取得しているのはタイプごとに PER_TYPE_FETCH_HARD_CAP（50件）で頭打ちにしたプール
        // （all、最大 150 件）のみ。1タイプが 51 件以上ヒットすると totalEstimate が
        // 実際にページ送りで到達可能な件数（= all.size()）を超えてしまい、その超過分を
        // ページ送りすると例外は出ずに空配列が返り続ける「静かな空ページ地獄」になっていた。
        // total は実際に到達可能な件数（= プールサイズ）を超えないよう補正する。
        // DTO は変更せず、既存の total フィールドの意味を「実際に取得可能な総件数」に厳密化する形で対応する
        // （「上限で打ち切られたか」を示す capped 相当のフィールド追加は、OpenAPI/FE 契約型の追随が
        // 別途必要になる DTO 変更を伴うため、本 PR ではスコープ外とし total の補正のみで根治する）。
        long total = Math.min(totalEstimate, all.size());

        return VillageInternalSearchResponse.builder()
                .items(pageItems)
                .page(safePage)
                .size(safeSize)
                .total(total)
                .build();
    }

    // ============================================================
    // 各タイプの検索
    // ============================================================

    private List<VillageInternalSearchItemResponse> searchPosts(UUID villageId, String q) {
        Pageable pageable = PageRequest.of(0, PER_TYPE_FETCH_HARD_CAP);
        List<VillageInternalSearchItemResponse> result = new ArrayList<>();

        List<BulletinThreadEntity> threads = bulletinThreadRepository
                .searchByVillageIdAndKeyword(villageId, q, pageable);
        for (BulletinThreadEntity t : threads) {
            result.add(VillageInternalSearchItemResponse.builder()
                    .type("POST")
                    .postKind("BULLETIN_THREAD")
                    .id(String.valueOf(t.getId()))
                    .title(t.getTitle())
                    .snippet(makeSnippet(t.getBody()))
                    .createdAt(t.getCreatedAt())
                    .build());
        }

        List<TimelinePostEntity> posts = timelinePostRepository
                .searchByVillageIdAndKeyword(villageId, q, pageable);
        for (TimelinePostEntity p : posts) {
            result.add(VillageInternalSearchItemResponse.builder()
                    .type("POST")
                    .postKind("TIMELINE_POST")
                    .id(String.valueOf(p.getId()))
                    .snippet(makeSnippet(p.getContent()))
                    .createdAt(p.getCreatedAt())
                    .build());
        }
        return result;
    }

    private long countPosts(UUID villageId, String q) {
        return bulletinThreadRepository.countByVillageIdAndKeyword(villageId, q)
                + timelinePostRepository.countByVillageIdAndKeyword(villageId, q);
    }

    private List<VillageInternalSearchItemResponse> searchLobbyMessages(UUID villageId, String q) {
        ChatChannelEntity lobby = chatChannelRepository
                .findByVillageIdAndChannelType(villageId, ChannelType.VILLAGE_LOBBY)
                .orElse(null);
        if (lobby == null) {
            return Collections.emptyList();
        }
        Pageable pageable = PageRequest.of(0, PER_TYPE_FETCH_HARD_CAP);
        List<ChatMessageEntity> messages = chatMessageRepository
                .searchByChannelIdAndKeyword(lobby.getId(), q, pageable);

        List<VillageInternalSearchItemResponse> result = new ArrayList<>(messages.size());
        for (ChatMessageEntity m : messages) {
            result.add(VillageInternalSearchItemResponse.builder()
                    .type("MESSAGE")
                    .id(String.valueOf(m.getId()))
                    .snippet(makeSnippet(m.getBody()))
                    .channelId(m.getChannelId())
                    .createdAt(m.getCreatedAt())
                    .build());
        }
        return result;
    }

    private long countLobbyMessages(UUID villageId, String q) {
        ChatChannelEntity lobby = chatChannelRepository
                .findByVillageIdAndChannelType(villageId, ChannelType.VILLAGE_LOBBY)
                .orElse(null);
        if (lobby == null) {
            return 0L;
        }
        return chatMessageRepository.countByChannelIdAndKeyword(lobby.getId(), q);
    }

    private List<VillageInternalSearchItemResponse> searchMembers(UUID villageId, String q) {
        List<Long> villagerUserIds = membershipRepository.findActiveUserSubjectIdsByVillageId(villageId);
        if (villagerUserIds.isEmpty()) {
            return Collections.emptyList();
        }
        Pageable pageable = PageRequest.of(0, PER_TYPE_FETCH_HARD_CAP);
        List<UserVillageNicknameEntity> nicks = nicknameRepository
                .searchByUserIdsAndKeyword(villagerUserIds, q, pageable);

        List<VillageInternalSearchItemResponse> result = new ArrayList<>(nicks.size());
        for (UserVillageNicknameEntity n : nicks) {
            // §6.1 攻撃シナリオ A 対策: userId は絶対に返さない
            result.add(VillageInternalSearchItemResponse.builder()
                    .type("MEMBER")
                    .id(String.valueOf(n.getId()))      // user_village_nicknames.id（個人特定不可な UUIDv7）
                    .nickname(n.getNickname())
                    .avatarR2Key(n.getAvatarR2Key())
                    .build());
        }
        return result;
    }

    private long countMembers(UUID villageId, String q) {
        List<Long> villagerUserIds = membershipRepository.findActiveUserSubjectIdsByVillageId(villageId);
        if (villagerUserIds.isEmpty()) {
            return 0L;
        }
        return nicknameRepository.countByUserIdsAndKeyword(villagerUserIds, q);
    }

    // ============================================================
    // 共通ヘルパ
    // ============================================================

    /**
     * 検索キーワードのバリデーション。空文字・短すぎは {@link VillageErrorCode#VILLAGE_SEARCH_INVALID_QUERY}。
     */
    private void validateQuery(String q) {
        if (q == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
        }
        String trimmed = q.trim();
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            throw new BusinessException(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
        }
    }

    /**
     * type パラメータを解釈する。
     *
     * <p>{@code null} / 空 / "ALL" → {@link SearchType#ALL}。
     * 不正な値は {@link VillageErrorCode#VILLAGE_SEARCH_INVALID_QUERY}。</p>
     */
    private SearchType resolveType(String type) {
        if (type == null || type.isBlank()) {
            return SearchType.ALL;
        }
        try {
            return SearchType.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(VillageErrorCode.VILLAGE_SEARCH_INVALID_QUERY);
        }
    }

    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null || v.getArchivedAt() != null) {
            // IDOR 対策で 404 統一
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return v;
    }

    /**
     * 認証ユーザーが当該村の現役メンバー（VILLAGER 以上）であることを要求する。
     * 違反時は {@link VillageErrorCode#NOT_MEMBER}（IDOR 対策で 404）。
     */
    private void requireVillageMember(UUID villageId, Long userId) {
        if (userId == null) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
        boolean isMember = membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
        if (!isMember) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }
    }

    /**
     * 本文の先頭 200 文字を抜粋として返す。改行は半角スペースに置換。
     * null セーフ。
     */
    static String makeSnippet(String body) {
        if (body == null) {
            return "";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= 200) {
            return normalized;
        }
        return normalized.substring(0, 200);
    }

    /** 検索タイプの内部 enum（リクエストパラメータ {@code type} のホワイトリスト）。 */
    enum SearchType {
        ALL, POST, MESSAGE, MEMBER
    }
}
