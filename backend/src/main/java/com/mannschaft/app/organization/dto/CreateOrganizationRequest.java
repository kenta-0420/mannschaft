package com.mannschaft.app.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

/**
 * 組織作成リクエスト。
 */
@Getter
@Setter
@RequiredArgsConstructor
public class CreateOrganizationRequest {

    @NotBlank
    private final String name;

    @NotBlank
    private final String orgType;

    private final String prefecture;
    private final String city;
    private final String visibility;
    private final Long parentOrganizationId;

    /**
     * ユーザーが任意に指定する URL スラッグ（村方式に統一）。
     *
     * <p>形式 {@code ^[a-z0-9-]{3,30}$}（先頭/末尾ハイフン不可・連続ハイフン不可）。
     * 詳細な形式・予約語・一意性の検証は Service 層
     * （{@link com.mannschaft.app.common.util.SlugValidator}）で行う。</p>
     *
     * <p><strong>後方互換</strong>: null または空文字の場合は従来どおり
     * {@link com.mannschaft.app.common.util.SlugGenerator} による自動生成へフォールバックする。
     * Bean Validation では空文字を許容する必要があるため {@code @Pattern} は付与せず、
     * 指定時のみ Service で検証する（村の {@code validateSlug} と同方式）。
     * コンストラクタ引数の後方互換のため末尾に置く。</p>
     */
    private final String slug;

    /**
     * CMP-260901-1538 柱③-A: 同名確認フロー。true の場合、同名候補の存在を確認済みとして
     * 作成を続行する（{@link #duplicateNameFingerprint} との組で TX 内再検証される）。
     * 省略時 false（@RequiredArgsConstructor 対象外の非 final フィールドのため、
     * 既存コンストラクタ呼び出しへの後方互換を維持する）。
     */
    private boolean confirmDuplicate;

    /**
     * CMP-260901-1538 柱③-A: {@code confirmDuplicate=true} 時に返送する HMAC fingerprint。
     * 確認時に提示した候補集合に束縛されており、作成 TX 内で再計算した候補集合と不一致の場合
     * （確認後に新たな同名が出現した場合）は再度 409 となる。
     */
    private String duplicateNameFingerprint;
}
