package com.mannschaft.app.faq.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.faq.FaqCategory;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

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
 * {@link FaqAdminService} の単体テスト（F21.1 §5.5、カテゴリ別固定質問対応）。
 *
 * <p>カテゴリ解決・取得ペイロード生成・バリデーション（カテゴリ外キー拒否含む）・
 * 固定 UPSERT / 自由差分適用のロジックを検証する。
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
    @Mock
    private AccessControlService accessControlService;
    // 実 Resolver を使う（純粋関数のためモック不要）。手動で実インスタンスを注入する。
    private final FaqCategoryResolver faqCategoryResolver = new FaqCategoryResolver();

    private FaqAdminService service;

    private static final Long TEAM_ID = 1L;
    private static final Long CURRENT_USER_ID = 100L;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        service = new FaqAdminService(
                faqRepository, teamRepository, organizationRepository, faqCategoryResolver, accessControlService);
        // SecurityUtils.getCurrentUserId() が参照する SecurityContext に認証済みユーザーを仕込む。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        String.valueOf(CURRENT_USER_ID), null, AuthorityUtils.NO_AUTHORITIES));
        // 既定では per-scope 認可を通す（既存 12 件の正常系を維持）。
        lenient().when(accessControlService.isSystemAdmin(CURRENT_USER_ID)).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * 指定 template を持つチームの存在を仕込む。
     *
     * @param template チームの template（カテゴリ解決に用いる。例: "CLUB"→SPORTS、null→GENERAL）
     */
    private void givenTeamExists(String template) {
        TeamEntity team = mock(TeamEntity.class);
        lenient().when(team.getDeletedAt()).thenReturn(null);
        lenient().when(team.getTemplate()).thenReturn(template);
        given(teamRepository.findById(TEAM_ID)).willReturn(Optional.of(team));
    }

    @Nested
    @DisplayName("getEditorPayload")
    class GetEditorPayload {

        @Test
        @DisplayName("正常系: GENERAL カテゴリ団体の固定6問を全件・自由質問を返す（未回答は answer=null）")
        void 固定6問と自由質問を返す() {
            givenTeamExists(null); // null → GENERAL
            FaqEntity activity = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionKey(FixedFaqQuestion.GENERAL_ACTIVITY.name())
                    .answerText("地域清掃をしています").displayOrder(1).build();
            FaqEntity custom = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionText("駐車場はありますか").answerText("あります").displayOrder(10).build();
            custom.setId(java.util.UUID.randomUUID());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of(activity, custom));

            FaqEditorResponse res = service.getEditorPayload(ScopeType.TEAM, TEAM_ID);

            assertThat(res.category()).isEqualTo(FaqCategory.GENERAL.name());
            assertThat(res.fixedQuestions()).hasSize(6);
            assertThat(res.fixedQuestions().get(0).questionKey())
                    .isEqualTo(FixedFaqQuestion.GENERAL_ACTIVITY.name());
            assertThat(res.fixedQuestions().get(0).answer()).isEqualTo("地域清掃をしています");
            // 2問目（GENERAL_LOCATION）は未回答 → answer=null
            assertThat(res.fixedQuestions().get(1).answer()).isNull();
            assertThat(res.customFaqs()).hasSize(1);
            assertThat(res.customFaqs().get(0).questionText()).isEqualTo("駐車場はありますか");
        }

        @Test
        @DisplayName("正常系: SPORTS カテゴリ団体（template=CLUB）は SPORTS の6問を displayOrder 順で返す")
        void スポーツカテゴリの6問を返す() {
            givenTeamExists("CLUB"); // CLUB → SPORTS
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            FaqEditorResponse res = service.getEditorPayload(ScopeType.TEAM, TEAM_ID);

            assertThat(res.category()).isEqualTo(FaqCategory.SPORTS.name());
            assertThat(res.fixedQuestions()).hasSize(6);
            assertThat(res.fixedQuestions())
                    .extracting(FaqEditorResponse.FixedFaqItem::questionKey)
                    .containsExactly(
                            FixedFaqQuestion.SPORTS_ACTIVITY.name(),
                            FixedFaqQuestion.SPORTS_LOCATION.name(),
                            FixedFaqQuestion.SPORTS_SCHEDULE.name(),
                            FixedFaqQuestion.SPORTS_JOIN.name(),
                            FixedFaqQuestion.SPORTS_COST.name(),
                            FixedFaqQuestion.SPORTS_LEVEL.name());
            assertThat(res.fixedQuestions()).allSatisfy(item -> assertThat(item.answer()).isNull());
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
            givenTeamExists(null);
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
            givenTeamExists(null);
            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer("UNKNOWN_KEY", "回答"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_002);
        }

        @Test
        @DisplayName("異常系: カテゴリ外の固定質問キー（GENERAL団体に SPORTS キー）で FAQ_002")
        void カテゴリ外の固定キー() {
            givenTeamExists(null); // GENERAL カテゴリ
            SaveFaqRequest req = new SaveFaqRequest();
            // SPORTS_ACTIVITY は実在するキーだが GENERAL 団体には属さない → FAQ_002
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.SPORTS_ACTIVITY.name(), "回答"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_002);
        }

        @Test
        @DisplayName("異常系: 固定質問キー重複で FAQ_003")
        void 固定キー重複() {
            givenTeamExists(null);
            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_ACTIVITY.name(), "回答1"));
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_ACTIVITY.name(), "回答2"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(FaqErrorCode.FAQ_003);
        }

        @Test
        @DisplayName("異常系: 自由質問の質問文が空で FAQ_004")
        void 自由質問文空() {
            givenTeamExists(null);
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
            givenTeamExists(null); // GENERAL
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, FixedFaqQuestion.GENERAL_ACTIVITY.name()))
                    .willReturn(Optional.empty());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_ACTIVITY.name(), "ボランティア活動"));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            ArgumentCaptor<FaqEntity> captor = ArgumentCaptor.forClass(FaqEntity.class);
            verify(faqRepository, atLeastOnce()).save(captor.capture());
            FaqEntity saved = captor.getAllValues().stream()
                    .filter(e -> FixedFaqQuestion.GENERAL_ACTIVITY.name().equals(e.getQuestionKey()))
                    .findFirst().orElseThrow();
            assertThat(saved.getAnswerText()).isEqualTo("ボランティア活動");
            assertThat(saved.getCreatedBy()).isEqualTo(100L);
            assertThat(saved.getDisplayOrder())
                    .isEqualTo(FixedFaqQuestion.GENERAL_ACTIVITY.displayOrder());
        }

        @Test
        @DisplayName("固定: answer 空かつ既存ありで論理削除")
        void 固定論理削除() {
            givenTeamExists(null);
            FaqEntity existing = FaqEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                    .questionKey(FixedFaqQuestion.GENERAL_ACTIVITY.name())
                    .answerText("旧回答").displayOrder(1).build();
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, FixedFaqQuestion.GENERAL_ACTIVITY.name()))
                    .willReturn(Optional.of(existing));
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_ACTIVITY.name(), ""));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            assertThat(existing.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("自由: リクエストに無い既存自由質問は論理削除される")
        void 自由質問差分削除() {
            givenTeamExists(null);
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
            givenTeamExists(null);
            given(faqRepository.findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
                    ScopeType.TEAM, TEAM_ID, FixedFaqQuestion.GENERAL_COST.name()))
                    .willReturn(Optional.empty());
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_COST.name(), null));

            service.save(ScopeType.TEAM, TEAM_ID, req, 100L);

            verify(faqRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("per-scope 認可（AccessControlService）")
    class ScopeAuthorization {

        @Test
        @DisplayName("異常系: 非管理者は getEditorPayload で COMMON_002（403相当）")
        void 非管理者は取得で403() {
            // checkAdminOrAbove が COMMON_002 を投げるよう仕込む（非管理者）
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(CURRENT_USER_ID, TEAM_ID, ScopeType.TEAM.name());

            assertThatThrownBy(() -> service.getEditorPayload(ScopeType.TEAM, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            // 認可で弾かれるため、本体処理（スコープ解決のための teamRepository 参照）には到達しない
            verify(teamRepository, never()).findById(any());
        }

        @Test
        @DisplayName("異常系: 非管理者は save で COMMON_002（403相当）")
        void 非管理者は保存で403() {
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService)
                    .checkAdminOrAbove(CURRENT_USER_ID, TEAM_ID, ScopeType.TEAM.name());

            SaveFaqRequest req = new SaveFaqRequest();
            req.getFixedAnswers().add(new SaveFaqRequest.FixedAnswer(
                    FixedFaqQuestion.GENERAL_ACTIVITY.name(), "回答"));

            assertThatThrownBy(() -> service.save(ScopeType.TEAM, TEAM_ID, req, CURRENT_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            // 認可で弾かれるため、永続化（save）には到達しない
            verify(faqRepository, never()).save(any());
        }

        @Test
        @DisplayName("正常系: 当該スコープの管理者は getEditorPayload が通る")
        void 管理者は取得が通る() {
            // checkAdminOrAbove は no-op（既定）= 管理者として通過
            givenTeamExists(null);
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            FaqEditorResponse res = service.getEditorPayload(ScopeType.TEAM, TEAM_ID);

            assertThat(res.fixedQuestions()).hasSize(6);
            verify(accessControlService).checkAdminOrAbove(CURRENT_USER_ID, TEAM_ID, ScopeType.TEAM.name());
        }

        @Test
        @DisplayName("正常系: SYSTEM_ADMIN は checkAdminOrAbove を経ず全スコープで通る")
        void システム管理者は短絡で通る() {
            given(accessControlService.isSystemAdmin(CURRENT_USER_ID)).willReturn(true);
            givenTeamExists(null);
            given(faqRepository.findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
                    ScopeType.TEAM, TEAM_ID)).willReturn(List.of());

            FaqEditorResponse res = service.getEditorPayload(ScopeType.TEAM, TEAM_ID);

            assertThat(res.fixedQuestions()).hasSize(6);
            // SYSTEM_ADMIN は短絡許可のため checkAdminOrAbove は呼ばれない
            verify(accessControlService, never()).checkAdminOrAbove(any(), any(), any());
        }
    }
}
