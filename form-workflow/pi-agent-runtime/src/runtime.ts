// Pi runtime adapter: wraps the real @earendil-works/pi-agent-core Agent with the
// official Faux model provider (packages/ai/src/providers/faux.ts, zero network).
// The adapter is intentionally thin — it does not reimplement the agent loop.
//
// Mapping (Pi -> this project):
//   pi Agent.state.messages/tools  -> live conversation; not persisted
//   pi subscribe() events          -> Trace entries (pi_event kind)
//   pi beforeToolCall/afterToolCall-> not used; authorization lives in Java
//   faux setResponses/appendResponses -> scripted model turns (Fake Model)
//   agent.state.errorMessage       -> stop reason classification

import { Agent, type AgentEvent, type AgentTool } from "@earendil-works/pi-agent-core";
import { createModels, fauxProvider, type FauxProviderHandle, type Model } from "@earendil-works/pi-ai";
import type { Trace } from "./trace.ts";

export interface PhaseResult {
  stop_reason: string;
}

export interface FlowRuntime {
  agent: Agent;
  faux: FauxProviderHandle;
  model: Model<string>;
  runPhase(phase: string, prompt: string): Promise<PhaseResult>;
}

export function createFlowRuntime(options: {
  systemPrompt: string;
  tools: AgentTool<any>[];
  trace: Trace;
}): FlowRuntime {
  const faux = fauxProvider();
  const models = createModels();
  models.setProvider(faux.provider);
  const model = faux.getModel();

  const agent = new Agent({
    initialState: {
      systemPrompt: options.systemPrompt,
      model,
      tools: options.tools,
    },
    streamFn: (requestModel, context, streamOptions) =>
      models.streamSimple(requestModel, context, streamOptions),
  });

  agent.subscribe((event: AgentEvent) => {
    options.trace.piEvent(event);
  });

  return {
    agent,
    faux,
    model,
    async runPhase(phase: string, prompt: string): Promise<PhaseResult> {
      options.trace.agentRunStart(phase);
      await agent.prompt(prompt);
      const result = classifyStop(agent);
      options.trace.agentRunEnd(phase, result.stop_reason);
      return result;
    },
  };
}

export function classifyStop(agent: Agent): PhaseResult {
  const errorMessage = agent.state.errorMessage;
  if (errorMessage) {
    if (/No more faux responses/i.test(errorMessage)) {
      return { stop_reason: "model_script_exhausted" };
    }
    return { stop_reason: "model_error" };
  }
  const lastAssistant = [...agent.state.messages].reverse().find((message) => message.role === "assistant") as
    | { stopReason?: string }
    | undefined;
  const stopReason = lastAssistant?.stopReason;
  switch (stopReason) {
    case "stop":
      return { stop_reason: "completed" };
    case "length":
      return { stop_reason: "max_tokens" };
    case "aborted":
      return { stop_reason: "aborted" };
    case undefined:
      return { stop_reason: "unknown" };
    default:
      return { stop_reason: stopReason };
  }
}
