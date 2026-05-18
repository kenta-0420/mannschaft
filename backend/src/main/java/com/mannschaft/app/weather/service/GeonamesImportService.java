package com.mannschaft.app.weather.service;

import com.mannschaft.app.weather.entity.GeonamesMetadataEntity;
import com.mannschaft.app.weather.repository.GeonamesMetadataRepository;
import com.mannschaft.app.weather.util.PostalCodeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * GeoNames Postal Codes 取り込みサービス（F02.10）。
 *
 * <p>{@code allCountries.zip} をダウンロード → 展開 → TSV パース →
 * JDBC バルク upsert で {@code postal_codes} に投入する。設計書 §10.3。</p>
 *
 * <p>呼び出し元:
 * <ul>
 *   <li>{@link com.mannschaft.app.weather.job.GeonamesImportScheduler} — 月次 cron 自動実行</li>
 *   <li>{@code ./gradlew importPostalCodes} — 手動再実行（Gradle カスタムタスク）</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeonamesImportService {

    /** 完全な allCountries の最低行数（破損疑い判定のしきい値、設計書 §10.3）。 */
    private static final long MIN_ROW_COUNT = 1_000_000L;

    /** JDBC バルクインサート 1 バッチあたり件数。 */
    private static final int BATCH_SIZE = 5_000;

    /** GeoNames TSV のカラム数。 */
    private static final int EXPECTED_COLUMNS = 12;

    private static final String UPSERT_SQL = """
            INSERT INTO postal_codes
              (country_code, postal_code, place_name, admin1_name, admin2_name,
               latitude, longitude, accuracy, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW())
            ON DUPLICATE KEY UPDATE
              place_name = VALUES(place_name),
              admin1_name = VALUES(admin1_name),
              admin2_name = VALUES(admin2_name),
              latitude = VALUES(latitude),
              longitude = VALUES(longitude),
              accuracy = VALUES(accuracy),
              updated_at = NOW()
            """;

    private final JdbcTemplate jdbcTemplate;
    private final GeonamesMetadataRepository geonamesMetadataRepository;

    /**
     * GeoNames を取り込む。指定 URL から zip をダウンロードして全件投入する。
     *
     * @param downloadUrl allCountries.zip の URL
     * @return 取り込み結果
     * @throws IllegalStateException 行数下限未満（破損疑い）
     */
    public GeonamesImportResult importAll(String downloadUrl) {
        log.info("GeoNames 取り込み開始: url={}", downloadUrl);
        long startMillis = System.currentTimeMillis();

        Path tempZip = null;
        try {
            tempZip = downloadZip(downloadUrl);
            long rowCount = parseAndUpsert(tempZip);

            // 行数下限チェック（設計書 §10.3）
            if (rowCount < MIN_ROW_COUNT) {
                log.warn("GeoNames 取り込み中止: 行数下限未満（破損疑い）: rowCount={}, threshold={}",
                        rowCount, MIN_ROW_COUNT);
                throw new IllegalStateException(
                        "GeoNames allCountries の行数が下限 " + MIN_ROW_COUNT + " 未満: " + rowCount);
            }

            // メタデータ更新
            String sourceVersion = "allCountries-"
                    + java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
            recordMetadata(rowCount, sourceVersion);

            long elapsedSec = (System.currentTimeMillis() - startMillis) / 1000L;
            log.info("GeoNames 取り込み完了: rowCount={}, elapsedSec={}", rowCount, elapsedSec);
            return new GeonamesImportResult(rowCount, sourceVersion, elapsedSec);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("GeoNames 取り込みに失敗", e);
        } finally {
            if (tempZip != null) {
                try {
                    Files.deleteIfExists(tempZip);
                } catch (IOException ignored) {
                    // 後続のプロセスが OS の temp cleanup で削除する
                }
            }
        }
    }

    /**
     * GeoNames zip を一時ファイルにダウンロードする。
     */
    private Path downloadZip(String downloadUrl) throws IOException, InterruptedException {
        Path tempZip = Files.createTempFile("geonames-allcountries-", ".zip");
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("ダウンロード失敗: status=" + response.statusCode());
        }
        try (InputStream in = response.body()) {
            Files.copy(in, tempZip, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("GeoNames zip ダウンロード完了: size={}MB",
                Files.size(tempZip) / (1024L * 1024L));
        return tempZip;
    }

    /**
     * zip から allCountries.txt を読み出して TSV をパースし、postal_codes へ upsert する。
     */
    private long parseAndUpsert(Path zipPath) throws IOException {
        long totalRows = 0L;
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);

        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipPath))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".txt")) {
                    continue;
                }
                log.info("GeoNames TSV 展開開始: entry={}", entry.getName());
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(zin, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        Object[] row = parseLine(line);
                        if (row == null) {
                            continue;
                        }
                        batch.add(row);
                        totalRows++;
                        if (batch.size() >= BATCH_SIZE) {
                            jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
                            batch.clear();
                        }
                    }
                }
            }
        }
        if (!batch.isEmpty()) {
            jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
        }
        return totalRows;
    }

    /**
     * GeoNames TSV 1 行をパースする。フォーマットは:
     * country_code, postal_code, place_name, admin1_name, admin1_code,
     * admin2_name, admin2_code, admin3_name, admin3_code, latitude, longitude, accuracy
     *
     * @return upsert 用バインドパラメータ配列、不正行なら null
     */
    private Object[] parseLine(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        String[] cols = line.split("\t", -1);
        if (cols.length < EXPECTED_COLUMNS) {
            return null;
        }
        try {
            String countryCode = nullIfBlank(cols[0]);
            String rawPostalCode = nullIfBlank(cols[1]);
            if (countryCode == null || rawPostalCode == null) {
                return null;
            }
            // 正規化（JP はハイフン除去 + 7 桁ゼロパディング）して保存。
            // 引き当て側 WeatherLocationDeriver と完全に同じロジックを使う（PostalCodeNormalizer）。
            // 2026-05-18 根治治療: ここで raw 値のまま書いていたため、マスタに hyphen 入り行と
            // 正規化形行が混在し、JP の引き当てが恒常的に失敗していた。
            String postalCode = PostalCodeNormalizer.normalize(countryCode, rawPostalCode);
            String placeName = defaultIfBlank(cols[2], "");
            String admin1Name = nullIfBlank(cols[3]);
            String admin2Name = nullIfBlank(cols[5]);
            BigDecimal latitude = parseBigDecimal(cols[9]);
            BigDecimal longitude = parseBigDecimal(cols[10]);
            if (latitude == null || longitude == null) {
                return null;
            }
            Short accuracy = parseShort(cols[11]);
            return new Object[]{
                    countryCode, postalCode, placeName, admin1Name, admin2Name,
                    latitude, longitude, accuracy
            };
        } catch (RuntimeException e) {
            // 1 行のパース失敗は全体を止めない（ログレベルは debug）
            log.debug("TSV 行パース失敗: line={}", line);
            return null;
        }
    }

    private static String nullIfBlank(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String defaultIfBlank(String s, String fallback) {
        String t = nullIfBlank(s);
        return t == null ? fallback : t;
    }

    private static BigDecimal parseBigDecimal(String s) {
        String t = nullIfBlank(s);
        if (t == null) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Short parseShort(String s) {
        String t = nullIfBlank(s);
        if (t == null) return null;
        try {
            return Short.valueOf(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * geonames_metadata を upsert する。
     */
    private void recordMetadata(long rowCount, String sourceVersion) {
        GeonamesMetadataEntity entity = geonamesMetadataRepository.findById((short) 1)
                .orElseGet(() -> GeonamesMetadataEntity.builder()
                        .id((short) 1)
                        .build());
        entity.setLastImportedAt(LocalDateTime.now());
        entity.setSourceVersion(sourceVersion);
        entity.setImportedRowCount(rowCount);
        // cron 自動実行は imported_by_user_id = null のまま
        geonamesMetadataRepository.save(entity);
    }

    /**
     * 取り込み結果のサマリ。
     */
    public record GeonamesImportResult(long rowCount, String sourceVersion, long elapsedSec) {}
}
