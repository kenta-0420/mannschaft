package com.mannschaft.app.weather.job;

import com.mannschaft.app.weather.config.WeatherLocationProperties;
import com.mannschaft.app.weather.service.GeonamesImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@code ./gradlew importPostalCodes} 用のエントリーランナー（F02.10）。
 *
 * <p>{@code weather-import} プロファイル時のみ起動する。設計書 §10.3 の
 * 手動再実行手順「Gradle タスクで再開」を担う。</p>
 *
 * <p>引数: {@code --country=ALL} / {@code --country=JP} を受け取れるが、Phase 2
 * 段階では ALL のみ動かす（JP オプションは将来拡張）。</p>
 *
 * <p>取り込み完了後は {@link SpringApplication#exit} で JVM を終了させ、
 * Gradle タスクが正常コード/異常コードを取得できるようにする。</p>
 */
@Slf4j
@Component
@Profile("weather-import")
@RequiredArgsConstructor
public class PostalCodesImportRunner implements ApplicationRunner {

    private final GeonamesImportService geonamesImportService;
    private final WeatherLocationProperties properties;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments args) {
        int exitCode = 0;
        try {
            List<String> countryValues = args.getOptionValues("country");
            String country = (countryValues == null || countryValues.isEmpty())
                    ? "ALL" : countryValues.get(0);
            log.info("PostalCodesImportRunner 起動: country={}", country);

            if (!"ALL".equalsIgnoreCase(country)) {
                log.warn("country={} は現フェーズでは未サポートのため ALL として実行します", country);
            }

            geonamesImportService.importAll(properties.getGeonames().getDownloadUrl());
            log.info("PostalCodesImportRunner 正常終了");
        } catch (RuntimeException e) {
            log.error("PostalCodesImportRunner 失敗: error={}", e.getMessage(), e);
            exitCode = 1;
        }
        final int finalExitCode = exitCode;
        ExitCodeGenerator generator = () -> finalExitCode;
        System.exit(SpringApplication.exit(context, generator));
    }
}
