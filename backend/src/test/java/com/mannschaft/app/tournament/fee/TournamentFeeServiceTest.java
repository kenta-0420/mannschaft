package com.mannschaft.app.tournament.fee;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.dto.CheckoutResponse;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.service.MemberPaymentService;
import com.mannschaft.app.payment.service.PaymentItemService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentDivisionEntity;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.fee.dto.CreateTournamentFeeRequest;
import com.mannschaft.app.tournament.fee.dto.TournamentFeeResponse;
import com.mannschaft.app.tournament.repository.TournamentDivisionRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link TournamentFeeService} の単体テスト（test-first）。
 *
 * <p>F08.7.1/07 大会費用支払い 設計書に準拠。新規の汎用決済基盤は作らず、F08.2 を再利用する
 * ファサードの「連結作成・支払い導線・未払いゲート判定・認可」を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TournamentFeeService 単体テスト")
class TournamentFeeServiceTest {

    @Mock
    private TournamentFeeRepository feeRepository;
    @Mock
    private TournamentFeeTargetRepository feeTargetRepository;
    @Mock
    private TournamentRepository tournamentRepository;
    @Mock
    private TournamentDivisionRepository divisionRepository;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private PaymentItemService paymentItemService;
    @Mock
    private MemberPaymentService memberPaymentService;
    @Mock
    private MemberPaymentRepository memberPaymentRepository;

    @InjectMocks
    private TournamentFeeService service;

    private static final Long ORG_ID = 1L;
    private static final Long TOURNAMENT_ID = 100L;
    private static final Long DIVISION_ID = 200L;
    private static final Long PAYMENT_ITEM_ID = 300L;
    private static final Long TEAM_ID = 400L;
    private static final Long ORG_ADMIN_ID = 10L;
    private static final Long TEAM_REP_ID = 20L;
    private static final Long OUTSIDER_ID = 30L;

    // =========================================================
    // フィクスチャ
    // =========================================================

    private TournamentEntity tournament(Long orgId) {
        // 本サービスは tournament.getId() を読まず organizationId のみ参照するため id は未設定で足りる
        return TournamentEntity.builder().organizationId(orgId).name("テスト大会").build();
    }

    private TournamentDivisionEntity division(Long tournamentId) {
        // 本サービスは division.getId() を読まず tournamentId のみ参照するため id は未設定で足りる
        return TournamentDivisionEntity.builder().tournamentId(tournamentId).name("1部").build();
    }

    private PaymentItemEntity paymentItem(Long orgId) {
        return PaymentItemEntity.builder()
                .organizationId(orgId)
                .name("参加費")
                .type(PaymentItemType.ITEM)
                .amount(new BigDecimal("5000"))
                .currency("JPY")
                .build();
    }

    private TournamentFeeEntity fee(TournamentFeeTargetScope scope) {
        TournamentFeeEntity f = TournamentFeeEntity.builder()
                .tournamentId(TOURNAMENT_ID)
                .paymentItemId(PAYMENT_ITEM_ID)
                .title("2026 春季 参加費")
                .targetScope(scope)
                .organizationId(ORG_ID)
                .createdBy(ORG_ADMIN_ID)
                .build();
        f.setId(UUID.randomUUID());
        return f;
    }

    private CreateTournamentFeeRequest createReq(String scope, Long divisionId, List<Long> teamIds) {
        return new CreateTournamentFeeRequest(PAYMENT_ITEM_ID, "2026 春季 参加費",
                divisionId, scope, LocalDateTime.now().plusDays(30), teamIds);
    }

    // =========================================================
    // 参加費作成
    // =========================================================

    @Nested
    @DisplayName("参加費作成")
    class CreateFee {

        @Test
        @DisplayName("主催組織 ADMIN は payment_item を大会に連結して参加費を作成できる")
        void createByOrgAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(ORG_ADMIN_ID)).willReturn(false);
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem(ORG_ID));
            given(feeRepository.save(any(TournamentFeeEntity.class))).willAnswer(inv -> {
                TournamentFeeEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(UUID.randomUUID());
                return e;
            });

            TournamentFeeResponse res = service.createFee(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, null));

            assertThat(res.paymentItemId()).isEqualTo(PAYMENT_ITEM_ID);
            assertThat(res.targetScope()).isEqualTo("ALL_TEAMS");
            assertThat(res.amount()).isEqualByComparingTo("5000");
            assertThat(res.currency()).isEqualTo("JPY");

            ArgumentCaptor<TournamentFeeEntity> captor = ArgumentCaptor.forClass(TournamentFeeEntity.class);
            verify(feeRepository).save(captor.capture());
            assertThat(captor.getValue().getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(captor.getValue().getCreatedBy()).isEqualTo(ORG_ADMIN_ID);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS では対象チーム明細を保存する（重複排除）")
        void createSpecificTeams() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem(ORG_ID));
            given(feeRepository.save(any(TournamentFeeEntity.class))).willAnswer(inv -> {
                TournamentFeeEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            TournamentFeeResponse res = service.createFee(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("SPECIFIC_TEAMS", null, List.of(TEAM_ID, TEAM_ID, 401L)));

            assertThat(res.targetTeamIds()).containsExactlyInAnyOrder(TEAM_ID, 401L);
            verify(feeTargetRepository, Mockito.times(2)).save(any(TournamentFeeTargetEntity.class));
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは作成できず 403（FEE_MANAGE_FORBIDDEN）")
        void createForbiddenForNonAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.createFee(ORG_ID, TOURNAMENT_ID, OUTSIDER_ID,
                    createReq("ALL_TEAMS", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_MANAGE_FORBIDDEN);
            verify(feeRepository, never()).save(any());
        }

        @Test
        @DisplayName("他組織の大会を指定すると 404（TOURNAMENT_NOT_FOUND・IDOR 対策）")
        void createCrossOrgTournament404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(999L)));

            assertThatThrownBy(() -> service.createFee(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("payment_item が他組織所属なら 422（FEE_PAYMENT_ITEM_SCOPE_MISMATCH）")
        void createPaymentItemScopeMismatch() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem(999L));

            assertThatThrownBy(() -> service.createFee(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            TournamentErrorCode.FEE_PAYMENT_ITEM_SCOPE_MISMATCH);
            verify(feeRepository, never()).save(any());
        }

        @Test
        @DisplayName("指定ディビジョンが当該大会配下でないと 404（DIVISION_NOT_FOUND）")
        void createDivisionMismatch404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(divisionRepository.findById(DIVISION_ID)).willReturn(Optional.of(division(999L)));

            assertThatThrownBy(() -> service.createFee(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID,
                    createReq("ALL_TEAMS", DIVISION_ID, null)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.DIVISION_NOT_FOUND);
        }
    }

    // =========================================================
    // 参加費一覧（全件閲覧＝主催組織 ADMIN）
    // =========================================================

    @Nested
    @DisplayName("参加費一覧（listFees）")
    class ListFees {

        @Test
        @DisplayName("主催組織 ADMIN は全件を取得できる（200 相当）")
        void listByOrgAdmin() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(ORG_ADMIN_ID)).willReturn(false);
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID)).willReturn(List.of(f));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem(ORG_ID));

            List<TournamentFeeResponse> res = service.listFees(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID);

            assertThat(res).hasSize(1);
            assertThat(res.get(0).paymentItemId()).isEqualTo(PAYMENT_ITEM_ID);
            assertThat(res.get(0).amount()).isEqualByComparingTo("5000");
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は全件を取得できる")
        void listBySystemAdmin() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(ORG_ADMIN_ID)).willReturn(true);
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID)).willReturn(List.of(f));
            given(paymentItemService.findByIdOrThrow(PAYMENT_ITEM_ID)).willReturn(paymentItem(ORG_ID));

            assertThat(service.listFees(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID)).hasSize(1);
        }

        @Test
        @DisplayName("主催組織 ADMIN でないユーザーは 403（FEE_MANAGE_FORBIDDEN）で参加費を一覧できない")
        void listForbiddenForNonAdmin() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.listFees(ORG_ID, TOURNAMENT_ID, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_MANAGE_FORBIDDEN);
            // 認可で弾かれるため参加費の読み取り（情報開示）は発生しない
            verify(feeRepository, never()).findByTournamentIdOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("未認証（userId=null）は 403（FEE_MANAGE_FORBIDDEN）")
        void listForbiddenForAnonymous() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));

            assertThatThrownBy(() -> service.listFees(ORG_ID, TOURNAMENT_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_MANAGE_FORBIDDEN);
            verify(feeRepository, never()).findByTournamentIdOrderByCreatedAtAsc(any());
        }

        @Test
        @DisplayName("他組織の大会は 404（TOURNAMENT_NOT_FOUND・IDOR 対策／認可より先に弾く）")
        void listCrossOrgTournament404() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(999L)));

            assertThatThrownBy(() -> service.listFees(ORG_ID, TOURNAMENT_ID, ORG_ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }

    // =========================================================
    // targetScope バリデーション（@Pattern で不正値を 400 に倒す）
    // =========================================================

    @Nested
    @DisplayName("targetScope バリデーション")
    class TargetScopeValidation {

        private Set<ConstraintViolation<CreateTournamentFeeRequest>> validate(String scope) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                Validator validator = factory.getValidator();
                return validator.validate(new CreateTournamentFeeRequest(
                        PAYMENT_ITEM_ID, "2026 春季 参加費", null, scope, LocalDateTime.now().plusDays(30), null));
            }
        }

        @Test
        @DisplayName("不正な targetScope はバリデーションエラー（→ 400 / 500 化しない）")
        void invalidScopeRejected() {
            Set<ConstraintViolation<CreateTournamentFeeRequest>> violations = validate("INVALID_SCOPE");
            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("targetScope"));
        }

        @Test
        @DisplayName("ALL_TEAMS / SPECIFIC_TEAMS / NULL は許容される")
        void validScopesAccepted() {
            assertThat(validate("ALL_TEAMS")).noneMatch(scopeViolation());
            assertThat(validate("SPECIFIC_TEAMS")).noneMatch(scopeViolation());
            assertThat(validate(null)).noneMatch(scopeViolation());
        }

        private java.util.function.Predicate<ConstraintViolation<CreateTournamentFeeRequest>> scopeViolation() {
            return v -> v.getPropertyPath().toString().equals("targetScope");
        }
    }

    // =========================================================
    // 支払い導線（checkout）
    // =========================================================

    @Nested
    @DisplayName("支払い導線（checkout）")
    class Checkout {

        @Test
        @DisplayName("自チーム代表（ADMIN/DEPUTY）は F08.2 の checkout に委譲される")
        void checkoutByTeamRep() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(feeRepository.findByIdAndOrganizationId(f.getId(), ORG_ID)).willReturn(Optional.of(f));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            CheckoutResponse expected = new CheckoutResponse("https://stripe/checkout", "sess_1", LocalDateTime.now());
            given(memberPaymentService.createCheckout(PAYMENT_ITEM_ID, TEAM_REP_ID)).willReturn(expected);

            CheckoutResponse res = service.checkout(ORG_ID, TOURNAMENT_ID, f.getId(), TEAM_ID, TEAM_REP_ID);

            assertThat(res).isSameAs(expected);
            verify(memberPaymentService).createCheckout(PAYMENT_ITEM_ID, TEAM_REP_ID);
        }

        @Test
        @DisplayName("チーム代表でないユーザーは 403（FEE_PAY_FORBIDDEN）で委譲されない")
        void checkoutForbiddenForNonRep() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(feeRepository.findByIdAndOrganizationId(f.getId(), ORG_ID)).willReturn(Optional.of(f));
            given(accessControlService.isAdminOrAbove(OUTSIDER_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.checkout(ORG_ID, TOURNAMENT_ID, f.getId(), TEAM_ID, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_PAY_FORBIDDEN);
            verifyNoInteractions(memberPaymentService);
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS で対象外チームの支払いは 403（FEE_TEAM_NOT_TARGET）")
        void checkoutTeamNotTarget() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.SPECIFIC_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(feeRepository.findByIdAndOrganizationId(f.getId(), ORG_ID)).willReturn(Optional.of(f));
            given(accessControlService.isAdminOrAbove(TEAM_REP_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(feeTargetRepository.existsByFeeIdAndTeamId(f.getId(), TEAM_ID)).willReturn(false);

            assertThatThrownBy(() -> service.checkout(ORG_ID, TOURNAMENT_ID, f.getId(), TEAM_ID, TEAM_REP_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_TEAM_NOT_TARGET);
            verifyNoInteractions(memberPaymentService);
        }

        @Test
        @DisplayName("存在しない / 他組織の fee は 404（FEE_NOT_FOUND）")
        void checkoutFeeNotFound() {
            UUID feeId = UUID.randomUUID();
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(feeRepository.findByIdAndOrganizationId(feeId, ORG_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkout(ORG_ID, TOURNAMENT_ID, feeId, TEAM_ID, TEAM_REP_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_NOT_FOUND);
        }
    }

    // =========================================================
    // 未払いゲート判定
    // =========================================================

    @Nested
    @DisplayName("未払いゲート判定（isTeamPaid）")
    class GateCheck {

        @Test
        @DisplayName("チーム代表が有効な PAID を持てば支払い済み（true）")
        void teamPaidTrue() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(feeRepository.findById(f.getId())).willReturn(Optional.of(f));
            given(memberPaymentRepository.existsValidPaidPaymentByTeamRepresentative(TEAM_ID, PAYMENT_ITEM_ID))
                    .willReturn(true);

            assertThat(service.isTeamPaid(f.getId(), TEAM_ID)).isTrue();
        }

        @Test
        @DisplayName("未払いなら false（提出受理・エントリー確定をゲートできる）")
        void teamPaidFalse() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(feeRepository.findById(f.getId())).willReturn(Optional.of(f));
            given(memberPaymentRepository.existsValidPaidPaymentByTeamRepresentative(TEAM_ID, PAYMENT_ITEM_ID))
                    .willReturn(false);

            assertThat(service.isTeamPaid(f.getId(), TEAM_ID)).isFalse();
        }

        @Test
        @DisplayName("SPECIFIC_TEAMS で対象外チームは課金対象外＝ゲートを通す（true）")
        void teamPaidNonTargetPassesGate() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.SPECIFIC_TEAMS);
            given(feeRepository.findById(f.getId())).willReturn(Optional.of(f));
            given(feeTargetRepository.existsByFeeIdAndTeamId(f.getId(), TEAM_ID)).willReturn(false);

            assertThat(service.isTeamPaid(f.getId(), TEAM_ID)).isTrue();
            verifyNoInteractions(memberPaymentRepository);
        }

        @Test
        @DisplayName("存在しない fee は 404（FEE_NOT_FOUND）")
        void gateFeeNotFound() {
            UUID feeId = UUID.randomUUID();
            given(feeRepository.findById(feeId)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.isTeamPaid(feeId, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_NOT_FOUND);
        }
    }

    // =========================================================
    // 大会単位の未払いゲート判定（F08.7.1/06 提出受理ゲート用）
    // =========================================================

    @Nested
    @DisplayName("大会単位の未払いゲート判定（isTeamPaidForTournament）")
    class TournamentGateCheck {

        @Test
        @DisplayName("参加費が 1 件も無い大会は課金なし＝ゲートを通す（true）")
        void noFeesPasses() {
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID)).willReturn(List.of());

            assertThat(service.isTeamPaidForTournament(TOURNAMENT_ID, null, TEAM_ID)).isTrue();
            verifyNoInteractions(memberPaymentRepository);
        }

        @Test
        @DisplayName("適用される全参加費を支払い済みなら true")
        void allPaid() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID)).willReturn(List.of(f));
            given(feeRepository.findById(f.getId())).willReturn(Optional.of(f));
            given(memberPaymentRepository.existsValidPaidPaymentByTeamRepresentative(TEAM_ID, PAYMENT_ITEM_ID))
                    .willReturn(true);

            assertThat(service.isTeamPaidForTournament(TOURNAMENT_ID, null, TEAM_ID)).isTrue();
        }

        @Test
        @DisplayName("いずれかの適用参加費が未払いなら false（提出受理をブロックできる）")
        void anyUnpaidBlocks() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.ALL_TEAMS);
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID)).willReturn(List.of(f));
            given(feeRepository.findById(f.getId())).willReturn(Optional.of(f));
            given(memberPaymentRepository.existsValidPaidPaymentByTeamRepresentative(TEAM_ID, PAYMENT_ITEM_ID))
                    .willReturn(false);

            assertThat(service.isTeamPaidForTournament(TOURNAMENT_ID, null, TEAM_ID)).isFalse();
        }

        @Test
        @DisplayName("他ディビジョン限定の参加費は対象外として除外する（当該チームの提出に無関係）")
        void otherDivisionFeeIgnored() {
            // division 限定（divisionId=DIVISION_ID）の参加費は、divisionId=null（大会全体）の提出には無関係
            TournamentFeeEntity divisionFee = TournamentFeeEntity.builder()
                    .tournamentId(TOURNAMENT_ID).divisionId(DIVISION_ID).paymentItemId(PAYMENT_ITEM_ID)
                    .title("ディビジョン限定費").targetScope(TournamentFeeTargetScope.ALL_TEAMS)
                    .organizationId(ORG_ID).createdBy(ORG_ADMIN_ID).build();
            divisionFee.setId(UUID.randomUUID());
            given(feeRepository.findByTournamentIdOrderByCreatedAtAsc(TOURNAMENT_ID))
                    .willReturn(List.of(divisionFee));

            // divisionId=null の提出枠なので、divisionId=DIVISION_ID の参加費はスキップ → 支払い確認に進まず true
            assertThat(service.isTeamPaidForTournament(TOURNAMENT_ID, null, TEAM_ID)).isTrue();
            verifyNoInteractions(memberPaymentRepository);
        }
    }

    // =========================================================
    // 削除
    // =========================================================

    @Nested
    @DisplayName("参加費削除")
    class DeleteFee {

        @Test
        @DisplayName("主催組織 ADMIN は論理削除でき、対象チーム明細も削除される")
        void deleteByOrgAdmin() {
            TournamentFeeEntity f = fee(TournamentFeeTargetScope.SPECIFIC_TEAMS);
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isAdmin(ORG_ADMIN_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(feeRepository.findByIdAndOrganizationId(f.getId(), ORG_ID)).willReturn(Optional.of(f));

            service.deleteFee(ORG_ID, TOURNAMENT_ID, f.getId(), ORG_ADMIN_ID);

            assertThat(f.getDeletedAt()).isNotNull();
            verify(feeTargetRepository).deleteByFeeId(f.getId());
            verify(feeRepository).save(f);
        }

        @Test
        @DisplayName("非 ADMIN の削除は 403（FEE_MANAGE_FORBIDDEN）")
        void deleteForbidden() {
            given(tournamentRepository.findById(TOURNAMENT_ID)).willReturn(Optional.of(tournament(ORG_ID)));
            given(accessControlService.isSystemAdmin(OUTSIDER_ID)).willReturn(false);
            given(accessControlService.isAdmin(OUTSIDER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.deleteFee(ORG_ID, TOURNAMENT_ID, UUID.randomUUID(), OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", TournamentErrorCode.FEE_MANAGE_FORBIDDEN);
        }
    }
}
