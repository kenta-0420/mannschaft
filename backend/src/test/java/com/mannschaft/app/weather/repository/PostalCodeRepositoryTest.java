package com.mannschaft.app.weather.repository;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.weather.entity.PostalCodeEntity;
import com.mannschaft.app.weather.entity.PostalCodeId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostalCodeRepository} 結合テスト。
 *
 * <p>自然複合主キー {@code (country_code, postal_code)} のマスタテーブルに対する
 * save / findByCountryCodeAndPostalCode の挙動を確認する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PostalCodeRepository 結合テスト")
class PostalCodeRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PostalCodeRepository repository;

    @PersistenceContext
    private EntityManager em;

    private PostalCodeEntity buildEntity(String country, String postal, String place, BigDecimal lat, BigDecimal lon) {
        return PostalCodeEntity.builder()
                .countryCode(country)
                .postalCode(postal)
                .placeName(place)
                .admin1Name("東京都")
                .admin2Name("千代田区")
                .latitude(lat)
                .longitude(lon)
                .accuracy((short) 4)
                .build();
    }

    @Test
    @DisplayName("save → findByCountryCodeAndPostalCode で複合キーで取得できる")
    void shouldFindByCompositeKey() {
        PostalCodeEntity saved = repository.save(
                buildEntity("JP", "1000001", "千代田",
                        new BigDecimal("35.68500"), new BigDecimal("139.75300")));
        em.flush();
        em.clear();

        Optional<PostalCodeEntity> found = repository.findByCountryCodeAndPostalCode("JP", "1000001");

        assertThat(found).isPresent();
        assertThat(found.get().getPlaceName()).isEqualTo("千代田");
        assertThat(found.get().getLatitude()).isEqualByComparingTo(new BigDecimal("35.68500"));
        assertThat(found.get().getAdmin1Name()).isEqualTo("東京都");
        assertThat(saved.getCountryCode()).isEqualTo("JP");
    }

    @Test
    @DisplayName("存在しない (country, postal) は空が返る")
    void shouldReturnEmptyWhenNotFound() {
        Optional<PostalCodeEntity> found = repository.findByCountryCodeAndPostalCode("US", "99999");
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findById に PostalCodeId（複合キー）を渡して取得できる")
    void shouldFindByIdClass() {
        repository.save(buildEntity("JP", "5300001", "梅田",
                new BigDecimal("34.70200"), new BigDecimal("135.49800")));
        em.flush();
        em.clear();

        Optional<PostalCodeEntity> found = repository.findById(new PostalCodeId("JP", "5300001"));

        assertThat(found).isPresent();
        assertThat(found.get().getPlaceName()).isEqualTo("梅田");
    }
}
