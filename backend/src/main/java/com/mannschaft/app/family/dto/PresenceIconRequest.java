package com.mannschaft.app.family.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * プレゼンスカスタムアイコン設定リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceIconRequest {

    @NotEmpty(message = "アイコン設定は1件以上指定してください")
    @Valid
    private final List<IconEntry> icons;

    /**
     * 個別のアイコン設定。
     */
    @Getter
    @RequiredArgsConstructor
    public static class IconEntry {

        @jakarta.validation.constraints.NotBlank(message = "イベントタイプは必須です")
        private final String eventType;

        @jakarta.validation.constraints.NotBlank(message = "アイコンは必須です")
        @jakarta.validation.constraints.Size(max = 10, message = "アイコンは10文字以内で入力してください")
        private final String icon;
    }
}
