package com.wzblog.common.profile;

/**
 * 称号简要信息，与 M1 的 TitleBriefVO 同构（M0 §11.3）。
 */
public record TitleBrief(Long id, String name, String cssClass) {
}