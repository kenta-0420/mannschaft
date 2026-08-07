package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.dto.ReflectionVocabCardsResponse;
import com.mannschaft.app.reflection.service.ReflectionVocabCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * F06.5 Phase 4: 期間横断 単語帳ビューコントローラー（EP #23・§13-F / §13-H）。
 *
 * <p>{@code GET /api/v1/me/reflections/cards}。認証必須＋本人スコープ。指定期間内の本人エントリの
 * TERM_CARD カードを横断抽出して返す（{@code recall_attempts} 非書込・閲覧専用）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections/cards")
@Tag(name = "振り返り単語帳", description = "F06.5 Phase 4 期間横断 単語帳ビュー")
@RequiredArgsConstructor
public class ReflectionVocabCardController {

    private final ReflectionVocabCardService vocabCardService;

    /**
     * EP #23: 期間横断 単語帳ビュー取得（§13-F-1・AC-57〜AC-65・AC-68）。
     *
     * @param from        期間開始日（必須・YYYY-MM-DD）
     * @param to          期間終了日（必須・YYYY-MM-DD）
     * @param themeId     テーマIDフィルタ（任意）
     * @param subjects    科目名フィルタ（任意・繰り返しパラメータ・OR 意味論・AC-62）
     * @param sourceTypes source_type フィルタ（任意・繰り返しパラメータ・OR 意味論・enum 外は 400・AC-65）
     * @param shuffle     true でシャッフル全件返却（ページング無効・既定 false・AC-63）
     * @param page        ページ番号（0 始まり・既定 0）
     * @param size        1 ページサイズ（既定 200・上限 500）
     */
    @SelfScopedEndpoint("ReflectionVocabCardService#getVocabCards が"
            + " findByUserIdAndTargetDateBetween(userId=SecurityUtils.getCurrentUserId(), ...) の"
            + "エントリのみを対象に、themeId フィルタも本人所有テーマの Map と突き合わせるため"
            + "他ユーザーの themeId は自然に空になる")
    @GetMapping
    @Operation(summary = "期間横断 単語帳ビュー取得")
    public ResponseEntity<ApiResponse<ReflectionVocabCardsResponse>> getVocabCards(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID themeId,
            @RequestParam(required = false) List<String> subjects,
            @RequestParam(required = false) List<ReflectionSourceType> sourceTypes,
            @RequestParam(required = false, defaultValue = "false") boolean shuffle,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        ReflectionVocabCardsResponse result = vocabCardService.getVocabCards(
                SecurityUtils.getCurrentUserId(), from, to, themeId, subjects, sourceTypes, shuffle, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
