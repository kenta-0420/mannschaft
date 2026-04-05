package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdSegmentResponse;
import com.mannschaft.app.advertising.service.AdSegmentService;
import com.mannschaft.app.common.PagedResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 広告セグメント抽出コントローラー（SYSTEM_ADMIN用）。
 * 1st Party Dataをセグメント別に抽出する。
 */
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
