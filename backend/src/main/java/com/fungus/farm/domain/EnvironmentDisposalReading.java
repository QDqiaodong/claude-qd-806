package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 处置单与环境读数的关联：TRIGGER 触发三条 / OVER 处置期越限 / RECOVERY 恢复计数。 */
@Data
public class EnvironmentDisposalReading {
    public static final String ROLE_TRIGGER = "TRIGGER";
    public static final String ROLE_OVER = "OVER";
    public static final String ROLE_RECOVERY = "RECOVERY";

    private Long id;
    private Long orderId;
    private Long recordId;
    private String role;
    private LocalDateTime linkedAt;
}
