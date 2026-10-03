package com.fungus.farm.repository;

import com.fungus.farm.domain.EnvironmentRecord;
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
public class EnvironmentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final SimpleJdbcInsert insert;
    private final BeanPropertyRowMapper<EnvironmentRecord> mapper = BeanPropertyRowMapper.newInstance(EnvironmentRecord.class);

    public EnvironmentRepository(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.insert = new SimpleJdbcInsert(dataSource).withTableName("environment_record").usingGeneratedKeyColumns("id");
    }

    public Optional<EnvironmentRecord> findLatestByRoomId(Long roomId) {
        String sql = "SELECT * FROM environment_record WHERE room_id=:roomId ORDER BY recorded_at DESC, id DESC LIMIT 1";
        return jdbc.query(sql, Map.of("roomId", roomId), mapper).stream().findFirst();
    }

    public Optional<EnvironmentRecord> findByRoomAndTime(Long roomId, LocalDateTime recordedAt) {
        String sql = "SELECT * FROM environment_record WHERE room_id=:roomId AND recorded_at=:recordedAt";
        return jdbc.query(sql,
                new MapSqlParameterSource().addValue("roomId", roomId).addValue("recordedAt", recordedAt),
                mapper).stream().findFirst();
    }

    /**
     * 处置对账所需的“当前判断窗口”：按采集时间升序取最近若干条，迟到/乱序读数入库后
     * 在这里自动落位。同一菇房的上报事务必须先 {@link #lockRoom} 串行化再读本窗口。
     */
    public List<EnvironmentRecord> findRecentOrdered(Long roomId, int limit) {
        String sql = """
                SELECT * FROM (
                    SELECT * FROM environment_record WHERE room_id=:roomId
                    ORDER BY recorded_at DESC, id DESC LIMIT :limit
                ) t ORDER BY recorded_at ASC, id ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource().addValue("roomId", roomId).addValue("limit", limit), mapper);
    }

    /** 对菇房加排他行锁，串行化同房间的上报/接单/解除等状态变更，杜绝并发双单。 */
    public void lockRoom(Long roomId) {
        jdbc.queryForObject("SELECT id FROM grow_room WHERE id=:roomId FOR UPDATE",
                Map.of("roomId", roomId), Long.class);
    }

    public List<Map<String, Object>> findDashboard() {
        String sql = """
                SELECT gr.id AS room_id, gr.room_code, gr.species, gr.status,
                       er.temperature, er.humidity, er.co2_ppm, er.light_lux,
                       er.recorded_at, er.over_limit,
                       edo.id AS disposal_id, edo.status AS disposal_status
                FROM grow_room gr
                JOIN environment_record er ON er.id = (
                    SELECT e2.id FROM environment_record e2
                    WHERE e2.room_id = gr.id ORDER BY e2.recorded_at DESC, e2.id DESC LIMIT 1
                )
                LEFT JOIN environment_disposal_order edo ON edo.room_id = gr.id AND edo.active_flag = 1
                ORDER BY gr.room_code
                """;
        return jdbc.queryForList(sql, Map.of());
    }

    public List<EnvironmentRecord> findRecords(Long roomId, Boolean overLimit, LocalDateTime from, LocalDateTime to) {
        StringBuilder sql = new StringBuilder("SELECT e.* FROM environment_record e WHERE 1=1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (roomId != null) {
            sql.append(" AND e.room_id=:roomId");
            params.addValue("roomId", roomId);
        }
        if (overLimit != null) {
            sql.append(" AND e.over_limit=:overLimit");
            params.addValue("overLimit", overLimit);
        }
        if (from != null) {
            sql.append(" AND e.recorded_at>=:fromTime");
            params.addValue("fromTime", from);
        }
        if (to != null) {
            sql.append(" AND e.recorded_at<=:toTime");
            params.addValue("toTime", to);
        }
        sql.append(" ORDER BY e.recorded_at DESC, e.id DESC LIMIT 300");
        return jdbc.query(sql.toString(), params, mapper);
    }

    public List<EnvironmentRecord> findHistory(Long roomId, int hours) {
        String sql = """
                SELECT * FROM environment_record
                WHERE room_id=:roomId AND recorded_at >= DATE_SUB(
                    (SELECT MAX(recorded_at) FROM environment_record WHERE room_id=:roomId), INTERVAL :hours HOUR)
                ORDER BY recorded_at, id
                """;
        return jdbc.query(sql, new MapSqlParameterSource().addValue("roomId", roomId).addValue("hours", hours), mapper);
    }

    public Long save(EnvironmentRecord record) {
        Number key = insert.executeAndReturnKey(new BeanPropertySqlParameterSource(record));
        return key.longValue();
    }

    public void update(EnvironmentRecord record) {
        jdbc.update("""
                UPDATE environment_record
                SET temperature=:temperature, humidity=:humidity, co2_ppm=:co2Ppm,
                    light_lux=:lightLux, over_limit=:overLimit
                WHERE id=:id
                """, new BeanPropertySqlParameterSource(record));
    }
}
