package com.mannschaft.app.village.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 1 USER が現役で所属できる村数の上限の現在値を返す（CMP-260827-1808）。
 *
 * <p>当面は設定 {@code mannschaft.village.participation-hard-limit} から読み、未設定なら
 * {@link VillageParticipationLimits#DEFAULT_HARD_LIMIT}。管理画面からの変更は別 CMP で、その際は
 * 本クラスの読み出し元だけを差し替える（枠の割当・上限判定は本クラスの値だけを使う）。</p>
 *
 * <p>値は {@link VillageParticipationLimits#MIN_HARD_LIMIT} 以上 {@link VillageParticipationLimits#DB_SLOT_CEILING}
 * 以下でなければならず、外れていれば構築時に例外を投げて起動を失敗させる（DB の CHECK を超える上限で
 * 入村が DB 制約違反になる事故を、起動時に止める）。</p>
 *
 * <p>上限を下げても既存の所属は残し、新しい入村だけを止める（退村は常にできる）。</p>
 */
@Component
public class VillageParticipationLimitProvider {

    private final int hardLimit;

    public VillageParticipationLimitProvider(
            @Value("${mannschaft.village.participation-hard-limit:#{null}}") Integer configuredHardLimit) {
        int value = configuredHardLimit == null ? VillageParticipationLimits.DEFAULT_HARD_LIMIT : configuredHardLimit;
        if (value < VillageParticipationLimits.MIN_HARD_LIMIT || value > VillageParticipationLimits.DB_SLOT_CEILING) {
            throw new IllegalStateException("mannschaft.village.participation-hard-limit は "
                    + VillageParticipationLimits.MIN_HARD_LIMIT + " 以上 " + VillageParticipationLimits.DB_SLOT_CEILING
                    + " 以下でなければなりません: " + value);
        }
        this.hardLimit = value;
    }

    /** 現在の上限値。 */
    public int currentHardLimit() {
        return hardLimit;
    }
}
