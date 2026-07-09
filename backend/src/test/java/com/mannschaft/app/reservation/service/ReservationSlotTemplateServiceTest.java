package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.ratelimit.RateLimitResult;
import com.mannschaft.app.common.ratelimit.ValkeyRateLimiter;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateSlotTemplateRequest;
import com.mannschaft.app.reservation.dto.DeleteSlotTemplateResponse;
import com.mannschaft.app.reservation.dto.GenerateSingleDayRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsRequest;
import com.mannschaft.app.reservation.dto.GenerateSlotsResponse;
import com.mannschaft.app.reservation.dto.SlotTemplateResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotTemplateRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationSlotTemplateService} の単体テスト（F03.4.2 試練）。
 *
 * <p>受け入れ条件との対応: F-1（作成・cellCount）/ F-2（時刻検証 007/022 再利用）/
 * F-3（上限500・RESERVATION_037）/ F-4（重複帯・共通枠許可）/ F-8（遡及なし・orphanedSlotCount）/
 * F-13（IDOR 404=RESERVATION_036）/ §6（generate レートリミット 044）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationSlotTemplateService 単体テスト (F03.4.2)")
class ReservationSlotTemplateServiceTest {

    @Mock
    private ReservationSlotTemplateRepository templateRepository;

    @Mock
    private ReservationLineRepository lineRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    @Mock
    private ReservationSlotGenerationService generationService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ValkeyRateLimiter rateLimiter;

    @InjectMocks
    private ReservationSlotTemplateService service;

    private static final Long TEAM_ID = 1L;
    private static final Long LINE_ID = 30L;
    private static final Long USER_ID = 100L;
    private static final UUID TEMPLATE_ID = UUID.randomUUID();

    private ReservationLineEntity activeLine() {
        return ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("席1")
                .displayOrder(1)
                .build();
    }

    private CreateSlotTemplateRequest createRequest(Long lineId, ReservationDayOfWeek dow,
                                                    LocalTime start, LocalTime end) {
        return new CreateSlotTemplateRequest(
                "平日午前・席1", lineId, dow, start, end, 1, null, null, null, null);
    }

    private ReservationSlotTemplateEntity templateEntity(Long lineId, ReservationDayOfWeek dow,
                                                         LocalTime start, LocalTime end) {
        ReservationSlotTemplateEntity entity = ReservationSlotTemplateEntity.builder()
                .teamId(TEAM_ID)
                .lineId(lineId)
                .dayOfWeek(dow)
                .startTime(start)
                .endTime(end)
                .build();
        entity.setId(TEMPLATE_ID);
        return entity;
    }

    /** 作成系テスト共通の正常スタブ（上限未満・ライン有効・保存はそのまま返す）。 */
    private void stubHappyCreate() {
        given(templateRepository.countByTeamId(TEAM_ID)).willReturn(0L);
        given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(activeLine()));
        given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of());
        given(templateRepository.save(any(ReservationSlotTemplateEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(nameResolverService.resolveUserFullName(any(Long.class))).willReturn("田中スタッフ");
    }

    // ========================================
    // F-1: テンプレ作成（正常・cellCount 導出）
    // ========================================

    @Nested
    @DisplayName("createTemplate（F-1/F-2/F-3/F-4）")
    class CreateTemplate {

        @Test
        @DisplayName("F-1: lineId=30, MON, 10:00-13:00 で作成すると保存され cellCount=6 が導出される")
        void テンプレ作成_正常_cellCount6() {
            // Given
            stubHappyCreate();
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));

            // When
            SlotTemplateResponse result = service.createTemplate(TEAM_ID, request, USER_ID);

            // Then: 保存 entity の内容と cellCount=6（(13:00-10:00)/30分）
            ArgumentCaptor<ReservationSlotTemplateEntity> captor =
                    ArgumentCaptor.forClass(ReservationSlotTemplateEntity.class);
            verify(templateRepository).save(captor.capture());
            ReservationSlotTemplateEntity saved = captor.getValue();
            assertThat(saved.getTeamId()).isEqualTo(TEAM_ID);
            assertThat(saved.getLineId()).isEqualTo(LINE_ID);
            assertThat(saved.getDayOfWeek()).isEqualTo(ReservationDayOfWeek.MON);
            assertThat(saved.getCapacity()).isEqualTo(1);
            assertThat(saved.getIsActive()).isTrue();
            assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
            assertThat(result.getCellCount()).isEqualTo(6);
            assertThat(result.getDayOfWeek()).isEqualTo("MON");
        }

        @Test
        @DisplayName("F-1: capacity 未指定（null）は既定 1 へ正規化される")
        void テンプレ作成_capacity既定1() {
            // Given
            stubHappyCreate();
            CreateSlotTemplateRequest request = new CreateSlotTemplateRequest(
                    null, LINE_ID, ReservationDayOfWeek.MON,
                    LocalTime.of(10, 0), LocalTime.of(11, 0), null, null, null, null, null);

            // When
            service.createTemplate(TEAM_ID, request, USER_ID);

            // Then
            ArgumentCaptor<ReservationSlotTemplateEntity> captor =
                    ArgumentCaptor.forClass(ReservationSlotTemplateEntity.class);
            verify(templateRepository).save(captor.capture());
            assertThat(captor.getValue().getCapacity()).isEqualTo(1);
        }

        @Test
        @DisplayName("F-2: 非30分グリッド（10:15-11:00）は INVALID_SLOT_GRANULARITY=022（既存コード再利用）")
        void テンプレ作成_非30分グリッドは022() {
            // Given
            stubHappyCreate();
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 15), LocalTime.of(11, 0));

            // When / Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_SLOT_GRANULARITY);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("F-2: start == end（10:00-10:00）は INVALID_TIME_RANGE=007（既存コード再利用）")
        void テンプレ作成_時刻同一は007() {
            // Given
            stubHappyCreate();
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(10, 0));

            // When / Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("F-3: 既存499行なら500行目は作成できる（境界）")
        void テンプレ作成_500行目は可() {
            // Given
            stubHappyCreate();
            given(templateRepository.countByTeamId(TEAM_ID)).willReturn(499L);
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0));

            // When
            SlotTemplateResponse result = service.createTemplate(TEAM_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(templateRepository).save(any(ReservationSlotTemplateEntity.class));
        }

        @Test
        @DisplayName("F-3: 既存500行のとき501行目は TEMPLATE_LIMIT_EXCEEDED=RESERVATION_037（400）")
        void テンプレ作成_501行目は拒否() {
            // Given
            given(templateRepository.countByTeamId(TEAM_ID)).willReturn(500L);
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0));

            // When / Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_LIMIT_EXCEEDED);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("不正 lineId（他チーム/不存在）は LINE_NOT_FOUND=001（400・再利用）")
        void テンプレ作成_不正ラインは001() {
            // Given
            stubHappyCreate();
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.empty());
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(11, 0));

            // When / Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("F-4: 同一 (lineId, MON) に 10:00-13:00 がある状態で 12:00-14:00 は 400（時間帯重複・007再利用）")
        void テンプレ作成_同一ライン重複帯は400() {
            // Given
            stubHappyCreate();
            given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of(
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0))));
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(12, 0), LocalTime.of(14, 0));

            // When / Then
            assertThatThrownBy(() -> service.createTemplate(TEAM_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_TIME_RANGE);
            verify(templateRepository, never()).save(any());
        }

        @Test
        @DisplayName("F-4: 共通枠テンプレ（lineId NULL）同士の同時間帯は許可（201 相当）")
        void テンプレ作成_共通枠同士の重複は許可() {
            // Given
            stubHappyCreate();
            given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of(
                    templateEntity(null, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0))));
            CreateSlotTemplateRequest request = createRequest(
                    null, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));

            // When
            SlotTemplateResponse result = service.createTemplate(TEAM_ID, request, USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(templateRepository).save(any(ReservationSlotTemplateEntity.class));
        }

        @Test
        @DisplayName("F-4: 別曜日（TUE）の同時間帯・同ラインは重複でない（201 相当）")
        void テンプレ作成_別曜日は重複でない() {
            // Given
            stubHappyCreate();
            given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of(
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0))));
            CreateSlotTemplateRequest request = createRequest(
                    LINE_ID, ReservationDayOfWeek.TUE, LocalTime.of(10, 0), LocalTime.of(13, 0));

            // When / Then
            assertThat(service.createTemplate(TEAM_ID, request, USER_ID)).isNotNull();
        }
    }

    // ========================================
    // F-8: 更新は既生成枠へ遡及しない / IDOR
    // ========================================

    @Nested
    @DisplayName("updateTemplate（F-8/F-13）")
    class UpdateTemplate {

        @Test
        @DisplayName("F-8: startTime 変更の PATCH はテンプレ行のみ更新し、既生成枠（slotRepository）には一切触れない")
        void テンプレ更新_既生成枠へ遡及しない() {
            // Given
            ReservationSlotTemplateEntity entity =
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of(entity));
            given(templateRepository.save(any(ReservationSlotTemplateEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            UpdateSlotTemplateRequest request = new UpdateSlotTemplateRequest(
                    null, null, null, null, LocalTime.of(11, 0), LocalTime.of(13, 0),
                    null, null, null, null, null, null);

            // When
            SlotTemplateResponse result = service.updateTemplate(TEAM_ID, TEMPLATE_ID, request, USER_ID);

            // Then: テンプレは新時刻・枠テーブルへの書き込みゼロ（遡及なし）
            assertThat(entity.getStartTime()).isEqualTo(LocalTime.of(11, 0));
            assertThat(result.getCellCount()).isEqualTo(4);
            org.mockito.Mockito.verifyNoInteractions(slotRepository);
        }

        @Test
        @DisplayName("isActive=false で生成対象から外れる（既生成枠は不変）")
        void テンプレ更新_無効化() {
            // Given
            ReservationSlotTemplateEntity entity =
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.save(any(ReservationSlotTemplateEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            UpdateSlotTemplateRequest request = new UpdateSlotTemplateRequest(
                    null, null, null, null, null, null, null, null, null, null, null, false);

            // When
            service.updateTemplate(TEAM_ID, TEMPLATE_ID, request, USER_ID);

            // Then
            assertThat(entity.getIsActive()).isFalse();
        }

        @Test
        @DisplayName("clearLineId=true で共通枠テンプレへ戻る（親 clearApprovalMode と同形）")
        void テンプレ更新_clearLineId() {
            // Given
            ReservationSlotTemplateEntity entity =
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.findByTeamId(TEAM_ID)).willReturn(List.of(entity));
            given(templateRepository.save(any(ReservationSlotTemplateEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            UpdateSlotTemplateRequest request = new UpdateSlotTemplateRequest(
                    null, null, true, null, null, null, null, null, null, null, null, null);

            // When
            service.updateTemplate(TEAM_ID, TEMPLATE_ID, request, USER_ID);

            // Then
            assertThat(entity.getLineId()).isNull();
        }

        @Test
        @DisplayName("F-13: 他チーム/不存在の templateId は TEMPLATE_NOT_FOUND=RESERVATION_036（IDOR 秘匿の同一 404）")
        void テンプレ更新_他チームは036() {
            // Given
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.empty());
            UpdateSlotTemplateRequest request = new UpdateSlotTemplateRequest(
                    "名前変更", null, null, null, null, null, null, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.updateTemplate(TEAM_ID, TEMPLATE_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    // ========================================
    // F-8: 削除（物理削除・orphanedSlotCount・SET NULL 残置）
    // ========================================

    @Nested
    @DisplayName("deleteTemplate（F-8/F-13）")
    class DeleteTemplate {

        @Test
        @DisplayName("F-8: 物理削除で orphanedSlotCount（SET NULL される枠数）が返り、枠自体は削除されない")
        void テンプレ削除_orphanedSlotCount() {
            // Given
            ReservationSlotTemplateEntity entity =
                    templateEntity(LINE_ID, ReservationDayOfWeek.MON, LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(slotRepository.countByTemplateId(TEMPLATE_ID)).willReturn(42L);

            // When
            DeleteSlotTemplateResponse result = service.deleteTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then: 物理削除＋orphanedSlotCount=42。枠の削除・更新は行わない（DB FK SET NULL に委ねる）
            assertThat(result.isDeleted()).isTrue();
            assertThat(result.getOrphanedSlotCount()).isEqualTo(42L);
            verify(templateRepository).delete(entity);
            verify(slotRepository, never()).delete(any());
            verify(slotRepository, never()).save(any());
        }

        @Test
        @DisplayName("F-13: 他チーム/不存在の templateId は TEMPLATE_NOT_FOUND=RESERVATION_036")
        void テンプレ削除_他チームは036() {
            // Given
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteTemplate(TEAM_ID, TEMPLATE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_NOT_FOUND);
            verify(templateRepository, never()).delete(any(ReservationSlotTemplateEntity.class));
        }
    }

    // ========================================
    // §6: generate のレートリミット＋委譲
    // ========================================

    @Nested
    @DisplayName("generate（§6 レートリミット・委譲）")
    class Generate {

        @Test
        @DisplayName("正常系: レートリミット内なら生成本体（単一実装）へ委譲し結果を返す")
        void 生成_委譲() {
            // Given
            given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                    .willReturn(new RateLimitResult(true, 2, 1, 0, 0));
            GenerateSlotsResponse expected = GenerateSlotsResponse.builder().generatedCount(6).build();
            given(generationService.generateForTeam(TEAM_ID, 1, USER_ID)).willReturn(expected);

            // When
            GenerateSlotsResponse result = service.generate(TEAM_ID, new GenerateSlotsRequest(1), USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isEqualTo(6);
            verify(generationService).generateForTeam(TEAM_ID, 1, USER_ID);
        }

        @Test
        @DisplayName("§6: 1チーム1分2回のレートリミット超過は TEMPLATE_GENERATE_RATE_LIMITED=RESERVATION_044（429）")
        void 生成_レートリミット超過は044() {
            // Given
            given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                    .willReturn(new RateLimitResult(false, 2, 0, 0, 60));

            // When / Then
            assertThatThrownBy(() -> service.generate(TEAM_ID, new GenerateSlotsRequest(1), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_GENERATE_RATE_LIMITED);
            verify(generationService, never()).generateForTeam(any(), any(), any());
        }
    }

    // ========================================
    // F03.4.5 §3.1: テンプレ保存＝同期自動生成のファサード（tx境界の外側）
    // ========================================

    @Nested
    @DisplayName("generateForTemplate ファサード（F03.4.5 §3.1・S-3b tx境界）")
    class GenerateForTemplateFacade {

        @Test
        @DisplayName("S-1: active テンプレは当該テンプレのみの生成へ委譲する（レートリミット非対象）")
        void 保存後生成_activeは委譲() {
            // Given
            ReservationSlotTemplateEntity tpl = templateEntity(LINE_ID, ReservationDayOfWeek.MON,
                    LocalTime.of(10, 0), LocalTime.of(13, 0));
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(tpl));
            given(generationService.generateForTemplate(TEAM_ID, tpl, USER_ID))
                    .willReturn(GenerateSlotsResponse.builder().generatedCount(24).build());

            // When
            GenerateSlotsResponse result = service.generateForTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then: 単一テンプレ生成へ委譲。レートリミットは消費しない（保存が自然な頻度上限）。
            assertThat(result.getGeneratedCount()).isEqualTo(24);
            verify(generationService).generateForTemplate(TEAM_ID, tpl, USER_ID);
            verify(rateLimiter, never()).tryConsume(anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("S-3b: 無効化テンプレ（isActive=false）は生成しない（空委譲・以後生成されないだけ）")
        void 保存後生成_無効テンプレは生成しない() {
            // Given: isActive=false のテンプレ
            ReservationSlotTemplateEntity inactive = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.MON)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(13, 0)).isActive(false).build();
            inactive.setId(TEMPLATE_ID);
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.of(inactive));
            given(generationService.generateForTemplates(eq(TEAM_ID), eq(List.of()), eq(USER_ID)))
                    .willReturn(GenerateSlotsResponse.builder().build());

            // When
            service.generateForTemplate(TEAM_ID, TEMPLATE_ID, USER_ID);

            // Then: 単一テンプレ生成は呼ばず空委譲
            verify(generationService, never()).generateForTemplate(any(), any(), any());
            verify(generationService).generateForTemplates(eq(TEAM_ID), eq(List.of()), eq(USER_ID));
        }

        @Test
        @DisplayName("IDOR: 他チーム/不存在の templateId は 404=RESERVATION_036")
        void 保存後生成_不存在は404() {
            given(templateRepository.findByIdAndTeamId(TEMPLATE_ID, TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.generateForTemplate(TEAM_ID, TEMPLATE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("generateForDaysOfWeek ファサード（F03.4.5 §3.2・営業時間変更差分）")
    class GenerateForDaysOfWeekFacade {

        @Test
        @DisplayName("S-6②: 変更曜日の active テンプレのみを対象に生成へ委譲する")
        void 変更曜日生成_該当曜日のみ委譲() {
            // Given: MON と TUE の active テンプレ。変更曜日は MON のみ。
            ReservationSlotTemplateEntity mon = templateEntity(LINE_ID, ReservationDayOfWeek.MON,
                    LocalTime.of(10, 0), LocalTime.of(11, 0));
            ReservationSlotTemplateEntity tue = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID).lineId(LINE_ID).dayOfWeek(ReservationDayOfWeek.TUE)
                    .startTime(LocalTime.of(10, 0)).endTime(LocalTime.of(11, 0)).build();
            tue.setId(UUID.randomUUID());
            given(templateRepository.findByTeamIdAndIsActiveTrue(TEAM_ID)).willReturn(List.of(mon, tue));
            given(generationService.generateForTemplates(eq(TEAM_ID), any(), eq(USER_ID)))
                    .willReturn(GenerateSlotsResponse.builder().build());

            // When
            service.generateForDaysOfWeek(TEAM_ID, Set.of(ReservationDayOfWeek.MON), USER_ID);

            // Then: MON テンプレのみが生成対象（TUE は含めない）
            ArgumentCaptor<List<ReservationSlotTemplateEntity>> captor = ArgumentCaptor.forClass(List.class);
            verify(generationService).generateForTemplates(eq(TEAM_ID), captor.capture(), eq(USER_ID));
            assertThat(captor.getValue()).containsExactly(mon);
        }

        @Test
        @DisplayName("S-6②: 変更曜日が空なら生成しない（空委譲・テンプレ走査もしない）")
        void 変更曜日生成_空は空委譲() {
            given(generationService.generateForTemplates(eq(TEAM_ID), eq(List.of()), eq(USER_ID)))
                    .willReturn(GenerateSlotsResponse.builder().build());

            service.generateForDaysOfWeek(TEAM_ID, Set.of(), USER_ID);

            verify(generationService).generateForTemplates(eq(TEAM_ID), eq(List.of()), eq(USER_ID));
            verify(templateRepository, never()).findByTeamIdAndIsActiveTrue(any());
        }
    }

    @Nested
    @DisplayName("generateSingleDay ファサード（F03.4.5 §3.3.2・レートリミット共有・監査）")
    class GenerateSingleDayFacade {

        @Test
        @DisplayName("S-8: レートリミット内なら単日生成へ委譲し監査ログを記録する")
        void 臨時営業_委譲と監査() {
            // Given
            given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                    .willReturn(new RateLimitResult(true, 2, 1, 0, 0));
            LocalDate date = LocalDate.now().plusDays(7);
            GenerateSlotsResponse expected = GenerateSlotsResponse.builder().generatedCount(6).build();
            given(generationService.generateSingleDay(eq(TEAM_ID), eq(date), any(), eq(USER_ID)))
                    .willReturn(expected);

            // When
            GenerateSlotsResponse result = service.generateSingleDay(
                    TEAM_ID, new GenerateSingleDayRequest(date, ReservationDayOfWeek.MON), USER_ID);

            // Then
            assertThat(result.getGeneratedCount()).isEqualTo(6);
            verify(generationService).generateSingleDay(eq(TEAM_ID), eq(date), eq(ReservationDayOfWeek.MON),
                    eq(USER_ID));
            verify(auditLogService).record(eq("RESERVATION_TEMPLATE_SINGLE_DAY_GENERATED"),
                    eq(USER_ID), any(), eq(TEAM_ID), any(), any(), any(), any(), anyString());
        }

        @Test
        @DisplayName("S-8: 既存 generate と同一 zone のレートリミット超過は RESERVATION_044（429・共有）")
        void 臨時営業_レートリミット超過は044共有() {
            // Given
            given(rateLimiter.tryConsume(anyString(), anyString(), anyInt(), any()))
                    .willReturn(new RateLimitResult(false, 2, 0, 0, 60));
            LocalDate date = LocalDate.now().plusDays(7);

            // When / Then: 既存 generate と同一 zone・同一エラーコードを共有する
            assertThatThrownBy(() -> service.generateSingleDay(
                    TEAM_ID, new GenerateSingleDayRequest(date, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.TEMPLATE_GENERATE_RATE_LIMITED);
            verify(generationService, never()).generateSingleDay(any(), any(), any(), any());
        }
    }
}
