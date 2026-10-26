package com.mannschaft.app.jobmatching.controller.dto;

import jakarta.validation.constraints.Size;

/**
 * 求人応募リクエスト。
 *
 * <p>自己PRは任意入力。プロフィールから自動補完する運用も見据え、空でも応募可能とする。</p>
 */
public record ApplyRequest(
        @Size(max = 500) String selfPr
) {
}
