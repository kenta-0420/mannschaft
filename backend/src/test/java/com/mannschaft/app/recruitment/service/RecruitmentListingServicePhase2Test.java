package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.RecruitmentFeedItemResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentDistributionTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.event.RecruitmentNotificationEvent;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentCategoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentDistributionTargetRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.FollowerType;
import com.mannschaft.app.social.repository.FollowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentListingService} Phase 2 の単体テスト。
 * publish / confirmApplication / getMyListings / getMyFeed を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingService Phase 2 単体テスト")
class RecruitmentListingServicePhase2Test {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentCategoryRepository categoryRepository;
    @Mock
    private RecruitmentDistributionTargetRepository distributionTargetRepository;
    @Mock
    private RecruitmentReminderRepository reminderRepository;
    @Mock
    private RecruitmentParticipantRepository participantRepository;
    @Mock
    private RecruitmentParticipantHistoryRepository participantHistoryRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private MembershipScopeQueryService membershipScopeQueryService;
    @Mock
    private FollowRepository followRepository;
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

    // F22.1 市 Phase 2 足場C: 札立て地域の team 既定補完
    @Mock
    private com.mannschaft.app.team.service.TeamService teamService;

    // Issue #2997: 通知の組み立て・配送は AFTER_COMMIT の RecruitmentNotificationListener へ移設した。
    // RecruitmentListingService は受信者を確定して publishEvent するのみで、locale 解決・文面・
    // NotificationHelper の依存は無い。

    @InjectMocks
    private RecruitmentListingService service;

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 1L;
    private static final Long ADMIN_ID = 2L;
    private static final Long LISTING_ID = 200L;
    private static final Long PARTICIPANT_ID = 300L;

    // ========================================
    // publish - Phase 2 配信対象チェック
    // ========================================

    @Nested
    @DisplayName("publish - Phase 2 配信対象・整合性チェック")
    class PublishPhase2 {

        @Test
        @DisplayName("配信対象が0件 → EMPTY_DISTRIBUTION_TARGETS")
        void publish_noTargets_throws() throws Exception {
            RecruitmentListingEntity listing = buildDraftListing();
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(distributionTargetRepository.countByListingId(LISTING_ID)).willReturn(0);

            assertThatThrownBy(() -> service.publish(LISTING_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.EMPTY_DISTRIBUTION_TARGETS);
        }

        @Test
        @DisplayName("PUBLIC visibility なのに PUBLIC_FEED が含まれない → VISIBILITY_TARGETS_INCONSISTENT")
        void publish_publicVisibilityWithoutPublicFeed_throws() throws Exception {
            RecruitmentListingEntity listing = buildDraftListingWithVisibility(RecruitmentVisibility.PUBLIC);
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(distributionTargetRepository.countByListingId(LISTING_ID)).willReturn(1);
            given(distributionTargetRepository.findByListingId(LISTING_ID))
                    .willReturn(List.of(buildTarget(RecruitmentDistributionTargetType.MEMBERS)));

            assertThatThrownBy(() -> service.publish(LISTING_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.VISIBILITY_TARGETS_INCONSISTENT);
        }

        @Test
        @DisplayName("配信対象あり・整合性OK → 公開成功 + 通知送信")
        void publish_validTargets_success() throws Exception {
            RecruitmentListingEntity listing = buildDraftListing();
            RecruitmentListingEntity savedListing = buildOpenListing();

            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(distributionTargetRepository.countByListingId(LISTING_ID)).willReturn(1);
            given(distributionTargetRepository.findByListingId(LISTING_ID))
                    .willReturn(List.of(buildTarget(RecruitmentDistributionTargetType.MEMBERS)));
            given(listingRepository.save(any())).willReturn(savedListing);
            given(userRoleRepository.findUserIdsByScope(anyString(), anyLong())).willReturn(List.of(5L, 6L));
            given(mapper.toListingResponse(any())).willReturn(null);

            service.publish(LISTING_ID, ADMIN_ID);

            verify(listingRepository).save(any());
        }

        /**
         * Issue #2997: 業務TXは通知を同期で作らず、{@link RecruitmentNotificationEvent} を publish するだけであること。
         * 受信者解決・文面・配送は AFTER_COMMIT のリスナーの責務（実DB検証: RecruitmentNotificationTransactionIT）。
         */
        @Test
        @DisplayName("配線確認: publish は RECRUITMENT_PUBLISHED の通知イベントを publish する（同期で通知を作らない）")
        void publish_通知イベントをpublishする() throws Exception {
            RecruitmentListingEntity listing = buildDraftListing();
            RecruitmentListingEntity savedListing = buildOpenListing();

            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(distributionTargetRepository.countByListingId(LISTING_ID)).willReturn(1);
            given(distributionTargetRepository.findByListingId(LISTING_ID))
                    .willReturn(List.of(buildTarget(RecruitmentDistributionTargetType.MEMBERS)));
            given(listingRepository.save(any())).willReturn(savedListing);
            given(userRoleRepository.findUserIdsByScope(anyString(), anyLong())).willReturn(List.of(5L, 6L));
            given(mapper.toListingResponse(any())).willReturn(null);

            service.publish(LISTING_ID, ADMIN_ID);

            verify(eventPublisher).publishEvent(argThat((Object event) ->
                    event instanceof RecruitmentNotificationEvent e
                            && e.kind() == RecruitmentNotificationEvent.Kind.PUBLISHED
                            && LISTING_ID.equals(e.listingId())
                            && List.of(5L, 6L).equals(e.recipientUserIds())));
        }
    }

    // ========================================
    // confirmApplication - Phase 2
    // ========================================

    @Nested
    @DisplayName("confirmApplication - Phase 2")
    class ConfirmApplication {

        @Test
        @DisplayName("有料募集の APPLIED → CONFIRMED + リマインダー作成 + 与信起票")
        void confirm_applied_success() throws Exception {
            RecruitmentParticipantEntity participant = buildParticipant(RecruitmentParticipantStatus.APPLIED);
            RecruitmentListingEntity listing = buildOpenListing();
            setField(listing, "paymentEnabled", true);
            setField(listing, "price", 2000);
            setField(listing, "payeeKind", "TEAM");

            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(participantHistoryRepository.save(any())).willReturn(null);
            given(listingRepository.incrementConfirmedAtomic(LISTING_ID)).willReturn(1);
            given(reminderRepository.save(any())).willReturn(null);
            given(mapper.toParticipantResponse(any())).willReturn(null);

            service.confirmApplication(PARTICIPANT_ID, ADMIN_ID);

            verify(participantRepository).save(any());
            verify(reminderRepository).save(any());
            // Issue #2997: RECRUITMENT_CONFIRMED 通知は同期で作らず、通知イベントを publish するだけ。
            verify(eventPublisher).publishEvent(argThat((Object event) ->
                    event instanceof RecruitmentNotificationEvent e
                            && e.kind() == RecruitmentNotificationEvent.Kind.CONFIRMED
                            && LISTING_ID.equals(e.listingId())
                            && List.of(USER_ID).equals(e.recipientUserIds())
                            && ADMIN_ID.equals(e.actorId())));
            verify(eventPublisher).publishEvent(argThat((Object event) -> {
                if (!(event instanceof RecruitmentParticipantConfirmedEvent confirmedEvent)) {
                    return false;
                }
                return LISTING_ID.equals(confirmedEvent.listingId())
                        && PARTICIPANT_ID.equals(confirmedEvent.participantId())
                        && USER_ID.equals(confirmedEvent.payerUserId())
                        && "TEAM".equals(confirmedEvent.listingScopeType())
                        && TEAM_ID.equals(confirmedEvent.listingScopeId())
                        && "TEAM".equals(confirmedEvent.payeeKind())
                        && confirmedEvent.payeeUserId() == null
                        && confirmedEvent.faceAmount() == 2000L
                        && listing.getStartAt().equals(confirmedEvent.serviceDate());
            }));
        }

        @Test
        @DisplayName("APPLIED 以外 → INVALID_STATE_TRANSITION")
        void confirm_notApplied_throws() throws Exception {
            RecruitmentParticipantEntity participant = buildParticipant(RecruitmentParticipantStatus.CONFIRMED);
            RecruitmentListingEntity listing = buildOpenListing();

            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.confirmApplication(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        @Test
        @DisplayName("参加者が存在しない → LISTING_NOT_FOUND")
        void confirm_notFound_throws() {
            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmApplication(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("K1: 募集が TEAM/ORGANIZATION でない（PERSONAL）→ MARKET_404 ではなく LISTING_NOT_FOUND で DB 不変")
        void confirm_personalListing_throwsListingNotFound() throws Exception {
            RecruitmentParticipantEntity participant = buildParticipant(RecruitmentParticipantStatus.APPLIED);
            RecruitmentListingEntity listing = buildOpenListing();
            setField(listing, "scopeType", RecruitmentScopeType.PERSONAL);
            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));

            assertThatThrownBy(() -> service.confirmApplication(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
            verify(participantRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("K1: 募集が消えていれば（論理削除・モデレーション非表示）LISTING_NOT_FOUND で DB 不変")
        void confirm_listingGone_throwsListingNotFound() throws Exception {
            RecruitmentParticipantEntity participant = buildParticipant(RecruitmentParticipantStatus.APPLIED);
            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.confirmApplication(PARTICIPANT_ID, ADMIN_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
            verify(participantRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    // ========================================
    // resolveConfirmScope - W4（認可の前の scope 解決）
    // ========================================

    @Nested
    @DisplayName("resolveConfirmScope - W4 認可前の scope 解決")
    class ResolveConfirmScope {

        @Test
        @DisplayName("参加者→募集をたどり、募集のスコープを返す（行ロックは取らない）")
        void resolve_returnsScope() throws Exception {
            given(participantRepository.findById(PARTICIPANT_ID))
                    .willReturn(Optional.of(buildParticipant(RecruitmentParticipantStatus.APPLIED)));
            given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(buildOpenListing()));

            RecruitmentListingService.ConfirmScope scope = service.resolveConfirmScope(LISTING_ID, PARTICIPANT_ID);

            assertThat(scope.scopeType()).isEqualTo(RecruitmentScopeType.TEAM);
            assertThat(scope.scopeId()).isEqualTo(TEAM_ID);
            verify(participantRepository, org.mockito.Mockito.never()).findByIdForUpdate(any());
            verify(listingRepository, org.mockito.Mockito.never()).findByIdForUpdate(any());
        }

        @Test
        @DisplayName("パスの listingId が参加者の募集と違えば LISTING_NOT_FOUND")
        void resolve_pathListingMismatch_throws() throws Exception {
            given(participantRepository.findById(PARTICIPANT_ID))
                    .willReturn(Optional.of(buildParticipant(RecruitmentParticipantStatus.APPLIED)));

            assertThatThrownBy(() -> service.resolveConfirmScope(LISTING_ID + 1, PARTICIPANT_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }

        @Test
        @DisplayName("参加者不在・募集不在・PERSONAL はすべて LISTING_NOT_FOUND")
        void resolve_missingOrPersonal_throws() throws Exception {
            given(participantRepository.findById(PARTICIPANT_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.resolveConfirmScope(LISTING_ID, PARTICIPANT_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);

            given(participantRepository.findById(PARTICIPANT_ID))
                    .willReturn(Optional.of(buildParticipant(RecruitmentParticipantStatus.APPLIED)));
            given(listingRepository.findById(LISTING_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.resolveConfirmScope(LISTING_ID, PARTICIPANT_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);

            RecruitmentListingEntity personal = buildOpenListing();
            setField(personal, "scopeType", RecruitmentScopeType.PERSONAL);
            given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(personal));
            assertThatThrownBy(() -> service.resolveConfirmScope(LISTING_ID, PARTICIPANT_ID))
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND);
        }
    }

    // ========================================
    // getMyListings - Phase 2
    // ========================================

    @Nested
    @DisplayName("getMyListings - Phase 2")
    class GetMyListings {

        @Test
        @DisplayName("参加予定一覧を取得できる")
        void getMyListings_success() throws Exception {
            given(participantRepository.findMyActiveParticipations(USER_ID)).willReturn(List.of());
            given(mapper.toParticipantResponseList(any())).willReturn(List.of());

            List<RecruitmentParticipantResponse> result = service.getMyListings(USER_ID);
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getMyFeed - Phase 2
    // ========================================

    @Nested
    @DisplayName("getMyFeed - Phase 2")
    class GetMyFeed {

        @Test
        @DisplayName("フォロー先・所属スコープがない場合は空リストを返す")
        void getMyFeed_noFollows_empty() {
            given(followRepository.findFollowedIdsByFollowerAndType(
                    FollowerType.USER, USER_ID, FollowerType.TEAM)).willReturn(List.of());
            given(followRepository.findFollowedIdsByFollowerAndType(
                    FollowerType.USER, USER_ID, FollowerType.ORGANIZATION)).willReturn(List.of());
            given(membershipScopeQueryService.findActiveTeamIds(USER_ID)).willReturn(List.of());
            given(membershipScopeQueryService.findActiveOrganizationIds(USER_ID)).willReturn(List.of());

            List<RecruitmentFeedItemResponse> result = service.getMyFeed(USER_ID);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("フォロー先チームがある場合は募集フィードを返す")
        void getMyFeed_withFollowedTeam_returnsFeed() throws Exception {
            given(followRepository.findFollowedIdsByFollowerAndType(
                    FollowerType.USER, USER_ID, FollowerType.TEAM)).willReturn(List.of(TEAM_ID));
            given(followRepository.findFollowedIdsByFollowerAndType(
                    FollowerType.USER, USER_ID, FollowerType.ORGANIZATION)).willReturn(List.of());
            given(membershipScopeQueryService.findActiveTeamIds(USER_ID)).willReturn(List.of());
            given(membershipScopeQueryService.findActiveOrganizationIds(USER_ID)).willReturn(List.of());
            given(listingRepository.findOpenByScopeIds(any(), any(Pageable.class)))
                    .willReturn(List.of());
            given(mapper.toFeedItemResponseList(any())).willReturn(List.of());

            List<RecruitmentFeedItemResponse> result = service.getMyFeed(USER_ID);
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private RecruitmentListingEntity buildDraftListing() throws Exception {
        return buildDraftListingWithVisibility(RecruitmentVisibility.SCOPE_ONLY);
    }

    private RecruitmentListingEntity buildDraftListingWithVisibility(RecruitmentVisibility visibility) throws Exception {
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("テスト募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(LocalDateTime.now().plusDays(2))
                .endAt(LocalDateTime.now().plusDays(2).plusHours(2))
                .applicationDeadline(LocalDateTime.now().plusDays(1))
                .autoCancelAt(LocalDateTime.now().plusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(visibility)
                .createdBy(ADMIN_ID)
                .build();
        setField(listing, "id", LISTING_ID);
        return listing;
    }

    private RecruitmentListingEntity buildOpenListing() throws Exception {
        RecruitmentListingEntity listing = buildDraftListing();
        setField(listing, "status", RecruitmentListingStatus.OPEN);
        return listing;
    }

    private RecruitmentParticipantEntity buildParticipant(RecruitmentParticipantStatus status) throws Exception {
        RecruitmentParticipantEntity participant = RecruitmentParticipantEntity.builder()
                .listingId(LISTING_ID)
                .participantType(com.mannschaft.app.recruitment.RecruitmentParticipantType.USER)
                .userId(USER_ID)
                .appliedBy(USER_ID)
                .build();
        setField(participant, "id", PARTICIPANT_ID);
        setField(participant, "status", status);
        return participant;
    }

    private RecruitmentDistributionTargetEntity buildTarget(RecruitmentDistributionTargetType type) {
        return RecruitmentDistributionTargetEntity.builder()
                .listingId(LISTING_ID)
                .targetType(type)
                .build();
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
