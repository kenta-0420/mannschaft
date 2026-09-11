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
            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), LocalDate.of(2026, 5, 1));
            ShiftHourlyRateEntity savedEntity = createRateEntity();
            HourlyRateResponse response = createRateResponse();
            given(hourlyRateRepository.save(any(ShiftHourlyRateEntity.class))).willReturn(savedEntity);
            given(shiftMapper.toHourlyRateResponse(savedEntity)).willReturn(response);

            // When
            HourlyRateResponse result = shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            // Then
            assertThat(result).isNotNull();
            verify(hourlyRateRepository).save(any(ShiftHourlyRateEntity.class));
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

    // ========================================
    // CMP-260910-1555 検分指摘の是正
    // ========================================

    /**
     * 指摘3: 同じ適用開始日での再登録が一意制約
     * {@code uq_shr_user_team_from (user_id, team_id, effective_from)} に衝突し、
     * 当日に登録した時給の打ち間違いを訂正できなかった欠陥の回帰テスト。
     */
    @Nested
    @DisplayName("同一適用開始日の訂正")
    class SameEffectiveFromCorrection {

        @Test
        @DisplayName("同じ適用開始日の既存行があれば INSERT せず金額を更新する（当日訂正が通る）")
        void 同日再登録は更新される() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate sameDay = LocalDate.of(2026, 4, 1);
            ShiftHourlyRateEntity existing = createRateEntity();
            given(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(USER_ID, TEAM_ID, sameDay))
                    .willReturn(Optional.of(existing));
            given(hourlyRateRepository.save(existing)).willReturn(existing);
            given(shiftMapper.toHourlyRateResponse(existing)).willReturn(createRateResponse());

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), sameDay);
            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            // 既存行そのものが保存される（＝新規行を作らないので一意制約に衝突しない）
            verify(hourlyRateRepository).save(existing);
            assertThat(existing.getHourlyRate()).isEqualByComparingTo("1500.00");
            // 適用開始日は書き換えない（履歴の並びを壊さない）
            assertThat(existing.getEffectiveFrom()).isEqualTo(sameDay);
        }

        @Test
        @DisplayName("別の適用開始日なら新規行を追加する（過去の履歴は消えない）")
        void 別日なら追加される() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            LocalDate newDay = LocalDate.of(2026, 5, 1);
            given(hourlyRateRepository.findByUserIdAndTeamIdAndEffectiveFrom(USER_ID, TEAM_ID, newDay))
                    .willReturn(Optional.empty());
            ShiftHourlyRateEntity saved = createRateEntity();
            given(hourlyRateRepository.save(any())).willReturn(saved);
            given(shiftMapper.toHourlyRateResponse(saved)).willReturn(createRateResponse());

            CreateHourlyRateRequest req = new CreateHourlyRateRequest(
                    USER_ID, new BigDecimal("1500.00"), newDay);
            shiftHourlyRateService.createHourlyRate(TEAM_ID, req, CURRENT_USER_ID);

            org.mockito.ArgumentCaptor<ShiftHourlyRateEntity> captor =
                    org.mockito.ArgumentCaptor.forClass(ShiftHourlyRateEntity.class);
            verify(hourlyRateRepository).save(captor.capture());
            assertThat(captor.getValue().getEffectiveFrom()).isEqualTo(newDay);
            assertThat(captor.getValue().getHourlyRate()).isEqualByComparingTo("1500.00");
        }
    }
}
