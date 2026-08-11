package com.jreact.plan;

import com.jreact.agent.AgentRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PlanController {

    private final PlannerService plannerService;
    private final ExecutorService executorService;

    public PlanController(PlannerService plannerService, ExecutorService executorService) {
        this.plannerService = plannerService;
        this.executorService = executorService;
    }

    @PostMapping("/agent/plan-and-execute")
    public PlanAndExecuteResult planAndExecute(@RequestBody AgentRequest request) {
        Plan plan = plannerService.plan(request.question());
        return executorService.execute(plan);
    }
}
