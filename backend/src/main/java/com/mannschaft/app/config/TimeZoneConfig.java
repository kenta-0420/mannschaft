package com.mannschaft.app.config;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * JVM のデフォルトタイムゾーンを Asia/Tokyo に設定（.claudecode.md §20）。
 */
@Configuration
public class TimeZoneConfig {

    @PostConstruct
    public void initTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }
}
