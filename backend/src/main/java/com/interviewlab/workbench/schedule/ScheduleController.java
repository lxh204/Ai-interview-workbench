package com.interviewlab.workbench.schedule;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
    private static final String COLUMNS = """
        id, company, position, starts_at, kind, location, notes, status,
        meeting_url, interviewer, round_number, reminder_minutes
        """;
    private final JdbcTemplate jdbc;
    private final ScheduleInvitationParser invitations;

    public ScheduleController(JdbcTemplate jdbc, ScheduleInvitationParser invitations) {
        this.jdbc = jdbc;
        this.invitations = invitations;
    }

    @GetMapping
    List<Appointment> list() {
        expire();
        return jdbc.query("select " + COLUMNS + " from scheduled_interview order by starts_at, id", this::appointment);
    }

    @PostMapping("/parse")
    ScheduleInvitationParser.Parsed parseInvitation(@Valid @RequestBody Invitation data) {
        return invitations.parse(data.text(), data.referenceDate() == null ? LocalDate.now() : data.referenceDate(),
            data.useModel());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Appointment create(@Valid @RequestBody ScheduleData data) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            insert into scheduled_interview(id, company, position, starts_at, kind, location, notes, status,
              meeting_url, interviewer, round_number, reminder_minutes)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, id, data.company().strip(), data.position().strip(), data.time(), data.type().name(),
            trim(data.location()), trim(data.notes()), statusForTime(data.status(), data.time()).name(),
            meetingLink(data.meetingUrl()), trim(data.interviewer()), data.round(), reminder(data.reminderMinutes()));
        return find(id);
    }

    @PutMapping("/{id}")
    Appointment update(@PathVariable String id, @Valid @RequestBody ScheduleData data) {
        int changed = jdbc.update("""
            update scheduled_interview set company = ?, position = ?, starts_at = ?, kind = ?, location = ?,
              notes = ?, status = ?, meeting_url = ?, interviewer = ?, round_number = ?, reminder_minutes = ?
            where id = ?
            """, data.company().strip(), data.position().strip(), data.time(), data.type().name(),
            trim(data.location()), trim(data.notes()), statusForTime(data.status(), data.time()).name(),
            meetingLink(data.meetingUrl()), trim(data.interviewer()), data.round(), reminder(data.reminderMinutes()), id);
        verify(changed);
        return find(id);
    }

    @PatchMapping("/{id}/time")
    Appointment move(@PathVariable String id, @Valid @RequestBody Move data) {
        LocalDateTime now = LocalDateTime.now();
        int changed = jdbc.update("""
            update scheduled_interview
            set starts_at = ?, status = case
                when status in ('PENDING', 'EXPIRED', 'RESCHEDULED') and ? > ? then 'RESCHEDULED'
                when status in ('PENDING', 'RESCHEDULED') and ? <= ? then 'EXPIRED'
                else status end
            where id = ?
            """, data.time(), data.time(), now, data.time(), now, id);
        verify(changed);
        return find(id);
    }

    @PatchMapping("/{id}/status")
    Appointment updateStatus(@PathVariable String id, @Valid @RequestBody StateChange data) {
        int changed = jdbc.update("""
            update scheduled_interview set status = case
                when ? in ('PENDING', 'RESCHEDULED') and starts_at < ? then 'EXPIRED'
                else ? end where id = ?
            """, data.status().name(), LocalDateTime.now(), data.status().name(), id);
        verify(changed);
        return find(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) {
        verify(jdbc.update("delete from scheduled_interview where id = ?", id));
    }

    @Scheduled(initialDelay = 30_000, fixedDelay = 60_000)
    void expire() {
        jdbc.update("update scheduled_interview set status='EXPIRED' where status in ('PENDING', 'RESCHEDULED') and starts_at < ?",
            LocalDateTime.now());
    }

    private Appointment find(String id) {
        return jdbc.query("select " + COLUMNS + " from scheduled_interview where id=?", this::appointment, id)
            .stream().findFirst().orElseThrow(this::missing);
    }

    private Appointment appointment(ResultSet row, int number) throws SQLException {
        return new Appointment(row.getString("id"), row.getString("company"), row.getString("position"),
            row.getObject("starts_at", LocalDateTime.class), Type.valueOf(row.getString("kind")),
            row.getString("location"), row.getString("notes"), Status.valueOf(row.getString("status")),
            row.getString("meeting_url"), row.getString("interviewer"), row.getObject("round_number", Integer.class),
            row.getInt("reminder_minutes"));
    }

    private Status statusForTime(Status status, LocalDateTime date) {
        if ((status == Status.PENDING || status == Status.RESCHEDULED) && date.isBefore(LocalDateTime.now())) return Status.EXPIRED;
        if (status == Status.EXPIRED && date.isAfter(LocalDateTime.now())) return Status.PENDING;
        return status;
    }

    private int reminder(Integer amount) {
        return amount == null ? 0 : amount;
    }

    private String meetingLink(String raw) {
        String value = trim(raw);
        String validated = ScheduleInvitationParser.safeUrl(value);
        if (!value.isEmpty() && validated.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "会议链接需要使用合法的 HTTP 或 HTTPS 地址。");
        }
        return validated;
    }

    private String trim(String value) {
        return value == null ? "" : value.strip();
    }

    private void verify(int changed) {
        if (changed == 0) throw missing();
    }

    private ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "日程已不存在。");
    }

    enum Type { ONLINE, ONSITE, PHONE }
    enum Status { PENDING, COMPLETED, CANCELLED, EXPIRED, RESCHEDULED }

    record ScheduleData(
        @NotBlank(message = "请输入公司名称。") @Size(max = 100) String company,
        @NotBlank(message = "请输入岗位名称。") @Size(max = 100) String position,
        @NotNull(message = "请选择面试时间。") LocalDateTime time, @NotNull Type type,
        @Size(max = 300) String location, @Size(max = 1000) String notes, @NotNull Status status,
        @Size(max = 500) String meetingUrl, @Size(max = 100) String interviewer,
        @Min(1) @Max(20) Integer round, @Min(0) @Max(1440) Integer reminderMinutes) {}
    record Appointment(String id, String company, String position, LocalDateTime time, Type type,
                       String location, String notes, Status status, String meetingUrl,
                       String interviewer, Integer round, int reminderMinutes) {}
    record Invitation(@NotBlank(message = "请先粘贴面试邀请。") @Size(max = 4000) String text,
                      LocalDate referenceDate, boolean useModel) {}
    record StateChange(@NotNull Status status) {}
    record Move(@NotNull(message = "请选择新的面试时间。") LocalDateTime time) {}
}
