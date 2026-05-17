package com.mannschaft.app.advertising.campaign.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * F09.17 フリークエンシーキャップ（広告疲れ防止）の上限値設定。
 *
 * <p>設計書 §5: 1 ユーザーが 1 週間に受け取れる広告は最大 3 件、同一広告主からは 1 件まで。
 * 週境界はユーザーローカル時刻の月曜 00:00（UTC ではない点に注意）。</p>
 *
 * <pre>
 * mannschaft.ad.frequency-cap.weekly-total = 3
 * mannschaft.ad.frequency-cap.weekly-per-advertiser = 1
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "mannschaft.ad.frequency-cap")
@Getter
@Setter
public class AdFrequencyCapConfig {

    /** 1 ユーザーあたり週次の全広告主合計上限件数（デフォルト 3 件）。 */
    private int weeklyTotal = 3;

    /** 同一広告主 × 1 ユーザーの週次上限件数（デフォルト 1 件）。 */
    private int weeklyPerAdvertiser = 1;
}
