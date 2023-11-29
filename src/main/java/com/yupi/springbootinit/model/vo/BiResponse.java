package com.yupi.springbootinit.model.vo;

import lombok.Data;

/**
 * Bi 返回结果
 */
@Data
public class BiResponse {
    // 返回文字形式，前端通过可视化插件生成
    private String genChart;

    private String genResult;

    private Long chartId;
}
