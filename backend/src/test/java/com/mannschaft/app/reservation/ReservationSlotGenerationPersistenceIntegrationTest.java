package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import com.mannschaft.app.reservation.service.ReservationSlotGenerationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ReservationSlotGenerationService} の<b>実 MySQL</b> 結合テスト（F03.4.2 試練）。
 *
 * <p>受け入れ条件との対応（実DB観測点）:</p>
 * <ul>
 *   <li><b>F-5</b>: MON 10:00-13:00 テンプレ×weeks=1 で horizon 内の月曜 1 日に
 *       {@code line_id / template_id / capacity=1} 付きの 30 分セル 6 行が INSERT される</li>
 *   <li><b>F-6</b>: 直後の再 generate で新規 INSERT 0 件（{@code skippedExistingCount=6}・行数不変）</li>
 *   <li><b>F-7b</b>: (1) business_hours 0 行のチーム (2) {@code is_open=TRUE} かつ時刻 NULL — いずれも
 *       例外にならず 200 相当で返り、当該日の行が増えない（実 DB で両ケースをシードして検証）</li>
 * </ul>
 *
 * <p>生成はサービス内部の日付単位チャンク tx（REQUIRES_NEW）で<b>実コミット</b>されるため、
 * テストは {@code @Transactional} を付けず、@AfterEach で自前クリーンアップする。</p>
 */
@DisplayName("ReservationSlotGenerationService 実MySQL結合テスト（F-5/F-6/F-7b）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationSlotGenerationPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationSlotGenerationService generationService;

    @Autowired
    private ReservationSlotTemplateRepository templateRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private ReservationBusinessHourRepository businessHourRepository;

    /** チームIDはテストごとに一意化して相互汚染を防ぐ（クロスドメインFKは撤廃済みのため親行不要）。 */
    private static final long TEAM_BASE = 9_420_000L;

    private Long teamId;

    @AfterEach
    void cleanup() {
        if (teamId == null) {
            return;
        }
        // チャンク tx で実コミットされるため自前で掃除する（他テストへの波及防止）
        slotRepository.deleteAll(slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(60)));
        templateRepository.deleteAll(templateRepository.findByTeamId(teamId));
        businessHourRepository.deleteAll(businessHourRepository.findByTeamIdOrderByIdAsc(teamId));
    }

    private ReservationSlotTemplateEntity seedMonTemplate(Long team, LocalTime start, LocalTime end) {
        return templateRepository.save(ReservationSlotTemplateEntity.builder()
                .teamId(team)
                .lineId(null) // FK対象のため実ライン行を作らず共通枠テンプレで観測する
                .dayOfWeek(ReservationDayOfWeek.MON)
                .startTime(start)
                .endTime(end)
                .capacity(1)
                .build());
    }

    private void seedOpenAllWeek(Long team) {
        for (ReservationDayOfWeek dow : ReservationDayOfWeek.values()) {
            businessHourRepository.save(ReservationBusinessHourEntity.builder()
                    .teamId(team)
                    .dayOfWeek(dow.name())
                    .isOpen(true)
                    .openTime(LocalTime.of(9, 0))
                    .closeTime(LocalTime.of(18, 0))
                    .build());
        }
    }

    @Test
    @DisplayName("F-5: MON 10:00-13:00 × weeks=1 → 月曜1日に 30分セル6行（template_id/capacity=1）が実INSERTされる")
    void 生成_実DBで30分セル6行() {
        // Given
        teamId = TEAM_BASE + 1;
        ReservationSlotTemplateEntity tpl = seedMonTemplate(teamId, LocalTime.of(10, 0), LocalTime.of(13, 0));
        seedOpenAllWeek(teamId);

        // When
        GenerateSlotsResponse result = generationService.generateForTeam(teamId, 1, 777L);

        // Then: レスポンスカウント＋実 DB 行
        assertThat(result.getGeneratedCount()).isEqualTo(6);
        List<ReservationSlotEntity> slots = slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, result.getHorizonFrom(), result.getHorizonTo());
        assertThat(slots).hasSize(6);
        assertThat(slots).allSatisfy(slot -> {
            assertThat(slot.getTemplateId()).isEqualTo(tpl.getId());
            assertThat(slot.getCapacity()).isEqualTo(1);
            assertThat(slot.getSlotDate().getDayOfWeek()).isEqualTo(java.time.DayOfWeek.MONDAY);
            assertThat(slot.getCreatedBy()).isEqualTo(777L);
        });
        List<LocalTime> starts = slots.stream()
                .map(ReservationSlotEntity::getStartTime)
                .sorted(Comparator.naturalOrder())
                .toList();
        assertThat(starts).containsExactly(
                LocalTime.of(10, 0), LocalTime.of(10, 30), LocalTime.of(11, 0),
                LocalTime.of(11, 30), LocalTime.of(12, 0), LocalTime.of(12, 30));
    }

    @Test
    @DisplayName("F-6: 直後にもう一度 generate しても新規 INSERT 0 件（skippedExistingCount=6・行数不変）")
    void 生成_実DBで冪等() {
        // Given
        teamId = TEAM_BASE + 2;
        seedMonTemplate(teamId, LocalTime.of(10, 0), LocalTime.of(13, 0));
        seedOpenAllWeek(teamId);
        GenerateSlotsResponse first = generationService.generateForTeam(teamId, 1, 777L);
        assertThat(first.getGeneratedCount()).isEqualTo(6);

        // When: 再実行
        GenerateSlotsResponse second = generationService.generateForTeam(teamId, 1, 777L);

        // Then
        assertThat(second.getGeneratedCount()).isZero();
        assertThat(second.getSkippedExistingCount()).isEqualTo(6);
        assertThat(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                teamId, second.getHorizonFrom(), second.getHorizonTo())).hasSize(6);
    }

    @Test
    @DisplayName("F-7b(1): business_hours 0 行のチームで generate しても例外にならず行が増えない（skippedClosedDay）")
    void 生成_実DBで営業時間0行でもNPEにならない() {
        // Given: 営業時間を一切シードしない（新規チーム 0 行の実態を再現）
        teamId = TEAM_BASE + 3;
        seedMonTemplate(teamId, LocalTime.of(10, 0), LocalTime.of(13, 0));

        // When / Then
        assertThatCode(() -> {
            GenerateSlotsResponse result = generationService.generateForTeam(teamId, 1, 777L);
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedClosedDayCount()).isEqualTo(6);
        }).doesNotThrowAnyException();
        assertThat(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                teamId, LocalDate.now(), LocalDate.now().plusDays(30))).isEmpty();
    }

    @Test
    @DisplayName("F-7b(2): is_open=TRUE かつ open/close NULL の曜日も例外にならずスキップされる")
    void 生成_実DBで時刻NULLでもNPEにならない() {
        // Given: V3.063 実 DDL は時刻 NULL 許容 — is_open=TRUE で時刻未設定の行をシード
        teamId = TEAM_BASE + 4;
        seedMonTemplate(teamId, LocalTime.of(10, 0), LocalTime.of(13, 0));
        for (ReservationDayOfWeek dow : ReservationDayOfWeek.values()) {
            businessHourRepository.save(ReservationBusinessHourEntity.builder()
                    .teamId(teamId)
                    .dayOfWeek(dow.name())
                    .isOpen(true)
                    .openTime(null)
                    .closeTime(null)
                    .build());
        }

        // When / Then
        assertThatCode(() -> {
            GenerateSlotsResponse result = generationService.generateForTeam(teamId, 1, 777L);
            assertThat(result.getGeneratedCount()).isZero();
            assertThat(result.getSkippedClosedDayCount()).isEqualTo(6);
        }).doesNotThrowAnyException();
        assertThat(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                teamId, LocalDate.now(), LocalDate.now().plusDays(30))).isEmpty();
    }
}
