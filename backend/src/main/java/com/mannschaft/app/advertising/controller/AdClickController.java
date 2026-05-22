package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AdClickResponse;
import com.mannschaft.app.advertising.dto.RecordAdClickRequest;
import com.mannschaft.app.advertising.service.AdClickService;
import com.mannschaft.app.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * F09.7 クリック計測コントローラー。
 *
 * <p>{@code POST /api/v1/ads/{adId}/click} で広告クリックを記録する。
 * 認証不要（未ログインユーザーのクリックにも対応）。SecurityConfig で permitAll 設定済み。</p>
 *
 * <p>TODO: 将来: IP ベースのレート制限を追加して不正クリックを防止する</p>
 */
@RestController
@RequestMapping("/api/v1/ads/{adId}/click")
@RequiredArgsConstructor
public class AdClickController {

    private final AdClickService adClickService;

    /**
     * 広告クリックを記録し、クリック ID と発生日時を返す。
     *
     * @param adId    ads.id（パス変数）
     * @param request クリック記録リクエスト
     * @return 作成したクリックの id と occurredAt
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdClickResponse> recordClick(
            @PathVariable Long adId,
            @Valid @RequestBody RecordAdClickRequest request) {

        Long clickId = adClickService.record(
                adId,
                request.campaignId(),
                request.impressionId(),
                request.userId());

        return ApiResponse.of(new AdClickResponse(clickId, LocalDateTime.now()));
    }
}
