package com.mannschaft.app.organization.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 組織作成リクエスト。
 */
@Getter
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
}
