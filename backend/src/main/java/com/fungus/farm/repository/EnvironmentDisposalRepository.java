package com.fungus.farm.repository;

import com.fungus.farm.domain.EnvironmentDisposalBatch;
import com.fungus.farm.domain.EnvironmentDisposalEvent;
import com.fungus.farm.domain.EnvironmentDisposalOrder;
import com.fungus.farm.domain.EnvironmentDisposalReading;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class EnvironmentDisposalRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert orderInsert;
    private final SimpleJdbcInsert eventInsert;
    private final SimpleJdbcInsert batchInsert;
    private final SimpleJdbcInsert readingInsert;
    private final BeanPropertyRowMapper<EnvironmentDisposalOrder> orderMapper =
            BeanPropertyRowMapper.newInstance(EnvironmentDisposalOrder.class);
    private final BeanPropertyRowMapper<EnvironmentDisposalEvent> eventMapper =
            BeanPropertyRowMapper.newInstance(EnvironmentDisposalEvent.class);
    private final BeanPropertyRowMapper<EnvironmentDisposalBatch> batchMapper =
            BeanPropertyRowMapper.newInstance(EnvironmentDisposalBatch.class);
    private final BeanPropertyRowMapper<EnvironmentDisposalReading> readingMapper =
            BeanPropertyRowMapper.newInstance(EnvironmentDisposalReading.class);

    public EnvironmentDisposalRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.orderInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_disposal_order")
                .usingColumns("order_no", "room_id", "status", "trigger_metrics",
                        "trigger_temperature", "trigger_humidity", "trigger_co2_ppm",
                        "trigger_recorded_start", "trigger_recorded_end", "triggered_at",
                        "assignee", "accepted_at", "expected_finish_at",
                        "over_count", "recovered_count",
                        "last_over_at", "last_over_temperature", "last_over_humidity", "last_over_co2_ppm",
                        "recovered_since", "resolution_note", "resolved_by", "resolved_at", "active_flag")
                .usingGeneratedKeyColumns("id");
        this.eventInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_disposal_event")
                .usingColumns("order_id", "event_type", "detail", "operator", "ref_record_id", "occurred_at")
                .usingGeneratedKeyColumns("id");
        this.batchInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_disposal_batch")
                .usingColumns("order_id", "batch_id", "batch_no", "original_status", "restored_at")
                .usingGeneratedKeyColumns("id");
        this.readingInsert = new SimpleJdbcInsert(dataSource).withTableName("environment_disposal_reading")
                .usingColumns("order_id", "record_id", "role", "linked_at")
                .usingGeneratedKeyColumns("id");
    }

    // ---------------- 处置单 ----------------

    public Optional<EnvironmentDisposalOrder> findActiveByRoom(Long roomId) {
        String sql = "SELECT * FROM environment_disposal_order WHERE room_id=:roomId AND active_flag=1";
        return jdbc.query(sql, Map.of("roomId", roomId), orderMapper).stream().findFirst();
    }

    public Optional<EnvironmentDisposalOrder> findById(Long id) {
        return jdbc.query("SELECT * FROM environment_disposal_order WHERE id=:id", Map.of("id", id), orderMapper)
                .stream().findFirst();
    }

    public List<Map<String, Object>> findOrders(String status, Long roomId) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.*, gr.room_code, gr.species
                FROM environment_disposal_order o
                JOIN grow_room gr ON gr.id = o.room_id
                WHERE 1=1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (status != null && !status.isBlank()) {
            sql.append(" AND o.status=:status");
            params.addValue("status", status);
        }
        if (roomId != null) {
            sql.append(" AND o.room_id=:roomId");
            params.addValue("roomId", roomId);
        }
        sql.append(" ORDER BY o.active_flag DESC, o.triggered_at DESC, o.id DESC");
        return jdbc.queryForList(sql.toString(), params);
    }

    public List<Map<String, Object>> findActiveSummaries() {
        return jdbc.queryForList("""
                SELECT o.id, o.room_id, o.order_no, o.status, gr.room_code
                FROM environment_disposal_order o
                JOIN grow_room gr ON gr.id = o.room_id
                WHERE o.active_flag=1
                ORDER BY gr.room_code
                """, Map.of());
    }

    public Long insertOrder(EnvironmentDisposalOrder order) {
        return orderInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(order)).longValue();
    }

    public void updateOrder(EnvironmentDisposalOrder order) {
        jdbc.update("""
                UPDATE environment_disposal_order SET
                    status=:status, trigger_metrics=:triggerMetrics,
                    trigger_temperature=:triggerTemperature, trigger_humidity=:triggerHumidity,
                    trigger_co2_ppm=:triggerCo2Ppm,
                    trigger_recorded_start=:triggerRecordedStart, trigger_recorded_end=:triggerRecordedEnd,
                    assignee=:assignee, accepted_at=:acceptedAt, expected_finish_at=:expectedFinishAt,
                    over_count=:overCount, recovered_count=:recoveredCount,
                    last_over_at=:lastOverAt, last_over_temperature=:lastOverTemperature,
                    last_over_humidity=:lastOverHumidity, last_over_co2_ppm=:lastOverCo2Ppm,
                    recovered_since=:recoveredSince, resolution_note=:resolutionNote,
                    resolved_by=:resolvedBy, resolved_at=:resolvedAt, active_flag=:activeFlag
                WHERE id=:id
                """, new BeanPropertySqlParameterSource(order));
    }

    /**
     * 原子接单：仅当处置单仍无人接单时成功，两人并发接单只有一人 affectedRows=1。
     * 已进入恢复待确认/已解除的单不再接单（确认解除走 resolve）。
     */
    public int acceptOrder(Long orderId, String assignee, LocalDateTime acceptedAt, LocalDateTime expectedFinishAt) {
        return jdbc.update("""
                UPDATE environment_disposal_order
                SET assignee=:assignee, accepted_at=:acceptedAt, expected_finish_at=:expectedFinishAt,
                    status=:toProcessing, updated_at=NOW()
                WHERE id=:id AND assignee IS NULL AND active_flag=1 AND status IN (:pending, :staysProcessing)
                """, new MapSqlParameterSource()
                        .addValue("assignee", assignee)
                        .addValue("acceptedAt", acceptedAt)
                        .addValue("expectedFinishAt", expectedFinishAt)
                        .addValue("toProcessing", EnvironmentDisposalOrder.STATUS_PROCESSING)
                        .addValue("id", orderId)
                        .addValue("pending", EnvironmentDisposalOrder.STATUS_PENDING)
                        .addValue("staysProcessing", EnvironmentDisposalOrder.STATUS_PROCESSING));
    }

    /**
     * 追加处置进展：只更新预计完成时间，经办人保持为原接单人（写进展的人只进时间线）。
     * expectedFinishAt 为 null 时不动业务字段。
     */
    public int updateProgress(Long orderId, LocalDateTime expectedFinishAt) {
        if (expectedFinishAt == null) {
            // 仅校验处置单仍处于可处理状态，不改任何字段
            Integer count = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM environment_disposal_order
                    WHERE id=:id AND active_flag=1 AND status IN (:processing, :recovering)
                    """, new MapSqlParameterSource()
                            .addValue("id", orderId)
                            .addValue("processing", EnvironmentDisposalOrder.STATUS_PROCESSING)
                            .addValue("recovering", EnvironmentDisposalOrder.STATUS_RECOVERING),
                    Integer.class);
            return count == null ? 0 : count;
        }
        return jdbc.update("""
                UPDATE environment_disposal_order
                SET expected_finish_at=:expectedFinishAt, updated_at=NOW()
                WHERE id=:id AND active_flag=1 AND status IN (:processing, :recovering)
                """, new MapSqlParameterSource()
                        .addValue("expectedFinishAt", expectedFinishAt)
                        .addValue("id", orderId)
                        .addValue("processing", EnvironmentDisposalOrder.STATUS_PROCESSING)
                        .addValue("recovering", EnvironmentDisposalOrder.STATUS_RECOVERING));
    }

    // ---------------- 时间线 ----------------

    public Long insertEvent(EnvironmentDisposalEvent event) {
        return eventInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(event)).longValue();
    }

    public List<EnvironmentDisposalEvent> findEvents(Long orderId) {
        return jdbc.query(
                "SELECT * FROM environment_disposal_event WHERE order_id=:orderId ORDER BY occurred_at, id",
                Map.of("orderId", orderId), eventMapper);
    }

    public boolean existsEvent(Long orderId, String eventType, Long refRecordId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM environment_disposal_event
                WHERE order_id=:orderId AND event_type=:eventType AND ref_record_id <=> :refRecordId
                """,
                new MapSqlParameterSource()
                        .addValue("orderId", orderId)
                        .addValue("eventType", eventType)
                        .addValue("refRecordId", refRecordId),
                Integer.class);
        return count != null && count > 0;
    }

    // ---------------- 暂停批次快照 ----------------

    /** 仍在生产（未结束、未被任何处置暂停、非人工暂停）的出菇批次。 */
    public List<FruitingSnapshot> findProducingBatches(Long roomId) {
        return jdbc.query("""
                SELECT id, batch_no, status FROM fruiting_batch
                WHERE room_id=:roomId AND status NOT IN ('已结束', '环境暂停', '人工暂停')
                ORDER BY id
                """, Map.of("roomId", roomId),
                (rs, rowNum) -> new FruitingSnapshot(rs.getLong("id"), rs.getString("batch_no"), rs.getString("status")));
    }

    public Long insertBatchSnapshot(EnvironmentDisposalBatch snapshot) {
        return batchInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(snapshot)).longValue();
    }

    public int pauseBatch(Long batchId) {
        return jdbc.update(
                "UPDATE fruiting_batch SET status='环境暂停' WHERE id=:id AND status NOT IN ('已结束','环境暂停','人工暂停')",
                Map.of("id", batchId));
    }

    public List<EnvironmentDisposalBatch> findBatchSnapshots(Long orderId) {
        return jdbc.query(
                "SELECT * FROM environment_disposal_batch WHERE order_id=:orderId ORDER BY id",
                Map.of("orderId", orderId), batchMapper);
    }

    /**
     * 解除恢复：只把当前仍是“环境暂停”的本单批次还原成原状态。
     * 已结束或被人工改过（不再等于环境暂停）的批次不受影响。
     */
    public int restoreBatch(Long batchId, String originalStatus) {
        return jdbc.update(
                "UPDATE fruiting_batch SET status=:originalStatus WHERE id=:id AND status='环境暂停'",
                new MapSqlParameterSource().addValue("originalStatus", originalStatus).addValue("id", batchId));
    }

    public void markSnapshotRestored(Long snapshotId, LocalDateTime restoredAt) {
        jdbc.update("UPDATE environment_disposal_batch SET restored_at=:restoredAt WHERE id=:id",
                new MapSqlParameterSource().addValue("id", snapshotId).addValue("restoredAt", restoredAt));
    }

    // ---------------- 读数关联 ----------------

    public Long insertReading(EnvironmentDisposalReading reading) {
        return readingInsert.executeAndReturnKey(new BeanPropertySqlParameterSource(reading)).longValue();
    }

    public List<Map<String, Object>> findReadingLinks(Long orderId) {
        return jdbc.queryForList("""
                SELECT l.role, e.id AS record_id, e.temperature, e.humidity, e.co2_ppm, e.light_lux,
                       e.recorded_at, e.over_limit
                FROM environment_disposal_reading l
                JOIN environment_record e ON e.id = l.record_id
                WHERE l.order_id=:orderId
                ORDER BY e.recorded_at, e.id
                """, Map.of("orderId", orderId));
    }

    public List<EnvironmentDisposalReading> findRecoveryLinks(Long orderId) {
        return jdbc.query(
                "SELECT * FROM environment_disposal_reading WHERE order_id=:orderId AND role='RECOVERY' ORDER BY id",
                Map.of("orderId", orderId), readingMapper);
    }

    public boolean existsReading(Long orderId, Long recordId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM environment_disposal_reading WHERE order_id=:orderId AND record_id=:recordId",
                new MapSqlParameterSource().addValue("orderId", orderId).addValue("recordId", recordId),
                Integer.class);
        return count != null && count > 0;
    }

    public int deleteRecoveryLinks(Long orderId) {
        return jdbc.update(
                "DELETE FROM environment_disposal_reading WHERE order_id=:orderId AND role='RECOVERY'",
                Map.of("orderId", orderId));
    }

    /** 仍在生产中的出菇批次行（用于暂停时快照）。 */
    public record FruitingSnapshot(Long batchId, String batchNo, String status) {
    }
}
