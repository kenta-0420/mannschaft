package com.mannschaft.app.shift;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import com.mannschaft.app.shift.service.ShiftHourlyRateService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ShiftHourlyRateService} の単体テスト。
 * 時給の設定・取得・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ShiftHourlyRateService 単体テスト")
class ShiftHourlyRateServiceTest {

    @Mock
    private ShiftHourlyRateRepository hourlyRateRepository;

    @Mock
    private ShiftMapper shiftMapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private ShiftHourlyRateService shiftHourlyRateService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 10L;
    private static final Long TEAM_ID = 1L;
    private static final Long RATE_ID = 500L;
    /** 対象と同一ユーザー（本人アクセス）を表す操作ユーザーID。 */
    private static final Long CURRENT_USER_ID = USER_ID;

    private ShiftHourlyRateEntity createRateEntity() {
        return ShiftHourlyRateEntity.builder()
                .userId(USER_ID)
                .teamId(TEAM_ID)
                .hourlyRate(new BigDecimal("1200.00"))
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .build();
    }

    private HourlyRateResponse createRateResponse() {
        return new HourlyRateResponse(
                RATE_ID, USER_ID, TEAM_ID,
                new BigDecimal("1200.00"), LocalDate.of(2026, 4, 1),
                LocalDateTime.now());
    }

    // ========================================
    // listHourlyRates
    // ========================================

    @Nested
    @DisplayName("listHourlyRates")
    class ListHourlyRates {

        @Test
        @DisplayName("時給履歴一覧取得_正常_リスト返却")
        void 時給履歴一覧取得_正常_リスト返却() {
            // Given
            ShiftHourlyRateEntity entity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            given(hourlyRateRepository.findByUserIdAndTeamIdOrderByEffectiveFromDesc(USER_ID, TEAM_ID))
                    .willReturn(List.of(entity));
            given(shiftMapper.toHourlyRateResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<HourlyRateResponse> result = shiftHourlyRateService.listHourlyRates(USER_ID, TEAM_ID, CURRENT_USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getHourlyRate()).isEqualByComparingTo("1200.00");
        }
    }

    // ========================================
    // getEffectiveRate
    // ========================================

    @Nested
    @DisplayName("getEffectiveRate")
    class GetEffectiveRate {

        @Test
        @DisplayName("有効時給取得_正常_レスポンス返却")
        void 有効時給取得_正常_レスポンス返却() {
            // Given
            ShiftHourlyRateEntity entity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            LocalDate date = LocalDate.of(2026, 4, 15);
            given(hourlyRateRepository.findEffectiveRate(USER_ID, TEAM_ID, date))
                    .willReturn(Optional.of(entity));
            given(shiftMapper.toHourlyRateResponse(entity)).willReturn(response);

            // When
            HourlyRateResponse result = shiftHourlyRateService.getEffectiveRate(USER_ID, TEAM_ID, date, CURRENT_USER_ID);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getHourlyRate()).isEqualByComparingTo("1200.00");
        }

        @Test
        @DisplayName("有効時給取得_存在しない_null返却")
        void 有効時給取得_存在しない_null返却() {
            // Given
            LocalDate date = LocalDate.of(2026, 1, 1);
            given(hourlyRateRepository.findEffectiveRate(USER_ID, TEAM_ID, date))
                    .willReturn(Optional.empty());

            // When
            HourlyRateResponse result = shiftHourlyRateService.getEffectiveRate(USER_ID, TEAM_ID, date, CURRENT_USER_ID);

            // Then
            assertThat(result).isNull();
        }
    }

    // ========================================
    // createHourlyRate
    // ========================================

    @Nested
    @DisplayName("createHourlyRate")
    class CreateHourlyRate {

        @Test
        @DisplayName("時給設定_正常_レスポンス返却")
        void 時給設定_正常_レスポンス返却() {
            // Given
            // CMP-260910-1555: 書き込みは JPA の save ではなく、一意制約に衝突解決させる
            // 原子的な upsert 1 文に変わった（並行時に片方が制約違反で落ちるのを防ぐため）。
            LocalDate effectiveFrom = LocalDate.of(2026, 5, 1);
            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), effectiveFrom);
            ShiftHourlyRateEntity savedEntity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            given(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(
                    USER_ID, TEAM_ID, effectiveFrom)).willReturn(Optional.of(savedEntity));
            given(shiftMapper.toHourlyRateResponse(savedEntity)).willReturn(response);

            // When
            HourlyRateResponse result = shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(hourlyRateRepository).upsertHourlyRate(
                    USER_ID, TEAM_ID, new BigDecimal("1500.00"), effectiveFrom);
        }
    }

    // ========================================
    // deleteHourlyRate
    // ========================================

    @Nested
    @DisplayName("deleteHourlyRate")
    class DeleteHourlyRate {

        @Test
        @DisplayName("時給設定削除_正常_deleteByIdが呼ばれる")
        void 時給設定削除_正常_deleteByIdが呼ばれる() {
            // When
            shiftHourlyRateService.deleteHourlyRate(RATE_ID);

            // Then
            verify(hourlyRateRepository).deleteById(RATE_ID);
        }
    }

    // ========================================
    // 認可（CMP-260910-1555: ロール横断の裏取り）
    // ========================================

    /**
     * 時給は金銭情報であり、画面側で導線を隠すだけでは URL 直打ち・API 直叩きを防げない。
     * BE が本当に弾いているか（＝素通りしていないか）をここで固定する。
     */
    @Nested
    @DisplayName("認可")
    class Authorization {

        private static final Long OTHER_USER_ID = 99L;
        private static final Long MEMBER_USER_ID = 20L;

        @Test
        @DisplayName("一般メンバーが他メンバーの時給を参照 → checkAdminOrAbove で弾かれる")
        void 他人の時給参照_一般メンバーは403() {
            given(accessControlService.isSystemAdmin(MEMBER_USER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(
                            new com.mannschaft.app.common.BusinessException(
                                    com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(MEMBER_USER_ID, TEAM_ID, "TEAM");

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            shiftHourlyRateService.listHourlyRates(OTHER_USER_ID, TEAM_ID, MEMBER_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

            // 弾かれた以上、リポジトリまで到達してはならない（存在オラクルも残さない）
            verify(hourlyRateRepository, org.mockito.Mockito.never())
                    .findByUserIdAndTeamIdOrderByEffectiveFromDesc(any(), any());
        }

        @Test
        @DisplayName("他チームのユーザーを対象にした時給登録 → 対象側の checkMembership で弾かれる")
        void 他テナントのユーザーへの登録は403() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(
                            new com.mannschaft.app.common.BusinessException(
                                    com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(OTHER_USER_ID, TEAM_ID, "TEAM");

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    OTHER_USER_ID, new BigDecimal("1200"), LocalDate.of(2026, 4, 1));

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);

            verify(hourlyRateRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("本人の時給参照 → チームメンバーであることだけを要求する")
        void 本人参照はメンバーシップのみ要求() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            given(hourlyRateRepository.findByUserIdAndTeamIdOrderByEffectiveFromDesc(USER_ID, TEAM_ID))
                    .willReturn(List.of());
            given(shiftMapper.toHourlyRateResponseList(List.of())).willReturn(List.of());

            shiftHourlyRateService.listHourlyRates(USER_ID, TEAM_ID, CURRENT_USER_ID);

            verify(accessControlService).checkMembership(CURRENT_USER_ID, TEAM_ID, "TEAM");
            verify(accessControlService, org.mockito.Mockito.never())
                    .checkAdminOrAbove(any(), any(), any());
        }
    }

    /**
     * 指摘3（検分2巡目）: 同一適用開始日の訂正を「探して無ければ INSERT」で実装すると、
     * 読みと書きの間に窓が開き、同じキーへの初回リクエストが並行したときに
     * 両方が「既存なし」を見て両方 INSERT へ進み、片方が uq_shr_user_team_from 違反で落ちる。
     * 一意制約に衝突解決させる単一文（ON DUPLICATE KEY UPDATE）へ移したことを固定する。
     */
    @Nested
    @DisplayName("同一適用開始日の訂正（原子的 upsert）")
    class SameEffectiveFromCorrection {

        private void givenUpsertReadBack(LocalDate day, ShiftHourlyRateEntity readBack) {
            given(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(USER_ID, TEAM_ID, day))
                    .willReturn(Optional.of(readBack));
            given(shiftMapper.toHourlyRateResponse(readBack)).willReturn(createRateResponse());
        }

        @Test
        @DisplayName("書き込みは原子的な upsert 1 文で行い、事前 SELECT による分岐を行わない")
        void 事前SELECTで分岐しない() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate day = LocalDate.of(2026, 4, 1);
            givenUpsertReadBack(day, createRateEntity());

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), day);
            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            // 一意制約に衝突解決させる単一文で書く
            verify(hourlyRateRepository).upsertHourlyRate(
                    USER_ID, TEAM_ID, new BigDecimal("1500.00"), day);
            // JPA の save 経由（＝読んでから INSERT/UPDATE を選ぶ経路）は使わない。
            // save が残っていると、並行時に両方が INSERT へ進む窓が残る。
            verify(hourlyRateRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("同じキーへ連続で登録しても例外にならず、常に最後の金額へ収束する（冪等）")
        void 同一キーの再送は冪等() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate day = LocalDate.of(2026, 4, 1);
            givenUpsertReadBack(day, createRateEntity());

            CreateHourlyRateRequest first = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1200.00"), day);
            CreateHourlyRateRequest second = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), day);

            shiftHourlyRateService.createHourlyRate(TEAM_ID, first, CURRENT_USER_ID);
            shiftHourlyRateService.createHourlyRate(TEAM_ID, second, CURRENT_USER_ID);

            verify(hourlyRateRepository).upsertHourlyRate(USER_ID, TEAM_ID, new BigDecimal("1200.00"), day);
            verify(hourlyRateRepository).upsertHourlyRate(USER_ID, TEAM_ID, new BigDecimal("1500.00"), day);
            verify(hourlyRateRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("別の適用開始日は別行として書かれる（過去の履歴は消えない）")
        void 別日は別行として書かれる() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate newDay = LocalDate.of(2026, 5, 1);
            givenUpsertReadBack(newDay, createRateEntity());

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), newDay);
            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            // 適用開始日が違えば一意キーが違うので、既存行には触れず新しい行が入る
            verify(hourlyRateRepository).upsertHourlyRate(
                    USER_ID, TEAM_ID, new BigDecimal("1500.00"), newDay);
        }

        @Test
        @DisplayName("upsert 直後に読み戻せない異常は握りつぶさず例外にする")
        void 読み戻せない場合は例外() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate day = LocalDate.of(2026, 4, 1);
            given(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(USER_ID, TEAM_ID, day))
                    .willReturn(Optional.empty());

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), day);

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
