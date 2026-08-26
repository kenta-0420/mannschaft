package com.mannschaft.app.weather.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.postal.CountryResolver;
import com.mannschaft.app.weather.entity.PostalCodeEntity;
import com.mannschaft.app.weather.entity.UserWeatherLocationEntity;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException;
import com.mannschaft.app.weather.exception.WeatherLocationDeriveException.ErrorCode;
import com.mannschaft.app.weather.metrics.WeatherMetrics;
import com.mannschaft.app.weather.repository.PostalCodeRepository;
import com.mannschaft.app.weather.repository.UserWeatherLocationRepository;
import com.mannschaft.app.weather.util.PostalCodeNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * F02.10 天気ウィジェット — 郵便番号から座標を導出するサービス。
 *
 * <p>処理フロー:
 * <ol>
 *   <li>ユーザーの暗号化郵便番号を {@link EncryptedStringConverter} 経由で復号</li>
 *   <li>国コードを正規化（NULL の場合は locale prefix から推定）</li>
 *   <li>郵便番号を国別フォーマットに正規化（JP: ハイフン除去 + 7 桁ゼロパディング）</li>
 *   <li>{@code postal_codes} を {@code (country_code, postal_code)} で引き当て</li>
 *   <li>緯度経度を 0.5 度に四捨五入</li>
 *   <li>平文郵便番号を HMAC-SHA256 でハッシュ化（既存共通シークレットを流用）</li>
 *   <li>{@code user_weather_locations(user_id, 'home')} を upsert</li>
 * </ol>
 *
 * <p><b>セキュリティ</b>:
 * 平文郵便番号・座標生値は一切ログに出さない。出力するのは
 * {@code country_code} と {@code place_name_snapshot} のみ。</p>
 *
 * <p>設計書: §5.3 / §7.1 / §7.2。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherLocationDeriver {

    /** 0.5 度丸めの分母（プライバシー保護のため約 55km 四方に丸める）。設計書 §7.1。 */
    private static final BigDecimal HALF_DEGREE = new BigDecimal("0.5");

    /** 本機能で扱う地点ラベル（将来複数地点拡張用、現状は home 固定）。 */
    private static final String DEFAULT_LABEL = "home";

    private final UserRepository userRepository;
    private final PostalCodeRepository postalCodeRepository;
    private final UserWeatherLocationRepository userWeatherLocationRepository;
    private final EncryptionService encryptionService;
    private final WeatherMetrics weatherMetrics;
    private final CountryResolver countryResolver;

    /**
     * 指定ユーザーの郵便番号から地点を導出し永続化する。
     *
     * @param userId 対象ユーザー ID
     * @return 保存された地点エンティティ。ユーザーが存在しない場合は空
     * @throws WeatherLocationDeriveException 郵便番号未登録 / マスタ未ヒット / 国未対応の各ケース
     */
    // noRollbackFor: WeatherLocationDeriveException は「マスタ未ヒット等の業務上想定される結果」であり
    // ロールバック不要。非同期リスナー（@TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW）が
    // 例外を catch しても、これを付けないと共有トランザクションが rollback-only にマークされ、
    // 最終 commit で UnexpectedRollbackException が発生する。本例外は DB 書き込み前に投げるため安全。
    @Transactional(noRollbackFor = WeatherLocationDeriveException.class)
    public Optional<UserWeatherLocationEntity> deriveAndPersist(Long userId) {
        Optional<UserEntity> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            log.debug("地点導出: 対象ユーザーが見つからない: userId={}", userId);
            return Optional.empty();
        }
        UserEntity user = userOpt.get();

        // AC-15: 郵便番号起因の決定的な失敗（未登録 / マスタ未ヒット / 国未対応）では、
        // 以前の有効な郵便番号で導出済みの古い地点キャッシュ（label="home"）を無効化（削除）してから
        // 再 throw する。これを残すとダッシュボード GET（行が存在すれば再導出しない実装）が
        // 旧郵便番号の天気を出し続けてしまう（設計 §458 違反）。WeatherProviderException（503/一時障害）は
        // この catch の対象外なので影響しない。noRollbackFor 指定によりこの削除は例外を再 throw してもコミットされる。
        try {
            return Optional.of(doDerive(userId, user));
        } catch (WeatherLocationDeriveException e) {
            userWeatherLocationRepository.findByUserIdAndLabel(userId, DEFAULT_LABEL)
                    .ifPresent(stale -> {
                        userWeatherLocationRepository.delete(stale);
                        log.info("地点導出失敗のため古い地点キャッシュを無効化: userId={}, errorCode={}",
                                userId, e.getErrorCode().name());
                    });
            throw e;
        }
    }

    /**
     * 郵便番号から地点を導出して永続化する本体処理。
     *
     * <p>郵便番号未登録 / マスタ未ヒット / 国未対応の場合は
     * {@link WeatherLocationDeriveException} を投げる。例外時の古い地点キャッシュ無効化（AC-15）は
     * 呼び出し元 {@link #deriveAndPersist(Long)} が担う。</p>
     *
     * @param userId 対象ユーザー ID
     * @param user   対象ユーザー（取得済み）
     * @return 保存された地点エンティティ
     */
    private UserWeatherLocationEntity doDerive(Long userId, UserEntity user) {
        // 1) 平文郵便番号を取得（EncryptedStringConverter で自動復号済み）
        String plainPostalCode = user.getPostalCode();
        if (plainPostalCode == null || plainPostalCode.isBlank()) {
            throw new WeatherLocationDeriveException(ErrorCode.POSTAL_CODE_MISSING);
        }

        // 2) 国コードを決定（NULL なら locale プレフィックスから推定）
        String countryCode = resolveCountryCode(user);

        // 3) 国別フォーマットに正規化（GeonamesImportService と同じロジックを共有）
        String normalizedPostalCode = PostalCodeNormalizer.normalize(countryCode, plainPostalCode);

        // 4) postal_codes 引き当て。マスタヒット失敗時は country/postal の粒度で切り分ける。
        Optional<PostalCodeEntity> masterOpt = postalCodeRepository
                .findByCountryCodeAndPostalCode(countryCode, normalizedPostalCode);
        if (masterOpt.isEmpty()) {
            // 該当国のマスタが 1 件も無いなら COUNTRY_NOT_SUPPORTED、
            // 一部だけ無いなら POSTAL_CODE_NOT_FOUND として切り分ける。
            if (!postalCodeRepository.existsByCountryCode(countryCode)) {
                log.info("地点導出: 国コードが GeoNames に未収録: countryCode={}", countryCode);
                weatherMetrics.recordLocationDerive("error");
                throw new WeatherLocationDeriveException(ErrorCode.COUNTRY_NOT_SUPPORTED);
            }
            log.info("地点導出: 郵便番号がマスタに未ヒット: countryCode={}", countryCode);
            weatherMetrics.recordLocationDerive("postal_not_found");
            throw new WeatherLocationDeriveException(ErrorCode.POSTAL_CODE_NOT_FOUND);
        }
        PostalCodeEntity master = masterOpt.get();

        // 5) 緯度経度を 0.5 度に四捨五入
        BigDecimal latRounded = roundToHalfDegree(master.getLatitude());
        BigDecimal lonRounded = roundToHalfDegree(master.getLongitude());

        // 6) 平文郵便番号を HMAC-SHA256 でハッシュ化（既存共通シークレット APP_HMAC_SECRET を流用）
        //    EncryptionService#hmac は内部で SHA-256 ベースのブラインドインデックスを生成する。
        String postalCodeHash = encryptionService.hmac(plainPostalCode);

        // 7) 地名スナップショット（admin1 / admin2 / place_name の優先順で表示用に整形）
        String placeNameSnapshot = formatPlaceName(master);

        // 8) upsert（user_id + label = home で UNIQUE）
        UserWeatherLocationEntity entity = userWeatherLocationRepository
                .findByUserIdAndLabel(userId, DEFAULT_LABEL)
                .map(existing -> {
                    existing.setCountryCode(countryCode);
                    existing.setPostalCodeHash(postalCodeHash);
                    existing.setLatitudeRounded(latRounded);
                    existing.setLongitudeRounded(lonRounded);
                    existing.setPlaceNameSnapshot(placeNameSnapshot);
                    existing.setDerivedAt(LocalDateTime.now());
                    return existing;
                })
                .orElseGet(() -> UserWeatherLocationEntity.builder()
                        .userId(userId)
                        .label(DEFAULT_LABEL)
                        .countryCode(countryCode)
                        .postalCodeHash(postalCodeHash)
                        .latitudeRounded(latRounded)
                        .longitudeRounded(lonRounded)
                        .placeNameSnapshot(placeNameSnapshot)
                        .derivedAt(LocalDateTime.now())
                        .build());

        UserWeatherLocationEntity saved = userWeatherLocationRepository.save(entity);

        // セキュリティ: 平文郵便番号・座標生値はログに出さない。country_code と place_name_snapshot のみ。
        log.info("地点導出完了: userId={}, countryCode={}, placeName={}",
                userId, countryCode, placeNameSnapshot);
        weatherMetrics.recordLocationDerive("success");

        return saved;
    }

    /**
     * 国コードを解決する。{@code users.country_code} が NULL の場合は
     * locale 文字列のプレフィックス（例: {@code "ja"} → {@code "JP"}）から推定する。
     *
     * <p>locale→国 マップは共有 {@link CountryResolver} に集約済み（auth ドメインの郵便番号検証と
     * 同じマップを使うため二重持ちを解消した）。解決不能時は従来どおり
     * {@link WeatherLocationDeriveException}（{@link ErrorCode#COUNTRY_NOT_SUPPORTED}）を投げる。</p>
     */
    private String resolveCountryCode(UserEntity user) {
        return countryResolver.resolve(user.getCountryCode(), user.getLocale())
                .orElseThrow(() -> new WeatherLocationDeriveException(ErrorCode.COUNTRY_NOT_SUPPORTED));
    }

    /**
     * 緯度・経度を 0.5 度単位に四捨五入する。
     * 例: 35.6812 → 35.5, 35.7501 → 35.5（HALF_UP 基準で 0.5 単位丸め）。
     */
    private BigDecimal roundToHalfDegree(BigDecimal value) {
        // value / 0.5 を整数桁に丸めて 0.5 を掛け直す
        BigDecimal scaled = value.divide(HALF_DEGREE, 0, RoundingMode.HALF_UP);
        return scaled.multiply(HALF_DEGREE).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 地名スナップショットを整形する。{@code admin1} + {@code admin2} を結合し、
     * 両方 NULL なら {@code place_name} を採用する。
     */
    private String formatPlaceName(PostalCodeEntity master) {
        String admin1 = master.getAdmin1Name();
        String admin2 = master.getAdmin2Name();
        if (admin1 != null && admin2 != null) {
            return admin1 + admin2;
        }
        if (admin1 != null) {
            return admin1;
        }
        return master.getPlaceName();
    }

}
