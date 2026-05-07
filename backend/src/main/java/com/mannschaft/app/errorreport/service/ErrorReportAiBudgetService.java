package com.mannschaft.app.errorreport.service;

import com.mannschaft.app.errorreport.ErrorReportProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * F12.5 Phase 2-C — Valkey ベースの AI 月次予算管理サービス。
 *
 * <p>キー {@code error-report:ai-budget:YYYYMM} に概算円を {@code INCRBY} で
 * 累積し、月初の初回加算時に TTL 35日でセットする。</p>
 */
@Service
@RequiredArgsConstructor
public class ErrorReportAiBudgetService {

    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMM");

    private final StringRedisTemplate redisTemplate;
    private final ErrorReportProperties props;

    /**
     * 推定コストを加算してもまだ予算内に収まるかを判定する。
     *
     * @param estimatedJpy これから消費する予定の円
     * @return 予算内に収まる場合 true、超過する場合 false
     */
    public boolean canExpend(int estimatedJpy) {
        long current = currentMonthlyExpense();
        return current + estimatedJpy <= props.getAi().getMonthlyBudgetJpy();
    }

    /**
     * 実際の AI 利用コストを加算する。月初の初回加算時に TTL 35日を設定する。
     *
     * @param jpy 加算する円
     */
    public void recordExpense(int jpy) {
        if (jpy <= 0) return;
        String key = budgetKey();
        Long newTotal = redisTemplate.opsForValue().increment(key, jpy);
        // 月初の初回 INCRBY → TTL 35日で月境界跨ぎを許容
        if (newTotal != null && newTotal == jpy) {
            redisTemplate.expire(key, Duration.ofDays(35));
        }
    }

    /**
     * 当月の累計支出（円）を返す。
     */
    public long currentMonthlyExpense() {
        String value = redisTemplate.opsForValue().get(budgetKey());
        return parseLong(value);
    }

    /**
     * 当月の予算キーを生成する。
     */
    private String budgetKey() {
        return "error-report:ai-budget:" + LocalDate.now().format(MONTH_FORMAT);
    }

    private long parseLong(String s) {
        if (s == null) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
