package com.fungus.farm.dto;

import java.time.LocalDateTime;

/**
 * 值班人员处置动作：接单 / 更新处理进度 / 解除确认 共用。
 * operator 为值班人员姓名；expectedFinishAt 为预计完成时间；note 为处理记录。
 */
public record DisposalActionRequest(
        String operator,
        LocalDateTime expectedFinishAt,
        String note) {
}
