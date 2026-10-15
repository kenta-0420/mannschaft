package com.mannschaft.app.recruitment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * F03.11 キャンセルポリシーの編集リクエスト。
 * is_template_policy=true のポリシーのみ編集可能 (Service 層で検証)。
 */
@Getter
@RequiredArgsConstructor
public class UpdateCancellationPolicyRequest {

    @Size(max = 100)
    private final String policyName;

    @Positive
    private final Integer freeUntilHoursBefore;

    /** null の場合は段階を変更しない。空リストの場合は全段階削除。 */
    @Valid
    private final List<CancellationPolicyTierRequest> tiers;
}
