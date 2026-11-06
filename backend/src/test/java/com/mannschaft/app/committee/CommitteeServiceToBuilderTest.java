package com.mannschaft.app.committee;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.committee.dto.CommitteeCreateRequest;
import com.mannschaft.app.committee.dto.CommitteeStatusTransitionRequest;
import com.mannschaft.app.committee.dto.CommitteeUpdateRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.CommitteeVisibility;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CommitteeService の toBuilder 廃止・id 保持を固定する回帰テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CommitteeService toBuilder廃止回帰テスト")
class CommitteeServiceToBuilderTest {

    @Mock
    private CommitteeRepository committeeRepository;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private UserRowLockService userRowLockService;

    /** 委員会内ロール判定ガード（本テストの関心外のため既定の no-op / false で通す）。 */
    @Mock
    private CommitteeAccessGuard committeeAccessGuard;

    @InjectMocks
    private CommitteeService committeeService;

    private static final Long COMMITTEE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long ORG_ID = 100L;
    private static final Long INITIAL_CHAIR_USER_ID = 11L;

    @Test
    void 一覧の組織所属拒否は管理者判定と全Repository参照より先に行う() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002)).given(accessControlService)
                .checkMembership(USER_ID, ORG_ID, "ORGANIZATION");

        assertThatThrownBy(() -> committeeService.listCommittees(ORG_ID, null, USER_ID, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class);

        verify(accessControlService, never()).isAdminOrAbove(any(), any(), any());
        verifyNoInteractions(committeeRepository, committeeMemberRepository);
    }

    @Test
    void 管理者一覧は既存status検索を使い本人委員会IDをロードしない() {
        var pageable = PageRequest.of(0, 20, Sort.by("id"));
        given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
        given(committeeRepository.findByOrganizationIdAndStatus(ORG_ID, CommitteeStatus.ARCHIVED, pageable))
                .willReturn(Page.empty(pageable));

        committeeService.listCommittees(ORG_ID, CommitteeStatus.ARCHIVED, USER_ID, pageable);

        InOrder order = inOrder(accessControlService, committeeRepository);
        order.verify(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
        order.verify(accessControlService).isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
        order.verify(committeeRepository).findByOrganizationIdAndStatus(ORG_ID, CommitteeStatus.ARCHIVED, pageable);
        verifyNoInteractions(committeeMemberRepository);
    }

    @Test
    void 非管理者一覧は可視Page検索だけを使い本人委員会IDをロードしない() {
        var pageable = PageRequest.of(1, 20, Sort.by("id"));
        Page<CommitteeEntity> expected = Page.empty(pageable);
        given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
        given(committeeRepository.findVisibleByOrganizationId(
                ORG_ID, CommitteeStatus.ACTIVE, USER_ID, CommitteeVisibility.HIDDEN, pageable)).willReturn(expected);

        assertThat(committeeService.listCommittees(ORG_ID, CommitteeStatus.ACTIVE, USER_ID, pageable))
                .isSameAs(expected);

        verify(committeeRepository).findVisibleByOrganizationId(
                ORG_ID, CommitteeStatus.ACTIVE, USER_ID, CommitteeVisibility.HIDDEN, pageable);
        verifyNoInteractions(committeeMemberRepository);
    }

    @ParameterizedTest
    @CsvSource({"false,NONE", "true,NONE", "false,CREATED", "true,CREATED",
            "false,ID_DESC", "true,ID_DESC", "false,COMPOSITE", "true,COMPOSITE",
            "false,UNPAGED", "true,UNPAGED"})
    void 一覧はcallerSortとページを保ち不足するID順序だけ補う(boolean admin, String kind) {
        Sort requested = switch (kind) {
            case "CREATED" -> Sort.by(Sort.Order.desc("createdAt").nullsLast());
            case "ID_DESC" -> Sort.by(Sort.Order.desc("id"));
            case "COMPOSITE" -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
            default -> Sort.unsorted();
        };
        Pageable input = "UNPAGED".equals(kind) ? Pageable.unpaged() : PageRequest.of(1, 7, requested);
        given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(admin);
        if (admin) {
            given(committeeRepository.findByOrganizationIdAndStatus(eq(ORG_ID), eq(CommitteeStatus.ACTIVE), any(Pageable.class)))
                    .willAnswer(invocation -> Page.empty(invocation.getArgument(2)));
        } else {
            given(committeeRepository.findVisibleByOrganizationId(eq(ORG_ID), eq(CommitteeStatus.ACTIVE), eq(USER_ID),
                    eq(CommitteeVisibility.HIDDEN), any(Pageable.class)))
                    .willAnswer(invocation -> Page.empty(invocation.getArgument(4)));
        }

        var result = committeeService.listCommittees(ORG_ID, CommitteeStatus.ACTIVE, USER_ID, input);
        var captor = ArgumentCaptor.forClass(Pageable.class);
        if (admin) {
            verify(committeeRepository).findByOrganizationIdAndStatus(eq(ORG_ID), eq(CommitteeStatus.ACTIVE), captor.capture());
        } else {
            verify(committeeRepository).findVisibleByOrganizationId(eq(ORG_ID), eq(CommitteeStatus.ACTIVE), eq(USER_ID),
                    eq(CommitteeVisibility.HIDDEN), captor.capture());
        }
        Pageable actual = captor.getValue();
        Sort expected = requested.getOrderFor("id") == null ? requested.and(Sort.by("id")) : requested;
        assertThat(actual.getSort()).isEqualTo(expected);
        assertThat(result.getSort()).isEqualTo(expected);
        assertThat(actual.isPaged()).isEqualTo(input.isPaged());
        if (input.isPaged()) {
            assertThat(actual.getPageNumber()).isEqualTo(1);
            assertThat(actual.getPageSize()).isEqualTo(7);
        }
        if (requested.getOrderFor("id") != null) {
            assertThat(actual).isSameAs(input);
        }
        verifyNoInteractions(committeeMemberRepository);
    }

    private CommitteeCreateRequest createRequest() throws Exception {
        CommitteeCreateRequest request = new CommitteeCreateRequest();
        java.lang.reflect.Field nameField = findField(request.getClass(), "name");
        nameField.setAccessible(true);
        nameField.set(request, "committee");
        java.lang.reflect.Field chairField = findField(request.getClass(), "initialChairUserId");
        chairField.setAccessible(true);
        chairField.set(request, INITIAL_CHAIR_USER_ID);
        return request;
    }

    private CommitteeEntity buildCommittee() throws Exception {
        CommitteeEntity entity = CommitteeEntity.builder()
                .organizationId(ORG_ID)
                .name("テスト委員会")
                .build();
        java.lang.reflect.Field idField = findField(entity.getClass(), "id");
        idField.setAccessible(true);
        idField.set(entity, COMMITTEE_ID);
        return entity;
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Nested
    @DisplayName("createCommittee - initial chair organization membership")
    class CreateCommittee {

        @Test
        @DisplayName("locks initial chair before checking active organization membership")
        void locksInitialChairBeforeMembershipCheck() throws Exception {
            CommitteeCreateRequest request = createRequest();
            given(accessControlService.isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION"))
                    .willReturn(true);

            committeeService.createCommittee(ORG_ID, request, USER_ID);

            InOrder order = inOrder(userRowLockService, accessControlService);
            order.verify(userRowLockService).lockAll(INITIAL_CHAIR_USER_ID);
            order.verify(accessControlService).checkMembership(
                    INITIAL_CHAIR_USER_ID, ORG_ID, "ORGANIZATION");
            order.verify(accessControlService).isAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
            verify(committeeRepository).save(any(CommitteeEntity.class));
            verify(committeeMemberRepository).save(any());
        }

        @Test
        @DisplayName("rejects an initial chair without active organization membership")
        void rejectsInitialChairWithoutActiveOrganizationMembership() throws Exception {
            CommitteeCreateRequest request = createRequest();
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkMembership(INITIAL_CHAIR_USER_ID, ORG_ID, "ORGANIZATION");

            assertThatThrownBy(() -> committeeService.createCommittee(ORG_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class);

            verify(committeeRepository, never()).save(any());
            verify(committeeMemberRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateCommittee - save に渡るのが findById の同一インスタンスかつ id 保持")
    class UpdateCommittee {

        @Test
        @DisplayName("updateCommittee → save に渡るエンティティが findById の同一インスタンスで id を保持する")
        void updateCommittee_savesOriginalInstanceWithIdPreserved() throws Exception {
            CommitteeEntity entity = buildCommittee();
            CommitteeUpdateRequest request = new CommitteeUpdateRequest();

            given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(entity));
            // updateCommittee の認可は CommitteeAccessGuard#requireCommitteeRole に委譲される
            // （拒否時に例外を投げる契約であり、no-op モックは「許可」を意味する）
            given(committeeRepository.save(any())).willReturn(entity);

            committeeService.updateCommittee(COMMITTEE_ID, request, USER_ID);

            ArgumentCaptor<CommitteeEntity> captor = ArgumentCaptor.forClass(CommitteeEntity.class);
            verify(committeeRepository).save(captor.capture());
            // save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
            assertThat(captor.getValue()).isSameAs(entity);
            assertThat(captor.getValue().getId()).isEqualTo(COMMITTEE_ID);
        }
    }

    @Nested
    @DisplayName("transitionStatus - save に渡るのが findById の同一インスタンスかつ id 保持")
    class TransitionStatus {

        @Test
        @DisplayName("transitionStatus(ACTIVATE) → save に渡るエンティティが findById の同一インスタンスで id を保持する")
        void transitionStatus_activate_savesOriginalInstanceWithIdPreserved() throws Exception {
            CommitteeEntity entity = buildCommittee();
            // DRAFT のまま（デフォルト）
            CommitteeStatusTransitionRequest request = new CommitteeStatusTransitionRequest();
            java.lang.reflect.Field actionField = request.getClass().getDeclaredField("action");
            actionField.setAccessible(true);
            actionField.set(request, "ACTIVATE");

            given(committeeRepository.findById(COMMITTEE_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(eq(USER_ID), any(), any())).willReturn(true);
            // CHAIR 判定（ガード）は false を返すが、組織 ADMIN であるため遷移は許可される
            given(committeeRepository.save(any())).willReturn(entity);

            committeeService.transitionStatus(COMMITTEE_ID, request, USER_ID);

            ArgumentCaptor<CommitteeEntity> captor = ArgumentCaptor.forClass(CommitteeEntity.class);
            verify(committeeRepository).save(captor.capture());
            // save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
            assertThat(captor.getValue()).isSameAs(entity);
            assertThat(captor.getValue().getId()).isEqualTo(COMMITTEE_ID);
            // activate() → status = ACTIVE になっているはず（applyStatusTransition経由）
            assertThat(captor.getValue().getStatus()).isEqualTo(CommitteeStatus.ACTIVE);
        }
    }
}

