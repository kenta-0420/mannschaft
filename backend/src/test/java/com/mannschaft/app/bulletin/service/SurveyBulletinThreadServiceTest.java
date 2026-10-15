package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyBulletinThreadService} の単体テスト。
 * アンケート専用掲示板スレッドの自動生成・ロックを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyBulletinThreadService 単体テスト")
class SurveyBulletinThreadServiceTest {

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;

    /** フラット enrichment 委譲先。findThreadResponseBySurveyId のスタブに使用。 */
    @Mock
    private BulletinThreadService bulletinThreadService;

    /** per-scope 所属認可ガード。findThreadResponseBySurveyId の認可検証に使用（軍議④）。 */
    @Mock
    private BulletinAccessGuard accessGuard;

    @InjectMocks
    private SurveyBulletinThreadService service;

    private static final long SURVEY_ID = 42L;
    private static final long SCOPE_ID = 1L;
    private static final String SURVEY_SOURCE_TYPE = "SURVEY";

    // =====================================================================
    // createForSurvey
    // =====================================================================

    @Nested
    @DisplayName("createForSurvey — スレッド作成")
    class CreateForSurvey {

        @Test
        @DisplayName("ORGANIZATION スコープで sourceType=SURVEY, categoryId=null のスレッドが保存されること")
        void shouldCreateThreadWithSurveySourceType_whenOrganizationScope() {
            // given
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            BulletinThreadEntity savedThread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("テストアンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            given(bulletinThreadRepository.save(any(BulletinThreadEntity.class)))
                    .willReturn(savedThread);

            // when
            BulletinThreadEntity result = service.createForSurvey(SURVEY_ID, "ORGANIZATION", SCOPE_ID, "テストアンケート");

            // then
            ArgumentCaptor<BulletinThreadEntity> captor = ArgumentCaptor.forClass(BulletinThreadEntity.class);
            verify(bulletinThreadRepository).save(captor.capture());
            BulletinThreadEntity captured = captor.getValue();

            assertThat(captured.getSourceType()).isEqualTo(SURVEY_SOURCE_TYPE);
            assertThat(captured.getSourceId()).isEqualTo(SURVEY_ID);
            assertThat(captured.getCategoryId()).isNull();
            assertThat(captured.getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
            assertThat(captured.getScopeId()).isEqualTo(SCOPE_ID);
            assertThat(captured.getTitle()).isEqualTo("テストアンケート — 掲示板");
            assertThat(captured.getAuthorId()).isNull();
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("TEAM スコープで ScopeType.TEAM のスレッドが保存されること")
        void shouldCreateThreadWithTeamScopeType_whenTeamScope() {
            // given
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            BulletinThreadEntity savedThread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .title("チームアンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            given(bulletinThreadRepository.save(any(BulletinThreadEntity.class)))
                    .willReturn(savedThread);

            // when
            service.createForSurvey(SURVEY_ID, "TEAM", SCOPE_ID, "チームアンケート");

            // then
            ArgumentCaptor<BulletinThreadEntity> captor = ArgumentCaptor.forClass(BulletinThreadEntity.class);
            verify(bulletinThreadRepository).save(captor.capture());
            assertThat(captor.getValue().getScopeType()).isEqualTo(ScopeType.TEAM);
        }

        @Test
        @DisplayName("COMMITTEE スコープは ORGANIZATION として扱われること")
        void shouldTreatCommitteeScopeAsOrganization() {
            // given
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            BulletinThreadEntity savedThread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("委員会アンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            given(bulletinThreadRepository.save(any(BulletinThreadEntity.class)))
                    .willReturn(savedThread);

            // when
            service.createForSurvey(SURVEY_ID, "COMMITTEE", SCOPE_ID, "委員会アンケート");

            // then
            ArgumentCaptor<BulletinThreadEntity> captor = ArgumentCaptor.forClass(BulletinThreadEntity.class);
            verify(bulletinThreadRepository).save(captor.capture());
            assertThat(captor.getValue().getScopeType()).isEqualTo(ScopeType.ORGANIZATION);
        }

        @Test
        @DisplayName("既にスレッドが存在する場合は重複作成しないこと（冪等性保証）")
        void shouldNotCreateDuplicateThread_whenAlreadyExists() {
            // given
            BulletinThreadEntity existingThread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("既存アンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(existingThread));

            // when
            BulletinThreadEntity result = service.createForSurvey(SURVEY_ID, "ORGANIZATION", SCOPE_ID, "既存アンケート");

            // then
            verify(bulletinThreadRepository, never()).save(any());
            assertThat(result).isSameAs(existingThread);
        }
    }

    // =====================================================================
    // lockForSurvey
    // =====================================================================

    @Nested
    @DisplayName("lockForSurvey — スレッドロック")
    class LockForSurvey {

        @Test
        @DisplayName("スレッドが存在する場合は isLocked=true に更新されること")
        void shouldLockThread_whenThreadExists() {
            // given
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("アンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            // isLocked のデフォルトは false
            assertThat(thread.getIsLocked()).isFalse();

            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(thread));
            given(bulletinThreadRepository.save(any(BulletinThreadEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            service.lockForSurvey(SURVEY_ID);

            // then
            assertThat(thread.getIsLocked()).isTrue();
            verify(bulletinThreadRepository).save(thread);
        }

        @Test
        @DisplayName("スレッドが存在しない場合は何もしないこと")
        void shouldDoNothing_whenThreadNotFound() {
            // given
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            // when
            service.lockForSurvey(SURVEY_ID);

            // then
            verify(bulletinThreadRepository, never()).save(any());
        }

        @Test
        @DisplayName("既にロック済みのスレッドは再ロックしないこと（toggleLock の二重呼び出し防止）")
        void shouldNotToggleLockTwice_whenAlreadyLocked() {
            // given
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("アンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .isLocked(true)
                    .build();

            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(thread));

            // when
            service.lockForSurvey(SURVEY_ID);

            // then: 既にロック済みのため save は呼ばれない
            verify(bulletinThreadRepository, never()).save(any());
            assertThat(thread.getIsLocked()).isTrue();
        }
    }

    // =====================================================================
    // findBySurveyId
    // =====================================================================

    @Nested
    @DisplayName("findBySurveyId — スレッド検索")
    class FindBySurveyId {

        @Test
        @DisplayName("存在するスレッドを返すこと")
        void shouldReturnThread_whenExists() {
            // given
            BulletinThreadEntity thread = BulletinThreadEntity.builder()
                    .scopeType(ScopeType.ORGANIZATION)
                    .scopeId(SCOPE_ID)
                    .title("アンケート — 掲示板")
                    .body("")
                    .sourceType(SURVEY_SOURCE_TYPE)
                    .sourceId(SURVEY_ID)
                    .build();
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(thread));

            // when
            Optional<BulletinThreadEntity> result = service.findBySurveyId(SURVEY_ID);

            // then
            assertThat(result).isPresent();
            assertThat(result.get()).isSameAs(thread);
        }

        @Test
        @DisplayName("存在しない場合は empty を返すこと")
        void shouldReturnEmpty_whenNotExists() {
            // given
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            // when
            Optional<BulletinThreadEntity> result = service.findBySurveyId(SURVEY_ID);

            // then
            assertThat(result).isEmpty();
        }
    }

    // =====================================================================
    // findThreadResponseBySurveyId — per-scope 認可（軍議④・F00漏洩根治）
    // =====================================================================

    @Nested
    @DisplayName("findThreadResponseBySurveyId — per-scope 認可")
    class FindThreadResponseBySurveyId {

        /**
         * スコープ・IDを持つスレッドのモックを生成する。
         * enrichSingle への委譲と accessGuard 引数検証で scopeType/scopeId を使う。
         */
        private BulletinThreadEntity threadWithScope(ScopeType scopeType, long scopeId) {
            BulletinThreadEntity thread = org.mockito.Mockito.mock(BulletinThreadEntity.class);
            given(thread.getScopeType()).willReturn(scopeType);
            given(thread.getScopeId()).willReturn(scopeId);
            return thread;
        }

        @Test
        @DisplayName("AC-1: 非所属ユーザーは COMMON_002(403) を受け取る（スレッドは存在する）")
        void shouldThrowForbidden_whenNotMember() {
            // given: スレッドは存在するが、当該ユーザーはそのスコープの非所属
            long currentUserId = 999L;
            BulletinThreadEntity thread = threadWithScope(ScopeType.TEAM, SCOPE_ID);
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(thread));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(currentUserId, ScopeType.TEAM, SCOPE_ID);

            // when / then: 認可違反が伝播する。enrichSingle は呼ばれない
            assertThatThrownBy(() -> service.findThreadResponseBySurveyId(SURVEY_ID, currentUserId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(ex -> ((BusinessException) ex).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(bulletinThreadService, never()).enrichSingle(any(), any());
        }

        @Test
        @DisplayName("AC-2: 所属メンバーは enrich 済みレスポンスを受け取り、スレッド自身のscopeでガードされる")
        void shouldReturnEnrichedResponse_whenMember() {
            // given: メンバー（checkMembership は void で何もしない）
            long currentUserId = 7L;
            BulletinThreadEntity thread = threadWithScope(ScopeType.TEAM, SCOPE_ID);
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.of(thread));
            ThreadResponse enriched = org.mockito.Mockito.mock(ThreadResponse.class);
            given(bulletinThreadService.enrichSingle(thread, currentUserId)).willReturn(enriched);

            // when
            Optional<ThreadResponse> result = service.findThreadResponseBySurveyId(SURVEY_ID, currentUserId);

            // then
            assertThat(result).isPresent();
            assertThat(result.get()).isSameAs(enriched);
            // スレッド自身の scopeType/scopeId でガードされること
            verify(accessGuard).checkMembership(currentUserId, ScopeType.TEAM, SCOPE_ID);
        }

        @Test
        @DisplayName("AC-3: スレッド未存在なら empty を返し、認可ガードは呼ばれない（既存404の非退行）")
        void shouldReturnEmptyAndNotGuard_whenThreadNotFound() {
            // given
            long currentUserId = 7L;
            given(bulletinThreadRepository.findBySourceTypeAndSourceIdAndDeletedAtIsNull(
                    SURVEY_SOURCE_TYPE, SURVEY_ID))
                    .willReturn(Optional.empty());

            // when
            Optional<ThreadResponse> result = service.findThreadResponseBySurveyId(SURVEY_ID, currentUserId);

            // then: 存在しないものは gate 前 → empty（コントローラで 404）。ガードは踏まない
            assertThat(result).isEmpty();
            verify(accessGuard, never()).checkMembership(any(), any(), any());
            verify(bulletinThreadService, never()).enrichSingle(any(), any());
        }
    }
}
