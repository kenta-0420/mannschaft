package com.mannschaft.app.reflection.repository;

import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ReflectionEntryRepository#findLatestTargetDateByThemeIds} の結合テスト（AC-26/AC-27）。
 *
 * <p>MySQL Testcontainers を使用するため、Docker が利用できない環境ではスキップされる。
 * CI（Docker 有）では実行される。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("ReflectionEntryRepository#findLatestTargetDateByThemeIds 結合テスト")
class ReflectionEntryRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReflectionEntryRepository entryRepository;

    @Autowired
    private ReflectionThemeRepository themeRepository;

    private static final Long USER_ID = 9001L;

    private UUID themeId1;
    private UUID themeId2;

    @BeforeEach
    void setUp() {
        // テーマを2つ作成
        ReflectionThemeEntity theme1 = themeRepository.saveAndFlush(
                ReflectionThemeEntity.builder()
                        .userId(USER_ID).title("テーマA").sourceType(ReflectionSourceType.FREE)
                        .recallIntervalDays("1,3,7").build());
        ReflectionThemeEntity theme2 = themeRepository.saveAndFlush(
                ReflectionThemeEntity.builder()
                        .userId(USER_ID).title("テーマB").sourceType(ReflectionSourceType.DIARY)
                        .recallIntervalDays("1,3,7").build());
        themeId1 = theme1.getId();
        themeId2 = theme2.getId();

        // テーマ1に3件のエントリ（最新は今日の3日前）
        LocalDate today = LocalDate.now();
        entryRepository.saveAndFlush(ReflectionEntryEntity.builder()
                .themeId(themeId1).userId(USER_ID)
                .targetDate(today.minusDays(10)).structuredContent("{}").build());
        entryRepository.saveAndFlush(ReflectionEntryEntity.builder()
                .themeId(themeId1).userId(USER_ID)
                .targetDate(today.minusDays(5)).structuredContent("{}").build());
        entryRepository.saveAndFlush(ReflectionEntryEntity.builder()
                .themeId(themeId1).userId(USER_ID)
                .targetDate(today.minusDays(3)).structuredContent("{}").build());

        // テーマ2に1件のエントリ
        entryRepository.saveAndFlush(ReflectionEntryEntity.builder()
                .themeId(themeId2).userId(USER_ID)
                .targetDate(today.minusDays(7)).structuredContent("{}").build());
    }

    @Test
    @DisplayName("AC-26: findLatestTargetDateByThemeIds - 各テーマの最新 targetDate（MAX）を GROUP BY で一括取得")
    void findLatestTargetDateByThemeIds_returnsMaxPerTheme() {
        LocalDate today = LocalDate.now();

        List<ReflectionEntryRepository.ThemeLastDateView> result =
                entryRepository.findLatestTargetDateByThemeIds(List.of(themeId1, themeId2));

        Map<UUID, LocalDate> byId = new java.util.HashMap<>();
        result.forEach(v -> byId.put(v.getThemeId(), v.getLastDate()));

        // テーマ1: 最新は today-3
        assertThat(byId.get(themeId1)).isEqualTo(today.minusDays(3));
        // テーマ2: 唯一のエントリで today-7
        assertThat(byId.get(themeId2)).isEqualTo(today.minusDays(7));
    }

    @Test
    @DisplayName("AC-27: themeIds が空コレクションのときは行を返さない")
    void findLatestTargetDateByThemeIds_emptyInput_returnsEmpty() {
        List<ReflectionEntryRepository.ThemeLastDateView> result =
                entryRepository.findLatestTargetDateByThemeIds(List.of());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("AC-26: エントリがないテーマは結果に含まれない（lastReflectedAt=null 相当）")
    void findLatestTargetDateByThemeIds_noEntriesForTheme_notIncludedInResult() {
        // 存在しない themeId を指定
        UUID unknownThemeId = UUID.randomUUID();

        List<ReflectionEntryRepository.ThemeLastDateView> result =
                entryRepository.findLatestTargetDateByThemeIds(List.of(unknownThemeId));

        assertThat(result).isEmpty();
    }
}
