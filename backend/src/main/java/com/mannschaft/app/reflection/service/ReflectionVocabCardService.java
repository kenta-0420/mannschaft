package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.ReflectionVocabCardItem;
import com.mannschaft.app.reflection.dto.ReflectionVocabCardsResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 期間横断 単語帳ビューのサービス（F06.5 Phase 4・§13-F・EP #23）。
 *
 * <p>指定期間（{@code from}〜{@code to}）内の<b>本人の</b>エントリを {@code idx_reflection_entries_user_date}
 * 経路で取得し、TERM_CARD section の {@code cards[]} をアプリ層で抽出して出典メタを付与する。
 * フィルタ（{@code themeId}/{@code sourceType}/{@code subject}）はテーマ属性で適用する。</p>
 *
 * <p><b>AC-59</b>: このビューは {@code recall_attempts} を一切書き込まず、マスク状態も変えない（閲覧専用）。
 * <b>AC-60</b>: 本人スコープ（{@code user_id = currentUserId}）で他人のカードは返さない。
 * <b>AC-60</b>: 期間幅 366 日超は 400（REFLECTION_015）。</p>
 *
 * <p>同一 reflection ドメイン内の entry/theme リポジトリのみを参照するため {@code @Transactional} は
 * ドメイン内に閉じる（原則 5）。読み取り専用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionVocabCardService {

    private static final String SECTION_TYPE_TERM_CARD = "TERM_CARD";

    private final ReflectionEntryRepository reflectionEntryRepository;
    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionContentSanitizer contentSanitizer;

    /**
     * 期間横断 単語帳ビューを取得する（§13-F-1・Phase 4.1 AC-62/63/65/68）。
     *
     * @param userId       本人ユーザーID（本人スコープ・AC-60）
     * @param from         期間開始日（必須）
     * @param to           期間終了日（必須）
     * @param themeId      テーマIDフィルタ（null=絞らない）
     * @param subjects     科目名フィルタ（OR 意味論・null/空=絞らない・AC-62）
     * @param sourceTypes  source_type フィルタ（OR 意味論・null/空=絞らない・AC-65）
     * @param shuffle      true でシャッフル全件返却（ページング無効・上限 500・AC-63）
     * @param page         ページ番号（0 始まり・負値は 0 に丸め・shuffle=true 時は無効）
     * @param size         1 ページサイズ（≤ {@code MAX_VOCAB_PAGE_SIZE}・shuffle=true 時は無効）
     * @return 抽出カード（ページングまたはシャッフル全件）
     * @throws BusinessException 期間幅 366 日超（REFLECTION_015）
     */
    @Transactional(readOnly = true)
    public ReflectionVocabCardsResponse getVocabCards(
            Long userId, LocalDate from, LocalDate to, UUID themeId,
            List<String> subjects, List<ReflectionSourceType> sourceTypes,
            Boolean shuffle, int page, int size) {

        // 期間幅の検証（§13-F-1・AC-60）。from > to も 400 として弾く。
        if (from == null || to == null || from.isAfter(to)) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_DATE_RANGE_INVALID);
        }
        long spanDays = ChronoUnit.DAYS.between(from, to) + 1; // 両端含む日数
        if (spanDays > ReflectionConstants.MAX_VOCAB_DATE_RANGE_DAYS) {
            throw new BusinessException(ReflectionErrorCode.REFLECTION_DATE_RANGE_INVALID);
        }

        boolean doShuffle = Boolean.TRUE.equals(shuffle);
        int normalizedPage = Math.max(page, 0);
        int normalizedSize = normalizeSize(size);

        // 本人の期間内エントリ（idx_reflection_entries_user_date 経路）。
        List<ReflectionEntryEntity> entries =
                reflectionEntryRepository.findByUserIdAndTargetDateBetween(userId, from, to);

        // エントリの親テーマを一括解決（本人所有のもののみ・他人 themeId は自然に空）。
        Map<UUID, ReflectionThemeEntity> themesById = reflectionThemeRepository
                .findByUserIdOrderByCreatedAtDesc(userId).stream()
                .collect(Collectors.toMap(ReflectionThemeEntity::getId, Function.identity(),
                        (a, b) -> a));

        List<ReflectionVocabCardItem> allCards = new ArrayList<>();
        for (ReflectionEntryEntity entry : entries) {
            ReflectionThemeEntity theme = themesById.get(entry.getThemeId());
            if (theme == null) {
                continue; // 本人所有テーマでないエントリは無視（本人スコープ・AC-60）。
            }
            if (!matchesFilters(theme, themeId, subjects, sourceTypes)) {
                continue;
            }
            allCards.addAll(extractCards(entry, theme));
        }

        int totalCards = allCards.size();

        if (doShuffle) {
            // AC-63: shuffle=true → Fisher-Yates シャッフル・ページング無効・上限 500 枚。
            List<ReflectionVocabCardItem> shuffled = new ArrayList<>(allCards);
            Collections.shuffle(shuffled);
            List<ReflectionVocabCardItem> limited = shuffled.size() > ReflectionConstants.MAX_VOCAB_PAGE_SIZE
                    ? shuffled.subList(0, ReflectionConstants.MAX_VOCAB_PAGE_SIZE)
                    : shuffled;
            return ReflectionVocabCardsResponse.builder()
                    .from(from)
                    .to(to)
                    .totalCards(totalCards)
                    .page(0)
                    .size(limited.size())
                    .cards(List.copyOf(limited))
                    .build();
        }

        List<ReflectionVocabCardItem> pageCards = paginate(allCards, normalizedPage, normalizedSize);

        return ReflectionVocabCardsResponse.builder()
                .from(from)
                .to(to)
                .totalCards(totalCards)
                .page(normalizedPage)
                .size(normalizedSize)
                .cards(pageCards)
                .build();
    }

    private boolean matchesFilters(ReflectionThemeEntity theme, UUID themeId,
                                   List<String> subjects, List<ReflectionSourceType> sourceTypes) {
        if (themeId != null && !themeId.equals(theme.getId())) {
            return false;
        }
        // AC-65: sourceTypes OR フィルタ（null/空=絞らない）
        if (sourceTypes != null && !sourceTypes.isEmpty()
                && !sourceTypes.contains(theme.getSourceType())) {
            return false;
        }
        // AC-62: subjects OR フィルタ（null/空=絞らない）
        if (subjects != null && !subjects.isEmpty()) {
            String linkedSubject = theme.getLinkedSubjectName();
            if (linkedSubject == null || !subjects.contains(linkedSubject)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 1 エントリの structured_content から TERM_CARD カードを抽出し出典メタを付与する（§13-F・AC-57）。
     * OUTLINE section は無視する。parse 失敗時は当該エントリをスキップ（閲覧ビューゆえ握り潰さず警告ログ）。
     */
    private List<ReflectionVocabCardItem> extractCards(ReflectionEntryEntity entry,
                                                       ReflectionThemeEntity theme) {
        List<ReflectionVocabCardItem> cards = new ArrayList<>();
        JsonNode content;
        try {
            content = contentSanitizer.parse(entry.getStructuredContent());
        } catch (Exception e) {
            log.warn("単語帳抽出: structured_content parse 失敗のためスキップ: entryId={}", entry.getId(), e);
            return cards;
        }
        if (content == null || !content.isObject()) {
            return cards;
        }
        JsonNode sectionsNode = content.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray()) {
            return cards;
        }
        for (JsonNode section : sectionsNode) {
            if (section == null || !section.isObject()) {
                continue;
            }
            // type 欠落=OUTLINE（§13-A-1）。TERM_CARD のみ抽出対象。
            JsonNode typeNode = section.get("type");
            String type = typeNode != null && typeNode.isTextual() ? typeNode.asText() : null;
            if (!SECTION_TYPE_TERM_CARD.equals(type)) {
                continue;
            }
            String sectionHeading = textOrNull(section, "heading");
            JsonNode cardsNode = section.get("cards");
            if (cardsNode == null || !cardsNode.isArray()) {
                continue;
            }
            for (JsonNode cardNode : cardsNode) {
                if (cardNode == null || !cardNode.isObject()) {
                    continue;
                }
                cards.add(ReflectionVocabCardItem.builder()
                        .term(textOrNull(cardNode, "term"))
                        .meaning(textOrNull(cardNode, "meaning"))
                        .themeId(theme.getId().toString())
                        .themeTitle(theme.getTitle())
                        .targetDate(entry.getTargetDate())
                        .sectionHeading(sectionHeading)
                        .build());
            }
        }
        return cards;
    }

    private List<ReflectionVocabCardItem> paginate(List<ReflectionVocabCardItem> all,
                                                   int page, int size) {
        int fromIndex = page * size;
        if (fromIndex >= all.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + size, all.size());
        return List.copyOf(all.subList(fromIndex, toIndex));
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return ReflectionConstants.DEFAULT_VOCAB_PAGE_SIZE;
        }
        return Math.min(size, ReflectionConstants.MAX_VOCAB_PAGE_SIZE);
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
