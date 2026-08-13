package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.recruitment.dto.WaiveCancellationFeeRequest;
import com.mannschaft.app.recruitment.service.RecruitmentCancellationFeeWaiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F03.11.1 募集キャンセル料の記録に対する操作 Controller（設計書 §10.1）。
 *
 * <p>受取先側の管理者も呼ぶため {@code /system-admin/} 配下には置かない（R-5 御裁可）。</p>
 */
@RestController
@RequestMapping("/api/v1/recruitment-cancellation-records")
@Tag(name = "F03.11.1 募集キャンセル料", description = "募集キャンセル料の免除")
@RequiredArgsConstructor
public class RecruitmentCancellationRecordController {

    private final RecruitmentCancellationFeeWaiveService waiveService;

    /**
     * キャンセル料を免除する。
     *
     * <p>認可根治済み: {@link RecruitmentCancellationFeeWaiveService#waive} が
     * 「受取先側の精算管理者（escrow の payee に基づく TEAM/ORG/個人の 3 種）」または
     * {@code SYSTEM_ADMIN} であることを検証し、いずれでもなければ {@code COMMON_002}(403) で拒否する。
     * キャンセル料を負っている本人はこのいずれにも該当しないため免除できない（§10.2）。</p>
     *
     * <p>{@code PAID} への免除は 409（免除ではなく返金の話であり混同させない）。
     * {@code WAIVED} への再免除は冪等に 200 で返す（終端状態なら何でも 409、にはしない）。</p>
     *
     * @param recordId 対象のキャンセル記録 ID
     * @param request  免除理由（必須）
     * @return 本文なしの 200
     */
    @AuthorizedInService
    @PostMapping("/{recordId}/waive")
    @Operation(summary = "キャンセル料の免除",
            description = "受取先側の管理者または運営管理者がキャンセル料の請求を取り消す。"
                    + "免除は債権の放棄を必ず行うが、そのユーザーに他の未払いが残っている場合は"
                    + "募集への申込制限は解除されない。")
    public ResponseEntity<Void> waive(
            @PathVariable Long recordId,
            @Valid @RequestBody WaiveCancellationFeeRequest request) {
        waiveService.waive(recordId, SecurityUtils.getCurrentUserId(), request.getReason());
        return ResponseEntity.ok().build();
    }
}
