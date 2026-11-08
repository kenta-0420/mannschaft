package com.mannschaft.app.reservation.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Check;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * UUIDv7を主キーとする仮押さえ失効の専用進捗1行。
 * singletonKeyの一意制約とCHECKで増行を禁止し、予約へのFKは持たない。
 */
@Entity
@Table(name = "reservation_pending_expire_scan_state", uniqueConstraints =
        @UniqueConstraint(name = "uq_rpess_singleton", columnNames = "singleton_key"))
@Check(name = "chk_rpess_singleton", constraints = "singleton_key = 1")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReservationPendingExpireScanStateEntity extends UuidV7Entity {

    public static final byte SINGLETON_KEY = 1;
    public static final int MAX_RETRIES = 500;

    @Column(name = "singleton_key", nullable = false, updatable = false)
    private Byte singletonKey;

    @Column(name = "cycle_high_water", nullable = false)
    private long cycleHighWater;

    @Column(name = "last_inspected_id", nullable = false)
    private long lastInspectedId;

    @Column(name = "run_epoch", nullable = false)
    private long runEpoch;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retry_primary_ids", nullable = false, columnDefinition = "json")
    private JsonNode retryPrimaryIds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** migrationが置く初期行と同値。運用入口からの自動作成はしない。 */
    public static ReservationPendingExpireScanStateEntity initial(Instant now) {
        requireNow(now);
        var state = new ReservationPendingExpireScanStateEntity();
        state.singletonKey = SINGLETON_KEY;
        state.retryPrimaryIds = JsonNodeFactory.instance.arrayNode();
        state.updatedAt = now;
        state.validate();
        return state;
    }

    public List<Long> getRetryPrimaryIds() {
        validate();
        return retryIds();
    }

    public void validate() {
        if (singletonKey == null || singletonKey != SINGLETON_KEY || cycleHighWater < 0 || lastInspectedId < 0
                || lastInspectedId > cycleHighWater || runEpoch < 0 || updatedAt == null
                || retryPrimaryIds == null || !retryPrimaryIds.isArray()
                || retryPrimaryIds.size() > MAX_RETRIES) {
            throw new IllegalStateException("仮押さえ失効の進捗行が不正です");
        }
        retryIds();
    }

    /** 永続JSONを整数のまま検査し、小数・文字列からLongへの暗黙変換を許さない。 */
    private List<Long> retryIds() {
        var ids = new ArrayList<Long>();
        for (JsonNode value : retryPrimaryIds) {
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw new IllegalStateException("仮押さえ失効の再試行IDが不正です");
            }
            ids.add(value.longValue());
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalStateException("仮押さえ失効の再試行IDが重複しています");
        }
        return List.copyOf(ids);
    }

    private void replaceRetryIds(List<Long> ids) {
        var array = JsonNodeFactory.instance.arrayNode();
        ids.forEach(array::add);
        retryPrimaryIds = array;
    }

    public void beginRun(long highWater, Instant now) {
        validate();
        requireNow(now);
        if (highWater < 0) {
            throw new IllegalArgumentException("高水位は非負で指定してください");
        }
        long nextEpoch = Math.incrementExact(runEpoch);
        // 0/0へ戻す操作は周回完了を確認した別工程が行う。
        if (cycleHighWater == 0 && lastInspectedId == 0) {
            cycleHighWater = highWater;
        }
        runEpoch = nextEpoch;
        updatedAt = now;
        validate();
    }

    public void enqueue(long primaryId, Instant now) {
        validate();
        requireNow(now);
        if (primaryId <= 0) {
            throw new IllegalArgumentException("代表予約IDは正数で指定してください");
        }
        var ids = new ArrayList<>(getRetryPrimaryIds());
        if (!ids.contains(primaryId)) {
            if (ids.size() == MAX_RETRIES) {
                throw new IllegalStateException("再試行の上限500件に達しています");
            }
            ids.add(primaryId);
            replaceRetryIds(ids);
        }
        updatedAt = now;
        validate();
    }

    public void advance(long inspectedId, Instant now) {
        validate();
        requireNow(now);
        if (inspectedId < lastInspectedId || inspectedId > cycleHighWater) {
            throw new IllegalArgumentException("検査済み位置は現在位置から高水位までです");
        }
        lastInspectedId = inspectedId;
        updatedAt = now;
        validate();
    }

    public void removeRetry(long primaryId, Instant now) {
        validate();
        requireNow(now);
        var ids = new ArrayList<>(getRetryPrimaryIds());
        if (!ids.remove(primaryId)) {
            throw new IllegalStateException("事前投入されていない代表予約です");
        }
        replaceRetryIds(ids);
        updatedAt = now;
        validate();
    }

    /** 窓末尾まで検査したときだけ次周回へ戻す。未解決retryは消去しない。 */
    public void finishCycle(Instant now) {
        validate();
        requireNow(now);
        if (lastInspectedId != cycleHighWater) {
            throw new IllegalStateException("未検査の位置を残した周回は完了できません");
        }
        cycleHighWater = 0;
        lastInspectedId = 0;
        updatedAt = now;
    }

    private static void requireNow(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("更新時刻は必須です");
        }
    }
}
