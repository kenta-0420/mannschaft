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
import static org.mockito.ArgumentMatchers.eq;
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

    // ========================================
    // listEffectiveRatesForTeam（CMP-260912-1525: 一括取得）
    // ========================================

    /**
     * チーム全員ぶんの「基準日時点で有効な時給」を 1 クエリで返す経路。
     *
     * <p>時給は金銭情報であり、F03.5 の方針は「本人 + 当該チームの ADMIN/DEPUTY_ADMIN のみ」。
     * 一括取得は本人ぶん以外も必ず含むため、<b>ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）以外は
     * 一律で拒否</b>されなければならない。一般メンバーが呼んで他人の時給が返るのは漏洩である。</p>
     */
    @Nested
    @DisplayName("listEffectiveRatesForTeam")
    class ListEffectiveRatesForTeam {

        private static final Long ADMIN_USER_ID = 99L;
        /** 時給は設定済みだが既にチームを脱退したユーザー。 */
        private static final Long LEFT_USER_ID = 77L;
        private static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 16);

        @Test
        @DisplayName("AC-3: 何人いても有効時給の取得は 1 クエリで済む")
        void 一括取得は1クエリ() {
            // Given: ADMIN として通す
            given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(USER_ID));
            List<ShiftHourlyRateEntity> entities = List.of(createRateEntity(), createRateEntity());
            given(hourlyRateRepository.findEffectiveRatesByTeam(TEAM_ID, BASE_DATE, List.of(USER_ID)))
                    .willReturn(entities);
            given(shiftMapper.toHourlyRateResponseList(entities))
                    .willReturn(List.of(createRateResponse(), createRateResponse()));

            // When
            List<HourlyRateResponse> result =
                    shiftHourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_USER_ID);

            // Then
            assertThat(result).hasSize(2);
            verify(hourlyRateRepository, org.mockito.Mockito.times(1))
                    .findEffectiveRatesByTeam(TEAM_ID, BASE_DATE, List.of(USER_ID));
            // 1 人ずつ引く経路は使わない（使うと人数ぶんクエリが出る）
            verify(hourlyRateRepository, org.mockito.Mockito.never())
                    .findEffectiveRate(any(), any(), any());
            verify(accessControlService).checkAdminOrAbove(ADMIN_USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("AC-5: ADMIN/DEPUTY_ADMIN でない一般メンバーは 403 で、時給を 1 件も読まない")
        void 一般メンバーは拒否され時給を読まない() {
            // Given
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(false);
            org.mockito.BDDMockito.willThrow(new com.mannschaft.app.common.BusinessException(
                            com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(CURRENT_USER_ID, TEAM_ID, "TEAM");

            // When / Then
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                            shiftHourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, CURRENT_USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            verify(hourlyRateRepository, org.mockito.Mockito.never())
                    .findEffectiveRatesByTeam(any(), any(), any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は短絡的に許可される（既存の時給 API と同じ方針）")
        void システム管理者は許可() {
            given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(true);
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(USER_ID));
            given(hourlyRateRepository.findEffectiveRatesByTeam(TEAM_ID, BASE_DATE, List.of(USER_ID)))
                    .willReturn(List.of());
            given(shiftMapper.toHourlyRateResponseList(List.of())).willReturn(List.of());

            assertThat(shiftHourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_USER_ID))
                    .isEmpty();
            verify(accessControlService, org.mockito.Mockito.never())
                    .checkAdminOrAbove(any(), any(), any());
        }

        /**
         * Codex 検分 P1 の回帰封じ。
         *
         * <p>単数取得は {@code checkHourlyRateAccess} が対象ユーザーの<b>現在の所属</b>まで確認していた。
         * 一括取得を teamId だけで引くと、<b>時給を設定されたあとにチームを脱退した元メンバーの
         * 金銭情報まで返ってしまう</b>（1 件ずつなら効いていた認可が、まとめて取ると効かなくなる）。
         * ロールの検査（誰が呼べるか）と対象の絞り込み（誰の時給を返すか）は別の軸である。</p>
         */
        @Test
        @DisplayName("AC-5: 取得対象は在籍中のメンバーに限られる（脱退した元メンバーの時給は引かない）")
        void 脱退者は取得対象から外れる() {
            // Given: 在籍中は USER_ID のみ。LEFT_USER_ID は時給を設定済みだが既に脱退している。
            given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of(USER_ID));
            given(hourlyRateRepository.findEffectiveRatesByTeam(eq(TEAM_ID), eq(BASE_DATE), any()))
                    .willReturn(List.of());
            given(shiftMapper.toHourlyRateResponseList(List.of())).willReturn(List.of());

            // When
            shiftHourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_USER_ID);

            // Then: 検索対象に渡る ID は在籍者だけで、脱退者は含まれない
            org.mockito.ArgumentCaptor<List<Long>> captor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(hourlyRateRepository).findEffectiveRatesByTeam(eq(TEAM_ID), eq(BASE_DATE), captor.capture());
            assertThat(captor.getValue()).containsExactly(USER_ID);
            assertThat(captor.getValue()).doesNotContain(LEFT_USER_ID);
        }

        @Test
        @DisplayName("在籍者が 0 名なら時給は 1 件も読まない（空 IN 句を DB へ投げない）")
        void 在籍者ゼロなら時給を読まない() {
            given(accessControlService.isSystemAdmin(ADMIN_USER_ID)).willReturn(false);
            given(accessControlService.listActiveMemberIds(TEAM_ID, "TEAM")).willReturn(List.of());

            assertThat(shiftHourlyRateService.listEffectiveRatesForTeam(TEAM_ID, BASE_DATE, ADMIN_USER_ID))
                    .isEmpty();
            verify(hourlyRateRepository, org.mockito.Mockito.never())
                    .findEffectiveRatesByTeam(any(), any(), any());
        }
    }
}
