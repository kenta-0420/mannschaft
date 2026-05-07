package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * TODO キャッチボール（引き渡し）リクエスト DTO（F02.3.1 Phase 2）。
 *
 * <ul>
 *   <li>{@code toUserIds}: 新しい担当者 userId 一覧（@NotEmpty。空配列で「無主」TODO を作らない）</li>
 *   <li>{@code statusLabelId}: 引き渡し時の新ステータスラベル（@NotNull。ラベルから bucket → status を導出）</li>
 *   <li>{@code message}: 添えメッセージ（任意。500 文字まで）</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TodoHandoffRequest {

    @NotEmpty(message = "宛先メンバーを1名以上指定してください")
    @Size(max = 20, message = "宛先は最大20名まで")
    private List<Long> toUserIds;

    @NotNull(message = "ステータスラベルを指定してください")
    private Long statusLabelId;

    @Size(max = 500, message = "メッセージは500文字以内で入力してください")
    private String message;
}
