package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.dto.VillageFeedItemResponse;
import com.mannschaft.app.village.dto.VillageFeedResponse;
import com.mannschaft.app.village.dto.VillagePinnedSummaryResponse;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.entity.UserVillagePinEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.UserVillagePinRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F17.1 Phase 1 B10 — ダッシュボード村フィード集約サービス（§4.13）。
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code GET /api/v1/me/village-feed?limit=20}</li>
 * </ul>
 *
 * <h2>戦略</h2>
 * <ol>
 *   <li>呼び出しユーザーがピン留めしている村（{@code user_village_pins}）の一覧を {@code sort_order} 昇順で取得</li>
 *   <li>各村について、最新タイムライン投稿 N 件 + 最新井戸端メッセージ N 件を取得（村ごと数件、合計 limit に丸める）</li>
 *   <li>{@code createdAt} 降順で結合・ソートして {@code limit} 件まで切り出す</li>
 *   <li>削除 / 凍結された村はピン解除されていなくても結果から除外する（運営凍結への即時反映）</li>
 * </ol>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: bulletin/timeline/chat ドメインの Repository は <b>読み取り専用</b> で呼ぶ</li>
 *   <li>原則5: {@code @Transactional(readOnly = true)} で書き込みなし</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageFeedService {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;

    /** 1 村あたりのフィード候補プール件数（村数 × これ件 でメモリ膨張しないようキャップ）。 */
    private static final int PER_VILLAGE_FETCH_HARD_CAP = 5;

    private final UserVillagePinRepository pinRepository;
    private final VillageAccessGate accessGate;
    // ピン村の一括取得専用（findAllById は ID 群の実体化であり、存在確認ではない）。
    // 村の存在確認・可視性判定は必ず accessGate を通すこと（番人が findById 系の直接呼び出しを禁じている）。
    private final VillageRepository villageRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final BulletinThreadRepository bulletinThreadRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final ChatMessageRepository chatMessageRepository;
    /** 村アイコンの生 R2 キーを表示用の署名付き URL へ解決する共通部品（#2355）。 */
    private final MediaUrlResolver mediaUrlResolver;
    /** 村内コンテンツの可視範囲を決める「現役の村人であること」の解決窓口（村ドメイン内）。 */
    private final PostingIdentityService postingIdentityService;

    /**
     * 個人ダッシュボードの村フィードを集約して返す。
     *
     * <p><b>可視範囲</b>: ピン留めは村外からでも行えるため、ピンの有無は村内コンテンツの
     * 閲覧資格にならない。本文（井戸端メッセージ・タイムライン投稿・掲示板スレッド）は
     * 呼び出しユーザーが<b>現役の村人である村に限って</b>集約する
     * （{@link PostingIdentityService#getActiveVillageIdsByUser} が退村・BAN 済みを除外する）。
     * ピン一覧そのもの（村名・アイコン）は呼び出しユーザー自身のピン行に束縛される。</p>
     *
     * @param actorUserId 認証ユーザー ID（必須）
     * @param limit       フィード件数の上限（最大 {@value #MAX_LIMIT}、デフォルト {@value #DEFAULT_LIMIT}）
     */
    public VillageFeedResponse build(Long actorUserId, int limit) {
        int safeLimit = clampLimit(limit);

        List<UserVillagePinEntity> pins = pinRepository.findByUserIdOrderBySortOrderAsc(actorUserId);
        if (pins.isEmpty()) {
            return VillageFeedResponse.builder()
                    .feed(List.of())
                    .pinnedVillages(List.of())
                    .build();
        }

        // ピン村を一括取得（村数だけまとめて引いてループ内 N+1 を避ける）
        Map<UUID, VillageEntity> villageMap = loadVillagesByPin(pins, actorUserId);

        // ピン村サマリー（アイコンは同一キーの presign 重複を避けるため一括解決してメモ化）
        Map<String, String> iconUrlsByKey = mediaUrlResolver.resolveAll(
                villageMap.values().stream().map(VillageEntity::getIconR2Key).toList());
        List<VillagePinnedSummaryResponse> pinned = new ArrayList<>(pins.size());
        for (UserVillagePinEntity pin : pins) {
            VillageEntity v = villageMap.get(pin.getVillageId());
            if (v == null) {
                // 削除済み / 凍結済みの村は表示しない
                continue;
            }
            pinned.add(VillagePinnedSummaryResponse.builder()
                    .id(v.getId())
                    .name(v.getName())
                    .iconUrl(iconUrlsByKey.get(v.getIconR2Key()))
                    // 未読件数は Phase 1 では 0 固定（B11 以降で既読管理連携）
                    .unreadCount(0L)
                    .build());
        }

        // 各村の最新動きを集約（本文は現役の村人である村のみ）
        Set<UUID> memberVillageIds =
                new HashSet<>(postingIdentityService.getActiveVillageIdsByUser(actorUserId));
        List<VillageFeedItemResponse> aggregate = new ArrayList<>();
        Pageable pageable = PageRequest.of(0, PER_VILLAGE_FETCH_HARD_CAP);
        for (VillageEntity v : villageMap.values()) {
            if (!memberVillageIds.contains(v.getId())) {
                continue;
            }
            collectTimelinePosts(v, pageable, aggregate);
            collectLobbyMessages(v, pageable, aggregate);
        }

        // createdAt 降順で並べて limit 件まで
        aggregate.sort(Comparator.comparing(
                (VillageFeedItemResponse i) -> i.createdAt(),
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<VillageFeedItemResponse> feed = aggregate.size() <= safeLimit
                ? aggregate
                : new ArrayList<>(aggregate.subList(0, safeLimit));

        return VillageFeedResponse.builder()
                .feed(feed)
                .pinnedVillages(pinned)
                .build();
    }

    // ============================================================
    // 内部ヘルパ
    // ============================================================

    static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /**
     * ピンに登録された村のうち、active（未削除・未凍結）かつ<b>閲覧者に可視</b>なもののみ返す。
     *
     * <h3>なぜ可視性判定が要るのか（ピン経由の存在漏洩）</h3>
     * <p>ピンは {@code VillagePinService#pin} / {@code autoPinOnJoin} で作られ、
     * <b>退村・BAN では削除されない</b>（削除されるのは明示的な unpin と退会時の
     * {@code VillageUserCleanerEventListener} だけ）。そのため非公開(UNLISTED)村をピンしたまま
     * 退村・BAN されたユーザーのフィードに、<b>その村の名前と村紋が残り続ける</b>。
     * 投稿本文は現役村人判定で除外されるが、ピン村サマリーはその判定の外側にあるため、
     * 「もう村人ではないユーザーに非公開村の実在と名称を見せる」漏洩経路になっていた。</p>
     *
     * <p>そこで {@link VillageAccessGate#filterVisible} を通し、可視でない村は黙って落とす。
     * 落ちた村は既存の null 防御（{@code v == null} で continue）でそのまま非表示になるため、
     * 応答形状は変わらない。PUBLIC 村はゲートを素通りするので従来どおり表示される。</p>
     *
     * <h3>1 件ずつ引かない理由（N+1 の回避）</h3>
     * <p>村を ID ごとに引くと、ピン上限（30 件）に比例してクエリが増える。
     * ここは<b>ダッシュボードで頻繁に開かれる経路</b>なので、村の取得は {@code findAllById} で 1 本に束ね、
     * 可視性判定も操作者を軸に畳む一括版へ渡す（村の件数によらず追加クエリは最大 2 本、
     * PUBLIC 村のみなら 0 本）。{@code findAllById} は「クライアント指定 ID の存在確認」ではなく
     * 取得済み ID 群の実体化なので、存在オラクルの経路にはならない。</p>
     */
    private Map<UUID, VillageEntity> loadVillagesByPin(List<UserVillagePinEntity> pins, Long actorUserId) {
        if (pins.isEmpty()) {
            return new HashMap<>();
        }
        List<UUID> ids = pins.stream().map(UserVillagePinEntity::getVillageId).toList();
        List<VillageEntity> alive = villageRepository.findAllById(ids).stream()
                .filter(v -> v.getDeletedAt() == null && v.getArchivedAt() == null)
                .toList();

        Map<UUID, VillageEntity> result = new HashMap<>();
        for (VillageEntity v : accessGate.filterVisible(alive, actorUserId)) {
            result.put(v.getId(), v);
        }
        return result;
    }

    private void collectTimelinePosts(VillageEntity v, Pageable pageable,
                                      List<VillageFeedItemResponse> out) {
        List<TimelinePostEntity> posts = timelinePostRepository
                .findLatestByVillageId(v.getId(), pageable);
        for (TimelinePostEntity p : posts) {
            out.add(VillageFeedItemResponse.builder()
                    .type("TIMELINE")
                    .villageId(v.getId())
                    .villageName(v.getName())
                    .postId(p.getId())
                    .snippet(VillageSearchService.makeSnippet(p.getContent()))
                    .createdAt(p.getCreatedAt())
                    .build());
        }
        // 掲示板スレッドも TIMELINE 型として含める（フィードは「最新動き」の包括的表示）
        List<BulletinThreadEntity> threads = bulletinThreadRepository
                .findLatestByVillageId(v.getId(), pageable);
        for (BulletinThreadEntity t : threads) {
            out.add(VillageFeedItemResponse.builder()
                    .type("TIMELINE")
                    .villageId(v.getId())
                    .villageName(v.getName())
                    .postId(t.getId())
                    .snippet(VillageSearchService.makeSnippet(t.getTitle()))
                    .createdAt(t.getCreatedAt())
                    .build());
        }
    }

    private void collectLobbyMessages(VillageEntity v, Pageable pageable,
                                      List<VillageFeedItemResponse> out) {
        ChatChannelEntity lobby = chatChannelRepository
                .findByVillageIdAndChannelType(v.getId(), ChannelType.VILLAGE_LOBBY)
                .orElse(null);
        if (lobby == null) {
            return;
        }
        List<ChatMessageEntity> messages = chatMessageRepository
                .findLatestRootMessagesByChannelId(lobby.getId(), pageable);
        for (ChatMessageEntity m : messages) {
            out.add(VillageFeedItemResponse.builder()
                    .type("LOBBY")
                    .villageId(v.getId())
                    .villageName(v.getName())
                    .messageId(m.getId())
                    .snippet(VillageSearchService.makeSnippet(m.getBody()))
                    .createdAt(m.getCreatedAt())
                    .build());
        }
    }
}
