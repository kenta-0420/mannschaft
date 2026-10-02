package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.ReflectionVocabCardsResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ReflectionVocabCardService} 単体テスト（F06.5 Phase 4・§13-F・EP #23）。
 *
 * <p>カバー AC: AC-57（期間内 TERM_CARD 抽出・OUTLINE 無視）/ AC-58（themeId/sourceType/subject フィルタ）/
 * AC-59（recall_attempts 非書込）/ AC-60（期間 366 日超 400・本人スコープ）/ AC-61（0 件で空）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionVocabCardService 単体テスト（§13-F EP #23）")
class ReflectionVocabCardServiceTest {

    private static final Long USER_ID = 100L;
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private RecallAttemptRepository recallAttemptRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ReflectionVocabCardService service;

    @BeforeEach
    void setUp() {
        ReflectionContentSanitizer sanitizer = new ReflectionContentSanitizer(objectMapper);
        service = new ReflectionVocabCardService(entryRepository, themeRepository, sanitizer);
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionThemeEntity theme(UUID id, String title, ReflectionSourceType st, String subject) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title(title).sourceType(st).build();
        if (subject != null) {
            t.setLinkedSubject(subject, null);
        }
        setId(t, id);
        return t;
    }

    private ReflectionEntryEntity entry(UUID themeId, LocalDate date, String json) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(themeId).userId(USER_ID).targetDate(date)
                .structuredContent(json).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private String termCardJson(String heading, String term, String meaning) {
        return "{\"main_theme\":\"x\",\"sections\":["
                + "{\"type\":\"TERM_CARD\",\"heading\":\"" + heading + "\",\"cards\":["
                + "{\"term\":\"" + term + "\",\"meaning\":\"" + meaning + "\"}]}]}";
    }

    private String outlineJson() {
        return "{\"main_theme\":\"x\",\"sections\":["
                + "{\"type\":\"OUTLINE\",\"heading\":\"見出し\",\"subsections\":["
                + "{\"sub_heading\":\"sh\",\"detail\":\"d\"}]}]}";
    }

    @Test
    @DisplayName("AC-57: 期間内エントリの TERM_CARD から cards を抽出し OUTLINE を無視する")
    void extractsTermCards_ignoresOutline() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英単語 Unit 5", ReflectionSourceType.SUBJECT, "英語")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(themeId, LocalDate.of(2026, 6, 10),
                                termCardJson("今日の単語", "abandon", "見捨てる")),
                        entry(themeId, LocalDate.of(2026, 6, 11), outlineJson())));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards()).hasSize(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("abandon");
        assertThat(resp.cards().get(0).meaning()).isEqualTo("見捨てる");
        assertThat(resp.cards().get(0).themeTitle()).isEqualTo("英単語 Unit 5");
        assertThat(resp.cards().get(0).themeId()).isEqualTo(themeId.toString());
        assertThat(resp.cards().get(0).sectionHeading()).isEqualTo("今日の単語");
    }

    @Test
    @DisplayName("AC-58: themeId フィルタで特定テーマのカードだけに絞られる")
    void filterByThemeId() {
        UUID theme1 = UUID.randomUUID();
        UUID theme2 = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(theme1, "英語", ReflectionSourceType.SUBJECT, "英語"),
                        theme(theme2, "数学", ReflectionSourceType.SUBJECT, "数学")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(theme1, LocalDate.of(2026, 6, 10), termCardJson("h", "en", "意味")),
                        entry(theme2, LocalDate.of(2026, 6, 11), termCardJson("h", "math", "意味"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, theme1, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("en");
    }

    @Test
    @DisplayName("AC-58: subject フィルタで科目名一致のカードだけに絞られる（単数 subjects リスト）")
    void filterBySubject() {
        UUID theme1 = UUID.randomUUID();
        UUID theme2 = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(theme1, "英語", ReflectionSourceType.SUBJECT, "英語"),
                        theme(theme2, "数学", ReflectionSourceType.SUBJECT, "数学")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(theme1, LocalDate.of(2026, 6, 10), termCardJson("h", "en", "意味")),
                        entry(theme2, LocalDate.of(2026, 6, 11), termCardJson("h", "math", "意味"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, List.of("数学"), null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("math");
    }

    @Test
    @DisplayName("AC-58: sourceType フィルタで該当 source_type のカードだけに絞られる（単数 sourceTypes リスト）")
    void filterBySourceType() {
        UUID subjectTheme = UUID.randomUUID();
        UUID projectTheme = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(subjectTheme, "英語", ReflectionSourceType.SUBJECT, "英語"),
                        theme(projectTheme, "案件A", ReflectionSourceType.PROJECT, null)));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(subjectTheme, LocalDate.of(2026, 6, 10), termCardJson("h", "subjectCard", "意味")),
                        entry(projectTheme, LocalDate.of(2026, 6, 11), termCardJson("h", "projectCard", "意味"))));

        ReflectionVocabCardsResponse resp = service.getVocabCards(
                USER_ID, FROM, TO, null, null, List.of(ReflectionSourceType.PROJECT), false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("projectCard");
    }

    @Test
    @DisplayName("AC-58: NULL フィルタは条件に含まれない（全カード返る）")
    void nullFilters_includeAll() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語", ReflectionSourceType.SUBJECT, "英語")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(themeId, LocalDate.of(2026, 6, 10), termCardJson("h", "a", "意味")),
                        entry(themeId, LocalDate.of(2026, 6, 11), termCardJson("h", "b", "意味"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-59: recall_attempts への INSERT が一切発生しない（閲覧専用・スケジュール想起と独立）")
    void noRecallAttemptWrite() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語", ReflectionSourceType.SUBJECT, "英語")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(entry(themeId, LocalDate.of(2026, 6, 10),
                        termCardJson("h", "a", "意味"))));

        service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        // RecallAttemptRepository は本サービスに注入もされていない（マスク状態を変えない）。
        verifyNoInteractions(recallAttemptRepository);
    }

    @Test
    @DisplayName("AC-60: 期間幅 367 日で 400（REFLECTION_015）")
    void dateRangeTooWide_throws() {
        LocalDate to = FROM.plusDays(366); // 両端含むと 367 日
        assertThatThrownBy(() ->
                service.getVocabCards(USER_ID, FROM, to, null, null, null, false, 0, 200))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_DATE_RANGE_INVALID);
    }

    @Test
    @DisplayName("AC-60: from > to は 400（REFLECTION_015）")
    void fromAfterTo_throws() {
        assertThatThrownBy(() ->
                service.getVocabCards(USER_ID, TO, FROM, null, null, null, false, 0, 200))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_DATE_RANGE_INVALID);
    }

    @Test
    @DisplayName("AC-60: 他人所有テーマのエントリ（本人テーマに無い themeId）は結果に含まれない")
    void otherUsersCards_excluded() {
        UUID myTheme = UUID.randomUUID();
        UUID foreignThemeId = UUID.randomUUID();
        // 本人のテーマ一覧には myTheme のみ。
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(myTheme, "英語", ReflectionSourceType.SUBJECT, "英語")));
        // エントリには他テーマ参照のものが混入していても、本人テーマで解決できないため無視される。
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(myTheme, LocalDate.of(2026, 6, 10), termCardJson("h", "mine", "意味")),
                        entry(foreignThemeId, LocalDate.of(2026, 6, 11), termCardJson("h", "leak", "意味"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("mine");
    }

    @Test
    @DisplayName("AC-61: 期間内 TERM_CARD 0 件で totalCards=0・cards=[]")
    void empty_zeroCards() {
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID)).willReturn(List.of());
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of());

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isZero();
        assertThat(resp.cards()).isEmpty();
        assertThat(resp.from()).isEqualTo(FROM);
        assertThat(resp.to()).isEqualTo(TO);
    }

    @Test
    @DisplayName("ページング: page/size でスライスされ totalCards は全件を示す")
    void paginates() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語", ReflectionSourceType.SUBJECT, "英語")));
        // 1 エントリに 3 枚のカード。
        String json = "{\"main_theme\":\"x\",\"sections\":[{\"type\":\"TERM_CARD\",\"heading\":\"h\",\"cards\":["
                + "{\"term\":\"a\",\"meaning\":\"1\"},{\"term\":\"b\",\"meaning\":\"2\"},{\"term\":\"c\",\"meaning\":\"3\"}]}]}";
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(entry(themeId, LocalDate.of(2026, 6, 10), json)));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 1, 2);

        assertThat(resp.totalCards()).isEqualTo(3);
        assertThat(resp.page()).isEqualTo(1);
        assertThat(resp.size()).isEqualTo(2);
        assertThat(resp.cards()).hasSize(1); // 2 枚目以降の残り 1 枚
        assertThat(resp.cards().get(0).term()).isEqualTo("c");
    }

    // ===== Phase 4.1: AC-62/63/65/68 追加フィルタ＆シャッフル =====

    @Test
    @DisplayName("AC-62: subjects（複数）で OR フィルタが機能し、指定外の科目は含まれない")
    void testGetVocabCards_subjectsFilter_orSemantics() {
        UUID theme1 = UUID.randomUUID();
        UUID theme2 = UUID.randomUUID();
        UUID theme3 = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(theme1, "英語テーマ", ReflectionSourceType.SUBJECT, "英語"),
                        theme(theme2, "理科テーマ", ReflectionSourceType.SUBJECT, "理科"),
                        theme(theme3, "数学テーマ", ReflectionSourceType.SUBJECT, "数学")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(theme1, LocalDate.of(2026, 6, 10), termCardJson("h", "english", "英語")),
                        entry(theme2, LocalDate.of(2026, 6, 11), termCardJson("h", "science", "理科")),
                        entry(theme3, LocalDate.of(2026, 6, 12), termCardJson("h", "math", "数学"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, List.of("英語", "理科"), null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(2);
        assertThat(resp.cards()).extracting(com.mannschaft.app.reflection.dto.ReflectionVocabCardItem::term)
                .containsExactlyInAnyOrder("english", "science");
    }

    @Test
    @DisplayName("AC-62: subjects=null で全教科のカードが返る")
    void testGetVocabCards_subjects_emptyMeansAll() {
        UUID theme1 = UUID.randomUUID();
        UUID theme2 = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(theme1, "英語テーマ", ReflectionSourceType.SUBJECT, "英語"),
                        theme(theme2, "数学テーマ", ReflectionSourceType.SUBJECT, "数学")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(theme1, LocalDate.of(2026, 6, 10), termCardJson("h", "english", "英語")),
                        entry(theme2, LocalDate.of(2026, 6, 11), termCardJson("h", "math", "数学"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-63: shuffle=true で返却カードが null でなく 0 件ではない（シャッフル自体の正常動作）")
    void testGetVocabCards_shuffle_true() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語テーマ", ReflectionSourceType.SUBJECT, "英語")));
        String json = "{\"main_theme\":\"x\",\"sections\":[{\"type\":\"TERM_CARD\",\"heading\":\"h\",\"cards\":["
                + "{\"term\":\"a\",\"meaning\":\"1\"},{\"term\":\"b\",\"meaning\":\"2\"},{\"term\":\"c\",\"meaning\":\"3\"}]}]}";
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(entry(themeId, LocalDate.of(2026, 6, 10), json)));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, true, 0, 200);

        assertThat(resp.cards()).isNotNull().hasSize(3);
        assertThat(resp.totalCards()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC-63: shuffle=true + page=1 でも page=0 と同じ全件が返る（ページング無効）")
    void testGetVocabCards_shuffle_paginationDisabled() {
        UUID themeId = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(theme(themeId, "英語テーマ", ReflectionSourceType.SUBJECT, "英語")));
        String json = "{\"main_theme\":\"x\",\"sections\":[{\"type\":\"TERM_CARD\",\"heading\":\"h\",\"cards\":["
                + "{\"term\":\"a\",\"meaning\":\"1\"},{\"term\":\"b\",\"meaning\":\"2\"},{\"term\":\"c\",\"meaning\":\"3\"}]}]}";
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(entry(themeId, LocalDate.of(2026, 6, 10), json)));

        // shuffle=true, page=1 でも全 3 件返る（ページング無効）
        ReflectionVocabCardsResponse page0 =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, true, 0, 200);
        ReflectionVocabCardsResponse page1 =
                service.getVocabCards(USER_ID, FROM, TO, null, null, null, true, 1, 200);

        assertThat(page0.cards()).hasSize(3);
        assertThat(page1.cards()).hasSize(3); // shuffle=true はページング無効
    }

    @Test
    @DisplayName("AC-65: sourceTypes（List）で OR フィルタが機能する")
    void testGetVocabCards_sourceTypesFilter() {
        UUID diaryTheme = UUID.randomUUID();
        UUID projectTheme = UUID.randomUUID();
        UUID subjectTheme = UUID.randomUUID();
        given(themeRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                .willReturn(List.of(
                        theme(diaryTheme, "日記テーマ", ReflectionSourceType.DIARY, null),
                        theme(projectTheme, "案件テーマ", ReflectionSourceType.PROJECT, null),
                        theme(subjectTheme, "科目テーマ", ReflectionSourceType.SUBJECT, "英語")));
        given(entryRepository.findByUserIdAndTargetDateBetween(USER_ID, FROM, TO))
                .willReturn(List.of(
                        entry(diaryTheme, LocalDate.of(2026, 6, 10), termCardJson("h", "diary", "日記")),
                        entry(projectTheme, LocalDate.of(2026, 6, 11), termCardJson("h", "project", "案件")),
                        entry(subjectTheme, LocalDate.of(2026, 6, 12), termCardJson("h", "subject", "科目"))));

        ReflectionVocabCardsResponse resp =
                service.getVocabCards(USER_ID, FROM, TO, null, null, List.of(ReflectionSourceType.DIARY), false, 0, 200);

        assertThat(resp.totalCards()).isEqualTo(1);
        assertThat(resp.cards().get(0).term()).isEqualTo("diary");
    }
}
