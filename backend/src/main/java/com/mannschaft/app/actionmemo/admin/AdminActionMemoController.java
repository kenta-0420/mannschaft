package com.mannschaft.app.actionmemo.admin;

import com.mannschaft.app.actionmemo.admin.dto.RegenerateWeeklySummaryResponse;
import com.mannschaft.app.actionmemo.service.ActionMemoWeeklySummaryService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * F02.5 Phase 3 行動メモ管理者コントローラー。
 *
 * <p>SYSTEM_ADMIN が週次まとめブログを手動で再生成する運用 API を提供する。
 * 通常時は {@link ActionMemoWeeklySummaryService#generateWeeklySummaries()} が
 * 毎週日曜 21:00 JST に自動実行されるが、バッチ実行時刻にサーバーが落ちていた場合や
 * バッチ本体が例外で中断した場合、この API で手動再実行する想定。</p>
 *
 * <p><b>認可</b>: クラスレベル {@code @PreAuthorize("hasRole('SYSTEM_ADMIN')")} により
 * SYSTEM_ADMIN ロール保持者のみアクセス可能。Spring Security の JWT フィルタが
 * {@code ROLE_SYSTEM_ADMIN} を authority に乗せる。</p>
 *
 * <p><b>ユーザーのメモ内容は閲覧しない</b>: 設計書 §5.5 の運用ポリシー通り、
 * SYSTEM_ADMIN は再生成トリガーのみ行い、個別ユーザーのメモ本文は参照しない
 * （プライベートな思考ログの保護）。レスポンスには件数のみを含める。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/action-memo")
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
@Tag(name = "行動メモ管理（管理者）", description = "F02.5 Phase 3 週次まとめ手動再生成 API")
@RequiredArgsConstructor
@Slf4j
public class AdminActionMemoController {

    private final ActionMemoWeeklySummaryService weeklySummaryService;

    /**
     * 週次まとめブログを手動で再生成する。
     *
     * <p>{@code userId} パラメータを指定した場合は対象 1 ユーザーのみ、省略した場合は
     * 当週にメモを書いた全ユーザーが対象となる。集計期間は常に「今日を含まない過去7日間」
     * （定期バッチと同じ期間）固定。</p>
     *
     * <p>同 slug の既存ブログ記事が存在する場合は論理削除してから新規 INSERT される
     * （{@link ActionMemoWeeklySummaryService#generateForUser} の冪等性）。</p>
     *
     * @param userId 対象ユーザー ID（任意。省略時は全ユーザー）
     * @return 生成・スキップ・失敗の件数
     */
    @PostMapping("/regenerate-weekly-summary")
    @Operation(summary = "週次まとめブログの手動再生成（管理者）",
            description = "userId 指定時は対象1ユーザー、省略時は当週にメモを書いた全ユーザーを再生成する")
    public ResponseEntity<ApiResponse<RegenerateWeeklySummaryResponse>> regenerateWeeklySummary(
            @RequestParam(required = false) Long userId) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        LocalDate[] period = weeklySummaryService.currentPeriod();
        LocalDate from = period[0];
        LocalDate to = period[1];

        log.info("admin manual regenerate: triggered by {} for userId={} period={}〜{}",
                currentUserId, userId, from, to);

        RegenerateWeeklySummaryResponse response;
        if (userId != null) {
            response = regenerateSingle(userId, from, to);
        } else {
            ActionMemoWeeklySummaryService.RegenerationResult result =
                    weeklySummaryService.regenerateForAll(from, to);
            response = RegenerateWeeklySummaryResponse.builder()
                    .regeneratedCount(result.regeneratedCount())
                    .skippedCount(result.skippedCount())
                    .failedCount(result.failedCount())
                    .build();
        }
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 単一ユーザー再生成のラッパー。Service 側の RuntimeException を failed_count として数える。
     */
    private RegenerateWeeklySummaryResponse regenerateSingle(Long userId, LocalDate from, LocalDate to) {
        int regenerated = 0;
        int skipped = 0;
        int failed = 0;
        try {
            if (weeklySummaryService.regenerateForUser(userId, from, to)) {
                regenerated = 1;
            } else {
                skipped = 1;
            }
        } catch (RuntimeException e) {
            failed = 1;
            log.error("admin manual regenerate: failed for userId={}", userId, e);
        }
        return RegenerateWeeklySummaryResponse.builder()
                .regeneratedCount(regenerated)
                .skippedCount(skipped)
                .failedCount(failed)
                .build();
    }
}
