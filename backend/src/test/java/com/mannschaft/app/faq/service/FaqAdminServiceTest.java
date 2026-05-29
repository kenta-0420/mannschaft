package com.mannschaft.app.faq.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.faq.FixedFaqQuestion;
import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.dto.FaqEditorResponse;
import com.mannschaft.app.faq.dto.SaveFaqRequest;
import com.mannschaft.app.faq.entity.FaqEntity;
import com.mannschaft.app.faq.error.FaqErrorCode;
import com.mannschaft.app.faq.repository.FaqRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link FaqAdminService} の単体テスト（F21.1 §5.5）。
 *
 * <p>取得ペイロード生成・バリデーション・固定 UPSERT / 自由差分適用のロジックを検証する。
 * 本格的な統合テストは後続の足軽が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FaqAdminService 単体テスト")
class FaqAdminServiceTest {

    @Mock
    private FaqRepository faqRepository;
    @Mock
    private TeamRepository teamRepository;
    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private FaqAdminService service;

    private static final Long TEAM_ID = 1L;

    private void givenTeamExists() {
        TeamEntity team = mock(TeamEntity.class);
        lenient().when(team.getDeletedAt()).thenReturn(null);
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
    }

    @Nested
    @DisplayName("getEditorPayload")
    class GetEditorPayload {

        @Test
        @DisplayName("正常系: 固定6問を全件・自由質問を返す（未回答は answer=null）")
        void 固定6問と自由質問を返す() {
            givenTeamExists();
            FaqEntity activity = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionKey(FixedFaqQuestion.ACTIVITY.name())
                    .answerText("サッカーをしています").displayOrder(1).build();
            FaqEntity custom = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionText("駐車場はありますか").answerText("あります").displayOrder(10).build();
            custom.setId(java.util.UUID.randomUUID());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of(activity, custom));

            FaqEditorResponse res = service.getEditorPayload(ScopeType.TEAM, TEAM_ID);

            assertThat(res.fixedQuestions()).hasSize(6);
            assertThat(res.fixedQuestions().get(0).questionKey())
                    .isEqualTo(FixedFaqQuestion.ACTIVITY.name());
            assertThat(res.fixedQuestions().get(0).answer()).isEqualTo("サッカーをしています");
            // LOCATION は未回答 → answer=null
            assertThat(res.fixedQuestions().get(1).answer()).isNull();
            assertThat(res.customFaqs()).hasSize(1);
            assertThat(res.customFaqs().get(0).questionText()).isEqualTo("駐車場はありますか");
        }

        @Test
        @DisplayName("異常系: 対象チーム不在で FAQ_010")
        void 対象不在で404() {
            given(teamRepository.findById(TEAM_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEditorPayload(ScopeType.TEAM, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_010);
        }
    }

    @Nested
    @DisplayName("save バリデーション")
    class SaveValidation {

        @Test
        @DisplayName("異常系: 自由質問が8件で FAQ_001")
        void 自由質問上限超過() {
            givenTeamExists();
            SaveFaqRequest req = new SaveFaqRequest();
            for (int i = 0; i < 8; i++) {
                req.getCustomFaqs().add(new SaveFaqRequest.CustomFaqInput(null, "Q" + i, "A" + i, i));
            }

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_001);
        }

        @Test
        @DisplayName("異常系: 不正な固定質問キーで FAQ_002")
        void 不正な固定キー() {
            givenTeamExists();
            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("UNKNOWN_KEY", "回答"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_002);
        }

        @Test
        @DisplayName("異常系: 固定質問キー重複で FAQ_003")
        void 固定キー重複() {
            givenTeamExists();
            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("ACTIVITY", "回答1"));
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("ACTIVITY", "回答2"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_003);
        }

        @Test
        @DisplayName("異常系: 自由質問の質問文が空で FAQ_004")
        void 自由質問文空() {
            givenTeamExists();
            SaveFaqRequest req = new SaveFaqRequest();
            req.getCustomFaqs().add(new SaveFaqRequest.CustomFaqInput(null, "   ", "回答", 0));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_004);
        }
    }

    @Nested
    @DisplayName("save 保存ロジック")
    class SaveLogic {

        @Test
        @DisplayName("固定: answer 非空かつ既存なしで新規作成（createdBy=操作ユーザー）")
        void 固定新規作成() {
            givenTeamExists();
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, "ACTIVITY")).willReturn(Optional.empty());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("ACTIVITY", "サッカー"));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            ArgumentCaptor<FaqEntity> captor = ArgumentCaptor.forClass(FaqEntity.class);
            verify(faqRepository, atLeastOnce()).save(captor.capture());
            FaqEntity saved = captor.getAllValues().stream()
                    .filter(e -> "ACTIVITY".equals(e.getQuestionKey())).findFirst().orElseThrow();
            assertThat(saved.getAnswerText()).isEqualTo("サッカー");
            assertThat(saved.getCreatedBy()).isEqualTo(100L);
            assertThat(saved.getDisplayOrder()).isEqualTo(FixedFaqQuestion.ACTIVITY.displayOrder());
        }

        @Test
        @DisplayName("固定: answer 空かつ既存ありで論理削除")
        void 固定論理削除() {
            givenTeamExists();
            FaqEntity existing = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionKey("ACTIVITY").answerText("旧回答").displayOrder(1).build();
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, "ACTIVITY")).willReturn(Optional.of(existing));
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("ACTIVITY", ""));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            assertThat(existing.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("自由: リクエストに無い既存自由質問は論理削除される")
        void 自由質問差分削除() {
            givenTeamExists();
            FaqEntity orphan = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionText("消える質問").answerText("回答").displayOrder(10).build();
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of(orphan));

            SaveFaqRequest req = new SaveFaqRequest(); // customFaqs 空 → 既存は全削除

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            assertThat(orphan.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("固定: answer 空かつ既存なしでは何も保存しない")
        void 固定空既存なしでノーオペ() {
            givenTeamExists();
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, "COST")).willReturn(Optional.empty());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("COST", null));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            verify(faqRepository, never()).save(any());
        }
    }
}
