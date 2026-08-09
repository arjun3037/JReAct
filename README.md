# JReAct

A ReAct (Reasoning + Acting) agent loop, built from scratch in Java/Spring Boot — no LangChain-style prebuilt agent abstraction.

This is a **learning-focused proof of concept**, not a production framework and not a replacement for LangGraph or Spring AI's own higher-level agent helpers (`ToolCallingAdvisor`, etc.). The goal is to hand-roll the loop that those tools normally hide, in order to understand the mechanic — not to ship something reusable.

## What is ReAct?

ReAct (Reason + Act) is a pattern for how an LLM-driven agent solves a task that requires more than one step:

1. The model is given a question plus a list of tools it's allowed to call (e.g. a calculator, a weather lookup).
2. Instead of answering immediately, the model can **reason** about what it needs, then **act** by requesting a tool call — the model doesn't run the tool itself, it just asks for it.
3. Your code executes the requested tool and feeds the result back into the conversation.
4. The model sees that result and decides: ask for another tool call, or give a final answer.
5. This repeats until the model returns a final answer, or a hard iteration cap is hit (to guard against infinite loops).

The interesting part — and the part this project makes deliberately visible — is that there's no magic here: it's just a `while` loop appending messages to a growing conversation history, calling the LLM again each time, and branching on whether the response contains a tool-call request or a final answer. See [`AgentLoopService.java`](src/main/java/com/jreact/agent/AgentLoopService.java) for the whole thing in ~60 lines.

## Tech stack

- **Java 25 LTS**
- **Spring Boot 4.0**
- **Spring AI 2.0**, driven directly against `ChatModel` (not `ChatClient`) with a manually-driven tool-calling loop. Spring AI 2.0 dropped the automatic tool-execution loop from `ChatModel.call()` — a single call just returns a `ChatResponse` that may contain requested tool calls, and it's on the caller to execute them and loop. We deliberately don't use `ToolCallingAdvisor`, since that would hide the exact mechanic this project exists to show.
- **OpenAI** (`gpt-4o-mini`) as the LLM provider. *(Originally planned as a fully local Ollama model — see [Notes on the OpenAI pivot](#notes-on-the-openai-pivot) below for why that changed.)*
- **Maven** as the build tool

## Architecture

```
User Question (REST endpoint)
        │
        ▼
┌───────────────────────┐
│   AgentController      │  POST /agent/ask
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│   AgentLoopService     │  ← owns the ReAct loop + running message history
└───────────┬───────────┘
            │
       ┌────┴─────┐
       ▼          ▼
┌───────────┐ ┌────────────────────────┐
│ ChatModel  │ │  Tool implementations   │
│ (OpenAI)   │ │  (@Tool-annotated methods)│
└───────────┘ └────────────────────────┘
```

## Tools

1. `calculate(expression: String)` — hand-written recursive-descent arithmetic evaluator (`+ - * /`, parentheses, decimals). Deliberately not using a scripting engine — the parser is dependency-free and small enough to read in one sitting. See [`CalculatorTool.java`](src/main/java/com/jreact/tools/CalculatorTool.java).
2. `getCurrentWeather(city: String)` — real current-weather data via [Open-Meteo](https://open-meteo.com), a free, keyless weather API. Internally does two HTTP calls (geocode the city name to lat/long, then fetch current weather for those coordinates), but the LLM only ever sees the one `getCurrentWeather(city)` function. No signup, no API key — matches this project's "no paid API keys" goal even better than the LLM provider does. See [`WeatherTool.java`](src/main/java/com/jreact/tools/WeatherTool.java).

## Setup

Prerequisites:

- Java 25 LTS ([Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or equivalent)
- Maven 3.9+
- An OpenAI API key
- Internet access at runtime (calls both the OpenAI API and the free Open-Meteo weather API — no key needed for the latter)

Steps:

1. Clone the repo.
2. Create `secrets.properties` in the project root (same folder as `pom.xml`) — this file is gitignored and never committed:
   ```properties
   OPENAI_API_KEY=sk-your-key-here
   ```
   `application.yml` picks this up via `spring.config.import: optional:file:./secrets.properties`, so the key never has to live in a tracked file.
3. Build and run:
   ```
   mvn spring-boot:run
   ```
4. Call the agent:
   ```
   curl -X POST http://localhost:8080/agent/ask \
     -H "Content-Type: application/json" \
     -d "{\"question\": \"What is the weather in Paris, and what is that temperature times 3?\"}"
   ```

## Example exchange

**Request:**
```json
POST /agent/ask
{ "question": "What is the weather in Paris, and what is that temperature times 3?" }
```

**Response** *(real, live output — temperature will differ when you run it, since `getCurrentWeather` calls the real Open-Meteo API):*
```json
{
  "answer": "The current weather in Paris is 21.6°C. Multiplying that by 3 gives 64.8.",
  "toolCalls": [
    {
      "toolName": "getCurrentWeather",
      "arguments": "{\"city\":\"Paris\"}",
      "result": "{\"city\":\"Paris\",\"temperatureCelsius\":21.6,\"condition\":\"Partly cloudy\"}"
    },
    {
      "toolName": "calculate",
      "arguments": "{\"expression\":\"21.6 * 3\"}",
      "result": "64.80000000000001"
    }
  ],
  "iterationsUsed": 3,
  "hitIterationLimit": false
}
```

Walking through what happened, iteration by iteration:

1. The model reads the question and the two tool schemas, and decides it needs the weather first — it can't compute "temperature times 3" without a temperature. It requests `getCurrentWeather(city="Paris")` rather than answering.
2. `AgentLoopService` executes that tool (which geocodes "Paris" then queries Open-Meteo for real current conditions), gets back `{"temperatureCelsius": 21.6, ...}`, and appends the result to the conversation as a new message.
3. The model is called again with that result now in its context. It reads `21.6`, realizes it now has what it needs for the second half of the question, and requests `calculate(expression="21.6 * 3")` — note it's the *model* that constructs this expression string, not our code.
4. `AgentLoopService` executes `calculate`, gets `64.8` back, appends it.
5. The model is called a third time. Now it has both results and no more tool calls to make — it writes the final sentence and the loop exits (`iterationsUsed: 3`, `hitIterationLimit: false`).

**Error handling** — asking `"What is 10 divided by 0?"` makes `CalculatorTool` throw an `ArithmeticException`. Spring AI's default `ToolExecutionExceptionProcessor` catches it and feeds the error message back into the conversation instead of letting it propagate, so the model explains the problem to the user rather than the request crashing:
```json
{
  "answer": "I called the calculate tool and it returned an error: \"Division by zero.\" ...",
  "toolCalls": [{ "toolName": "calculate", "arguments": "{\"expression\":\"10 / 0\"}", "result": "Division by zero in expression: 10 / 0" }],
  "hitIterationLimit": false
}
```

**Iteration cap** — `jreact.agent.max-iterations` (default `6`, in `application.yml`) bounds how many model calls a single request can make. If a question needs more back-and-forth than that to resolve, the loop stops and returns a graceful partial result (`hitIterationLimit: true`) instead of looping forever or timing out unexpectedly.

## Watching it think

`AgentLoopService` logs each iteration at `INFO` level (`logging.level.com.jreact` in `application.yml`) — while `mvn spring-boot:run` is running, the console shows every tool call requested, its arguments, and its result in real time, e.g.:
```
INFO  AgentLoopService : New question: "What's the weather in Paris, and what's that temperature times 3?"
INFO  AgentLoopService : [iteration 1] model requested 1 tool call(s)
INFO  AgentLoopService :   -> getCurrentWeather({"city":"Paris"})
INFO  AgentLoopService :   <- getCurrentWeather returned: {"city":"Paris","temperatureCelsius":21.6,"condition":"Partly cloudy"}
INFO  AgentLoopService : [iteration 2] model requested 1 tool call(s)
INFO  AgentLoopService :   -> calculate({"expression":"21.6 * 3"})
INFO  AgentLoopService :   <- calculate returned: 64.80000000000001
INFO  AgentLoopService : Finished after 3 iteration(s), no further tool calls requested
```
This is separate from (and redundant with) the `toolCalls` trace in the HTTP response — the logs are there for watching the loop unfold live, the response payload is there for programmatic inspection after the fact.

## Notes on the OpenAI pivot

The original plan (see the build order below) was a fully local model via Ollama, with no paid API keys. The development machine turned out to have 8GB RAM and a 2GB-VRAM GPU — not enough headroom to run an 8B tool-calling model alongside the JVM without heavy swapping. The active provider was switched to OpenAI (`gpt-4o-mini`) as a result. This didn't require changing the ReAct loop design at all — Spring AI's `ChatModel`/tool-calling contract is the same regardless of backing provider, only the Maven starter and `application.yml` config changed. The Ollama dependency and config are still present in the project, dormant, as a documented fallback.

## Build order

This project was built incrementally so each mechanic was verified working before the next was layered on:

1. ✅ Plain OpenAI chat call via `ChatModel` — no tools yet (confirms Spring AI ↔ OpenAI wiring)
2. ✅ One tool (`calculate`) — confirmed the model correctly decides to call it, verified via an integration test asserting `hasToolCalls()`
3. ✅ Second tool (`getCurrentWeather`) — confirmed the model picks the right tool per question, and correctly chains both for a dependent question
4. ✅ Full explicit iteration loop with hard stop + error handling (`AgentLoopService`, `AgentController`)
5. ✅ Reasoning/tool-call trace exposed in the response payload (`AgentResult.toolCalls`)
6. ✅ README + example exchange (this file)

## Out of scope

This POC deliberately does not include: multi-agent orchestration, RAG/retrieval, persistent memory across sessions, a frontend UI, or provider failover across multiple LLM backends.
