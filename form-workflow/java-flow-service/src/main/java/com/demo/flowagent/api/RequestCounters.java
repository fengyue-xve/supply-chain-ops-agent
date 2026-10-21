package com.demo.flowagent.api;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 写入端点触达计数（demo 观测）：记录 POST /apply 与 POST /replay 被请求的次数。
 * 计数发生在控制器方法入口、角色校验之前——"触达"以请求到达端点为准，
 * 因此可用来证明"未批准执行时写入端点触达数为 0"。
 */
@Component
public class RequestCounters {

    private final AtomicLong applyEndpointHits = new AtomicLong();
    private final AtomicLong replayEndpointHits = new AtomicLong();

    public void recordApplyHit() {
        applyEndpointHits.incrementAndGet();
    }
    public void reset() { applyEndpointHits.set(0); replayEndpointHits.set(0); }

    public void recordReplayHit() {
        replayEndpointHits.incrementAndGet();
    }

    public long applyEndpointHits() {
        return applyEndpointHits.get();
    }

    public long replayEndpointHits() {
        return replayEndpointHits.get();
    }
}
