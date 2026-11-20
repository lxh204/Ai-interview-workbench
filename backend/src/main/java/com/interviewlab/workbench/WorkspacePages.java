package com.interviewlab.workbench;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class WorkspacePages {
    @GetMapping({
        "/history", "/history/{id}", "/upload", "/interview-hub", "/interview",
        "/interview/create/{id}", "/interview/session/{id}", "/interview/report/{id}", "/interview/{id}",
        "/interviews", "/interviews/{id}", "/voice-interview",
        "/voice-interview/{id}/evaluation", "/voice-interview/evaluation/{id}", "/interview-schedule",
        "/knowledgebase", "/knowledgebase/upload", "/knowledgebase/chat",
        "/knowledgebase-interview", "/knowledgebase-interview/records", "/knowledgebase-interview/{id}",
        "/knowledgebase-interview/{id}/questions", "/knowledgebase-interview/{id}/interviews",
        "/knowledgebase-interview/{id}/interviews/{sessionId}", "/settings"
    })
    String index() {
        return "forward:/index.html";
    }
}
