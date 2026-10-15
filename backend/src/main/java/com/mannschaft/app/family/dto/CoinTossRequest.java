package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * コイントスリクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CoinTossRequest {

    /** モード（COIN / CUSTOM。デフォルト: COIN） */
    private final String mode;

    /** 選択肢（CUSTOMモード時必須、2〜6個） */
    private final List<String> options;

    /** 質問文（最大200文字） */
    @Size(max = 200, message = "質問文は200文字以内で入力してください")
    private final String question;
}
