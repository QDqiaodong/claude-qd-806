package com.fungus.farm.service;

import com.fungus.farm.common.BizException;
import com.fungus.farm.domain.EnvironmentDisposalBatch;
import com.fungus.farm.domain.EnvironmentDisposalEvent;
import com.fungus.farm.domain.EnvironmentDisposalOrder;
import com.fungus.farm.domain.EnvironmentDisposalReading;
import com.fungus.farm.domain.EnvironmentRecord;
import com.fungus.farm.domain.GrowRoom;
import com.fungus.farm.domain.FruitingBatch;
import com.fungus.farm.dto.DisposalActionRequest;
import com.fungus.farm.dto.EnvironmentRecordRequest;
import com.fungus.farm.repository.EnvironmentDisposalRepository;
import com.fungus.farm.repository.EnvironmentDisposalRepository.FruitingSnapshot;
import com.fungus.farm.repository.EnvironmentRepository;
import com.fungus.farm.repository.FruitingRepository;
import com.fungus.farm.repository.GrowRoomRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 环境越限处置闭环。
 *
 * 并发与乱序口径：
 * 1) 同一菇房的每次上报先对 grow_room 加排他行锁，串行化对账，杜绝并发双单/双接单之外的竞争；
 * 2) 全部判断以“按采集时间升序”的读数序列为准，并只看当前越限/恢复后缀，
 *    迟到、乱序读数只在真正进入当前后缀时才影响处置单，补历史缺口不会误触发、不会冲掉较新判断；
 * 3) 一房一活动单由环境处置单表 (room_id, active_flag) 唯一索引兜底；接单/解除均为条件 UPDATE 原子抢占；
 * 4) 暂停的出菇批次逐批快照原状态，解除时只还原当前仍为“环境暂停”的本单批次。
 */
@Service
public class EnvironmentDisposalService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentDisposalRepository disposalRepository;
    private final GrowRoomRepository roomRepository;
    private final FruitingRepository fruitingRepository;

    public EnvironmentDisposalService(EnvironmentRepository environmentRepository,
            EnvironmentDisposalRepository disposalRepository, GrowRoomRepository roomRepository,
            FruitingRepository fruitingRepository) {
        this.environmentRepository = environmentRepository;
        this.disposalRepository = disposalRepository;
        this.roomRepository = roomRepository;
        this.fruitingRepository = fruitingRepository;
    }

    // ============================== 读数上报 + 对账 ==============================

    @Transactional
    public Long report(EnvironmentRecordRequest request) {
        if (request.roomId() == null || request.temperature() == null
                || request.humidity() == null || request.co2Ppm() == null) {
            throw new BizException("菇房、温度、湿度和 CO₂ 均不能为空");
        }
        if (request.temperature().signum() < 0 || request.humidity().signum() < 0 || request.co2Ppm() < 0) {
            throw new BizException("温度、湿度和 CO₂ 读数不能为负数");
        }
        GrowRoom room = roomRepository.findById(request.roomId())
                .orElseThrow(() -> new BizException("菇房不存在"));

        LocalDateTime recordedAt = request.recordedAt() == null ? LocalDateTime.now() : request.recordedAt();

        // 串行化同一菇房的上报与处置状态变更，保证下面读-判-写的原子性
        environmentRepository.lockRoom(room.getId());

        EnvironmentRecord existing = environmentRepository.findByRoomAndTime(room.getId(), recordedAt).orElse(null);
        EnvironmentRecord record;
        if (existing != null) {
            boolean same = eq(existing.getTemperature(), request.temperature())
                    && eq(existing.getHumidity(), request.humidity())
                    && existing.getCo2Ppm().equals(request.co2Ppm());
            if (same) {
                // 重复上报同一读数：幂等返回，绝不重复开单/重复累积
                return existing.getId();
            }
            // 同一采集时间已有不同读数：拒绝改写历史，避免冲掉较新的判断
            throw new BizException("该菇房 " + recordedAt.format(TS) + " 已存在不同读数，不能用同采集时间的读数覆盖");
        }

        record = new EnvironmentRecord();
        record.setRoomId(room.getId());
        record.setTemperature(request.temperature());
        record.setHumidity(request.humidity());
        record.setCo2Ppm(request.co2Ppm());
        record.setLightLux(request.lightLux() == null ? 0 : request.lightLux());
        record.setRecordedAt(recordedAt);
        record.setOverLimit(EnvironmentRules.isOverLimit(room.getSpecies(), record));
        Long recordId = environmentRepository.save(record);

        try {
            reconcile(room);
        } catch (DuplicateKeyException e) {
            // 并发兜底：唯一索引 uk_disposal_active_room
            throw new BizException("该菇房已存在活动处置单，请勿并发重复上报");
        }
        return recordId;
    }

    /**
     * 按采集时间有序的读数后缀重算本菇房处置单状态。调用前必须已持有菇房行锁。
     */
    private void reconcile(GrowRoom room) {
        List<EnvironmentRecord> window = environmentRepository.findRecentOrdered(room.getId(), 200);
        if (window.isEmpty()) {
            return;
        }
        int overRun = trailingRun(window, true);
        int normalRun = trailingRun(window, false);
        List<EnvironmentRecord> overSuffix = overRun == 0 ? List.of()
                : window.subList(window.size() - overRun, window.size());
        List<EnvironmentRecord> normalSuffix = normalRun == 0 ? List.of()
                : window.subList(window.size() - normalRun, window.size());

        EnvironmentDisposalOrder active = disposalRepository.findActiveByRoom(room.getId()).orElse(null);

        if (active == null) {
            // 只有“当前最新连续三条”齐了才开单；迟到补进历史缺口的读数进不了当前后缀，不会误触发
            if (overRun >= EnvironmentRules.TRIGGER_REQUIRED) {
                createOrder(room, overSuffix);
            }
            return;
        }

        switch (active.getStatus()) {
            case EnvironmentDisposalOrder.STATUS_PENDING,
                 EnvironmentDisposalOrder.STATUS_PROCESSING -> {
                accumulateOverReadings(active, room, overSuffix);
                rebuildRecoveryProgress(active, normalSuffix);
                // 进入恢复待确认：按采集时间有序的“最近六条”全部正常。
                // 迟到读数若落在六条窗口内，会直接体现在窗口判定里，凑不数也冲不掉事实。
                if (window.size() >= EnvironmentRules.RECOVERY_REQUIRED
                        && latestAllNormal(window, EnvironmentRules.RECOVERY_REQUIRED)) {
                    active.setStatus(EnvironmentDisposalOrder.STATUS_RECOVERING);
                    List<EnvironmentRecord> six = lastN(window, EnvironmentRules.RECOVERY_REQUIRED);
                    active.setRecoveredCount(EnvironmentRules.RECOVERY_REQUIRED);
                    active.setRecoveredSince(six.get(0).getRecordedAt());
                    disposalRepository.updateOrder(active);
                    addEvent(active.getId(), EnvironmentDisposalEvent.RECOVER_PENDING,
                            "连续 " + EnvironmentRules.RECOVERY_REQUIRED
                                    + " 条读数恢复到适宜范围（" + six.get(0).getRecordedAt().format(TS)
                                    + " ~ " + six.get(5).getRecordedAt().format(TS) + "），等待值班人员确认解除",
                            "系统", null);
                }
            }
            case EnvironmentDisposalOrder.STATUS_RECOVERING -> {
                List<EnvironmentRecord> six = window.size() >= EnvironmentRules.RECOVERY_REQUIRED
                        ? lastN(window, EnvironmentRules.RECOVERY_REQUIRED) : List.of();
                boolean sixAllNormal = six.size() == EnvironmentRules.RECOVERY_REQUIRED
                        && six.stream().allMatch(r -> !Boolean.TRUE.equals(r.getOverLimit()));
                if (!sixAllNormal) {
                    // 确认前再次越限（含乱序插入六条窗口内的越限读数）立即退回处理中，不能直接解除
                    List<EnvironmentRecord> newOvers = six.stream()
                            .filter(r -> Boolean.TRUE.equals(r.getOverLimit())
                                    && !disposalRepository.existsReading(active.getId(), r.getId()))
                            .toList();
                    EnvironmentRecord latestOver = newOvers.isEmpty()
                            ? six.stream().filter(r -> Boolean.TRUE.equals(r.getOverLimit()))
                                    .reduce((a, b) -> b).orElseGet(() -> overSuffix.isEmpty() ? null : overSuffix.get(overSuffix.size() - 1))
                            : newOvers.get(newOvers.size() - 1);
                    active.setStatus(EnvironmentDisposalOrder.STATUS_PROCESSING);
                    active.setRecoveredCount(0);
                    active.setRecoveredSince(null);
                    disposalRepository.deleteRecoveryLinks(active.getId());
                    for (EnvironmentRecord r : newOvers) {
                        linkReading(active.getId(), r, EnvironmentDisposalReading.ROLE_OVER);
                    }
                    if (!newOvers.isEmpty()) {
                        active.setOverCount(countLinks(active.getId(), EnvironmentDisposalReading.ROLE_OVER)
                                + countLinks(active.getId(), EnvironmentDisposalReading.ROLE_TRIGGER));
                    }
                    if (latestOver != null) {
                        markOver(active, latestOver);
                    }
                    disposalRepository.updateOrder(active);
                    String when = latestOver != null ? "（" + readingBrief(room, latestOver) + "）" : "";
                    addEvent(active.getId(), EnvironmentDisposalEvent.RELAPSE,
                            "恢复确认期间连续恢复读数被打断" + when + "，退回处理中，需重新累计连续 "
                                    + EnvironmentRules.RECOVERY_REQUIRED + " 条正常读数",
                            "系统", latestOver == null ? null : latestOver.getId());
                } else {
                    // 六条仍全部正常（可能只是窗口继续延长）：计数与链接以当前正常后缀为准
                    rebuildRecoveryProgress(active, normalSuffix);
                }
            }
            default -> {
            }
        }
    }

    /** 最近 n 条读数是否全部处于适宜范围。 */
    private boolean latestAllNormal(List<EnvironmentRecord> window, int n) {
        if (window.size() < n) {
            return false;
        }
        return lastN(window, n).stream().noneMatch(r -> Boolean.TRUE.equals(r.getOverLimit()));
    }

    private List<EnvironmentRecord> lastN(List<EnvironmentRecord> window, int n) {
        return window.subList(window.size() - n, window.size());
    }

    // ============================== 开单 ==============================

    private void createOrder(GrowRoom room, List<EnvironmentRecord> overSuffix) {
        List<EnvironmentRecord> triggers = overSuffix.subList(overSuffix.size() - EnvironmentRules.TRIGGER_REQUIRED,
                overSuffix.size());

        Set<String> metricSet = new HashSet<>();
        for (EnvironmentRecord r : triggers) {
            metricSet.addAll(EnvironmentRules.exceededMetrics(room.getSpecies(), r));
        }
        List<String> metrics = EnvironmentRules.METRIC_NAMES.stream().filter(metricSet::contains).toList();

        LocalDateTime now = LocalDateTime.now();
        EnvironmentDisposalOrder order = new EnvironmentDisposalOrder();
        order.setOrderNo(room.getRoomCode() + "-HJ" + triggers.get(2).getRecordedAt()
                .format(DateTimeFormatter.ofPattern("MMddHHmm")) + String.format("%02d", triggers.get(2).getId() % 100));
        order.setRoomId(room.getId());
        order.setStatus(EnvironmentDisposalOrder.STATUS_PENDING);
        order.setTriggerMetrics(String.join(",", metrics));
        order.setTriggerTemperature(triggers.get(2).getTemperature());
        order.setTriggerHumidity(triggers.get(2).getHumidity());
        order.setTriggerCo2Ppm(triggers.get(2).getCo2Ppm());
        order.setTriggerRecordedStart(triggers.get(0).getRecordedAt());
        order.setTriggerRecordedEnd(triggers.get(2).getRecordedAt());
        order.setTriggeredAt(now);
        order.setOverCount(overSuffix.size());
        order.setRecoveredCount(0);
        markOver(order, triggers.get(2));
        order.setActiveFlag(1);
        Long orderId = disposalRepository.insertOrder(order);
        order.setId(orderId);

        // 同一读数只能关联一种角色：最后三条为 TRIGGER，越限后缀其余读数为 OVER
        for (EnvironmentRecord r : overSuffix) {
            boolean isTrigger = r.getId() >= triggers.get(0).getId();
            linkReading(orderId, r, isTrigger
                    ? EnvironmentDisposalReading.ROLE_TRIGGER
                    : EnvironmentDisposalReading.ROLE_OVER);
        }

        addEvent(orderId, EnvironmentDisposalEvent.TRIGGERED,
                "连续三条读数越限（" + triggers.get(0).getRecordedAt().format(TS) + " ~ "
                        + triggers.get(2).getRecordedAt().format(TS) + "），指标：" + String.join("、", metrics)
                        + "；触发读数 温度" + triggers.get(2).getTemperature() + "℃ 湿度"
                        + triggers.get(2).getHumidity() + "% CO₂" + triggers.get(2).getCo2Ppm() + "ppm",
                "系统", triggers.get(2).getId());

        // 把仍在生产的出菇批次暂停，逐批快照原状态；人工暂停或已结束的批次不动
        List<FruitingSnapshot> batches = disposalRepository.findProducingBatches(room.getId());
        for (FruitingSnapshot b : batches) {
            if (disposalRepository.pauseBatch(b.batchId()) == 1) {
                EnvironmentDisposalBatch snapshot = new EnvironmentDisposalBatch();
                snapshot.setOrderId(orderId);
                snapshot.setBatchId(b.batchId());
                snapshot.setBatchNo(b.batchNo());
                snapshot.setOriginalStatus(b.status());
                disposalRepository.insertBatchSnapshot(snapshot);
            }
        }
    }

    // ============================== 处置期累积 ==============================

    /**
     * 处理中/待接单期间，把当前越限后缀里尚未挂到本单的读数累积进来（保留经办轨迹所依赖的原始读数）。
     * 仅对本次新出现的越限读数追加事件；迟到进历史段的读数不产生事件。
     */
    private void accumulateOverReadings(EnvironmentDisposalOrder order, GrowRoom room,
            List<EnvironmentRecord> overSuffix) {
        boolean changed = false;
        boolean worsened = false;
        Set<String> triggerMetrics = Set.of(order.getTriggerMetrics().split(","));

        for (EnvironmentRecord r : overSuffix) {
            if (disposalRepository.existsReading(order.getId(), r.getId())) {
                continue;
            }
            linkReading(order.getId(), r, EnvironmentDisposalReading.ROLE_OVER);
            changed = true;
            List<String> currentMetrics = EnvironmentRules.exceededMetrics(room.getSpecies(), r);
            if (currentMetrics.stream().anyMatch(m -> !triggerMetrics.contains(m))) {
                worsened = true;
            }
        }

        if (changed) {
            int totalOver = countLinks(order.getId(), EnvironmentDisposalReading.ROLE_OVER)
                    + countLinks(order.getId(), EnvironmentDisposalReading.ROLE_TRIGGER);
            order.setOverCount(totalOver);
            markOver(order, overSuffix.get(overSuffix.size() - 1));
            disposalRepository.updateOrder(order);

            EnvironmentRecord latestOver = overSuffix.get(overSuffix.size() - 1);
            String type = worsened ? EnvironmentDisposalEvent.OVER_WORSENED : EnvironmentDisposalEvent.OVER_CONTINUED;
            String prefix = worsened ? "异常恶化，出现触发之外的越限指标" : "异常持续";
            addEvent(order.getId(), type,
                    prefix + "（累计越限读数 " + totalOver + " 条，最新：" + readingBrief(room, latestOver) + "）",
                    "系统", latestOver.getId());
        }
    }

    /** 以当前连续正常后缀重建 RECOVERY 关联与计数（DELETE+INSERT 幂等，迟到读数无法凑数）。 */
    private void rebuildRecoveryProgress(EnvironmentDisposalOrder order, List<EnvironmentRecord> normalSuffix) {
        disposalRepository.deleteRecoveryLinks(order.getId());
        int capped = Math.min(normalSuffix.size(), EnvironmentRules.RECOVERY_REQUIRED);
        for (int i = normalSuffix.size() - capped; i < normalSuffix.size(); i++) {
            linkReading(order.getId(), normalSuffix.get(i), EnvironmentDisposalReading.ROLE_RECOVERY);
        }
        order.setRecoveredCount(normalSuffix.size());
        if (capped > 0) {
            order.setRecoveredSince(normalSuffix.get(normalSuffix.size() - capped).getRecordedAt());
        }
        disposalRepository.updateOrder(order);
    }    // ============================== 值班动作：接单 / 进度 / 解除 ==============================

    @Transactional
    public EnvironmentDisposalOrder accept(Long orderId, DisposalActionRequest request) {
        String operator = requireOperator(request);
        EnvironmentDisposalOrder order = lockAndGetOrder(orderId);
        if (EnvironmentDisposalOrder.STATUS_RESOLVED.equals(order.getStatus())) {
            throw new BizException("处置单已解除，无需接单");
        }
        if (EnvironmentDisposalOrder.STATUS_RECOVERING.equals(order.getStatus())) {
            throw new BizException("处置单已进入恢复待确认，请直接确认解除或等待观察");
        }
        if (order.getAssignee() != null) {
            throw new BizException("该处置单已由 " + order.getAssignee() + " 接单，不能重复接单");
        }
        LocalDateTime expectedFinish = request.expectedFinishAt();
        if (expectedFinish != null && order.getTriggeredAt() != null
                && expectedFinish.isBefore(order.getTriggeredAt())) {
            throw new BizException("预计完成时间不能早于触发时间");
        }
        // 条件 UPDATE 原子抢占：并发两人接单只可能一人 affectedRows=1
        int updated = disposalRepository.acceptOrder(orderId, operator, LocalDateTime.now(), expectedFinish);
        if (updated == 0) {
            throw new BizException("接单失败，处置单可能已被其他值班人员接走，请刷新后重试");
        }
        EnvironmentDisposalOrder reloaded = disposalRepository.findById(orderId).orElseThrow();
        addEvent(orderId, EnvironmentDisposalEvent.ACCEPTED,
                "值班人员 " + operator + " 接单"
                        + (expectedFinish != null ? "，预计完成时间 " + expectedFinish.format(TS) : "")
                        + (text(request.note()) != null ? "，处理记录：" + text(request.note()) : ""),
                operator, null);
        return reloaded;
    }

    @Transactional
    public EnvironmentDisposalOrder progress(Long orderId, DisposalActionRequest request) {
        String operator = requireOperator(request);
        EnvironmentDisposalOrder order = lockAndGetOrder(orderId);
        if (EnvironmentDisposalOrder.STATUS_RESOLVED.equals(order.getStatus())) {
            throw new BizException("处置单已解除，不能再填写处理记录");
        }
        String note = text(request.note());
        if (note == null && request.expectedFinishAt() == null) {
            throw new BizException("请填写处理记录或调整预计完成时间");
        }
        int updated = disposalRepository.updateProgress(orderId, request.expectedFinishAt());
        if (updated == 0) {
            throw new BizException("处置单状态已变化，请刷新后重试");
        }
        StringBuilder detail = new StringBuilder("值班人员 ").append(operator).append(" 更新处置进展");
        if (request.expectedFinishAt() != null) {
            detail.append("，预计完成时间调整为 ").append(request.expectedFinishAt().format(TS));
        }
        if (note != null) {
            detail.append("，处理记录：").append(note);
        }
        addEvent(orderId, EnvironmentDisposalEvent.PROGRESS, detail.toString(), operator, null);
        return disposalRepository.findById(orderId).orElseThrow();
    }

    @Transactional
    public EnvironmentDisposalOrder resolve(Long orderId, DisposalActionRequest request) {
        String operator = requireOperator(request);
        EnvironmentDisposalOrder order = lockAndGetOrder(orderId);
        if (EnvironmentDisposalOrder.STATUS_RESOLVED.equals(order.getStatus())) {
            throw new BizException("处置单已解除，请勿重复操作");
        }
        if (!EnvironmentDisposalOrder.STATUS_RECOVERING.equals(order.getStatus())) {
            throw new BizException("必须连续 " + EnvironmentRules.RECOVERY_REQUIRED
                    + " 条读数恢复适宜并进入“恢复待确认”后，才能解除处置单");
        }

        LocalDateTime now = LocalDateTime.now();
        List<String> restoredBatches = new ArrayList<>();
        for (EnvironmentDisposalBatch snapshot : disposalRepository.findBatchSnapshots(orderId)) {
            // 只恢复当前仍是“环境暂停”的本单批次；已结束/人工暂停/被改过的批次不动
            if (disposalRepository.restoreBatch(snapshot.getBatchId(), snapshot.getOriginalStatus()) == 1) {
                disposalRepository.markSnapshotRestored(snapshot.getId(), now);
                restoredBatches.add(snapshot.getBatchNo() + "（恢复为" + snapshot.getOriginalStatus() + "）");
            }
        }

        order.setStatus(EnvironmentDisposalOrder.STATUS_RESOLVED);
        order.setActiveFlag(null);
        order.setResolvedBy(operator);
        order.setResolvedAt(now);
        order.setResolutionNote(text(request.note()));
        disposalRepository.updateOrder(order);

        addEvent(orderId, EnvironmentDisposalEvent.RESOLVED,
                "值班人员 " + operator + " 确认解除，生产恢复"
                        + (restoredBatches.isEmpty() ? "（本单暂停的批次均已结束或被人工调整，无需恢复）"
                                : "，恢复批次：" + String.join("、", restoredBatches))
                        + (text(request.note()) != null ? "；解除备注：" + text(request.note()) : ""),
                operator, null);
        return order;
    }

    // ============================== 查询 ==============================

    @Transactional(readOnly = true)
    public List<Map<String, Object>> orders(String status, Long roomId) {
        return disposalRepository.findOrders(status, roomId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long orderId) {
        EnvironmentDisposalOrder order = disposalRepository.findById(orderId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", order);
        GrowRoom room = roomRepository.findById(order.getRoomId()).orElse(null);
        result.put("roomCode", room == null ? null : room.getRoomCode());
        result.put("species", room == null ? null : room.getSpecies());
        result.put("events", disposalRepository.findEvents(orderId));
        result.put("readings", disposalRepository.findReadingLinks(orderId));
        List<Map<String, Object>> batches = new ArrayList<>();
        for (EnvironmentDisposalBatch b : disposalRepository.findBatchSnapshots(orderId)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("snapshot", b);
            fruitingRepository.findById(b.getBatchId()).map(FruitingBatch::getStatus).ifPresent(s -> item.put("currentStatus", s));
            batches.add(item);
        }
        result.put("batches", batches);
        return result;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeSummaries() {
        return disposalRepository.findActiveSummaries();
    }

    // ============================== 辅助方法 ==============================

    /** 先锁菇房（与上报同序，避免死锁），再锁处置单行。 */
    private EnvironmentDisposalOrder lockAndGetOrder(Long orderId) {
        EnvironmentDisposalOrder order = disposalRepository.findById(orderId)
                .orElseThrow(() -> new BizException("处置单不存在"));
        environmentRepository.lockRoom(order.getRoomId());
        return disposalRepository.findById(orderId).orElseThrow(() -> new BizException("处置单不存在"));
    }

    private int countLinks(Long orderId, String role) {
        return (int) disposalRepository.findReadingLinks(orderId).stream()
                .filter(link -> role.equals(String.valueOf(link.get("role"))))
                .count();
    }

    private void markOver(EnvironmentDisposalOrder order, EnvironmentRecord r) {
        order.setLastOverAt(r.getRecordedAt());
        order.setLastOverTemperature(r.getTemperature());
        order.setLastOverHumidity(r.getHumidity());
        order.setLastOverCo2Ppm(r.getCo2Ppm());
    }

    private void linkReading(Long orderId, EnvironmentRecord r, String role) {
        if (disposalRepository.existsReading(orderId, r.getId())) {
            return;
        }
        EnvironmentDisposalReading link = new EnvironmentDisposalReading();
        link.setOrderId(orderId);
        link.setRecordId(r.getId());
        link.setRole(role);
        link.setLinkedAt(LocalDateTime.now());
        disposalRepository.insertReading(link);
    }

    private void addEvent(Long orderId, String type, String detail, String operator, Long refRecordId) {
        EnvironmentDisposalEvent event = new EnvironmentDisposalEvent();
        event.setOrderId(orderId);
        event.setEventType(type);
        event.setDetail(detail);
        event.setOperator(operator == null ? "系统" : operator);
        event.setRefRecordId(refRecordId);
        event.setOccurredAt(LocalDateTime.now());
        disposalRepository.insertEvent(event);
    }

    /** 窗口末尾连续 overLimit(=expected) 的条数。 */
    private int trailingRun(List<EnvironmentRecord> window, boolean over) {
        int run = 0;
        for (int i = window.size() - 1; i >= 0; i--) {
            if (Boolean.TRUE.equals(window.get(i).getOverLimit()) == over) {
                run++;
            } else {
                break;
            }
        }
        return run;
    }

    private String readingBrief(GrowRoom room, EnvironmentRecord r) {
        return r.getRecordedAt().format(TS) + " " + String.join("、",
                EnvironmentRules.exceededMetrics(room.getSpecies(), r))
                + "（温度" + r.getTemperature() + "℃ 湿度" + r.getHumidity() + "% CO₂" + r.getCo2Ppm() + "ppm）";
    }

    private String requireOperator(DisposalActionRequest request) {
        if (request == null || request.operator() == null || request.operator().isBlank()) {
            throw new BizException("值班人员姓名不能为空");
        }
        String operator = request.operator().trim();
        if (operator.length() > 40) {
            throw new BizException("值班人员姓名最长 40 个字符");
        }
        return operator;
    }

    private String text(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }

    private boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && a.compareTo(b) == 0;
    }
}
