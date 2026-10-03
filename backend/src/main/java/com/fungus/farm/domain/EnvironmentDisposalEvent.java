package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 处置时间线条目：触发、接单、处理记录、反复（持续/恶化/退回）与解除全程留痕。 */
@Data
public class EnvironmentDisposalEvent {
    public static final String TRIGGERED = "触发";
    public static final String ACCEPTED = "接单";
    public static final String PROGRESS = "处理记录";
    public static final String OVER_CONTINUED = "持续越限";
    public static final String OVER_WORSENED = "恶化";
    public static final String RECOVER_PENDING = "恢复待确认";
    public static final String RELAPSE = "再次越限退回";
    public static final String RESOLVED = "解除";

    private Long id;
    private Long orderId;
    private String eventType;
    private String detail;
    private String operator;
    private Long refRecordId;
    private LocalDateTime occurredAt;
}
