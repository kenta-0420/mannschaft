package com.mannschaft.app.reservation;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotTemplateRepository;
import com.mannschaft.app.reservation.service.ReservationLineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReservationLineService} の単体テスト。
 * 予約ラインのCRUD操作を検証する。
 *
 * <p>F03.4.2 改訂: ライン上限 5→20（F-10・§3.4）とライン削除フロー再設計（F-15・§5.5）を反映。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReservationLineService 単体テスト")
class ReservationLineServiceTest {

    @Mock
    private ReservationLineRepository lineRepository;

    @Mock
    private ReservationMapper reservationMapper;

    @Mock
    private ReservationSlotTemplateRepository templateRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationSlotRepository slotRepository;

    private ReservationLineService service;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long TEAM_ID = 1L;
    private static final Long LINE_ID = 10L;
    private static final Long STAFF_USER_ID = 50L;

    /** ライン削除フローの「今日以降」判定用の固定 Clock（2026-07-05）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(LocalDate.of(2026, 7, 5).atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneId.of("UTC"));

    @BeforeEach
    void setUp() {
        // @InjectMocks は final Clock を mock で埋めるため、固定 Clock を明示注入して生成する。
        service = new ReservationLineService(
                lineRepository, reservationMapper, templateRepository, reservationRepository,
                slotRepository, FIXED_CLOCK);
    }

    private ReservationLineEntity createLineEntity() {
        return ReservationLineEntity.builder()
                .teamId(TEAM_ID)
                .name("カウンセリング")
                .description("個別カウンセリング60分")
                .displayOrder(1)
                .defaultStaffUserId(STAFF_USER_ID)
                .build();
    }

    private ReservationLineResponse createLineResponse() {
        return ReservationLineResponse.builder()
                .id(LINE_ID)
                .teamId(TEAM_ID)
                .meta(new ReservationLineResponse.LineMetaDto("カウンセリング", "個別カウンセリング60分", 1, true, STAFF_USER_ID))
                .audit(new ReservationLineResponse.ReservationLineAuditDto(null, null))
                .build();
    }

    // ========================================
    // listLines
    // ========================================

    @Nested
    @DisplayName("listLines")
    class ListLines {

        @Test
        @DisplayName("正常系: チームの予約ライン一覧が返却される")
        void ライン一覧_正常() {
            // Given
            List<ReservationLineEntity> entities = List.of(createLineEntity());
            List<ReservationLineResponse> responses = List.of(createLineResponse());
            given(lineRepository.findByTeamIdOrderByDisplayOrderAsc(TEAM_ID)).willReturn(entities);
            given(reservationMapper.toLineResponseList(entities)).willReturn(responses);

            // When
            List<ReservationLineResponse> result = service.listLines(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getMeta().name()).isEqualTo("カウンセリング");
        }
    }

    // ========================================
    // listActiveLines
    // ========================================

    @Nested
    @DisplayName("listActiveLines")
    class ListActiveLines {

        @Test
        @DisplayName("正常系: 有効なライン一覧が返却される")
        void 有効ライン一覧_正常() {
            // Given
            List<ReservationLineEntity> entities = List.of(createLineEntity());
            List<ReservationLineResponse> responses = List.of(createLineResponse());
            given(lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(TEAM_ID)).willReturn(entities);
            given(reservationMapper.toLineResponseList(entities)).willReturn(responses);

            // When
            List<ReservationLineResponse> result = service.listActiveLines(TEAM_ID);

            // Then
            assertThat(result).hasSize(1);
        }
    }

    // ========================================
    // createLine
    // ========================================

    @Nested
    @DisplayName("createLine")
    class CreateLine {

        @Test
        @DisplayName("正常系: 予約ラインが作成される")
        void ライン作成_正常() {
            // Given
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "新メニュー", "説明文", 2, STAFF_USER_ID);
            ReservationLineEntity savedEntity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toLineResponse(savedEntity)).willReturn(response);

            // When
            ReservationLineResponse result = service.createLine(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(lineRepository).save(any(ReservationLineEntity.class));
        }

        @Test
        @DisplayName("正常系: displayOrderがnullの場合デフォルト値1が使用される")
        void ライン作成_表示順デフォルト() {
            // Given
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "新メニュー", null, null, null);
            ReservationLineEntity savedEntity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(savedEntity);
            given(reservationMapper.toLineResponse(savedEntity)).willReturn(response);

            // When
            ReservationLineResponse result = service.createLine(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(lineRepository).save(any(ReservationLineEntity.class));
        }

        // F-10（F03.4.2 §3.4）: ライン上限 5→20
        @Test
        @DisplayName("F-10: 既存19本（20本目）なら作成できる")
        void ライン作成_20本目まで可() {
            // Given: 既存 19 本 → 20 本目は許可
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "20本目メニュー", null, null, null);
            given(lineRepository.countByTeamId(TEAM_ID)).willReturn(19L);
            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(createLineEntity());
            given(reservationMapper.toLineResponse(any(ReservationLineEntity.class)))
                    .willReturn(createLineResponse());

            // When
            ReservationLineResponse result = service.createLine(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(lineRepository).save(any(ReservationLineEntity.class));
        }

        @Test
        @DisplayName("F-10: 既存20本（21本目）はLINE_LIMIT_EXCEEDED=024（400・メッセージ「最大20本」）で拒否され保存されない")
        void ライン作成_21本目拒否() {
            // Given: 既存 20 本 → 21 本目は拒否
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "21本目メニュー", null, null, null);
            given(lineRepository.countByTeamId(TEAM_ID)).willReturn(20L);

            // When / Then: コードは 024 再利用・メッセージが 20 本へ改訂されていること
            assertThatThrownBy(() -> service.createLine(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("20")
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_LIMIT_EXCEEDED);
            verify(lineRepository, never()).save(any(ReservationLineEntity.class));
        }

        @Test
        @DisplayName("F-10: display_order=20 は有効（境界）")
        void ライン作成_表示順20は有効() {
            // Given
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "20列目メニュー", null, 20, null);
            given(lineRepository.countByTeamId(TEAM_ID)).willReturn(0L);
            given(lineRepository.save(any(ReservationLineEntity.class))).willReturn(createLineEntity());
            given(reservationMapper.toLineResponse(any(ReservationLineEntity.class)))
                    .willReturn(createLineResponse());

            // When / Then
            assertThat(service.createLine(TEAM_ID, request)).isNotNull();
        }

        @Test
        @DisplayName("F-10: display_orderが範囲外（21）はINVALID_DISPLAY_ORDER=025（400・範囲1〜20）")
        void ライン作成_表示順範囲外() {
            // Given: 上限未満だが display_order=21（範囲外）
            CreateReservationLineRequest request = new CreateReservationLineRequest(
                    "範囲外メニュー", null, 21, null);
            given(lineRepository.countByTeamId(TEAM_ID)).willReturn(0L);

            // When / Then
            assertThatThrownBy(() -> service.createLine(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.INVALID_DISPLAY_ORDER);
            verify(lineRepository, never()).save(any(ReservationLineEntity.class));
        }
    }

    // ========================================
    // updateLine
    // ========================================

    @Nested
    @DisplayName("updateLine")
    class UpdateLine {

        @Test
        @DisplayName("正常系: ライン名が更新される")
        void ライン更新_名前変更() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "更新後メニュー", null, null, null, null);
            ReservationLineEntity entity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            ReservationLineResponse result = service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("更新後メニュー");
        }

        @Test
        @DisplayName("正常系: 全フィールドを更新する")
        void ライン更新_全フィールド() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "全更新メニュー", "新しい説明", 5, false, 99L);
            ReservationLineEntity entity = createLineEntity();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            ReservationLineResponse result = service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(entity.getName()).isEqualTo("全更新メニュー");
            assertThat(entity.getDescription()).isEqualTo("新しい説明");
            assertThat(entity.getDisplayOrder()).isEqualTo(5);
            assertThat(entity.getIsActive()).isFalse();
            assertThat(entity.getDefaultStaffUserId()).isEqualTo(99L);
        }

        @Test
        @DisplayName("正常系: isActiveをtrueに設定するとactivate()が呼ばれる")
        void ライン更新_有効化() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    null, null, null, true, null);
            ReservationLineEntity entity = createLineEntity();
            entity.deactivate();
            ReservationLineResponse response = createLineResponse();

            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(lineRepository.save(entity)).willReturn(entity);
            given(reservationMapper.toLineResponse(entity)).willReturn(response);

            // When
            service.updateLine(TEAM_ID, LINE_ID, request);

            // Then
            assertThat(entity.getIsActive()).isTrue();
        }

        @Test
        @DisplayName("異常系: ラインが存在しない場合LINE_NOT_FOUNDエラー")
        void ライン更新_存在しない() {
            // Given
            UpdateReservationLineRequest request = new UpdateReservationLineRequest(
                    "更新", null, null, null, null);
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateLine(TEAM_ID, LINE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
        }
    }

    // ========================================
    // deleteLine
    // ========================================

    @Nested
    @DisplayName("deleteLine（F-15・F03.4.2 §5.5 再設計フロー）")
    class DeleteLine {

        private ReservationSlotTemplateEntity activeTemplateForLine() {
            ReservationSlotTemplateEntity tpl = ReservationSlotTemplateEntity.builder()
                    .teamId(TEAM_ID)
                    .lineId(LINE_ID)
                    .dayOfWeek(ReservationDayOfWeek.MON)
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(13, 0))
                    .build();
            tpl.setId(java.util.UUID.randomUUID());
            return tpl;
        }

        private ReservationSlotEntity futureLineSlot(Long id) {
            ReservationSlotEntity slot = ReservationSlotEntity.builder()
                    .teamId(TEAM_ID)
                    .lineId(LINE_ID)
                    .slotDate(LocalDate.of(2026, 7, 10))
                    .startTime(LocalTime.of(10, 0))
                    .endTime(LocalTime.of(10, 30))
                    .build();
            // BaseEntity の id は @GeneratedValue のため reflection でセットする（purge 対象の識別用）
            try {
                java.lang.reflect.Field idField =
                        com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(slot, id);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return slot;
        }

        @Test
        @DisplayName("F-15①: active予約なし・未来のライン軸枠あり・activeテンプレあり → 200（旧設計なら409）。"
                + "テンプレis_active=FALSE・予約なし未来枠purge・ライン論理削除の番号順")
        void ライン削除_非循環フロー成功() {
            // Given
            ReservationLineEntity entity = createLineEntity();
            ReservationSlotTemplateEntity tpl = activeTemplateForLine();
            ReservationSlotEntity purgeable = futureLineSlot(101L);
            ReservationSlotEntity reserved = futureLineSlot(102L);
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.findByLineIdAndIsActiveTrue(LINE_ID)).willReturn(List.of(tpl));
            given(reservationRepository.existsByLineIdAndStatusIn(eq(LINE_ID), anyList())).willReturn(false);
            given(slotRepository.findByLineIdAndSlotDateGreaterThanEqual(eq(LINE_ID), any(LocalDate.class)))
                    .willReturn(List.of(purgeable, reserved));
            // 102 だけ active 予約が紐づく（枠は履歴として残す）
            given(reservationRepository.findSlotIdsWithActiveReservations(anyList(), anyList()))
                    .willReturn(List.of(102L));

            // When
            service.deleteLine(TEAM_ID, LINE_ID);

            // Then: 1. テンプレ停止
            assertThat(tpl.getIsActive()).isFalse();
            verify(templateRepository).saveAll(List.of(tpl));
            // 3. 予約なし未来枠のみ論理削除（102 は残す）
            assertThat(purgeable.getDeletedAt()).isNotNull();
            assertThat(reserved.getDeletedAt()).isNull();
            // 5. ライン本体の論理削除
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(lineRepository).save(entity);
        }

        @Test
        @DisplayName("F-15②: active予約（PENDING/CONFIRMED）を持つラインの削除は LINE_HAS_ACTIVE_RESERVATIONS=RESERVATION_045（409）。"
                + "purge・ライン削除は実行されない（txロールバックで手順1も巻き戻る前提）")
        void ライン削除_active予約ありは409() {
            // Given
            ReservationLineEntity entity = createLineEntity();
            ReservationSlotTemplateEntity tpl = activeTemplateForLine();
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.findByLineIdAndIsActiveTrue(LINE_ID)).willReturn(List.of(tpl));
            given(reservationRepository.existsByLineIdAndStatusIn(eq(LINE_ID), anyList())).willReturn(true);

            // When / Then: 唯一の 409 事由（「予約のない未来枠あり」は 409 にしない — 循環の根の除去）
            assertThatThrownBy(() -> service.deleteLine(TEAM_ID, LINE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_HAS_ACTIVE_RESERVATIONS);
            // ガード以降の手順（3〜5）が実行されないこと
            verify(slotRepository, never()).findByLineIdAndSlotDateGreaterThanEqual(any(), any());
            assertThat(entity.getDeletedAt()).isNull();
            verify(lineRepository, never()).save(any(ReservationLineEntity.class));
        }

        @Test
        @DisplayName("F-15②続: 409 は @Transactional 単一 tx 内で投げられ、手順1（テンプレ停止）が部分適用されない")
        void ライン削除_単一tx宣言() throws Exception {
            // deleteLine が @Transactional であること（409 時に手順1がロールバックされる構造保証）
            java.lang.reflect.Method method =
                    ReservationLineService.class.getMethod("deleteLine", Long.class, Long.class);
            org.springframework.transaction.annotation.Transactional tx =
                    method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
            assertThat(tx).as("deleteLine は書き込み @Transactional（単一tx）であること").isNotNull();
            assertThat(tx.readOnly()).isFalse();
        }

        @Test
        @DisplayName("F-15③: 予約振替後の再実行（active予約なし）は 200 相当で完走する（再実行可能）")
        void ライン削除_振替後の再実行成功() {
            // Given: テンプレは前回の実行途中で既に停止済み（findByLineIdAndIsActiveTrue が空）でも落ちない
            ReservationLineEntity entity = createLineEntity();
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.of(entity));
            given(templateRepository.findByLineIdAndIsActiveTrue(LINE_ID)).willReturn(List.of());
            given(reservationRepository.existsByLineIdAndStatusIn(eq(LINE_ID), anyList())).willReturn(false);
            given(slotRepository.findByLineIdAndSlotDateGreaterThanEqual(eq(LINE_ID), any(LocalDate.class)))
                    .willReturn(List.of());

            // When
            service.deleteLine(TEAM_ID, LINE_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(lineRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: ラインが存在しない場合LINE_NOT_FOUNDエラー")
        void ライン削除_存在しない() {
            // Given
            given(lineRepository.findByIdAndTeamId(LINE_ID, TEAM_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteLine(TEAM_ID, LINE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ReservationErrorCode.LINE_NOT_FOUND);
        }
    }
}
