package com.mannschaft.app.gallery.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.gallery.dto.RegenerateThumbnailsRequest;
import com.mannschaft.app.gallery.dto.RegenerateThumbnailsResponse;
import com.mannschaft.app.gallery.event.ThumbnailRegenerateEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * ギャラリー管理者コントローラー。SYSTEM_ADMIN向けのサムネイル再生成APIを提供する。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@RequestMapping("/api/v1/system-admin/gallery")
@Tag(name = "ギャラリー管理", description = "F06.2 SYSTEM_ADMIN向けギャラリー管理API")
@RequiredArgsConstructor
public class GalleryAdminController {

    private final DomainEventPublisher eventPublisher;

    /**
     * サムネイルを一括再生成する（非同期ジョブ）。
     */
    @PostMapping("/regenerate-thumbnails")
    @Operation(summary = "サムネイル一括再生成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "ジョブ受付成功")
    public ResponseEntity<ApiResponse<RegenerateThumbnailsResponse>> regenerateThumbnails(
            @Valid @RequestBody(required = false) RegenerateThumbnailsRequest request) {
        String jobId = "regen-thumb-" + UUID.randomUUID().toString().substring(0, 8);

        Long teamId = request != null ? request.getTeamId() : null;
        Long orgId = request != null ? request.getOrganizationId() : null;
        eventPublisher.publish(new ThumbnailRegenerateEvent(jobId, teamId, orgId));

        RegenerateThumbnailsResponse response = new RegenerateThumbnailsResponse(jobId, "PROCESSING");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.of(response));
    }
}
