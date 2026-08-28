package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdSegmentResponse;
import com.mannschaft.app.advertising.service.AdSegmentService;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 広告セグメント抽出コントローラー（SYSTEM_ADMIN用）。
 * 1st Party Dataをセグメント別に抽出する。
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
@RequestMapping("/api/v1/system-admin/ad-segments")
@RequiredArgsConstructor
public class AdSegmentController {

    private final AdSegmentService adSegmentService;

    /**
     * チーム単位のセグメント情報を取得する。
     * 例: /api/v1/system-admin/ad-segments?template=baseball&prefecture=東京都&minMemberCount=30
     */
    @GetMapping
    public PagedResponse<AdSegmentResponse> getSegments(
            @RequestParam(required = false) String template,
            @RequestParam(required = false) String prefecture,
            @RequestParam(required = false) Long minMemberCount,
            Pageable pageable) {
        return adSegmentService.getSegments(template, prefecture, minMemberCount, pageable);
    }
}
