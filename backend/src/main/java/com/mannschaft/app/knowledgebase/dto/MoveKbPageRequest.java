package com.mannschaft.app.knowledgebase.dto;

/**
 * ナレッジベースページ移動リクエスト。
 * newParentId が null の場合はルートへの移動を意味する。
 */
public record MoveKbPageRequest(
        Long newParentId
) {}
