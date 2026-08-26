package com.mannschaft.app.committee;

import com.mannschaft.app.committee.dto.CommitteeStatusTransitionRequest;
import com.mannschaft.app.committee.dto.CommitteeUpdateRequest;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.committee.service.CommitteeAccessGuard;
import com.mannschaft.app.committee.service.CommitteeService;
import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

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

    /** 委員会内ロール判定ガード（本テストの関心外のため既定の no-op / false で通す）。 */
    @Mock
    private CommitteeAccessGuard committeeAccessGuard;

    @InjectMocks
    private CommitteeService committeeService;

    private static final Long COMMITTEE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long ORG_ID = 100L;

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

