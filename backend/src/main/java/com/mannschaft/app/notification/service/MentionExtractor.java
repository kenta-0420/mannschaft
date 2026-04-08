package com.mannschaft.app.notification.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * テキストから {@code @contactHandle} 表記を抽出してメンション対象ユーザーを解決するユーティリティ。
 *
 * <p>F04.8 で導入された contact_handle (半角英数 + アンダースコア) をメンションキーとする。
 * 同じテキストに同じハンドルが複数回現れても重複なく1件として扱う。</p>
 */
@Component
@RequiredArgsConstructor
public class MentionExtractor {

    /**
     * {@code @} の直後に半角英数とアンダースコアが続くトークンを抽出する。
     * 直前が単語境界（記号・空白・先頭）でないと拾わない（メールアドレスを誤検出しないため）。
     */
    private static final Pattern HANDLE_PATTERN =
            Pattern.compile("(?:^|[^A-Za-z0-9_])@([A-Za-z0-9_]{2,30})");

    private final UserRepository userRepository;

    /**
     * テキストからメンション対象ユーザーを解決する。
     *
     * @param text 解析対象のテキスト（null/空文字なら空リスト）
     * @return メンションされたユーザーのリスト（同一ユーザーは1件にまとめる、ハンドルが解決できないものは無視）
     */
    public List<UserEntity> extractMentionedUsers(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Matcher matcher = HANDLE_PATTERN.matcher(text);
        Set<String> seenHandles = new HashSet<>();
        List<UserEntity> result = new ArrayList<>();
        while (matcher.find()) {
            String handle = matcher.group(1);
            if (!seenHandles.add(handle)) {
                continue;
            }
            Optional<UserEntity> user = userRepository.findByContactHandle(handle);
            user.ifPresent(result::add);
        }
        return result;
    }

    /**
     * 本文の抜粋を生成する。最大長を超える場合は末尾に "..." を付与する。
     *
     * @param text   元の本文
     * @param maxLen 最大文字数
     * @return 抜粋
     */
    public String buildSnippet(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        if (trimmed.length() <= maxLen) {
            return trimmed;
        }
        return trimmed.substring(0, maxLen - 3) + "...";
    }
}
