package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentListingService} の単体テスト。
 * §5.1 募集作成のバリデーションと §5.7 編集制約を中心に検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingService 単体テスト")
class RecruitmentListingServiceTest {

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentCategoryRepository categoryRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private RecruitmentMapper mapper;

    // F22.1 市: 追加依存
    @Mock
    private MarketRegionValidator marketRegionValidator;

    @Mock
    private MarketFriendTargetService marketFriendTargetService;

    @Mock
    private MarketResponseEnricher marketResponseEnricher;

    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentFriendTargetRepository friendTargetRepository;

    // F22.1 市 Phase 2 D: 複数地域（N:N）中間表（create happy-path で replaceListingRegions が使用）
    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentListingRegionRepository listingRegionRepository;

    // F22.1 市 Phase 2 足場C: 札立て地域の team 既定補完
    @Mock
    private com.mannschaft.app.team.service.TeamService teamService;

    @InjectMocks
    private RecruitmentListingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long CATEGORY_ID = 100L;
    private static final Long LISTING_ID = 200L;
    private static final Long PAYEE_USER_ID = 42L;
    private static final LocalDateTime BASE_TIME = LocalDateTime.now();

    // ========================================
    // create - §5.1 バリデーション
    // ========================================

    @Nested
    @DisplayName("create - §5.1 バリデーション")
    class CreateValidation {

        @Test
        @DisplayName("category_id が存在しない → CATEGORY_NOT_SPECIFIED")
        void create_categoryNotFound_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(false);

            CreateRecruitmentListingRequest request = validRequest();
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CATEGORY_NOT_SPECIFIED);
        }

        @Test
        @DisplayName("min_capacity > capacity → INVALID_CAPACITY")
        void create_minCapacityExceedsCapacity_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = new CreateRecruitmentListingRequest(
                    CATEGORY_ID, null, "test", null,
                    RecruitmentParticipationType.INDIVIDUAL,
                    BASE_TIME.plusDays(2),
                    BASE_TIME.plusDays(2).plusHours(2),
                    BASE_TIME.plusDays(1),
                    BASE_TIME.plusDays(1),
                    5, 10, // capacity=5, minCapacity=10 → 不正
                    false, null,
                    RecruitmentVisibility.SCOPE_ONLY,
                    null, null, null, null,
                    null, null, null, null, null,
                    null, null); // F22.1 地域・フレンド宛先・配信対象・複数地域(regions)・payee
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_CAPACITY);
        }

        @Test
        @DisplayName("payment_enabled=true で price=null → PRICE_REQUIRED")
        void create_paymentEnabledWithoutPrice_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = new CreateRecruitmentListingRequest(
                    CATEGORY_ID, null, "test", null,
                    RecruitmentParticipationType.INDIVIDUAL,
                    BASE_TIME.plusDays(2),
                    BASE_TIME.plusDays(2).plusHours(2),
                    BASE_TIME.plusDays(1),
                    BASE_TIME.plusDays(1),
                    10, 1,
                    true, null, // paymentEnabled=true, price=null
                    RecruitmentVisibility.SCOPE_ONLY,
                    null, null, null, null,
                    null, null, null, null, null,
                    null, null); // F22.1 地域・フレンド宛先・配信対象・複数地域(regions)・payee
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.PRICE_REQUIRED);
        }
    }

    // ========================================
    // create - F22.1 市 謝礼決済: 受領主体（payee）検証（02_api_design §3）
    // ========================================

    @Nested
    @DisplayName("create - F22.1 謝礼決済 受領主体検証")
    class CreatePayeeValidation {

        @Test
        @DisplayName("① payment_enabled=true で payeeKind 未指定 → PAYEE_REQUIRED")
        void create_paymentEnabledWithoutPayeeKind_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = paymentRequest(5000, null, null);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.PAYEE_REQUIRED);
        }

        @Test
        @DisplayName("② payeeKind=USER で payeeUserId 未指定 → PAYEE_USER_REQUIRED")
        void create_payeeUserWithoutUserId_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = paymentRequest(5000, "USER", null);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.PAYEE_USER_REQUIRED);
        }

        @Test
        @DisplayName("③ payeeKind=USER の payeeUserId が札主 scope 非所属 → PAYEE_NOT_IN_SCOPE")
        void create_payeeUserNotInScope_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
            given(accessControlService.isMember(PAYEE_USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            CreateRecruitmentListingRequest request = paymentRequest(5000, "USER", PAYEE_USER_ID);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.PAYEE_NOT_IN_SCOPE);
        }

        @Test
        @DisplayName("④ TEAM 札に payeeKind=ORG 指定（scope 不一致）→ PAYEE_NOT_IN_SCOPE")
        void create_orgPayeeOnTeamScope_throws() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            CreateRecruitmentListingRequest request = paymentRequest(5000, "ORG", null);
            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.PAYEE_NOT_IN_SCOPE);
        }

        @Test
        @DisplayName("④' payeeKind=TEAM で payeeUserId 指定 → 非 USER ゆえ payee_user_id は NULL 強制")
        void create_teamPayeeWithUserId_normalizesNull() {
            stubCreateHappyPath();
            // TEAM 受領は isMember を呼ばない（USER のみ所属検証する）。

            CreateRecruitmentListingRequest request = paymentRequest(5000, "TEAM", PAYEE_USER_ID);
            service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

            RecruitmentListingEntity saved = captureSaved();
            org.assertj.core.api.Assertions.assertThat(saved.getPayeeKind()).isEqualTo("TEAM");
            org.assertj.core.api.Assertions.assertThat(saved.getPayeeUserId()).isNull();
        }

        @Test
        @DisplayName("⑤ 正常: payeeKind=USER + 所属者 + price → payee が永続化される")
        void create_validUserPayee_persists() {
            stubCreateHappyPath();
            given(accessControlService.isMember(PAYEE_USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            CreateRecruitmentListingRequest request = paymentRequest(5000, "USER", PAYEE_USER_ID);
            service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID, request);

            RecruitmentListingEntity saved = captureSaved();
            org.assertj.core.api.Assertions.assertThat(saved.getPaymentEnabled()).isTrue();
            org.assertj.core.api.Assertions.assertThat(saved.getPayeeKind()).isEqualTo("USER");
            org.assertj.core.api.Assertions.assertThat(saved.getPayeeUserId()).isEqualTo(PAYEE_USER_ID);
            org.assertj.core.api.Assertions.assertThat(saved.getPrice()).isEqualTo(5000);
        }
    }

    // ========================================
    // update - §5.7 編集制約
    // ========================================

    @Nested
    @DisplayName("update - §5.7 編集制約")
    class UpdateConstraints {

        @Test
        @DisplayName("capacity を confirmed_count 未満に変更 → CAPACITY_BELOW_CONFIRMED")
        void update_capacityBelowConfirmed_throws() throws Exception {
            RecruitmentListingEntity listing = buildListingWithConfirmed(5);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            UpdateRecruitmentListingRequest request = new UpdateRecruitmentListingRequest(
                    null, null, null, null, null, null, null,
                    3, // capacity=3 < confirmed_count=5
                    null, null, null, null, null, null, null, null,
                    null, null, null,
                    null, null); // F22.1 prefectureCode, cityCode, regions, payeeKind, payeeUserId

            assertThatThrownBy(() -> service.update(LISTING_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.CAPACITY_BELOW_CONFIRMED);
        }

        @Test
        @DisplayName("listing not found → LISTING_NOT_FOUND")
        void update_notFound_throws() {
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.update(LISTING_ID, USER_ID,
                    new UpdateRecruitmentListingRequest(null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null,
                            null, null, null,
                            null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private CreateRecruitmentListingRequest validRequest() {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "test title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                BASE_TIME.plusDays(2),
                BASE_TIME.plusDays(2).plusHours(2),
                BASE_TIME.plusDays(1),
                BASE_TIME.plusDays(1),
                10, 1,
                false, null,
                RecruitmentVisibility.SCOPE_ONLY,
                "東京", null, null, null,
                null, null, null, null, null,
                null, null); // F22.1 地域・フレンド宛先・配信対象・複数地域(regions)・payee
    }

    /**
     * F22.1 謝礼決済: payment_enabled=true の作成リクエストを payee 指定付きで生成する。
     */
    private CreateRecruitmentListingRequest paymentRequest(Integer price, String payeeKind, Long payeeUserId) {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "test title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                BASE_TIME.plusDays(2),
                BASE_TIME.plusDays(2).plusHours(2),
                BASE_TIME.plusDays(1),
                BASE_TIME.plusDays(1),
                10, 1,
                true, price, // paymentEnabled=true
                RecruitmentVisibility.SCOPE_ONLY,
                null, null, null, null,
                null, null, null, null, null,
                payeeKind, payeeUserId); // F22.1 payee
    }

    /**
     * create() の happy-path 用に地域解決・友達宛先・保存・enrich をスタブする。
     * 地域なし（TEAM 既定補完も空）・friendTargets なしの最小経路。
     */
    private void stubCreateHappyPath() {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        given(teamService.findRegionCodes(TEAM_ID)).willReturn(Optional.empty());
        given(marketRegionValidator.validateAndNormalize(isNull(), isNull()))
                .willReturn(new MarketRegionValidator.ResolvedRegion(null, null));
        given(listingRepository.save(any(RecruitmentListingEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        // mapper / enricher の戻り値は内容不問（payee 永続は captureSaved で entity を直接検証する）。
        RecruitmentListingResponse stub = org.mockito.Mockito.mock(RecruitmentListingResponse.class);
        given(mapper.toListingResponse(any(RecruitmentListingEntity.class))).willReturn(stub);
        given(marketResponseEnricher.enrich(any(), any(RecruitmentListingEntity.class))).willReturn(stub);
    }

    /** listingRepository.save に渡された entity を捕捉する。 */
    private RecruitmentListingEntity captureSaved() {
        ArgumentCaptor<RecruitmentListingEntity> captor =
                ArgumentCaptor.forClass(RecruitmentListingEntity.class);
        verify(listingRepository).save(captor.capture());
        return captor.getValue();
    }

    private RecruitmentListingEntity buildListingWithConfirmed(int confirmedCount) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(CATEGORY_ID)
                .title("test")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(BASE_TIME.plusDays(2))
                .endAt(BASE_TIME.plusDays(2).plusHours(2))
                .applicationDeadline(BASE_TIME.plusDays(1))
                .autoCancelAt(BASE_TIME.plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .createdBy(USER_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        setField(listing, "confirmedCount", confirmedCount);
        return listing;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
