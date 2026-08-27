package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.VillageRepository;
import com.mannschaft.app.village.service.VillageSerendipityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F17.1 Phase 3-β — ご縁スコア日次更新バッチ。
 *
 * <p>毎日深夜 02:00（JST）に前日分の村内アクティビティを集計し、
 * {@link com.mannschaft.app.village.entity.VillageSerendipityScoreEntity} の
 * {@code encounterCount} / {@code interactionScore} を加算的に更新する。</p>
 *
 * <h2>簡易指標（Phase 3-β）</h2>
 * <p>マスター裁可: 「返信ペア出現数」を交流度の代理指標とする。</p>
 * <ul>
 *   <li>{@code bulletin_replies} で同一スレッド内に投稿しあう author_id ペアを 1 出会いとカウント</li>
 *   <li>1 出会いあたり {@code encounterCount += 1}, {@code interactionScore += 1}</li>
 *   <li>chat_messages（村ロビー）も同様の方針で集計可能だが、本実装では構造化のみで
 *       実クエリは Phase 4 で拡張する（TODO 参照）。</li>
 * </ul>
 *
 * <h2>アーキテクチャ原則</h2>
 * <ul>
 *   <li>原則1: bulletin / chat ドメインのテーブルは read-only でクロスドメイン参照する
 *       （TODO コメントを残す）</li>
 *   <li>原則5: 書き込みは {@link VillageSerendipityService#updateUserScore} 経由のみ。
 *       バッチ全体での @Transactional は付けず、村単位で短時間に閉じる。</li>
 *   <li>ShedLock により複数インスタンス起動時の二重実行を防ぐ。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageSerendipityBatchService {

    private final VillageRepository villageRepository;
    private final VillageSerendipityService serendipityService;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbcTemplate;

    /**
     * 毎日 02:00（JST）に集計を実行する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村のご縁スコア集計であり、再開後の実行で作り直される。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "village-serendipity-daily", description = "村のご縁スコアを毎日 02:00 に集計する")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = "villageSerendipityBatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runBatch() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate targetDate = LocalDate.now().minusDays(1);
        log.info("ご縁スコア日次バッチ開始: targetDate={} now={}", targetDate, now);

        int totalVillages = 0;
        int totalUpdatedUsers = 0;

        final int CHUNK_SIZE = 500;
        Pageable pageable = PageRequest.of(0, CHUNK_SIZE);
        Page<VillageEntity> page;
        do {
            page = villageRepository.findByDeletedAtIsNull(pageable);
            for (VillageEntity village : page.getContent()) {
                try {
                    int updated = processVillage(village.getId(), targetDate);
                    totalUpdatedUsers += updated;
                    totalVillages++;
                } catch (RuntimeException ex) {
                    // 1 件失敗しても次に進む（バッチ全体を巻き戻さない）
                    log.error("ご縁スコア集計失敗: villageId={} error={}",
                            village.getId(), ex.getMessage(), ex);
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        auditLogService.record(
                AuditEventType.VILLAGE_SERENDIPITY_UPDATED.name(),
                null, null, null, null,
                null, null, null,
                "{\"targetDate\":\"" + targetDate
                        + "\",\"villagesProcessed\":" + totalVillages
                        + ",\"usersUpdated\":" + totalUpdatedUsers + "}"
        );
        log.info("ご縁スコア日次バッチ完了: villages={} users={}", totalVillages, totalUpdatedUsers);
    }

    /**
     * 1 村分の集計を実行する。
     *
     * @param villageId  村 ID
     * @param targetDate 集計対象日（前日）
     * @return スコアを加算したユーザー数
     */
    int processVillage(UUID villageId, LocalDate targetDate) {
        // ユーザーごとの { 出会い増分, スコア増分 } を集約
        Map<Long, long[]> userIncrements = new HashMap<>();

        // ─── 集計 1: bulletin_replies の同一スレッド返信ペア ──────────────
        // TODO Phase 4: chat_messages（村ロビー）も同様に集計する。
        //   現状は構造化のみで、village_id を経由した JOIN は bulletin_threads
        //   側の scope_village_id を辿る必要がある（V9.133 で追加済み）。
        aggregateBulletinReplyPairs(villageId, targetDate, userIncrements);

        // 集計結果を Service 経由で永続化
        for (Map.Entry<Long, long[]> e : userIncrements.entrySet()) {
            long[] inc = e.getValue();
            serendipityService.updateUserScore(villageId, e.getKey(), inc[0], inc[1]);
        }
        return userIncrements.size();
    }

    /**
     * 前日の村スコープ掲示板返信から「同一スレッド内に投稿しあった著者ペア」を集計する。
     *
     * <p>クエリ:</p>
     * <pre>{@code
     * SELECT r1.author_id AS u1, r2.author_id AS u2
     *   FROM bulletin_replies r1
     *   JOIN bulletin_replies r2 ON r1.thread_id = r2.thread_id AND r1.author_id < r2.author_id
     *   JOIN bulletin_threads t ON t.id = r1.thread_id
     *  WHERE t.scope_village_id = ?
     *    AND DATE(r1.created_at) = ?
     *    AND DATE(r2.created_at) = ?
     *    AND r1.deleted_at IS NULL
     *    AND r2.deleted_at IS NULL;
     * }</pre>
     *
     * <p>各ペア (u1, u2) について、両者の出会い / スコアを 1 ずつ増やす。</p>
     */
    private void aggregateBulletinReplyPairs(UUID villageId,
                                             LocalDate targetDate,
                                             Map<Long, long[]> userIncrements) {
        // TODO Phase 4: 反射爆撃対策（同一人物の連投で出会い回数が膨れる）と
        //   重み付け（深夜帯 < 昼間、相手の応答有無、絵文字リアクションなど）を導入する。
        String sql = """
                SELECT r1.author_id AS u1, r2.author_id AS u2
                  FROM bulletin_replies r1
                  JOIN bulletin_replies r2
                    ON r1.thread_id = r2.thread_id
                   AND r1.author_id < r2.author_id
                  JOIN bulletin_threads t ON t.id = r1.thread_id
                 WHERE t.scope_village_id = ?
                   AND DATE(r1.created_at) = ?
                   AND DATE(r2.created_at) = ?
                   AND r1.deleted_at IS NULL
                   AND r2.deleted_at IS NULL
                   AND r1.author_id IS NOT NULL
                   AND r2.author_id IS NOT NULL
                """;

        // bulletin_threads.scope_village_id は BINARY(16) のため UUID をバイト列で渡す
        byte[] villageIdBytes = uuidToBytes(villageId);

        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, villageIdBytes, targetDate, targetDate);
        } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
            // bulletin_threads / bulletin_replies が未マイグレーションのテスト環境は黙って空集合扱い
            log.warn("ご縁スコア集計クエリ失敗: villageId={} reason={}", villageId, ex.getMessage());
            return;
        }

        for (Map<String, Object> row : rows) {
            Long u1 = ((Number) row.get("u1")).longValue();
            Long u2 = ((Number) row.get("u2")).longValue();
            addIncrement(userIncrements, u1, 1L, 1L);
            addIncrement(userIncrements, u2, 1L, 1L);
        }
    }

    private void addIncrement(Map<Long, long[]> map, Long userId, long encounter, long score) {
        long[] cur = map.computeIfAbsent(userId, k -> new long[]{0L, 0L});
        cur[0] += encounter;
        cur[1] += score;
    }

    /** UUID → 16 byte 配列（BINARY(16) バインド用）。 */
    private static byte[] uuidToBytes(UUID uuid) {
        byte[] buf = new byte[16];
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        for (int i = 7; i >= 0; i--) {
            buf[i] = (byte) (msb & 0xFFL);
            msb >>= 8;
        }
        for (int i = 15; i >= 8; i--) {
            buf[i] = (byte) (lsb & 0xFFL);
            lsb >>= 8;
        }
        return buf;
    }
}
