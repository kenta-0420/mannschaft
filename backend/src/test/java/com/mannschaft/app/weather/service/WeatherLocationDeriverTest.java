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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WeatherLocationDeriver} の単体テスト。
 */
@DisplayName("WeatherLocationDeriver 単体テスト")
@ExtendWith(MockitoExtension.class)
class WeatherLocationDeriverTest {

    @Mock private UserRepository userRepository;
    @Mock private PostalCodeRepository postalCodeRepository;
    @Mock private UserWeatherLocationRepository userWeatherLocationRepository;
    @Mock private EncryptionService encryptionService;
    @Mock private WeatherMetrics weatherMetrics;
    // locale→国 マップは実ロジックを使う（共有 CountryResolver の挙動回帰防止も兼ねる）
    @Spy private CountryResolver countryResolver = new CountryResolver();

    @InjectMocks private WeatherLocationDeriver deriver;

    private ListAppender<ILoggingEvent> logAppender;
    private ch.qos.logback.classic.Level originalLogLevel;

    @BeforeEach
    void attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WeatherLocationDeriver.class);
        originalLogLevel = logger.getLevel();
        // ログ出力検証テストのため、同一 gradle テストフォーク内で先行する SpringBootTest
        // （test プロファイル・root=WARN）の影響を受けないよう実効レベルを自明に設定する。
        logger.setLevel(ch.qos.logback.classic.Level.ALL);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(WeatherLocationDeriver.class);
        logger.detachAppender(logAppender);
        logger.setLevel(originalLogLevel);
    }

    @Test
    @DisplayName("正常系: JP 郵便番号から緯度経度を導出し 0.5 度に丸めて保存する")
    void deriveAndPersist_jpNormal() {
        Long userId = 100L;
        UserEntity user = UserEntity.builder()
                .postalCode("100-0001")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        PostalCodeEntity master = PostalCodeEntity.builder()
                .countryCode("JP")
                .postalCode("1000001")
                .placeName("Chiyoda")
                .admin1Name("東京都")
                .admin2Name("千代田区")
                .latitude(new BigDecimal("35.68190"))
                .longitude(new BigDecimal("139.69300"))
                .accuracy((short) 4)
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "1000001"))
                .thenReturn(Optional.of(master));
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.empty());
        when(encryptionService.hmac("100-0001")).thenReturn("deadbeefdeadbeef");
        when(userWeatherLocationRepository.save(any(UserWeatherLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Optional<UserWeatherLocationEntity> result = deriver.deriveAndPersist(userId);

        assertThat(result).isPresent();
        ArgumentCaptor<UserWeatherLocationEntity> captor =
                ArgumentCaptor.forClass(UserWeatherLocationEntity.class);
        verify(userWeatherLocationRepository).save(captor.capture());
        UserWeatherLocationEntity saved = captor.getValue();
        // 35.6819 → 0.5 度に丸めると 35.5
        assertThat(saved.getLatitudeRounded()).isEqualByComparingTo("35.5");
        // 139.693 → 0.5 度に丸めると 139.5（東京駅の実経度に近い値）
        // NOTE: 139.75 は 0.5/140.0 グリッドの中間で HALF_UP では 140.0 になる
        assertThat(saved.getLongitudeRounded()).isEqualByComparingTo("139.5");
        assertThat(saved.getCountryCode()).isEqualTo("JP");
        assertThat(saved.getPlaceNameSnapshot()).isEqualTo("東京都千代田区");
        assertThat(saved.getPostalCodeHash()).isEqualTo("deadbeefdeadbeef");
        assertThat(saved.getLabel()).isEqualTo("home");
        assertThat(saved.getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("正常系: 0.5 度丸めは四捨五入で動作する（境界 35.7501 → 35.5 ではなく 36.0）")
    void roundingBehavesAsHalfUp() {
        Long userId = 101L;
        UserEntity user = UserEntity.builder()
                .postalCode("0600000")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        // 35.7501 / 0.5 = 71.5002 → HALF_UP で 72 → * 0.5 = 36.0
        PostalCodeEntity master = PostalCodeEntity.builder()
                .countryCode("JP")
                .postalCode("0600000")
                .placeName("Sapporo")
                .admin1Name("北海道")
                .admin2Name("札幌市")
                .latitude(new BigDecimal("35.75010"))
                .longitude(new BigDecimal("139.25000"))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "0600000"))
                .thenReturn(Optional.of(master));
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.empty());
        when(encryptionService.hmac("0600000")).thenReturn("hash");
        when(userWeatherLocationRepository.save(any(UserWeatherLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        deriver.deriveAndPersist(userId);

        ArgumentCaptor<UserWeatherLocationEntity> captor =
                ArgumentCaptor.forClass(UserWeatherLocationEntity.class);
        verify(userWeatherLocationRepository).save(captor.capture());
        assertThat(captor.getValue().getLatitudeRounded()).isEqualByComparingTo("36.0");
        // 139.25 / 0.5 = 278.5 → HALF_UP で 279 → 139.5
        assertThat(captor.getValue().getLongitudeRounded()).isEqualByComparingTo("139.5");
    }

    @Test
    @DisplayName("異常系: 郵便番号が NULL なら POSTAL_CODE_MISSING")
    void postalCodeMissing() {
        Long userId = 102L;
        UserEntity user = UserEntity.builder()
                .postalCode(null)
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> deriver.deriveAndPersist(userId))
                .isInstanceOf(WeatherLocationDeriveException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.POSTAL_CODE_MISSING);
        verify(userWeatherLocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("異常系: 郵便番号がマスタに未ヒット（同国の他レコードは存在）→ POSTAL_CODE_NOT_FOUND")
    void postalCodeNotFound() {
        Long userId = 103L;
        UserEntity user = UserEntity.builder()
                .postalCode("9999999")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "9999999"))
                .thenReturn(Optional.empty());
        when(postalCodeRepository.existsByCountryCode("JP")).thenReturn(true);

        assertThatThrownBy(() -> deriver.deriveAndPersist(userId))
                .isInstanceOf(WeatherLocationDeriveException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.POSTAL_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("異常系: 同国マスタが 1 件も無い → COUNTRY_NOT_SUPPORTED")
    void countryNotSupported() {
        Long userId = 104L;
        UserEntity user = UserEntity.builder()
                .postalCode("99999")
                .countryCode("XX")
                .locale("en-US")
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("XX", "99999"))
                .thenReturn(Optional.empty());
        when(postalCodeRepository.existsByCountryCode("XX")).thenReturn(false);

        assertThatThrownBy(() -> deriver.deriveAndPersist(userId))
                .isInstanceOf(WeatherLocationDeriveException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.COUNTRY_NOT_SUPPORTED);
    }

    @Test
    @DisplayName("セキュリティ: 平文郵便番号がログ出力に含まれない")
    void plaintextPostalCodeNotInLogs() {
        Long userId = 105L;
        String plainPostalCode = "100-0001";
        UserEntity user = UserEntity.builder()
                .postalCode(plainPostalCode)
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        PostalCodeEntity master = PostalCodeEntity.builder()
                .countryCode("JP")
                .postalCode("1000001")
                .placeName("Chiyoda")
                .admin1Name("東京都")
                .admin2Name("千代田区")
                .latitude(new BigDecimal("35.68190"))
                .longitude(new BigDecimal("139.69300"))
                .build();
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "1000001"))
                .thenReturn(Optional.of(master));
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.empty());
        when(encryptionService.hmac(plainPostalCode)).thenReturn("hash");
        when(userWeatherLocationRepository.save(any(UserWeatherLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        deriver.deriveAndPersist(userId);

        // ログから平文郵便番号が出ていないこと（"100-0001" も "1000001" も出ない）を確認
        for (ILoggingEvent event : logAppender.list) {
            String formatted = event.getFormattedMessage();
            assertThat(formatted).doesNotContain("100-0001");
            assertThat(formatted).doesNotContain("1000001");
            // 座標生値も出ないこと
            assertThat(formatted).doesNotContain("35.68190");
            assertThat(formatted).doesNotContain("139.69300");
        }
        verify(userWeatherLocationRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("正常系: 既存レコードがあれば update する（save が呼ばれるが新規 builder ではない）")
    void existingRecordIsUpdated() {
        Long userId = 106L;
        UserEntity user = UserEntity.builder()
                .postalCode("100-0001")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        PostalCodeEntity master = PostalCodeEntity.builder()
                .countryCode("JP")
                .postalCode("1000001")
                .placeName("Chiyoda")
                .admin1Name("東京都")
                .admin2Name("千代田区")
                .latitude(new BigDecimal("35.68190"))
                .longitude(new BigDecimal("139.69300"))
                .build();
        UserWeatherLocationEntity existing = UserWeatherLocationEntity.builder()
                .userId(userId)
                .label("home")
                .countryCode("JP")
                .postalCodeHash("oldhash")
                .latitudeRounded(new BigDecimal("0.0"))
                .longitudeRounded(new BigDecimal("0.0"))
                .placeNameSnapshot("古い地名")
                .derivedAt(java.time.LocalDateTime.now().minusDays(10))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "1000001"))
                .thenReturn(Optional.of(master));
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.of(existing));
        when(encryptionService.hmac("100-0001")).thenReturn("newhash");
        when(userWeatherLocationRepository.save(any(UserWeatherLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        deriver.deriveAndPersist(userId);

        // 既存インスタンスのフィールドが更新されたことを確認
        assertThat(existing.getPostalCodeHash()).isEqualTo("newhash");
        assertThat(existing.getLatitudeRounded()).isEqualByComparingTo("35.5");
        assertThat(existing.getPlaceNameSnapshot()).isEqualTo("東京都千代田区");
    }

    @Test
    @DisplayName("AC-15: マスタ未ヒット失敗時、既存の古い地点キャッシュを削除してから再 throw する")
    void staleLocationDeletedOnFailure() {
        Long userId = 107L;
        UserEntity user = UserEntity.builder()
                .postalCode("9999999")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        UserWeatherLocationEntity stale = UserWeatherLocationEntity.builder()
                .userId(userId)
                .label("home")
                .countryCode("JP")
                .postalCodeHash("oldhash")
                .latitudeRounded(new BigDecimal("35.5"))
                .longitudeRounded(new BigDecimal("139.5"))
                .placeNameSnapshot("古い地名")
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "9999999"))
                .thenReturn(Optional.empty());
        when(postalCodeRepository.existsByCountryCode("JP")).thenReturn(true);
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> deriver.deriveAndPersist(userId))
                .isInstanceOf(WeatherLocationDeriveException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.POSTAL_CODE_NOT_FOUND);
        // 古い地点キャッシュが削除されること
        verify(userWeatherLocationRepository).delete(stale);
        // 新規保存はされないこと
        verify(userWeatherLocationRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-15: 成功時は古い地点キャッシュを delete しない（update のみ）")
    void staleLocationNotDeletedOnSuccess() {
        Long userId = 108L;
        UserEntity user = UserEntity.builder()
                .postalCode("100-0001")
                .countryCode("JP")
                .locale("ja-JP")
                .build();
        PostalCodeEntity master = PostalCodeEntity.builder()
                .countryCode("JP")
                .postalCode("1000001")
                .placeName("Chiyoda")
                .admin1Name("東京都")
                .admin2Name("千代田区")
                .latitude(new BigDecimal("35.68190"))
                .longitude(new BigDecimal("139.69300"))
                .build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(postalCodeRepository.findByCountryCodeAndPostalCode("JP", "1000001"))
                .thenReturn(Optional.of(master));
        when(userWeatherLocationRepository.findByUserIdAndLabel(userId, "home"))
                .thenReturn(Optional.empty());
        when(encryptionService.hmac("100-0001")).thenReturn("hash");
        when(userWeatherLocationRepository.save(any(UserWeatherLocationEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        deriver.deriveAndPersist(userId);

        verify(userWeatherLocationRepository, never()).delete(any());
    }

    @Test
    @DisplayName("正常系: ユーザーが存在しなければ Optional.empty を返す")
    void userNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        Optional<UserWeatherLocationEntity> result = deriver.deriveAndPersist(999L);

        assertThat(result).isEmpty();
        verify(userWeatherLocationRepository, never()).save(any());
    }
}
