package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
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
    private UserRoleRepository userRoleRepository;
    @Mock
    private FollowRepository followRepository;
    @Mock
    private NotificationHelper notificationHelper;
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

    // Issue #2715 ロットA: 通知本文の i18n 化で RecruitmentListingService に追加した依存。
    // confirmApplication / publish(→sendPublishedNotifications) が受信者 locale 解決のため呼び出すので、
    // スタブしないと Locale.forLanguageTag(null) の NPE になる（行単位 try/catch に飲まれて
    // 「通知が飛ばない」という一見無関係な失敗に化ける罠があるため、削除せず必ず維持すること）。
    @Mock
    private com.mannschaft.app.common.i18n.UserLocaleCache userLocaleCache;
    @Mock
    private org.springframework.context.MessageSource messageSource;

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
            // Issue #2715 / 検分是正: sendPublishedNotifications は notificationHelper.notifyAllLocalized(...)
            // に委譲するのみで、locale 解決 (userLocaleCache) や本文組み立て (messageSource) は
            // NotificationHelper 側 or bodyBuilder ラムダ内で行われる。notificationHelper 自体を
            // @Mock にしているためラムダは呼ばれず、ここでのスタブは不要（UnnecessaryStubbingException）。

            service.publish(LISTING_ID, ADMIN_ID);

            verify(listingRepository).save(any());
        }

        /**
         * PR #2764 検分是正の配線確認テスト。
         *
         * <p>訂正（2026-08-14）: 当初は「notify を受信者数分ループ直呼びすると F00 Phase F の
         * 可視性フィルタを迂回し情報漏洩する退行」だと判断していたが、これは誤りだった。
         * {@code NotificationService#createNotification} が単発経路でも {@code canView} による
         * 可視性ガードを担保しているため、notify 直呼びループでも漏洩は発生しない。</p>
         *
         * <p>本テストが検証しているのは漏洩の有無ではなく、publish() が
         * {@code notificationHelper.notify(...)} を直接ループ呼び出しせず、必ず
         * {@code notifyAllLocalized(...)} を経由して配線されていることである。
         * {@code notifyAllLocalized} は「受信者別 locale の一括本文組み立て」「locale の一括解決
         * による N+1 回避」「前段フィルタで閲覧不可ユーザー分の無駄な本文組み立て・
         * createNotification 呼び出しを省くこと」を目的として導入したものであり、本テストは
         * その配線が保たれていることを確認する。前段フィルタの単体動作は
         * {@code NotificationHelperTest#NotifyAllLocalized} で検証する。</p>
         */
        @Test
        @DisplayName("配線確認: publish は notify を直接ループせず notifyAllLocalized を経由する")
        void publish_通知はnotifyAllLocalized経由でありnotify直呼びしない() throws Exception {
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

            // notifyAllLocalized が sourceType=RECRUITMENT_LISTING で呼ばれること（前段フィルタが
            // このソース種別で ReferenceType 解決できる前提の配線を検証する）。
            verify(notificationHelper).notifyAllLocalized(
                    eq(List.of(5L, 6L)),
                    eq("RECRUITMENT_PUBLISHED"),
                    eq("RECRUITMENT_LISTING"), eq(LISTING_ID),
                    any(), any(), any(), any(),
                    any());
            // notify の受信者ループ直呼び（notifyAllLocalized 経由に一本化する前の形）が復活していないこと。
            verify(notificationHelper, never()).notify(
                    any(), eq("RECRUITMENT_PUBLISHED"), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ========================================
    // confirmApplication - Phase 2
    // ========================================

    @Nested
    @DisplayName("confirmApplication - Phase 2")
    class ConfirmApplication {

        @Test
        @DisplayName("APPLIED → CONFIRMED + リマインダー作成")
        void confirm_applied_success() throws Exception {
            RecruitmentParticipantEntity participant = buildParticipant(RecruitmentParticipantStatus.APPLIED);
            RecruitmentListingEntity listing = buildOpenListing();

            given(participantRepository.findByIdForUpdate(PARTICIPANT_ID)).willReturn(Optional.of(participant));
            given(listingRepository.findByIdForUpdate(LISTING_ID)).willReturn(Optional.of(listing));
            given(participantHistoryRepository.save(any())).willReturn(null);
            given(listingRepository.incrementConfirmedAtomic(LISTING_ID)).willReturn(1);
            given(reminderRepository.save(any())).willReturn(null);
            given(mapper.toParticipantResponse(any())).willReturn(null);
            // Issue #2715: RECRUITMENT_CONFIRMED 通知の受信者 locale 解決のためのスタブ。
            given(userLocaleCache.getLocale(any())).willReturn("ja");
            given(messageSource.getMessage(any(), any(), any(), any()))
                    .willAnswer(invocation -> invocation.getArgument(2));

            service.confirmApplication(PARTICIPANT_ID, ADMIN_ID);

            verify(participantRepository).save(any());
            verify(reminderRepository).save(any());
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
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

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
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());
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
