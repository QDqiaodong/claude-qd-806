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
        String sql = "SELECT * FROM environment_record WHERE room_id=:roomId ORDER BY recorded_at DESC LIMIT 1";
        return jdbc.query(sql, Map.of("roomId", roomId), mapper).stream().findFirst();
    }

    public List<Map<String, Object>> findDashboard() {
        String sql = """
                SELECT gr.id AS room_id, gr.room_code, gr.species, gr.status,
                       er.temperature, er.humidity, er.co2_ppm, er.light_lux,
                       er.recorded_at, er.over_limit
                FROM grow_room gr
                JOIN environment_record er ON er.id = (
                    SELECT e2.id FROM environment_record e2
                    WHERE e2.room_id = gr.id ORDER BY e2.recorded_at DESC LIMIT 1
                )
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
        sql.append(" ORDER BY e.recorded_at DESC LIMIT 300");
        return jdbc.query(sql.toString(), params, mapper);
    }

    public List<EnvironmentRecord> findHistory(Long roomId, int hours) {
        String sql = """
                SELECT * FROM environment_record
                WHERE room_id=:roomId AND recorded_at >= DATE_SUB(
                    (SELECT MAX(recorded_at) FROM environment_record WHERE room_id=:roomId), INTERVAL :hours HOUR)
                ORDER BY recorded_at
                """;
        return jdbc.query(sql, new MapSqlParameterSource().addValue("roomId", roomId).addValue("hours", hours), mapper);
    }

    public Long save(EnvironmentRecord record) {
        Number key = insert.executeAndReturnKey(new BeanPropertySqlParameterSource(record));
        return key.longValue();
    }
}
