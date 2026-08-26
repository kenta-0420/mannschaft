package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.PublicNewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.PublicNewsletterIssueResponse;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * F17.1 ②-4 — 村ニュースレター公開一覧 Controller（村横断・案Y の「みんなのお便り」）。
 *
 * <p>村スコープ（{@code /api/v1/villages/{villageId}/newsletter}）とは別に、全村横断の
 * <b>公開号</b>（{@code visibility=PUBLIC} かつ {@code status=PUBLISHED}）だけを扱う。
 * ルーティングは {@code /api/v1/newsletter/public} で村スコープと衝突しない（設計書 §8.2）。</p>
 *
 * <h2>認可</h2>
 * <p><b>ログイン必須のみ</b>（村メンバーでなくてよい）。認可は Service 内で完結し、Controller は
 * {@link SecurityUtils#getCurrentUserId()} を取り出して委譲するのみ。公開一覧は PUBLIC×PUBLISHED を
 * クエリで直接引くため {@code VILLAGE_MEMBERS} 号は構造的に混入しない（AC-16）。PUBLIC 以外への
 * 直 ID アクセスは 404 で存在秘匿（AC-17・IDOR 対策）。</p>
 */
@RestController
@RequestMapping("/api/v1/newsletter/public")
@Tag(name = "村ニュースレター公開一覧 (F17.1 ②-4)",
     description = "全村横断の公開号（PUBLIC×PUBLISHED）一覧・詳細。ログイン必須のみ")
@PreAuthorize("isAuthenticated()")
@RequiredArgsConstructor
public class VillageNewsletterPublicController {

    private final VillageNewsletterIssueService issueService;

    @GetMapping
    @Operation(summary = "公開ニュースレター号の村横断一覧（新しい順）")
    public ApiResponse<PublicNewsletterIssuePageResponse> listPublicIssues(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        // size を [1,100] に丸める（過大要求での大量取得・DoS 防止・②-4 堅牢性 AC-10。他ドメイン慣習に合わせる）。
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return ApiResponse.of(issueService.listPublicIssues(actorUserId, pageable));
    }

    @GetMapping("/{issueId}")
    @Operation(summary = "公開ニュースレター号の詳細（PUBLIC 以外は 404 秘匿）")
    public ApiResponse<PublicNewsletterIssueResponse> getPublicIssue(
            @PathVariable("issueId") UUID issueId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(issueService.getPublicIssue(issueId, actorUserId));
    }
}
