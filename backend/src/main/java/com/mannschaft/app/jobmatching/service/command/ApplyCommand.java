package com.mannschaft.app.jobmatching.service.command;

/**
 * 求人応募コマンド（Service 層入力 DTO）。
 *
 * <p>{@code selfPr} はフリーテキストの自己PR（500 文字以内）。null または空文字列の応募も許容する
 * （後続 Phase で profile 連携により自動補完するケースのため）。</p>
 */
public record ApplyCommand(String selfPr) {
}
