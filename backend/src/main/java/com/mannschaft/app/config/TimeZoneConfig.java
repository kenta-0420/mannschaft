package com.mannschaft.app.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * JVM のデフォルトタイムゾーンを業務ローカル時刻の基準ゾーンに設定する（.claudecode.md §20）。
 *
 * <p>ゾーンは {@code mannschaft.app-timezone}（既定 {@code Asia/Tokyo}）から読む。
 * {@code ClockConfig#wallClock} が<b>同一のプロパティ</b>から壁時計 Clock を組み立てるため、
 * 「JVM 既定ゾーンで書かれた LocalDateTime 列」と「壁時計 Clock で作った判定基準時刻」が
 * 食い違うことは構造的に起こらない。</p>
 */
@Configuration
public class TimeZoneConfig {

    @Value("${mannschaft.app-timezone:Asia/Tokyo}")
    private String appTimeZone;

    @PostConstruct
    public void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone(appTimeZone));
    }
}
