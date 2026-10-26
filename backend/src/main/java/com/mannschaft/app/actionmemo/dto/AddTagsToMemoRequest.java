package com.mannschaft.app.actionmemo.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * F02.5 メモへのタグ追加リクエスト DTO。
 *
 * <p>設計書 §3 に従い、1メモあたりのタグ数上限は10個。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AddTagsToMemoRequest {

    @NotEmpty(message = "タグ ID を1つ以上指定してください")
    @Size(max = 10, message = "1メモあたりのタグは10個までです")
    @JsonProperty("tag_ids")
    private List<Long> tagIds;
}
