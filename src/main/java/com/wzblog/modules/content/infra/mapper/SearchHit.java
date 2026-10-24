package com.wzblog.modules.content.infra.mapper;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 全文检索命中行：应用层在本行基础上叠加标题权重与时间衰减。
 */
@Getter
@Setter
public class SearchHit {

    private Long id;
    private String title;
    private LocalDateTime publishedAt;
    private Double score;
}