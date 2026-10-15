package com.mannschaft.app.todo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 進捗モード切替リクエストDTO。手動モード（true）と自動算出モード（false）を切り替える。
 */
@Getter
@RequiredArgsConstructor
public class ProgressModeRequest {

    /**
     * 進捗率の手動設定モードかどうか（必須）。
     * true: 手動設定モード（ユーザーが直接設定）
     * false: 自動算出モード（子TODOの平均から算出）
     */
    @NotNull
    private final Boolean progressManual;
}
