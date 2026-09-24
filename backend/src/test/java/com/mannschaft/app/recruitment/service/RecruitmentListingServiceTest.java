package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
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
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    private RecruitmentListingAudienceScopeRepository audienceScopeRepository;

    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository distributionTargetRepository;

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

    // #2497: 募集枠論理削除に伴う未解決異議の自動取下げ（同一ドメイン内の委譲先）
    @Mock
    private RecruitmentNoShowService noShowService;

    // Issue #2715 ロットA: confirmApplication の通知 i18n 化検証に必要な依存。
    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository participantRepository;

    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository participantHistoryRepository;

    @Mock
    private com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository reminderRepository;

    @Mock
    private com.mannschaft.app.notification.service.NotificationHelper notificationHelper;

    @Mock
    private com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache;

    @Mock
    private org.springframework.context.MessageSource messageSource;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

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
        @DisplayName("開催終了が開催開始以前 → INVALID_EVENT_TIME_RANGE")
        void create_endAtNotAfterStartAt_throwsSpecificValidationError() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID,
                    requestWithDates(BASE_TIME.plusDays(2), BASE_TIME.plusDays(2),
                            BASE_TIME.plusDays(1), BASE_TIME.plusDays(1))))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_EVENT_TIME_RANGE);
        }

        @Test
        @DisplayName("応募締切が開催開始以後 → INVALID_APPLICATION_DEADLINE")
        void create_deadlineNotBeforeStartAt_throwsSpecificValidationError() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID,
                    requestWithDates(BASE_TIME.plusDays(2), BASE_TIME.plusDays(3),
                            BASE_TIME.plusDays(2), BASE_TIME.plusDays(1))))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_APPLICATION_DEADLINE);
        }

        @Test
        @DisplayName("自動キャンセル判定が応募締切より後 → INVALID_AUTO_CANCEL_AT")
        void create_autoCancelAfterDeadline_throwsSpecificValidationError() {
            given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);

            assertThatThrownBy(() -> service.create(RecruitmentScopeType.TEAM, TEAM_ID, USER_ID,
                    requestWithDates(BASE_TIME.plusDays(3), BASE_TIME.plusDays(4),
                            BASE_TIME.plusDays(2), BASE_TIME.plusDays(2).plusMinutes(1))))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_AUTO_CANCEL_AT);
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

    @Nested
    @DisplayName("PERSONAL札主のサーバー側境界")
    class PersonalScopeValidation {

        @Test
        @DisplayName("本人以外のscopeIdでは作成できない")
        void create_personalWithAnotherScopeId_throws() {
            assertThatThrownBy(() -> service.create(
                    RecruitmentScopeType.PERSONAL, TEAM_ID, USER_ID,
                    personalRequest(false, RecruitmentVisibility.SCOPE_ONLY, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("paymentEnabledまたはpayeeをPERSONALに指定するとMARKET_006")
        void create_personalWithPaymentOrPayee_throwsMarket006() {
            assertThatThrownBy(() -> service.create(
                    RecruitmentScopeType.PERSONAL, USER_ID, USER_ID,
                    personalRequest(false, RecruitmentVisibility.SCOPE_ONLY, "USER", PAYEE_USER_ID)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_PAYMENT_DISABLED);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("FRIEND_TEAMS_ONLYをPERSONALに指定するとMARKET_008")
        void create_personalWithFriendOnly_throwsMarket008() {
            assertThatThrownBy(() -> service.create(
                    RecruitmentScopeType.PERSONAL, USER_ID, USER_ID,
                    personalRequest(false, RecruitmentVisibility.FRIEND_TEAMS_ONLY, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }

        @Test
        @DisplayName("汎用更新経路はPERSONAL札のPUBLIC更新を存在秘匿404で拒否する")
        void update_personalToPublicThroughGenericRoute_throwsMarket404() {
            RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                    .scopeType(RecruitmentScopeType.PERSONAL)
                    .scopeId(USER_ID)
                    .createdBy(USER_ID)
                    .paymentEnabled(false)
                    .build();
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.update(
                    LISTING_ID, USER_ID, personalUpdateWithVisibility(RecruitmentVisibility.PUBLIC)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("PERSONAL作成はscopeId・createdBy・DRAFTを認証済み本人に固定する")
        void create_personalFixesOwnerAndDraft() {
            stubCreateHappyPath(false);

            service.create(RecruitmentScopeType.PERSONAL, USER_ID, USER_ID,
                    personalRequest(false, RecruitmentVisibility.SCOPE_ONLY, null, null));

            RecruitmentListingEntity saved = captureSaved();
            assertThat(saved.getScopeType()).isEqualTo(RecruitmentScopeType.PERSONAL);
            assertThat(saved.getScopeId()).isEqualTo(USER_ID);
            assertThat(saved.getCreatedBy()).isEqualTo(USER_ID);
            assertThat(saved.getStatus()).isEqualTo(RecruitmentListingStatus.DRAFT);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("PERSONAL札は公開できず通知・配信処理へ進まない")
        void publish_personal_throwsMarket008BeforeDistribution() {
            RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                    .scopeType(RecruitmentScopeType.PERSONAL)
                    .scopeId(USER_ID)
                    .createdBy(USER_ID)
                    .build();
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.publish(LISTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }

        @Test
        @DisplayName("公開後のPERSONAL札は内部IDを含む汎用詳細DTOから返さない")
        void getListing_publishedPersonal_throwsMarket404() {
            RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                    .scopeType(RecruitmentScopeType.PERSONAL)
                    .scopeId(USER_ID)
                    .createdBy(USER_ID)
                    .status(RecruitmentListingStatus.OPEN)
                    .build();
            given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.getListing(LISTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);
            verifyNoInteractions(mapper);
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
        @DisplayName("汎用更新経路はPERSONAL札を存在秘匿404で拒否する")
        void genericUpdate_personalIsHidden() throws Exception {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.update(LISTING_ID, USER_ID,
                    new UpdateRecruitmentListingRequest(null, null, null, null, null, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("専用更新経路は不在・他人・他スコープを同一MARKET_404に畳み込む")
        void personalUpdate_missingOrOtherScopeIsHidden() {
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updatePersonalDraft(LISTING_ID, USER_ID,
                    personalUpdateWithVisibility(RecruitmentVisibility.SCOPE_ONLY)))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("専用更新経路はDRAFT以外をRECRUITMENT_100で拒否する")
        void personalUpdate_nonDraftIsRejected() {
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(personalListing(RecruitmentListingStatus.OPEN)));

            assertThatThrownBy(() -> service.updatePersonalDraft(LISTING_ID, USER_ID,
                    personalUpdateWithVisibility(RecruitmentVisibility.SCOPE_ONLY)))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("専用更新経路は本人のPERSONAL+DRAFTを更新する")
        void personalUpdate_draftSucceeds() {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(listing));
            given(listingRepository.save(listing)).willReturn(listing);
            RecruitmentListingResponse response = org.mockito.Mockito.mock(RecruitmentListingResponse.class);
            given(mapper.toListingResponse(listing)).willReturn(response);
            given(marketResponseEnricher.enrich(response, listing)).willReturn(response);

            RecruitmentListingResponse actual = service.updatePersonalDraft(
                    LISTING_ID, USER_ID, personalUpdateWithVisibility(RecruitmentVisibility.SCOPE_ONLY));

            assertThat(actual).isSameAs(response);
            verify(listingRepository).save(listing);
        }

        @Test
        @DisplayName("専用更新経路は個人札の決済と公開範囲を拒否する")
        void personalUpdate_paymentAndUnsupportedVisibilityAreRejected() {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.updatePersonalDraft(LISTING_ID, USER_ID,
                    updateWithPayment(true)))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_PAYMENT_DISABLED);
            assertThatThrownBy(() -> service.updatePersonalDraft(LISTING_ID, USER_ID,
                    personalUpdateWithVisibility(RecruitmentVisibility.FRIEND_TEAMS_ONLY)))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);
        }

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

    private CreateRecruitmentListingRequest personalRequest(
            boolean paymentEnabled, RecruitmentVisibility visibility, String payeeKind, Long payeeUserId) {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "personal title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                BASE_TIME.plusDays(2),
                BASE_TIME.plusDays(2).plusHours(2),
                BASE_TIME.plusDays(1),
                BASE_TIME.plusDays(1),
                10, 1,
                paymentEnabled, paymentEnabled ? 5000 : null,
                visibility,
                null, null, null, null,
                null, null, null, null, null,
                payeeKind, payeeUserId);
    }

    private UpdateRecruitmentListingRequest personalUpdateWithVisibility(RecruitmentVisibility visibility) {
        return new UpdateRecruitmentListingRequest(
                null, null, null, null, null, null, null,
                null, null, null, null,
                visibility,
                null, null, null, null,
                null, null, null,
                null, null);
    }

    @Nested
    @DisplayName("PERSONAL取消・汎用経路遮断")
    class PersonalCancelConstraints {

        @Test
        @DisplayName("汎用取消経路はPERSONAL札をMARKET_404で存在秘匿する")
        void genericCancel_personalIsHidden() {
            given(listingRepository.findByIdForUpdate(LISTING_ID))
                    .willReturn(Optional.of(personalListing(RecruitmentListingStatus.DRAFT)));

            assertThatThrownBy(() -> service.cancelByAdmin(LISTING_ID, USER_ID,
                    new CancelRecruitmentListingRequest("test")))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("専用取消経路は公開済みPERSONAL札も本人が取り下げられる")
        void personalCancel_openSucceeds() {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.OPEN);
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(listing));
            given(listingRepository.save(listing)).willReturn(listing);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), any())).willReturn(Page.empty());
            RecruitmentListingResponse response = org.mockito.Mockito.mock(RecruitmentListingResponse.class);
            given(mapper.toListingResponse(listing)).willReturn(response);

            RecruitmentListingResponse actual = service.cancelPersonalListing(LISTING_ID, USER_ID,
                    new CancelRecruitmentListingRequest("test"));

            assertThat(actual).isSameAs(response);
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.CANCELLED);
            verify(listingRepository).save(listing);
        }

        @Test
        @DisplayName("専用取消経路はPERSONAL+DRAFTを本人固定で取消する")
        void personalCancel_draftSucceeds() {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(listing));
            given(listingRepository.save(listing)).willReturn(listing);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), any())).willReturn(Page.empty());
            RecruitmentListingResponse response = org.mockito.Mockito.mock(RecruitmentListingResponse.class);
            given(mapper.toListingResponse(listing)).willReturn(response);

            RecruitmentListingResponse actual = service.cancelPersonalListing(LISTING_ID, USER_ID,
                    new CancelRecruitmentListingRequest("test"));

            assertThat(actual).isSameAs(response);
            assertThat(listing.getStatus()).isEqualTo(RecruitmentListingStatus.CANCELLED);
            verify(listingRepository).save(listing);
        }

        @Test
        @DisplayName("公開札の取消は確定参加者も取り消して履歴を残す")
        void personalCancel_cancelsActiveParticipants() throws Exception {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.FULL);
            var participant = com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity.builder()
                    .listingId(LISTING_ID)
                    .participantType(com.mannschaft.app.recruitment.RecruitmentParticipantType.USER)
                    .userId(99L)
                    .appliedBy(99L)
                    .status(com.mannschaft.app.recruitment.RecruitmentParticipantStatus.CONFIRMED)
                    .build();
            setField(participant, "id", 301L);
            given(listingRepository.findByIdAndScopeTypeAndScopeIdForUpdate(
                    eq(LISTING_ID), eq(RecruitmentScopeType.PERSONAL), eq(USER_ID)))
                    .willReturn(Optional.of(listing));
            given(listingRepository.save(listing)).willReturn(listing);
            given(participantRepository.findByListingIdAndStatusIn(
                    eq(LISTING_ID), any(), eq(PageRequest.of(0, 100))))
                    .willReturn(new PageImpl<>(List.of(participant)), Page.empty());

            service.cancelPersonalListing(LISTING_ID, USER_ID,
                    new CancelRecruitmentListingRequest("test"));

            assertThat(participant.getStatus())
                    .isEqualTo(com.mannschaft.app.recruitment.RecruitmentParticipantStatus.CANCELLED);
            verify(participantRepository).save(participant);
            verify(participantHistoryRepository).save(any());
            // Issue #2990 L2: 取下げ通知は業務TX内で発火せず、受信者IDを載せたイベントを publish する。
            // 「通知を消して番人を黙らせる」是正になっていないことを、この検証が固定する。
            verify(eventPublisher).publishEvent(
                    new com.mannschaft.app.recruitment.event.RecruitmentCancelledNotificationEvent(
                            listing.getId(), java.util.List.of(99L)));
        }
    }

    @Nested
    @DisplayName("PERSONAL運用・配信経路のfail-closed")
    class PersonalOperationalScopeGuard {

        @Test
        @DisplayName("archive はPERSONAL札をMARKET_404で存在秘匿し削除・異議取下げを呼ばない")
        void archive_personal_doesNotCauseSideEffects() throws Exception {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.archive(LISTING_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.LISTING_NOT_FOUND);

            verify(listingRepository, never()).save(any());
            verifyNoInteractions(noShowService);
        }

        @Test
        @DisplayName("配信対象取得はPERSONAL札をMARKET_008で拒否し配信Repositoryを呼ばない")
        void getDistributionTargets_personal_doesNotQueryTargets() throws Exception {
            RecruitmentListingEntity listing = personalListing(RecruitmentListingStatus.DRAFT);
            given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.getDistributionTargets(LISTING_ID, USER_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MarketErrorCode.PERSONAL_VISIBILITY_NOT_ALLOWED);

            verify(distributionTargetRepository, never()).findByListingId(anyLong());
        }
    }

    private UpdateRecruitmentListingRequest updateWithPayment(boolean enabled) {
        return new UpdateRecruitmentListingRequest(
                null, null, null, null, null, null, null,
                null, null, enabled, enabled ? 1000 : null,
                RecruitmentVisibility.SCOPE_ONLY,
                null, null, null, null, null, null, null, null, null);
    }

    private RecruitmentListingEntity personalListing(RecruitmentListingStatus status) {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.PERSONAL)
                .scopeId(USER_ID)
                .createdBy(USER_ID)
                .categoryId(CATEGORY_ID)
                .title("personal")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(BASE_TIME.plusDays(2))
                .endAt(BASE_TIME.plusDays(2).plusHours(2))
                .applicationDeadline(BASE_TIME.plusDays(1))
                .autoCancelAt(BASE_TIME.plusDays(1))
                .capacity(1)
                .minCapacity(1)
                .paymentEnabled(false)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .status(status)
                .build();
        try {
            setField(listing, "id", LISTING_ID);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return listing;
    }

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

    private CreateRecruitmentListingRequest requestWithDates(
            LocalDateTime startAt, LocalDateTime endAt,
            LocalDateTime applicationDeadline, LocalDateTime autoCancelAt) {
        return new CreateRecruitmentListingRequest(
                CATEGORY_ID, null, "test title", "desc",
                RecruitmentParticipationType.INDIVIDUAL,
                startAt, endAt, applicationDeadline, autoCancelAt,
                10, 1,
                false, null,
                RecruitmentVisibility.SCOPE_ONLY,
                "東京", null, null, null,
                null, null, null, null, null,
                null, null);
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
        stubCreateHappyPath(true);
    }

    private void stubCreateHappyPath(boolean stubTeamRegion) {
        given(categoryRepository.existsById(CATEGORY_ID)).willReturn(true);
        if (stubTeamRegion) {
            given(teamService.findRegionCodes(TEAM_ID)).willReturn(Optional.empty());
        }
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

    // ========================================
    // archive - #2497 論理削除に伴う未解決異議の自動取下げ
    // ========================================

    /**
     * #2497 の<b>配線</b>だけを固定する。取り下げの中身（対象の絞り込み・REVOKED の適用・監査）は
     * {@link RecruitmentNoShowService} 側の責務であり、実 DB での実証は
     * {@code com.mannschaft.app.recruitment.RecruitmentListingArchiveDisputeAutoRevokeIT} が担う。
     */
    @Nested
    @DisplayName("archive - #2497 論理削除に伴う未解決異議の自動取下げ")
    class Archive {

        @Test
        @DisplayName("論理削除すると、当該募集枠のスコープ文脈と操作者を添えて自動取下げが呼ばれる")
        void archive_未解決異議の自動取下げを委譲する() throws Exception {
            RecruitmentListingEntity listing = buildListingWithConfirmed(0);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            service.archive(LISTING_ID, USER_ID);

            assertThat(listing.getDeletedAt())
                    .as("論理削除自体は従来どおり行われること")
                    .isNotNull();
            verify(noShowService).autoRevokeOpenDisputesOnListingArchived(
                    LISTING_ID, RecruitmentScopeType.TEAM, TEAM_ID, USER_ID);
        }

        @Test
        @DisplayName("認可で弾かれた場合は自動取下げも行われない")
        void archive_認可失敗時は自動取下げしない() throws Exception {
            RecruitmentListingEntity listing = buildListingWithConfirmed(0);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, RecruitmentScopeType.TEAM.name());

            assertThatThrownBy(() -> service.archive(LISTING_ID, USER_ID))
                    .isInstanceOf(BusinessException.class);

            verifyNoInteractions(noShowService);
        }
    }

    // ========================================
    // confirmApplication - Issue #2715 ロットA: 通知 i18n 化
    // ========================================

    @Nested
    @DisplayName("confirmApplication - 受信者locale対応通知（Issue #2715）")
    class ConfirmApplication {

        @Test
        @DisplayName("正常系: 受信者localeに従って RECRUITMENT_CONFIRMED 通知の件名・本文が切り替わる")
        void confirmApplication_localeAware_buildsMessageInRecipientLocale() throws Exception {
            Long participantId = 900L;
            Long applicantUserId = 5L;
            RecruitmentListingEntity listing = buildListingWithConfirmed(0);

            com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity participant =
                    com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity.builder()
                            .listingId(LISTING_ID)
                            .participantType(com.mannschaft.app.recruitment.RecruitmentParticipantType.USER)
                            .userId(applicantUserId)
                            .appliedBy(applicantUserId)
                            .status(com.mannschaft.app.recruitment.RecruitmentParticipantStatus.APPLIED)
                            .build();
            setField(participant, "id", participantId);

            given(participantRepository.findByIdForUpdate(participantId)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(userLocaleCache.getLocale(applicantUserId)).willReturn("en");
            given(messageSource.getMessage(eq("notification.recruitment.confirmed.title"), any(), any(),
                    eq(java.util.Locale.forLanguageTag("en"))))
                    .willReturn("Participation confirmed");
            given(messageSource.getMessage(eq("notification.recruitment.confirmed.body"), any(), any(),
                    eq(java.util.Locale.forLanguageTag("en"))))
                    .willReturn("Your participation in test has been confirmed.");

            service.confirmApplication(participantId, USER_ID);

            verify(notificationHelper).notify(
                    eq(applicantUserId), eq("RECRUITMENT_CONFIRMED"),
                    eq("Participation confirmed"), eq("Your participation in test has been confirmed."),
                    any(), any(), any(), any(), any(), any());
        }
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
