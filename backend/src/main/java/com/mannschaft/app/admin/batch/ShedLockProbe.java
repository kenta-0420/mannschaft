package com.mannschaft.app.admin.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * F10.X 第二陣 — {@code shedlock} テーブル直問い合わせによる「ロック取得中か」判定ユーティリティ。
 *
 * <p>汎用バッチキック API（{@code SystemAdminBatchController}）が同名バッチの二重起動を防ぐため、
 * 起動前に {@code shedlock.lock_until} が現在時刻より未来かどうかを軽くチェックする。
 * ShedLock 本体の取得ロジックを介さず {@link JdbcTemplate} で直接 SELECT するため、
 * 副作用なしで「ロック中?」だけを判定できる。</p>
 *
 * <p>注意:</p>
 * <ul>
 *   <li>本プローブはあくまで「リクエスト時点」のスナップショット判定であり、
 *       後段の {@code @SchedulerLock} 取得との間に窓があり完全な排他保証にはならない。
 *       本物の排他は ShedLock 側が担い、本プローブは UX 改善（早期 409 応答）目的とする。</li>
 *   <li>{@code shedlock} テーブルが未生成のプロファイル（テスト等）では SELECT 自体が失敗し得るため、
 *       例外を握って「ロック中でない」と扱う方が運用上安全。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShedLockProbe {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 指定 lockName が現在ロック中か判定する。
     *
     * <p>{@code shedlock} テーブルから {@code lock_until} を取得し、現在時刻より未来であれば
     * 「ロック中」と判定する。レコードが無い、または {@code lock_until <= NOW()} なら false。</p>
     *
     * @param lockName {@code @SchedulerLock.name()} と同じ値
     * @return ロック中なら true
     */
    public boolean isLocked(String lockName) {
        if (lockName == null || lockName.isBlank()) {
            return false;
        }
        try {
            List<Timestamp> rows = jdbcTemplate.queryForList(
                    "SELECT lock_until FROM shedlock WHERE name = ?",
                    Timestamp.class,
                    lockName);
            if (rows.isEmpty()) {
                return false;
            }
            Timestamp lockUntil = rows.get(0);
            if (lockUntil == null) {
                return false;
            }
            return lockUntil.toInstant().isAfter(Instant.now());
        } catch (Exception ex) {
            // shedlock テーブル未作成プロファイル等での SQL 例外は「ロック中でない」とみなす。
            // ここで例外を再送すると、ShedLock 未使用のプロファイル全体が POST trigger を呼べなくなり過剰。
            log.debug("ShedLockProbe SELECT 失敗、ロック未取得とみなす: name={}, ex={}", lockName, ex.toString());
            return false;
        }
    }
}
