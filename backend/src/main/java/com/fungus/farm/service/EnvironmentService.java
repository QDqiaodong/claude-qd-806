package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.dto.EnvironmentRecordRequest;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final EnvironmentRepository environmentRepository;
    private final GrowRoomRepository roomRepository;
    private final EnvironmentDisposalService disposalService;

    public EnvironmentService(EnvironmentRepository environmentRepository,
            GrowRoomRepository roomRepository, EnvironmentDisposalService disposalService) {
        this.environmentRepository = environmentRepository;
        this.roomRepository = roomRepository;
        this.disposalService = disposalService;
    }

    public List<Map<String, Object>> dashboard() {
        return environmentRepository.findDashboard();
    }

    public List<EnvironmentRecord> records(Long roomId, Boolean overLimit, LocalDateTime from, LocalDateTime to) {
        return environmentRepository.findRecords(roomId, overLimit, from, to);
    }

    public List<EnvironmentRecord> history(Long roomId, int hours) {
        if (roomRepository.findById(roomId).isEmpty()) {
            throw new BizException("菇房不存在");
        }
        return environmentRepository.findHistory(roomId, Math.max(1, Math.min(hours, 168)));
    }

    /**
     * 读数上报统一进入处置闭环：保存读数后在同一事务内按采集时间有序后缀对账
     * （三连越限开单、暂停批次、六连恢复等规则见 EnvironmentDisposalService）。
     */
    @Transactional
    public Long add(EnvironmentRecordRequest request) {
        return disposalService.report(request);
    }
}
