package com.mannschaft.app.incidentbanner.service;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 障害告知バナーの Claude 自動翻訳の設定プロパティ。
 *
 * <p>{@code mannschaft.incident-banner.translation.*} 配下にバインドする。
 * コスト青天井ガード（AC-13）として Claude API 呼び出しのタイムアウトを外部化する。
 * 翻訳は短文・低頻度（シスアド手動公開時のみ）のためリトライは任意とし、
 * 既定ではタイムアウトのみを設ける。</p>
 */
@ConfigurationProperties(prefix = "mannschaft.incident-banner.translation")
@Component
@Getter
@Setter
public class IncidentBannerTranslationProperties {

    /** Claude API 呼び出しのタイムアウト（ミリ秒、既定 5 秒）。 */
    private int timeoutMs = 5000;
}
