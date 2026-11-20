package com.interviewlab.workbench.resume;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "workbench.resume.queue", havingValue = "database", matchIfMissing = true)
class DatabaseResumeWorker {
    private final ResumeReviews jobs;

    DatabaseResumeWorker(ResumeReviews jobs) {
        this.jobs = jobs;
    }

    @PostConstruct
    void recover() {
        jobs.recover();
    }

    @Scheduled(initialDelay = 1600, fixedDelay = 600)
    void tick() {
        jobs.work();
    }
}
