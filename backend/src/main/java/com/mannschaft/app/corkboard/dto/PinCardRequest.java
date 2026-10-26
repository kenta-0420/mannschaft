package com.mannschaft.app.corkboard.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;

/**
 * F09.8.1 ピン止め切替リクエストDTO。
 * 個人コルクボードのカードに対するピン止め状態のトグルに使用する。
 *
 * <p>F09.8 件3' (V9.098) 追補:</p>
 * <ul>
 *   <li>{@code userNote} — ピン時に書き込む付箋メモ本文（任意・後方互換のため optional）</li>
 *   <li>{@code noteColor} — ピン時に明示する付箋色（任意・null はカラーラベルと同色を意味）</li>
 * </ul>
 *
 * <p>アンピン時 ({@code isPinned=false}) は {@code userNote}/{@code noteColor} を送っても
 * 既存値は更新せず保持する（付箋メタファ・再ピン時に再利用可）。</p>
 */
@Getter
public class PinCardRequest {

    /** ピン止めする (true) / 解除する (false) */
    @NotNull
    private final Boolean isPinned;

    /**
     * F09.8 件3': 付箋メモ本文（任意）。
     * pin 時にのみ書き込まれ、アンピン時は無視される。
     * {@code null} の場合はカードの既存 {@code userNote} を変更しない。
     */
    private final String userNote;

    /**
     * F09.8 件3': 付箋色（任意）。
     * カードの {@code colorLabel} とは独立。
     * {@code null} の場合はカードの既存 {@code noteColor} を変更しない（→ 表示時はカラーラベルと同色になる）。
     */
    private final String noteColor;

    @JsonCreator
    public PinCardRequest(
            @JsonProperty("isPinned") Boolean isPinned,
            @JsonProperty("userNote") String userNote,
            @JsonProperty("noteColor") String noteColor) {
        this.isPinned = isPinned;
        this.userNote = userNote;
        this.noteColor = noteColor;
    }
}
