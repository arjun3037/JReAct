package com.jreact.agent;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final AgentLoopService agentLoopService;

    public AgentController(AgentLoopService agentLoopService) {
        this.agentLoopService = agentLoopService;
    }

    @PostMapping("/agent/ask")
    public AgentResult ask(@RequestBody AgentRequest request) {
        return agentLoopService.run(request.question());
    }
}
