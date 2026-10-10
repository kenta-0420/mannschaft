package com.mannschaft.app.ranch.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;

/** 正式承認を変更せず、専用隔離環境のDEV政策だけを許可する一正本。 */
@Component
@RequiredArgsConstructor
public class RanchDevelopmentFixturePolicyGate {
    private static final String PROPERTY = "mannschaft.ranch.development-fixtures";
    private static final String PREFIX = "DEV_";
    private final Environment environment;

    public boolean isDevelopment(String reasonCode) {
        return reasonCode != null && reasonCode.startsWith(PREFIX);
    }

    public boolean enabled() {
        return "true".equals(environment.getProperty(PROPERTY))
                && environment.acceptsProfiles(Profiles.of("ranch-isolated"))
                && !environment.acceptsProfiles(Profiles.of("prod | production"));
    }

    public boolean readable(String reasonCode) {
        return !isDevelopment(reasonCode) || (enabled() && reasonCode.matches("DEV_[A-Z0-9_]{1,76}"));
    }

    /** 初回公開にのみ使用。保存ACKの再送はこの検査より先に返す。 */
    public void requirePublicationAllowed(String reasonCode) {
        if (isDevelopment(reasonCode) && !readable(reasonCode)) {
            throw new BusinessException(RanchErrorCode.RANCH_004);
        }
    }

    /** 非対応環境でDEVを新規消費しない。既成功decision replayは呼出側で先行する。 */
    public void requireConsumptionAllowed(String reasonCode) {
        if (!readable(reasonCode)) {
            throw new IllegalStateException("開発専用報酬policyをこの環境では利用できません");
        }
    }

    /** 正準DEV理由の環境OFFだけを保留する。壊れた理由やsnapshotは異常として扱う。 */
    public boolean shouldDeferConsumption(String reasonCode) {
        return isDevelopment(reasonCode) && reasonCode.matches("DEV_[A-Z0-9_]{1,76}") && !enabled();
    }

    public boolean isCurrentWeek(Instant effectiveAt, Instant now) {
        Instant current = now.atOffset(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        return current.equals(effectiveAt);
    }

    // 隔離DEVだけ同じ制御行ロックを使う。正式経路と保存済み再送の順序は維持する。
    public void requireInitialCurrentWeek(boolean storeEmpty) {
        if (!enabled() || !storeEmpty) {
            throw new BusinessException(RanchErrorCode.RANCH_007);
        }
    }
}
