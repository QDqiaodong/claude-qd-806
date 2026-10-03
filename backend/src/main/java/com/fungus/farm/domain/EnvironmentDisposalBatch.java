package com.fungus.farm.domain;

import java.time.LocalDateTime;
import lombok.Data;

/** 被处置单暂停的出菇批次快照，解除时凭此仅恢复本单暂停、且当前仍为“环境暂停”的批次。 */
@Data
public class EnvironmentDisposalBatch {
    private Long id;
    private Long orderId;
    private Long batchId;
    private String batchNo;
    private String originalStatus;
    private LocalDateTime restoredAt;
}
