package com.interviewlab.workbench.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {
    private final JdbcTemplate jdbc;

    public ScheduleController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    List<Appointment> list() {
        return jdbc.query("""
            select id, company, position, starts_at, kind, location, notes, status
            from scheduled_interview order by starts_at, id
            """, this::appointment);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Appointment create(@Valid @RequestBody ScheduleData data) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            insert into scheduled_interview(id, company, position, starts_at, kind, location, notes, status)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, data.company().strip(), data.position().strip(), data.time(), data.type().name(),
            trim(data.location()), trim(data.notes()), data.status().name());
        return new Appointment(id, data.company().strip(), data.position().strip(), data.time(),
            data.type(), trim(data.location()), trim(data.notes()), data.status());
    }

    @PutMapping("/{id}")
    Appointment update(@PathVariable String id, @Valid @RequestBody ScheduleData data) {
        int changed = jdbc.update("""
            update scheduled_interview set company = ?, position = ?, starts_at = ?, kind = ?,
              location = ?, notes = ?, status = ? where id = ?
            """, data.company().strip(), data.position().strip(), data.time(), data.type().name(),
            trim(data.location()), trim(data.notes()), data.status().name(), id);
        verify(changed);
        return new Appointment(id, data.company().strip(), data.position().strip(), data.time(),
            data.type(), trim(data.location()), trim(data.notes()), data.status());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) {
        verify(jdbc.update("delete from scheduled_interview where id = ?", id));
    }

    private Appointment appointment(ResultSet row, int number) throws SQLException {
        return new Appointment(row.getString(1), row.getString(2), row.getString(3), row.getObject(4, LocalDateTime.class),
            Type.valueOf(row.getString(5)), row.getString(6), row.getString(7), Status.valueOf(row.getString(8)));
    }

    private String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private void verify(int changed) {
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "日程已不存在。");
        }
    }

    enum Type { ONLINE, ONSITE, PHONE }
    enum Status { PENDING, COMPLETED, CANCELLED }

    record ScheduleData(
        @NotBlank(message = "请输入公司名称。") @Size(max = 100) String company,
        @NotBlank(message = "请输入岗位名称。") @Size(max = 100) String position,
        @NotNull(message = "请选择面试时间。") LocalDateTime time, @NotNull Type type,
        @Size(max = 300) String location, @Size(max = 1000) String notes, @NotNull Status status) {}
    record Appointment(String id, String company, String position, LocalDateTime time,
                       Type type, String location, String notes, Status status) {}
}
