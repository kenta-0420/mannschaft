package com.mannschaft.app.weather.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link UserWeatherLocationEntity} の構造的検証。
 *
 * <p>新規 Entity の必須アノテーション（@Entity / @Table / @PersonalData / UuidV7Entity 継承）と
 * Builder / Setter / PrePersist の挙動を確認する。</p>
 */
@DisplayName("UserWeatherLocationEntity 単体テスト")
class UserWeatherLocationEntityTest {

    @Test
    @DisplayName("@Entity アノテーションが付与されている")
    void hasEntityAnnotation() {
        assertThat(UserWeatherLocationEntity.class.isAnnotationPresent(Entity.class)).isTrue();
    }

    @Test
    @DisplayName("@Table(name = \"user_weather_locations\") を持つ")
    void hasTableAnnotation() {
        Table table = UserWeatherLocationEntity.class.getAnnotation(Table.class);
        assertThat(table).isNotNull();
        assertThat(table.name()).isEqualTo("user_weather_locations");
    }

    @Test
    @DisplayName("UuidV7Entity を継承している（CLAUDE.md 原則 6 適用）")
    void extendsUuidV7Entity() {
        assertThat(UuidV7Entity.class.isAssignableFrom(UserWeatherLocationEntity.class)).isTrue();
    }

    @Test
    @DisplayName("@PersonalData(category = \"location_preference\") が付与されている")
    void hasPersonalDataAnnotation() {
        PersonalData personalData = UserWeatherLocationEntity.class.getAnnotation(PersonalData.class);
        assertThat(personalData).isNotNull();
        assertThat(personalData.category()).isEqualTo("location_preference");
    }

    @Test
    @DisplayName("Builder で各フィールドを設定できる")
    void canBuildWithBuilder() {
        LocalDateTime now = LocalDateTime.now();
        UserWeatherLocationEntity entity = UserWeatherLocationEntity.builder()
                .userId(100L)
                .label("home")
                .countryCode("JP")
                .postalCodeHash("a".repeat(64))
                .latitudeRounded(new BigDecimal("35.5"))
                .longitudeRounded(new BigDecimal("139.5"))
                .placeNameSnapshot("東京都千代田区")
                .derivedAt(now)
                .build();

        assertThat(entity.getUserId()).isEqualTo(100L);
        assertThat(entity.getLabel()).isEqualTo("home");
        assertThat(entity.getCountryCode()).isEqualTo("JP");
        assertThat(entity.getPostalCodeHash()).hasSize(64);
        assertThat(entity.getLatitudeRounded()).isEqualTo(new BigDecimal("35.5"));
        assertThat(entity.getLongitudeRounded()).isEqualTo(new BigDecimal("139.5"));
        assertThat(entity.getPlaceNameSnapshot()).isEqualTo("東京都千代田区");
        assertThat(entity.getDerivedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("getId() は永続化前は null を返す（UuidV7Entity の挙動を継承）")
    void getIdIsNullBeforePersist() {
        UserWeatherLocationEntity entity = UserWeatherLocationEntity.builder()
                .userId(1L)
                .label("home")
                .countryCode("JP")
                .postalCodeHash("a".repeat(64))
                .latitudeRounded(new BigDecimal("0.0"))
                .longitudeRounded(new BigDecimal("0.0"))
                .placeNameSnapshot("test")
                .derivedAt(LocalDateTime.now())
                .build();
        assertThat(entity.getId()).isNull();
    }
}
