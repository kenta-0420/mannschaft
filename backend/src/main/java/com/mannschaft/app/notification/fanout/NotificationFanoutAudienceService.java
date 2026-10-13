package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.UuidV7;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/**
 * fan-out の宛先集合（{@code ORGANIZATION_TEAMS} 戦略用。F01.2.1 §5.6・§8.5.3）の登録サービス。
 *
 * <p>呼び出し元は通知ドメインの outbox 取り込み（{@code NotificationOutboxIngestService} の
 * {@code FANOUT_WITH_AUDIENCE}）だけ。取り込みの tx に参加して（{@link Propagation#MANDATORY}）見出しと宛先チームを書き、
 * 同じ tx で fan-out ジョブも登録する（宛先集合とジョブは同時に確定する。AC-H21a）。他ドメインからの依存は番人 D-3P-3 が
 * 禁じる（送り手ドメインは自ドメインの outbox に書く。docs/architecture/notification_outbox.md）。</p>
 *
 * <h2>冪等（AC-H21・H21d）</h2>
 * <p>主キー {@code audience_snapshot_id} は送り手が決定的に導いた UUID。同じ依頼の二重取り込み（印付け前の落ち・回収後の
 * 再 claim）や同時取り込みでも結果が1組に収束するよう、存在確認をせず冪等 SQL で書く:</p>
 * <ul>
 *   <li>見出し: {@code INSERT … ON DUPLICATE KEY UPDATE audience_snapshot_id = audience_snapshot_id}。並行する2本目は
 *       1本目の行ロックを待ち、重複として何も変えない（例外にしないので tx を rollback-only にしない）</li>
 *   <li>宛先チーム: 多値 {@code INSERT … ON DUPLICATE KEY UPDATE id = id}（UNIQUE {@code (audience_snapshot_id, team_id)}）。
 *       チーム ID の昇順に並べ、並行する取り込み同士のロック順をそろえる</li>
 *   <li>既存の見出しの {@code organization_id} が依頼と食い違えば例外（別テナントの集合に混ぜない）</li>
 * </ul>
 * <p>それでも起きたデッドロック・ロック待ちタイムアウトは握りつぶさず例外のまま返し、relay が失敗として記録して再試行する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationFanoutAudienceService {

    /** 宛先チームの多値 INSERT の1文あたりの行数。 */
    static final int TEAM_INSERT_CHUNK = 500;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 宛先集合（見出し＋宛先チーム）を冪等に登録する。宛先チームは 0 件でもよい（直属メンバーだけに届く告知）。
     *
     * @param audienceSnapshotId 宛先集合のキー（決定的に導いた UUID。ジョブ行の {@code scope_ref} に入る）
     * @param organizationId     宛先の組織 ID（直属メンバーの解決と加盟の再確認に使う）
     * @param teamIds            宛先チーム ID（重複は除く）
     * @throws IllegalStateException 同じキーの見出しが別の組織で既にある
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registerAudience(UUID audienceSnapshotId, Long organizationId, Collection<Long> teamIds) {
        Objects.requireNonNull(audienceSnapshotId, "audienceSnapshotId");
        Objects.requireNonNull(organizationId, "organizationId");
        byte[] key = toBytes(audienceSnapshotId);

        jdbcTemplate.update("""
                INSERT INTO notification_fanout_audiences (audience_snapshot_id, organization_id, created_at)
                VALUES (?, ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE audience_snapshot_id = audience_snapshot_id
                """, key, organizationId);
        // 自分の INSERT（新規）か重複の行ロックの後なので、ロック読みで最新の見出しを読む
        List<Long> owners = jdbcTemplate.queryForList(
                "SELECT organization_id FROM notification_fanout_audiences WHERE audience_snapshot_id = ? FOR UPDATE",
                Long.class, key);
        if (owners.size() != 1 || !organizationId.equals(owners.get(0))) {
            throw new IllegalStateException("宛先集合の見出しの組織が依頼と食い違う: audienceSnapshotId="
                    + audienceSnapshotId + " 依頼の組織=" + organizationId + " 既存=" + owners);
        }

        List<Long> sorted = new ArrayList<>(new TreeSet<>(teamIds));
        for (int from = 0; from < sorted.size(); from += TEAM_INSERT_CHUNK) {
            insertTeams(key, sorted.subList(from, Math.min(sorted.size(), from + TEAM_INSERT_CHUNK)));
        }
        log.debug("fan-out 宛先集合を登録: audienceSnapshotId={} organizationId={} teams={}",
                audienceSnapshotId, organizationId, sorted.size());
    }

    /** 宛先チームを多値 INSERT で冪等に書く（既にある組は何も変えない）。 */
    private void insertTeams(byte[] key, List<Long> teamIds) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO notification_fanout_audience_teams (id, audience_snapshot_id, team_id) VALUES ");
        List<Object> args = new ArrayList<>(teamIds.size() * 3);
        for (int i = 0; i < teamIds.size(); i++) {
            sql.append(i == 0 ? "(?, ?, ?)" : ", (?, ?, ?)");
            args.add(toBytes(UuidV7.generate()));
            args.add(key);
            args.add(teamIds.get(i));
        }
        sql.append(" ON DUPLICATE KEY UPDATE id = id");
        jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /** UUID を BINARY(16)（上位 64 ビット→下位 64 ビットの順。Entity の保存形式と同じ）にする。 */
    private static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits())
                .array();
    }
}
