package com.demo.flowagent.api;

import com.demo.flowagent.api.dto.ApplyOutcomeView;
import com.demo.flowagent.api.dto.FlowEventRequest;
import com.demo.flowagent.api.dto.NoteRequest;
import com.demo.flowagent.api.dto.PlanRequest;
import com.demo.flowagent.api.dto.RecordView;
import com.demo.flowagent.api.dto.RejectRequest;
import com.demo.flowagent.api.dto.RunView;
import com.demo.flowagent.api.dto.StatsView;
import com.demo.flowagent.api.dto.SubmitView;
import com.demo.flowagent.api.dto.ValidateRequest;
import com.demo.flowagent.api.dto.ValidateView;
import com.demo.flowagent.domain.Actor;
import com.demo.flowagent.domain.Role;
import com.demo.flowagent.engine.FlowEngine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP 边界：查询任意已认证角色可用；写路径按角色收口——
 * 事件提交 OPERATOR/AGENT；计划/申请/执行/replay 仅 AGENT；审批仅 APPROVER。
 */
@RestController
@RequestMapping("/api/flow")
public class FlowController {

    private final FlowEngine engine;
    private final ActorResolver actors;
    private final RequestCounters counters;

    public FlowController(FlowEngine engine, ActorResolver actors, RequestCounters counters) {
        this.engine = engine;
        this.actors = actors;
        this.counters = counters;
    }

    @PostMapping("/events")
    public SubmitView submitEvent(@Valid @RequestBody FlowEventRequest request, HttpServletRequest http) {
        Actor actor = actors.require(http, Role.OPERATOR, Role.AGENT);
        return engine.submitEvent(request, actor);
    }

    @GetMapping("/records/{recordId}")
    public RecordView getRecord(@PathVariable String recordId, HttpServletRequest http) {
        actors.require(http);
        return engine.getRecord(recordId);
    }

    @PostMapping("/validate-transition")
    public ValidateView validateTransition(@Valid @RequestBody ValidateRequest request, HttpServletRequest http) {
        actors.require(http, Role.AGENT);
        return engine.validateTransition(request);
    }

    @PostMapping("/runs/{runId}/plan")
    public RunView plan(@PathVariable String runId, @Valid @RequestBody PlanRequest request, HttpServletRequest http) {
        Actor actor = actors.require(http, Role.AGENT);
        return engine.recordPlan(runId, request, actor);
    }

    @PostMapping("/runs/{runId}/request-approval")
    public RunView requestApproval(@PathVariable String runId,
                                   @RequestBody(required = false) NoteRequest request,
                                   HttpServletRequest http) {
        Actor actor = actors.require(http, Role.AGENT);
        return engine.requestApproval(runId, request, actor);
    }

    @PostMapping("/runs/{runId}/approve")
    public RunView approve(@PathVariable String runId,
                           @RequestBody(required = false) NoteRequest request,
                           HttpServletRequest http) {
        Actor actor = actors.require(http, Role.APPROVER);
        return engine.approve(runId, request, actor);
    }

    @PostMapping("/runs/{runId}/reject")
    public RunView reject(@PathVariable String runId,
                          @Valid @RequestBody RejectRequest request,
                          HttpServletRequest http) {
        Actor actor = actors.require(http, Role.APPROVER);
        return engine.reject(runId, request, actor);
    }

    @PostMapping("/runs/{runId}/apply")
    public ApplyOutcomeView apply(@PathVariable String runId, HttpServletRequest http) {
        counters.recordApplyHit();
        Actor actor = actors.require(http, Role.AGENT);
        return engine.apply(runId, actor);
    }

    @PostMapping("/runs/{runId}/replay")
    public ApplyOutcomeView replay(@PathVariable String runId, HttpServletRequest http) {
        counters.recordReplayHit();
        Actor actor = actors.require(http, Role.AGENT);
        return engine.replay(runId, actor);
    }

    @GetMapping("/runs/{runId}")
    public RunView getRun(@PathVariable String runId, HttpServletRequest http) {
        actors.require(http);
        return engine.getRun(runId);
    }

    @GetMapping("/stats")
    public StatsView stats(HttpServletRequest http) {
        actors.require(http);
        return engine.stats();
    }
}
