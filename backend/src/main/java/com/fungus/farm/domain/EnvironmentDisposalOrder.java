package com.fungus.farm.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 环境越限处置单。状态机：
 * 待接单 -> 处理中 -> 恢复待确认 -> 已解除
 *                ^         |
 *                +---------+（确认前再次越限退回处理中）
 * 活动单为“待接单/处理中/恢复待确认”，每个菇房同时至多一张（active_flag=1）。
 */
@Data
public class EnvironmentDisposalOrder {
    public static final String STATUS_PENDING = "待接单";
    public static final String STATUS_PROCESSING = "处理中";
    public static final String STATUS_RECOVERING = "恢复待确认";
    public static final String STATUS_RESOLVED = "已解除";

    private Long id;
    private String orderNo;
    private Long roomId;
    private String status;
    /** 触发指标，逗号分隔，如 “温度,CO2” */
    private String triggerMetrics;
    private BigDecimal triggerTemperature;
    private BigDecimal triggerHumidity;
    private Integer triggerCo2Ppm;
    private LocalDateTime triggerRecordedStart;
    private LocalDateTime triggerRecordedEnd;
    private LocalDateTime triggeredAt;
    private String assignee;
    private LocalDateTime acceptedAt;
    private LocalDateTime expectedFinishAt;
    /** 处置期累积越限读数条数（含触发三条） */
    private Integer overCount;
    /** 当前连续恢复（适宜范围）读数条数 */
    private Integer recoveredCount;
    private LocalDateTime lastOverAt;
    private BigDecimal lastOverTemperature;
    private BigDecimal lastOverHumidity;
    private Integer lastOverCo2Ppm;
    /** 本轮连续恢复起始读数的采集时间，退回处理中时清空 */
    private LocalDateTime recoveredSince;
    private String resolutionNote;
    private String resolvedBy;
    private LocalDateTime resolvedAt;
    private Integer activeFlag;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
