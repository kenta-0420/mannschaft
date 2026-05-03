package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetablePeriodEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetablePeriodRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.15 Phase 1 個人時間割の Repository 層統合テスト。
 *
 * <p>Testcontainers で MySQL を起動し、以下を検証する:</p>
 * <ul>
 *   <li>PersonalTimetableRepository の主要クエリ（findByUserId..., countByUser..., findOverlappingActive）</li>
 *   <li>論理削除（deleted_at IS NULL）の SQLRestriction が効いていること</li>
 * </ul>
 *
 * <p>テーブルは Flyway マイグレーション (V14.001 〜 V14.008) により Testcontainers MySQL 上に生成される
 * （application-test.yml で {@code spring.flyway.enabled=true} かつ {@code spring.jpa.hibernate.ddl-auto=validate}）。</p>
 *
 * <p><b>OOM 対策</b>: {@link AbstractMySqlIntegrationTest} を継承して ApplicationContext と
 * MySQL コンテナを他統合テストと共有する。詳細は親クラスの Javadoc を参照。</p>
 */
@DisplayName("PersonalTimetable 統合テスト")
// JUnit 5 の @EnabledIf は @Inherited ではないため、派生クラスでも明示的に再宣言する必要がある
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PersonalTimetableIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PersonalTimetableRepository repository;

    @Autowired
    private PersonalTimetablePeriodRepository periodRepository;

    @Autowired
    private PersonalTimetableSlotRepository slotRepository;

    private PersonalTimetableEntity persist(Long userId,
                                            String name,
                                            PersonalTimetableStatus status,
                                            LocalDate from,
                                            LocalDate until) {
        PersonalTimetableEntity entity = PersonalTimetableEntity.builder()
                .userId(userId)
                .name(name)
                .effectiveFrom(from)
                .effectiveUntil(until)
                .status(status)
                .visibility(PersonalTimetableVisibility.PRIVATE)
                .weekPatternEnabled(false)
                .build();
        return repository.saveAndFlush(entity);
    }

    @Test
    @DisplayName("findByUserIdAndDeletedAtIsNullOrderByEffectiveFromDesc: 自分の未削除のみ effective_from 降順で返る")
    void findByUserId_順序と論理削除フィルタ() {
        Long userId = 5_001L;
        PersonalTimetableEntity early = persist(userId, "前期", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));
        PersonalTimetableEntity later = persist(userId, "後期", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 10, 1), LocalDate.of(2027, 3, 31));
        PersonalTimetableEntity deleted = persist(userId, "消去予定", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2025, 4, 1), LocalDate.of(2025, 9, 30));
        deleted.softDelete();
        repository.saveAndFlush(deleted);

        List<PersonalTimetableEntity> result =
                repository.findByUserIdAndDeletedAtIsNullOrderByEffectiveFromDesc(userId);

        assertThat(result).extracting(PersonalTimetableEntity::getId)
                .containsExactly(later.getId(), early.getId());
    }

    @Test
    @DisplayName("countByUserIdAndDeletedAtIsNull: 論理削除分はカウント対象外")
    void count_論理削除を除外() {
        Long userId = 5_002L;
        persist(userId, "A", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        PersonalTimetableEntity gone = persist(userId, "B", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));
        gone.softDelete();
        repository.saveAndFlush(gone);

        assertThat(repository.countByUserIdAndDeletedAtIsNull(userId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("findOverlappingActive: 同一ユーザーの ACTIVE で期間重複のみ返す（自身は除外）")
    void overlapping_ACTIVEのみ_期間重複() {
        Long userId = 5_003L;
        // 自分（DRAFT 中、これから activate する想定）
        PersonalTimetableEntity self = persist(userId, "新規", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));
        // 重複ACTIVE
        PersonalTimetableEntity overlapping = persist(userId, "既存ACTIVE", PersonalTimetableStatus.ACTIVE,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 10, 31));
        // 重複しないACTIVE
        persist(userId, "別期間ACTIVE", PersonalTimetableStatus.ACTIVE,
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31));
        // 別ユーザーのACTIVEは無関係
        persist(9_999L, "他人ACTIVE", PersonalTimetableStatus.ACTIVE,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        List<PersonalTimetableEntity> result = repository.findOverlappingActive(
                userId, self.getId(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        assertThat(result).extracting(PersonalTimetableEntity::getId)
                .containsExactly(overlapping.getId());
    }

    // ============================================================
    // Phase 2: 時限・コマ Repository 統合テスト
    // ============================================================

    @Test
    @Transactional
    @DisplayName("Phase 2 PeriodRepository: 個人時間割IDで periodNumber 昇順取得・一括削除")
    void period_リポジトリCRUD() {
        Long userId = 6_001L;
        PersonalTimetableEntity tt = persist(userId, "Phase2-period", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        periodRepository.save(PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(tt.getId())
                .periodNumber(2)
                .label("2限")
                .startTime(LocalTime.of(10, 40))
                .endTime(LocalTime.of(12, 10))
                .isBreak(false)
                .build());
        periodRepository.save(PersonalTimetablePeriodEntity.builder()
                .personalTimetableId(tt.getId())
                .periodNumber(1)
                .label("1限")
                .startTime(LocalTime.of(9, 0))
                .endTime(LocalTime.of(10, 30))
                .isBreak(false)
                .build());
        periodRepository.flush();

        List<PersonalTimetablePeriodEntity> result =
                periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(tt.getId());
        assertThat(result).extracting(PersonalTimetablePeriodEntity::getPeriodNumber)
                .containsExactly(1, 2);

        periodRepository.deleteByPersonalTimetableId(tt.getId());
        periodRepository.flush();
        assertThat(periodRepository.findByPersonalTimetableIdOrderByPeriodNumberAsc(tt.getId()))
                .isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("Phase 2 SlotRepository: 曜日フィルタと部分削除（曜日指定）")
    void slot_曜日フィルタと部分削除() {
        Long userId = 6_002L;
        PersonalTimetableEntity tt = persist(userId, "Phase2-slot", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        slotRepository.save(PersonalTimetableSlotEntity.builder()
                .personalTimetableId(tt.getId())
                .dayOfWeek("MON").periodNumber(1).weekPattern(WeekPattern.EVERY)
                .subjectName("国語").autoSyncChanges(true).build());
        slotRepository.save(PersonalTimetableSlotEntity.builder()
                .personalTimetableId(tt.getId())
                .dayOfWeek("MON").periodNumber(2).weekPattern(WeekPattern.EVERY)
                .subjectName("数学").autoSyncChanges(true).build());
        slotRepository.save(PersonalTimetableSlotEntity.builder()
                .personalTimetableId(tt.getId())
                .dayOfWeek("TUE").periodNumber(1).weekPattern(WeekPattern.EVERY)
                .subjectName("理科").autoSyncChanges(true).build());
        slotRepository.flush();

        assertThat(slotRepository.countByPersonalTimetableId(tt.getId())).isEqualTo(3L);
        assertThat(slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                tt.getId(), "MON")).hasSize(2);

        // 曜日指定削除
        slotRepository.deleteByPersonalTimetableIdAndDayOfWeek(tt.getId(), "MON");
        slotRepository.flush();
        assertThat(slotRepository.countByPersonalTimetableId(tt.getId())).isEqualTo(1L);
        assertThat(slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                tt.getId(), "MON")).isEmpty();
        assertThat(slotRepository.findByPersonalTimetableIdAndDayOfWeekOrderByPeriodNumberAsc(
                tt.getId(), "TUE")).hasSize(1);
    }

    @Test
    @DisplayName("findOverlappingActive: effective_until が NULL の既存 ACTIVE も対象")
    void overlapping_終端なしACTIVE() {
        Long userId = 5_004L;
        PersonalTimetableEntity self = persist(userId, "新規", PersonalTimetableStatus.DRAFT,
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));
        PersonalTimetableEntity ongoing = persist(userId, "終端なしACTIVE", PersonalTimetableStatus.ACTIVE,
                LocalDate.of(2026, 1, 1), null);

        List<PersonalTimetableEntity> result = repository.findOverlappingActive(
                userId, self.getId(),
                LocalDate.of(2026, 4, 1), LocalDate.of(2026, 9, 30));

        assertThat(result).extracting(PersonalTimetableEntity::getId)
                .containsExactly(ongoing.getId());
    }
}
