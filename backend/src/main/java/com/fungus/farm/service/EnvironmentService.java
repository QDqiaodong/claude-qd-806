package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.dto.EnvironmentRecordRequest;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EnvironmentService {
    private final EnvironmentRepository environmentRepository;
    private final GrowRoomRepository roomRepository;

    public EnvironmentService(EnvironmentRepository environmentRepository, GrowRoomRepository roomRepository) {
        this.environmentRepository = environmentRepository;
        this.roomRepository = roomRepository;
    }

    public List<Map<String, Object>> dashboard() {
        return environmentRepository.findDashboard();
    }

    public List<EnvironmentRecord> records(Long roomId, Boolean overLimit, LocalDateTime from, LocalDateTime to) {
        return environmentRepository.findRecords(roomId, overLimit, from, to);
    }

    public List<EnvironmentRecord> history(Long roomId, int hours) {
        room(roomId);
        return environmentRepository.findHistory(roomId, Math.max(1, Math.min(hours, 168)));
    }

    @Transactional
    public Long add(EnvironmentRecordRequest request) {
        if (request.roomId() == null || request.temperature() == null || request.humidity() == null || request.co2Ppm() == null) {
            throw new BizException("菇房、温度、湿度和 CO₂ 均不能为空");
        }
        GrowRoom room = room(request.roomId());
        EnvironmentRecord record = new EnvironmentRecord();
        record.setRoomId(request.roomId());
        record.setTemperature(request.temperature());
        record.setHumidity(request.humidity());
        record.setCo2Ppm(request.co2Ppm());
        record.setLightLux(request.lightLux() == null ? 0 : request.lightLux());
        record.setRecordedAt(request.recordedAt() == null ? LocalDateTime.now() : request.recordedAt());
        record.setOverLimit(isOverLimit(room.getSpecies(), record));
        return environmentRepository.save(record);
    }

    private GrowRoom room(Long id) {
        return roomRepository.findById(id).orElseThrow(() -> new BizException("菇房不存在"));
    }

    private boolean isOverLimit(String species, EnvironmentRecord record) {
        BigDecimal minTemp;
        BigDecimal maxTemp;
        BigDecimal minHumidity;
        BigDecimal maxHumidity = BigDecimal.valueOf(95);
        int maxCo2;
        switch (species) {
            case "金针菇" -> { minTemp = BigDecimal.valueOf(4); maxTemp = BigDecimal.valueOf(12); minHumidity = BigDecimal.valueOf(85); maxCo2 = 1200; }
            case "杏鲍菇" -> { minTemp = BigDecimal.valueOf(12); maxTemp = BigDecimal.valueOf(18); minHumidity = BigDecimal.valueOf(85); maxCo2 = 1500; }
            case "香菇" -> { minTemp = BigDecimal.valueOf(12); maxTemp = BigDecimal.valueOf(20); minHumidity = BigDecimal.valueOf(80); maxCo2 = 1500; }
            default -> { minTemp = BigDecimal.valueOf(15); maxTemp = BigDecimal.valueOf(22); minHumidity = BigDecimal.valueOf(80); maxCo2 = 1800; }
        }
        return record.getTemperature().compareTo(minTemp) < 0
                || record.getTemperature().compareTo(maxTemp) > 0
                || record.getHumidity().compareTo(minHumidity) < 0
                || record.getHumidity().compareTo(maxHumidity) > 0
                || record.getCo2Ppm() > maxCo2;
    }
}
