package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityParticipantEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityParticipantRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.activity.service.ActivityTemplateService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityResultService 単体テスト")
class ActivityResultServiceTest {

    @Mock private ActivityResultRepository resultRepository;
    @Mock private ActivityParticipantRepository participantRepository;
    @Mock private ActivityTemplateService templateService;
    @Mock private ActivityMapper activityMapper;
    @Mock private ObjectMapper objectMapper;
    @Mock private ContentVisibilityChecker contentVisibilityChecker;
    @Mock private com.mannschaft.app.activity.service.ActivityScopeAccessGuard scopeAccessGuard;

    @InjectMocks
    private ActivityResultService service;

    private static final Long ACTIVITY_ID = 10L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 1L;

    @Nested
    @DisplayName("createActivity")
    class CreateActivity {

        @Test
        @DisplayName("正常系: 活動記録が作成される")
        void 作成_正常_保存() {
            CreateActivityRequest request = new CreateActivityRequest(
                    1L, "練習", LocalDate.now(), null, null, null, null, null, null, null, null, null);
            ActivityResultEntity saved = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("練習").build();
            given(resultRepository.save(any())).willReturn(saved);

            ActivityResultEntity result = service.createActivity(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request);
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 終了時刻が開始時刻より前でACTIVITY_020例外")
        void 作成_時刻不正_例外() {
            CreateActivityRequest request = new CreateActivityRequest(
                    1L, "練習", LocalDate.now(), LocalTime.of(15, 0), LocalTime.of(10, 0),
                    null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createActivity(USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_020"));
        }
    }

    @Nested
    @DisplayName("deleteActivity")
    class DeleteActivity {
        @Test
        @DisplayName("正常系: 活動記録が論理削除される")
        void 削除_正常_論理削除() {
            ActivityResultEntity entity = ActivityResultEntity.builder().title("テスト").build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(entity));
            service.deleteActivity(ACTIVITY_ID, USER_ID);
            verify(resultRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: 活動記録不在でACTIVITY_001例外")
        void 削除_不在_例外() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteActivity(ACTIVITY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_001"));
        }
    }

    @Nested
    @DisplayName("duplicateActivity")
    class DuplicateActivity {

        private ActivityResultEntity teamOriginal() {
            return ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("元活動").build();
        }

        // AC-1: 他スコープ会員は複製不可（IDOR封じ）
        @Test
        @DisplayName("duplicateActivity_他スコープ会員は403（COMMON_002）")
        void 複製_他スコープ_403() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamOriginal()));
            org.mockito.BDDMockito.willThrow(new BusinessException(
                    com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);

            assertThatThrownBy(() -> service.duplicateActivity(ACTIVITY_ID, USER_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        // AC-2: 自スコープ会員は従来通り複製成功（非回帰）
        @Test
        @DisplayName("duplicateActivity_自スコープ会員は成功（非回帰）")
        void 複製_自スコープ_成功() {
            ActivityResultEntity original = teamOriginal();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(original));
            given(resultRepository.save(any())).willReturn(original);
            given(participantRepository.findByActivityResultIdOrderByCreatedAtAsc(ACTIVITY_ID))
                    .willReturn(List.of());

            ActivityResultEntity result = service.duplicateActivity(ACTIVITY_ID, USER_ID, null);
            assertThat(result).isNotNull();
            verify(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
        }
    }

    /**
     * 公開活動記録一覧（匿名）の単体テスト。
     *
     * <p><b>書き換えの理由</b>: 旧テストは
     * {@code findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc}（スコープ配下の全行）を
     * モックし、「取得後にメモリで F00 フィルタする」旧実装をそのまま写していた。
     * この構造こそが<b>ページング歯抜け</b>（1 ページ分を取ってから落とすので補充されない）の
     * 原因だったため、SQL 述語（{@code findPublicByScopeTypeAndScopeId}）に載せ替えた
     * 新実装に合わせて書き換える。F00 は<b>第二の門</b>として残り、通常は 1 件も落とさない。</p>
     */
    @Nested
    @DisplayName("listPublicActivities")
    class ListPublicActivities {

        private ActivityResultEntity entityWithId(long id, String title) {
            ActivityResultEntity e = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title(title).build();
            ReflectionTestUtils.setField(e, "id", id);
            return e;
        }

        @Test
        @DisplayName("SQL述語で絞った行はF00（第二の門）も全件通し、総件数はSQLの実総数がそのまま出る")
        void SQL述語で絞った行はF00も全件通す() {
            ActivityResultEntity a = entityWithId(1L, "公開活動A");
            ActivityResultEntity b = entityWithId(2L, "公開活動B");

            // SQL 述語（visibility=PUBLIC AND status=PUBLISHED）で既に絞られた 2 件。
            // 総件数 55 = 公開記録の実総数（歯抜けもページ内件数への化けも無い）。
            given(resultRepository.findPublicByScopeTypeAndScopeId(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 10), 55L));
            // 第二の門（userId=null = 未認証）。SQL と一致するので 1 件も落とさないのが正常。
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.ACTIVITY_RESULT, Set.of(1L, 2L), null))
                    .willReturn(Set.of(1L, 2L));

            Page<ActivityResultEntity> result = service.listPublicActivities(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10));

            assertThat(result.getContent())
                    .extracting(ActivityResultEntity::getId)
                    .containsExactly(1L, 2L);
            assertThat(result.getTotalElements())
                    .as("総件数は SQL が算出した実総数（ページ内件数 2 に化けない）")
                    .isEqualTo(55L);
        }

        @Test
        @DisplayName("F00がSQL述語と乖離した場合はfail-closed（落とした行は返さず総件数からも引く）")
        void F00と乖離したらfail_closedで落とす() {
            ActivityResultEntity a = entityWithId(1L, "公開活動A");
            ActivityResultEntity b = entityWithId(2L, "乖離した活動B");

            given(resultRepository.findPublicByScopeTypeAndScopeId(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(new PageImpl<>(List.of(a, b), PageRequest.of(0, 10), 55L));
            // 本来ありえない乖離（SQL は通したが F00 が拒否）。fail-closed で返さない。
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.ACTIVITY_RESULT, Set.of(1L, 2L), null))
                    .willReturn(Set.of(1L));

            Page<ActivityResultEntity> result = service.listPublicActivities(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10));

            assertThat(result.getContent())
                    .as("F00 が落とした行は返さない（fail-closed を維持）")
                    .extracting(ActivityResultEntity::getId)
                    .containsExactly(1L);
            assertThat(result.getTotalElements())
                    .as("乖離して落とした 1 件は総件数からも差し引く")
                    .isEqualTo(54L);
        }

        @Test
        @DisplayName("空ページ: リポジトリが空なら Checker を呼ばず空ページを返す")
        void 空ページ_Checker不呼び出し_空返却() {
            given(resultRepository.findPublicByScopeTypeAndScopeId(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10)))
                    .willReturn(Page.empty());

            Page<ActivityResultEntity> result = service.listPublicActivities(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 10));

            assertThat(result.getContent()).isEmpty();
            verify(contentVisibilityChecker, never())
                    .filterAccessible(any(), any(), any());
        }
    }

    // ========================================================================
    // F06.4 下書き（DRAFT）対応 — AC-8 / AC-9 / AC-10
    // ========================================================================

    @Nested
    @DisplayName("createDraftActivity（AC-8）")
    class CreateDraftActivity {

        @Test
        @DisplayName("AC-8 正常系: title+activityDateのみ（テンプレート未指定）でDRAFT作成できる")
        void 下書き作成_最小項目_status_DRAFT() {
            // 最小項目: title + activityDate のみ、templateId は null
            com.mannschaft.app.activity.dto.CreateDraftActivityRequest request =
                    new com.mannschaft.app.activity.dto.CreateDraftActivityRequest(
                            "下書き活動", LocalDate.now(), null, null, null, null, null, null);
            given(resultRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ActivityResultEntity result = service.createDraftActivity(
                    USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request);

            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(ActivityStatus.DRAFT);
            assertThat(result.getTitle()).isEqualTo("下書き活動");
            assertThat(result.getTemplateId()).isNull();
        }

        @Test
        @DisplayName("AC-8 異常系: 時刻不正でACTIVITY_020例外")
        void 下書き作成_時刻不正_例外() {
            com.mannschaft.app.activity.dto.CreateDraftActivityRequest request =
                    new com.mannschaft.app.activity.dto.CreateDraftActivityRequest(
                            "下書き活動", LocalDate.now(), null, LocalTime.of(15, 0), LocalTime.of(10, 0),
                            null, null, null);

            assertThatThrownBy(() -> service.createDraftActivity(
                    USER_ID, ActivityScopeType.TEAM, SCOPE_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_020"));
        }
    }

    @Nested
    @DisplayName("publishActivity（AC-9）")
    class PublishActivity {

        @Test
        @DisplayName("AC-9 正常系: DRAFT を publish すると PUBLISHED になる")
        void 公開_DRAFTからPUBLISHED() {
            ActivityResultEntity draft = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("下書き")
                    .status(ActivityStatus.DRAFT).createdBy(USER_ID).build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(draft));
            given(resultRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

            ActivityResultEntity result = service.publishActivity(ACTIVITY_ID, USER_ID);

            assertThat(result.getStatus()).isEqualTo(ActivityStatus.PUBLISHED);
            verify(resultRepository).save(draft);
            // 作成者本人でも認可ガードは必ず経由する（本人判定はガード内部の責務）
            verify(scopeAccessGuard).checkAuthorOrAdmin(
                    USER_ID, USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
        }

        @Test
        @DisplayName("AC-9 異常系: 既に PUBLISHED のものを publish すると ACTIVITY_021（400相当）")
        void 公開_既にPUBLISHED_例外() {
            ActivityResultEntity published = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("公開済み")
                    .status(ActivityStatus.PUBLISHED).createdBy(USER_ID).build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(published));

            assertThatThrownBy(() -> service.publishActivity(ACTIVITY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_021"));
        }
    }

    @Nested
    @DisplayName("DRAFT 可視性（AC-10）")
    class DraftVisibility {

        @Test
        @DisplayName("AC-10 正常系: DRAFT は作成者本人なら詳細取得できる")
        void 詳細_DRAFT_作成者本人_取得可() {
            ActivityResultEntity draft = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("下書き")
                    .status(ActivityStatus.DRAFT).createdBy(USER_ID).build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(draft));

            ActivityResultEntity result = service.getActivity(ACTIVITY_ID, USER_ID);
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo(ActivityStatus.DRAFT);
        }

        @Test
        @DisplayName("AC-10 異常系: DRAFT は作成者でない会員（非管理者）には ACTIVITY_001（不在扱い）")
        void 詳細_DRAFT_他人_不在扱い() {
            Long otherUser = 999L;
            ActivityResultEntity draft = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("下書き")
                    .status(ActivityStatus.DRAFT).createdBy(USER_ID).build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(draft));
            // 会員だが管理者ではない
            given(scopeAccessGuard.isAdminOrAbove(otherUser, ActivityScopeType.TEAM, SCOPE_ID)).willReturn(false);

            assertThatThrownBy(() -> service.getActivity(ACTIVITY_ID, otherUser))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_001"));
        }

        @Test
        @DisplayName("AC-10 正常系: スコープ一覧はF00 Checkerで閲覧不可（他人のDRAFT）を除外する")
        void 一覧_DRAFT除外_F00経由() {
            ActivityResultEntity published = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("公開活動")
                    .status(ActivityStatus.PUBLISHED).createdBy(USER_ID).build();
            ActivityResultEntity otherDraft = ActivityResultEntity.builder()
                    .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("他人の下書き")
                    .status(ActivityStatus.DRAFT).createdBy(999L).build();
            ReflectionTestUtils.setField(published, "id", 1L);
            ReflectionTestUtils.setField(otherDraft, "id", 2L);

            given(resultRepository.findByScopeTypeAndScopeIdOrderByActivityDateDescIdDesc(
                    ActivityScopeType.TEAM, SCOPE_ID, PageRequest.of(0, 20)))
                    .willReturn(new PageImpl<>(List.of(published, otherDraft)));
            // F00: PUBLISHED(1L) のみ可視、他人の DRAFT(2L) は除外
            given(contentVisibilityChecker.filterAccessible(
                    ReferenceType.ACTIVITY_RESULT, Set.of(1L, 2L), USER_ID))
                    .willReturn(Set.of(1L));

            Page<ActivityResultEntity> result = service.listActivities(
                    USER_ID, ActivityScopeType.TEAM, SCOPE_ID, null, PageRequest.of(0, 20));

            assertThat(result.getContent())
                    .extracting(ActivityResultEntity::getId)
                    .containsExactly(1L);
        }
    }

    @Nested
    @DisplayName("addParticipants")
    class AddParticipants {
        @Test
        @DisplayName("正常系: 重複参加者はスキップされる")
        void 追加_重複スキップ() {
            ActivityResultEntity entity = ActivityResultEntity.builder().title("テスト").build();
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(entity));
            given(participantRepository.findByActivityResultIdAndUserId(ACTIVITY_ID, 1L))
                    .willReturn(Optional.of(ActivityParticipantEntity.builder().build()));
            given(participantRepository.findByActivityResultIdOrderByCreatedAtAsc(ACTIVITY_ID)).willReturn(List.of());
            given(activityMapper.toParticipantResponseList(any())).willReturn(List.of());

            AddParticipantsRequest request = new AddParticipantsRequest(List.of(1L), null);

            service.addParticipants(ACTIVITY_ID, USER_ID, request);
            // No new save since already exists
        }
    }
}
