// Smoke test: proves tsx can load the pinned real Pi SDK packages from local node_modules
// and that the official Faux provider drives the same Agent runtime with zero network.
import { Agent } from "@earendil-works/pi-agent-core";
import { createModels, fauxAssistantMessage, fauxProvider, fauxText } from "@earendil-works/pi-ai";

const faux = fauxProvider();
const models = createModels();
models.setProvider(faux.provider);
faux.setResponses([fauxAssistantMessage([fauxText("hello from faux")])]);

const agent = new Agent({
  initialState: {
    systemPrompt: "smoke",
    model: faux.getModel(),
    tools: [],
  },
  streamFn: (model, context, options) => models.streamSimple(model, context, options),
});

const events: string[] = [];
agent.subscribe((event) => {
  events.push(event.type);
});

await agent.prompt("hi");
console.log(
  JSON.stringify({
    pi_source: "npm: @earendil-works/pi-agent-core + pi-ai @ 0.85.1",
    event_sequence: events,
    message_count: agent.state.messages.length,
    last_message_role: agent.state.messages.at(-1)?.role,
  }),
);
