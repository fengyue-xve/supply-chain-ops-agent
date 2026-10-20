package com.demo.supplyagent.gateway;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 上游(FastAPI)响应原样透传结果:envelope 即上游统一信封 {code,message,data},
 * Java 侧不回包不改造,避免第二套信封。
 */
public record UpstreamEnvelope(int httpStatus, JsonNode envelope) {
}
