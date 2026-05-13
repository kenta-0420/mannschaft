package com.mannschaft.app.weather.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * F02.10 天気ウィジェット — 座標導出・GeoNames 取り込みバッチ・ブートストラップジョブ用設定。
 *
 * <p>設計書 §10.3 / §8.3 を参照。</p>
 */
@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "weather.location")
public class WeatherLocationProperties {

    /** GeoNames 取り込み関連設定。 */
    @Valid
    private Geonames geonames = new Geonames();

    /** 既存ユーザー座標ブートストラップジョブ関連設定。 */
    @Valid
    private Bootstrap bootstrap = new Bootstrap();

    @Getter
    @Setter
    public static class Geonames {
        /** allCountries.zip ダウンロード URL。 */
        @NotBlank
        private String downloadUrl = "https://download.geonames.org/export/zip/allCountries.zip";

        /** 月次取り込みバッチの cron（既定: 毎月 5 日 02:00）。 */
        @NotBlank
        private String cron = "0 0 2 5 * ?";
    }

    @Getter
    @Setter
    public static class Bootstrap {
        /** 既存ユーザー処理時の 1 チャンクあたり件数。 */
        @Min(1)
        private int chunkSize = 1000;
    }
}
