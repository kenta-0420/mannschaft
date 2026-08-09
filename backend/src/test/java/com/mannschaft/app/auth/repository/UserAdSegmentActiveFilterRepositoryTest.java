package com.mannschaft.app.auth.repository;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 広告配信ターゲット解決 — 退会・停止ユーザー除外の統一を検証する結合テスト。
 *
 * <p>REGION_PREFECTURE / REGION_CITY / AGE_RANGE / GENDER の 4 セグメント種別は、
 * 従来 {@code deleted_at IS NULL} のみで絞り込んでおり {@code status = ACTIVE} を要求していなかった。
 * 本テストは {@link UserRepository} の各 find/count クエリが「論理削除済みユーザー」「ACTIVE 以外の
 * ステータスのユーザー」の双方を除外し、かつ find 版と count 版の件数が完全に一致することを検証する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("UserRepository 広告セグメントクエリ status=ACTIVE 統一 結合テスト")
class UserAdSegmentActiveFilterRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime());

    private String genderHash;
    private String prefectureHash;
    private String cityHash;
    private static final int BIRTH_YEAR = 1990;

    private Long activeUserId;
    private Long frozenUserId;
    private Long deletedUserId;

    @BeforeEach
    void setUp() {
        genderHash = encryptionService.hmac("male");
        prefectureHash = encryptionService.hmac("13");
        cityHash = encryptionService.hmac("13101");

        // ACTIVE かつ未削除 → 結果に含まれるべき唯一のユーザー
        activeUserId = persistUser(UserEntity.UserStatus.ACTIVE);

        // ステータスが ACTIVE 以外（停止中） → 除外されるべき
        frozenUserId = persistUser(UserEntity.UserStatus.FROZEN);

        // status=ACTIVE のまま論理削除済み（退会処理中） → 除外されるべき
        deletedUserId = persistUser(UserEntity.UserStatus.ACTIVE);
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), deletedUserId);
    }

    private Long persistUser(UserEntity.UserStatus status) {
        long n = SEQ.incrementAndGet();
        UserEntity user = UserEntity.builder()
                .email("ad-segment-" + n + "@example.com")
                .lastName("山田")
                .firstName("太郎")
                .displayName("user" + n)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(status)
                .isSearchable(true)
                .genderHash(genderHash)
                .prefectureCodeHash(prefectureHash)
                .cityCodeHash(cityHash)
                .birthYear(BIRTH_YEAR)
                .build();
        return userRepository.saveAndFlush(user).getId();
    }

    @Test
    @DisplayName("GENDER: ACTIVE かつ未削除のユーザーのみ返り、find/count の件数が一致する")
    void genderSegment_onlyActiveAndUndeleted() {
        List<Long> found = userRepository.findUserIdsByGenderHashIn(List.of(genderHash));
        long count = userRepository.countUserIdsByGenderHashIn(List.of(genderHash));

        assertThat(found).containsExactly(activeUserId);
        assertThat(found).doesNotContain(frozenUserId, deletedUserId);
        assertThat(count).isEqualTo(found.size());
    }

    @Test
    @DisplayName("REGION_PREFECTURE: ACTIVE かつ未削除のユーザーのみ返り、find/count の件数が一致する")
    void prefectureSegment_onlyActiveAndUndeleted() {
        List<Long> found = userRepository.findUserIdsByPrefectureCodeHashIn(List.of(prefectureHash));
        long count = userRepository.countUserIdsByPrefectureCodeHashIn(List.of(prefectureHash));

        assertThat(found).containsExactly(activeUserId);
        assertThat(found).doesNotContain(frozenUserId, deletedUserId);
        assertThat(count).isEqualTo(found.size());
    }

    @Test
    @DisplayName("REGION_CITY: ACTIVE かつ未削除のユーザーのみ返り、find/count の件数が一致する")
    void citySegment_onlyActiveAndUndeleted() {
        List<Long> found = userRepository.findUserIdsByCityCodeHashIn(List.of(cityHash));
        long count = userRepository.countUserIdsByCityCodeHashIn(List.of(cityHash));

        assertThat(found).containsExactly(activeUserId);
        assertThat(found).doesNotContain(frozenUserId, deletedUserId);
        assertThat(count).isEqualTo(found.size());
    }

    @Test
    @DisplayName("AGE_RANGE: ACTIVE かつ未削除のユーザーのみ返り、find/count の件数が一致する")
    void ageRangeSegment_onlyActiveAndUndeleted() {
        List<Long> found = userRepository.findUserIdsByBirthYearBetween(BIRTH_YEAR - 1, BIRTH_YEAR + 1);
        long count = userRepository.countUserIdsByBirthYearBetween(BIRTH_YEAR - 1, BIRTH_YEAR + 1);

        assertThat(found).containsExactly(activeUserId);
        assertThat(found).doesNotContain(frozenUserId, deletedUserId);
        assertThat(count).isEqualTo(found.size());
    }
}
