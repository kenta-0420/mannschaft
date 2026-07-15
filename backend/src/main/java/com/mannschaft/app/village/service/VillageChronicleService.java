package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.ChronicleResponse;
import com.mannschaft.app.village.entity.VillageChronicleEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.VillageChronicleRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * F17.1 Phase 3-β — 村史（月次ダイジェスト）サービス。
 *
 * <p>月単位の村活動サマリ（投稿数・新メンバー数・TOP3 トピック）を集計・保存・参照する。
 * LLM は使用しない方針が裁可済み（マスター裁可 2026-05-14）。</p>
 *
 * <h2>権限</h2>
 * <ul>
 *   <li>生成: バッチ専用（呼出元で actor を null とする）。認可は行わない。</li>
 *   <li>取得（一覧・単月）: 村掲示板と<b>同一の閲覧認可</b>に従う。
 *       村の {@code bulletin_visibility} が {@code MEMBERS_ONLY}（既定値）なら村メンバーまたは
 *       SYSTEM_ADMIN のみ、{@code PUBLIC} ならログイン済ユーザーなら誰でも参照できる。
 *       判定は {@link VillageBulletinAccessService#checkVillageBulletinViewAccess} に委譲する。</li>
 * </ul>
 *
 * <h2>なぜ掲示板と同じ認可なのか</h2>
 * <p>TOP3 トピックは {@code BulletinThreadRepository#findTitlesByVillageIdAndCreatedAtBetween} で
 * <b>村掲示板スレッドのタイトルを集計したもの</b>である。掲示板 API が {@code MEMBERS_ONLY} で
 * 守っている情報が村史経由で非メンバーに漏れてはならないため、参照系は掲示板の閲覧認可に従う
 * （マスター裁可 2026-07-15「掲示板の設定に従う」）。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 作成者・村人テーブルへの FK は張らない。</li>
 *   <li>原則5: {@code @Transactional} は village ドメイン内に閉じる。
 *       BulletinThreadRepository / TimelinePostRepository は <b>read-only</b> 呼出のみで
 *       書き込みを行わない。将来 VillagePostCreatedEvent によるカウンタ非同期更新へ
 *       移行することを TODO として明記する。</li>
 *   <li>タイムゾーン: Phase 3 では UTC 固定。村ローカル TZ 対応は将来 Phase へ繰越。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageChronicleService {

    /**
     * TOP トピック抽出時に title をスペース/句読点で分割するパターン。
     * 全角・半角の空白と一部句読点で分割し、簡易的にトークン化する。
     */
    private static final Pattern TOKEN_SPLIT_PATTERN =
            Pattern.compile("[\\s\\u3000、。,.!?！？・/\\\\|\\[\\]()【】「」\"'`]+");

    /** TOP トピックの最小文字数（1 文字のノイズ語を除外）。 */
    private static final int MIN_TOKEN_LENGTH = 2;

    /** TOP トピックの最大文字数（DDL 上限と一致）。 */
    private static final int MAX_TOKEN_LENGTH = 100;

    private final VillageChronicleRepository chronicleRepository;
    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    /** 参照系の閲覧認可（村の bulletin_visibility）を一元判定する。独自の可視性述語は書かない。 */
    private final VillageBulletinAccessService bulletinAccessService;
    // TODO: 将来は VillagePostCreatedEvent を購読するカウンタテーブルへ分離し、
    //       本サービスはそのカウンタを読むだけにする（原則5 完全準拠）。
    private final BulletinThreadRepository bulletinThreadRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final AuditLogService auditLogService;

    // ====================================================================
    // 生成（UPSERT）
    // ====================================================================

    /**
     * 指定村・指定年月の村史を集計し UPSERT する。
     *
     * @param villageId 村 ID
     * @param yearMonth 対象月（任意の日付を渡してもその月の 1 日に正規化する）
     * @return 生成 or 更新後のレスポンス
     */
    @Transactional
    public ChronicleResponse generateForVillage(UUID villageId, LocalDate yearMonth) {
        VillageEntity village = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (village.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        LocalDate monthFirstDay = yearMonth.withDayOfMonth(1);
        LocalDateTime from = monthFirstDay.atStartOfDay();
        LocalDateTime to = monthFirstDay.plusMonths(1).atStartOfDay();

        long bulletinCount = bulletinThreadRepository
                .countByVillageIdAndCreatedAtBetween(villageId, from, to);
        long timelineCount = timelinePostRepository
                .countByVillageIdAndCreatedAtBetween(villageId, from, to);
        long newMembers = membershipRepository
                .countByVillageIdAndJoinedAtBetween(villageId, from, to);

        List<String> titles = bulletinThreadRepository
                .findTitlesByVillageIdAndCreatedAtBetween(villageId, from, to);
        List<Map.Entry<String, Integer>> top3 = extractTop3Topics(titles);

        VillageChronicleEntity entity = chronicleRepository
                .findByVillageIdAndYearMonth(villageId, monthFirstDay)
                .orElseGet(() -> VillageChronicleEntity.builder()
                        .villageId(villageId)
                        .yearMonth(monthFirstDay)
                        .build());

        entity.setPostCount((int) Math.min(bulletinCount + timelineCount, Integer.MAX_VALUE));
        entity.setNewMemberCount((int) Math.min(newMembers, Integer.MAX_VALUE));
        entity.setGeneratedAt(LocalDateTime.now());
        applyTopics(entity, top3);

        VillageChronicleEntity saved = chronicleRepository.save(entity);

        auditLogService.record(
                AuditEventType.VILLAGE_CHRONICLE_GENERATED.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"yearMonth\":\"" + monthFirstDay
                        + "\",\"postCount\":" + saved.getPostCount()
                        + ",\"newMemberCount\":" + saved.getNewMemberCount() + "}"
        );
        log.info("村史生成: villageId={} yearMonth={} postCount={} newMembers={}",
                villageId, monthFirstDay, saved.getPostCount(), saved.getNewMemberCount());

        return ChronicleResponse.of(saved);
    }

    // ====================================================================
    // 参照
    // ====================================================================

    /**
     * 村の村史一覧（年月降順）を返す。
     *
     * @param villageId    村 ID
     * @param actorUserId  閲覧しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない（404）／掲示板の閲覧権限が無い（403）
     */
    public List<ChronicleResponse> listChronicles(UUID villageId, Long actorUserId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, actorUserId);
        return chronicleRepository.findByVillageIdOrderByYearMonthDesc(villageId).stream()
                .map(ChronicleResponse::of)
                .toList();
    }

    /**
     * 単一月の村史を取得する。
     *
     * <p>認可は村史の存在確認より先に行う。非メンバーには「その月の村史が存在するか否か」も
     * 秘匿する必要があるため、順序を入れ替えてはならない。</p>
     *
     * @param villageId    村 ID
     * @param yearMonth    対象月（任意の日付を渡してもその月の 1 日に正規化する）
     * @param actorUserId  閲覧しようとするログイン済ユーザー ID
     * @throws BusinessException 村が存在しない（404）／掲示板の閲覧権限が無い（403）／
     *                           該当月の村史が無い（404）
     */
    public ChronicleResponse getChronicle(UUID villageId, LocalDate yearMonth, Long actorUserId) {
        bulletinAccessService.checkVillageBulletinViewAccess(villageId, actorUserId);
        LocalDate monthFirstDay = yearMonth.withDayOfMonth(1);
        VillageChronicleEntity entity = chronicleRepository
                .findByVillageIdAndYearMonth(villageId, monthFirstDay)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.CHRONICLE_NOT_FOUND));
        return ChronicleResponse.of(entity);
    }

    /**
     * title のリストから TOP3 トピックを抽出する（簡易頻度カウント）。
     *
     * <p>TOKEN_SPLIT_PATTERN でスペース・句読点分割し、{@value #MIN_TOKEN_LENGTH} 文字以上
     * {@value #MAX_TOKEN_LENGTH} 文字以内のトークンのみを採用する。
     * 大文字小文字は区別しない（小文字化して集計）。</p>
     *
     * <p>TODO: 真のタグ抽出は将来 NLP/形態素解析へ拡張する。</p>
     */
    List<Map.Entry<String, Integer>> extractTop3Topics(List<String> titles) {
        if (titles == null || titles.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> counter = new HashMap<>();
        for (String title : titles) {
            if (title == null || title.isBlank()) {
                continue;
            }
            String[] tokens = TOKEN_SPLIT_PATTERN.split(title);
            for (String raw : tokens) {
                if (raw == null) {
                    continue;
                }
                String t = raw.trim();
                if (t.length() < MIN_TOKEN_LENGTH || t.length() > MAX_TOKEN_LENGTH) {
                    continue;
                }
                String key = t.toLowerCase();
                counter.merge(key, 1, Integer::sum);
            }
        }
        return counter.entrySet().stream()
                .sorted((a, b) -> {
                    int byCount = Integer.compare(b.getValue(), a.getValue());
                    if (byCount != 0) {
                        return byCount;
                    }
                    return a.getKey().compareTo(b.getKey());
                })
                .limit(3)
                .collect(Collectors.toList());
    }

    private void applyTopics(VillageChronicleEntity entity, List<Map.Entry<String, Integer>> topics) {
        entity.setTopic1Name(null);
        entity.setTopic1Count(0);
        entity.setTopic2Name(null);
        entity.setTopic2Count(0);
        entity.setTopic3Name(null);
        entity.setTopic3Count(0);

        if (topics.size() > 0) {
            entity.setTopic1Name(topics.get(0).getKey());
            entity.setTopic1Count(topics.get(0).getValue());
        }
        if (topics.size() > 1) {
            entity.setTopic2Name(topics.get(1).getKey());
            entity.setTopic2Count(topics.get(1).getValue());
        }
        if (topics.size() > 2) {
            entity.setTopic3Name(topics.get(2).getKey());
            entity.setTopic3Count(topics.get(2).getValue());
        }
    }
}
