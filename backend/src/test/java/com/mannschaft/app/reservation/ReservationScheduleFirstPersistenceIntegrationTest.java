package com.mannschaft.app.reservation;

import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import com.mannschaft.app.reservation.service.ReservationSlotGenerationService;
import com.mannschaft.app.reservation.service.ReservationSlotTemplateService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F03.4.5 §3（営業スケジュール中心モデル）BE の<b>実 MySQL</b> 結合テスト。
 *
 * <p>受け入れ条件との対応（実DB観測点）:</p>
 * <ul>
 *   <li><b>S-1 / S-3b</b>: テンプレ保存（{@code createTemplate}・{@code @Transactional} コミット）→ 外側で
 *       {@code generateForTemplate}（SUPPORTS）を呼ぶと、<b>lock wait timeout を起こさず</b> horizon 内の枠が
 *       実 INSERT される（FK {@code fk_rs_template} 自己デッドロック罠の回避を実走で確認）</li>
 *   <li><b>S-8③（クランプ番人）</b>: horizon 外（tomorrow+28〜+90日）へ臨時営業（{@code generateSingleDay}）で
 *       {@code template_id} 付きセルを作った後でも、日次バッチ（{@code generateDiffForTeam}）が当該テンプレの
 *       <b>horizon 内の未生成週次枠を欠落なく生成</b>する（クランプなし実装なら差分レンジが空になり 0 件のまま=red）</li>
 *   <li><b>S-8（skip business hours）</b>: 営業時間 0 行（定休相当）でも {@code generateSingleDay} はセルを生成する</li>
 * </ul>
 *
 * <p>生成はチャンク tx（REQUIRES_NEW）で実コミットされるため {@code @Transactional} を付けず
 * {@code @AfterEach} で自前クリーンアップする（既存 {@code ReservationSlotGenerationPersistenceIntegrationTest} 写経）。</p>
 */
@DisplayName("F03.4.5 §3 営業スケジュール中心モデル 実MySQL結合テスト（S-1/S-3b/S-8③）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ReservationScheduleFirstPersistenceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ReservationSlotGenerationService generationService;

    @Autowired
    private ReservationSlotTemplateService templateService;

    @Autowired
    private ReservationSlotTemplateRepository templateRepository;

    @Autowired
    private ReservationSlotRepository slotRepository;

    @Autowired
    private ReservationBusinessHourRepository businessHourRepository;

    /** チームIDはテストごとに一意化して相互汚染を防ぐ。 */
    private static final long TEAM_BASE = 9_450_000L;

    private Long teamId;

    @AfterEach
    void cleanup() {
        if (teamId == null) {
            return;
        }
        slotRepository.deleteAll(slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, LocalDate.now().minusDays(1), LocalDate.now().plusDays(100)));
        templateRepository.deleteAll(templateRepository.findByTeamId(teamId));
        businessHourRepository.deleteAll(businessHourRepository.findByTeamIdOrderByIdAsc(teamId));
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

    private ReservationSlotTemplateEntity seedTemplate(Long team, ReservationDayOfWeek dow,
                                                       LocalTime start, LocalTime end) {
        return templateRepository.save(ReservationSlotTemplateEntity.builder()
                .teamId(team)
                .lineId(null) // 共通枠テンプレ（ライン FK 行を作らずに観測する）
                .dayOfWeek(dow)
                .startTime(start)
                .endTime(end)
                .capacity(1)
                .build());
    }

    private int countInHorizonSlots(LocalDate from, LocalDate to) {
        return slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, from, to)
                .size();
    }

    @Test
    @DisplayName("S-1/S-3b: createTemplate コミット後に generateForTemplate（外側）を呼んでも lock wait timeout せず枠が実INSERTされる")
    void 保存後の自動生成がtx境界罠を踏まず枠を作る() {
        // Given: 営業時間を全曜日オープンにしておく（MON テンプレが生成対象になる）
        teamId = TEAM_BASE + 1;
        seedOpenAllWeek(teamId);
        CreateSlotTemplateRequest request = new CreateSlotTemplateRequest(
                "平日午前", null, ReservationDayOfWeek.MON,
                LocalTime.of(10, 0), LocalTime.of(13, 0), 1, null, null, null, null);

        // When: ① 保存 tx コミット → ② 外側（SUPPORTS）で同期自動生成
        SlotTemplateResponse saved = templateService.createTemplate(teamId, request, 777L);
        final GenerateSlotsResponse[] holder = new GenerateSlotsResponse[1];
        assertThatCode(() ->
                holder[0] = templateService.generateForTemplate(teamId, saved.getId(), 777L))
                .doesNotThrowAnyException();

        // Then: horizon 28日内に MON セル（6セル/日 × 月曜出現数）が実 INSERT される
        GenerateSlotsResponse gen = holder[0];
        assertThat(gen.getGeneratedCount()).isGreaterThanOrEqualTo(6);
        List<ReservationSlotEntity> slots = slotRepository
                .findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, gen.getHorizonFrom(), gen.getHorizonTo());
        assertThat(slots).isNotEmpty();
        assertThat(slots).allSatisfy(s -> {
            assertThat(s.getTemplateId()).isEqualTo(saved.getId());
            assertThat(s.getSlotDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        });
    }

    @Test
    @DisplayName("S-8③: horizon外(+28〜+90日)へ臨時営業した後も、日次バッチが horizon内の未生成週次枠を欠落なく生成する（クランプ番人）")
    void 臨時営業でhorizon外にセルを作っても日次バッチが週次枠を埋める() {
        // Given: MON テンプレ 10:00-11:00（2セル/日）＋全曜日オープン
        teamId = TEAM_BASE + 2;
        seedOpenAllWeek(teamId);
        seedTemplate(teamId, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0));

        LocalDate tomorrow = LocalDate.now().plusDays(1);
        LocalDate horizonEnd = tomorrow.plusDays(27);
        // horizon 内の月曜出現数（バッチ完了後の期待セル数の基準）
        int mondaysInHorizon = countWeekdayOccurrences(tomorrow, horizonEnd, DayOfWeek.MONDAY);

        // ① weeks=1 で最初の月曜のみ生成（horizon 内に「歯抜け」を作る）
        generationService.generateForTeam(teamId, 1, 777L);
        int afterWeeks1 = countInHorizonSlots(tomorrow, horizonEnd);
        assertThat(afterWeeks1).isEqualTo(2); // 最初の月曜の 2 セルのみ

        // ② horizon 外の月曜へ臨時営業（MON ダイヤ）→ 素の MAX(slot_date) が horizon 外へ跳ねる
        LocalDate outsideMonday = horizonEnd.plusDays(1).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        GenerateSlotsResponse single =
                generationService.generateSingleDay(teamId, outsideMonday, ReservationDayOfWeek.MON, 777L);
        assertThat(single.getGeneratedCount()).isEqualTo(2);

        // When: 日次バッチ差分生成（クランプ導出でウォーターマークが horizon 内に留まる）
        generationService.generateDiffForTeam(teamId);

        // Then: horizon 内の全月曜（2セル×月曜数）が生成されている＝歯抜けが埋まった（クランプなしなら 2 のまま=red）
        int afterBatch = countInHorizonSlots(tomorrow, horizonEnd);
        assertThat(afterBatch).isEqualTo(mondaysInHorizon * 2);
        assertThat(afterBatch).isGreaterThan(afterWeeks1);
    }

    @Test
    @DisplayName("S-8: 営業時間 0 行（定休相当）でも generateSingleDay はセルを生成する（営業時間チェック省略）")
    void 臨時営業は営業時間0行でも生成する() {
        // Given: 火曜テンプレ 10:00-11:00（2セル）。営業時間は一切シードしない（定休相当）。
        teamId = TEAM_BASE + 3;
        seedTemplate(teamId, ReservationDayOfWeek.TUE, LocalTime.of(10, 0), LocalTime.of(11, 0));
        LocalDate futureTuesday = LocalDate.now().plusDays(1)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.TUESDAY));

        // When
        GenerateSlotsResponse result =
                generationService.generateSingleDay(teamId, futureTuesday, null, 777L);

        // Then: 営業時間なしでも 2 セル生成
        assertThat(result.getGeneratedCount()).isEqualTo(2);
        assertThat(slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                teamId, futureTuesday, futureTuesday)).hasSize(2);
    }

    private int countWeekdayOccurrences(LocalDate from, LocalDate to, DayOfWeek weekday) {
        int count = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() == weekday) {
                count++;
            }
        }
        return count;
    }
}
