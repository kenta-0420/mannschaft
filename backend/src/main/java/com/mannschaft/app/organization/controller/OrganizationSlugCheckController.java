package com.mannschaft.app.organization.controller;

import com.mannschaft.app.common.util.SlugGenerator;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.SlugCheckResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 組織URLスラッグの可用性チェックコントローラー。
 *
 * <p>組織作成・編集時にリアルタイムでスラッグの重複を確認するためのエンドポイントを提供する。
 * 使用不可の場合は代替候補（最大3件）を返す。</p>
 *
 * <p>レートリミット: 60 req/min/user（{@link SlugCheckRateLimitFilter} で制御）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations")
@Tag(name = "組織管理")
@RequiredArgsConstructor
@Validated
public class OrganizationSlugCheckController {

    /** 使用不可時の代替候補最大件数。 */
    private static final int MAX_SUGGESTIONS = 3;

    /** 代替候補生成の最大試行回数。 */
    private static final int MAX_ATTEMPTS = 10;

    private final OrganizationRepository organizationRepository;

    /**
     * 組織スラッグの可用性を確認する。
     *
     * <p>スラッグが使用可能な場合は {@code available: true, suggestions: []} を返す。
     * 既に使用中の場合は {@code available: false} と代替候補（最大3件）を返す。</p>
     *
     * @param slug チェック対象のスラッグ（3〜30文字の英小文字・数字・ハイフン）
     * @return スラッグ可用性情報
     */
    @GetMapping("/slug-check")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "組織スラッグ可用性チェック")
    public ResponseEntity<SlugCheckResponse> checkSlugAvailability(
            @RequestParam
            @NotBlank(message = "スラッグは必須です")
            @Size(min = 3, max = 30, message = "スラッグは3〜30文字で入力してください")
            @Pattern(regexp = "^[a-z0-9][a-z0-9-]*[a-z0-9]$|^[a-z0-9]{3}$",
                     message = "スラッグは英小文字・数字・ハイフンのみ使用できます（先頭・末尾にハイフン不可）")
            String slug
    ) {
        boolean available = !organizationRepository.existsBySlugAndDeletedAtIsNull(slug);

        if (available) {
            return ResponseEntity.ok(new SlugCheckResponse(true, List.of()));
        }

        // 使用不可の場合は候補を最大3件生成
        List<String> suggestions = new ArrayList<>();
        for (int i = 1; i <= MAX_ATTEMPTS && suggestions.size() < MAX_SUGGESTIONS; i++) {
            String candidate = SlugGenerator.withSuffix(slug, i);
            if (!organizationRepository.existsBySlugAndDeletedAtIsNull(candidate)) {
                suggestions.add(candidate);
            }
        }

        return ResponseEntity.ok(new SlugCheckResponse(false, suggestions));
    }
}
