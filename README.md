# JReAct

A ReAct (Reasoning + Acting) agent loop implemented from scratch in Java and Spring Boot, without a prebuilt agent framework.

> **Status:** Educational proof of concept. Not a production framework, and not a replacement for LangGraph or Spring AI's own higher-level agent abstractions (e.g. `ToolCallingAdvisor`). Built to make the ReAct control-flow mechanic explicit and inspectable, not to be reused as a library.

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Architecture](#architecture)
- [Tools](#tools)
- [Getting Started](#getting-started)
- [API Reference](#api-reference)
- [Plan-and-Execute](#plan-and-execute)
- [Configuration](#configuration)
- [Logging](#logging)
- [Testing](#testing)
- [Project Scope](#project-scope)

## Overview

ReAct (Reason + Act) is a pattern for multi-step LLM agent behavior: given a question and a set of callable tools, the model alternates between **reasoning** about what it needs and **acting** by requesting a tool call, until it has enough information to answer. The caller — not the model — executes each requested tool and returns the result, which the model reads before deciding on its next step.

This project implements that loop manually against Spring AI's `ChatModel` API (Spring AI 2.0 no longer executes tool calls automatically), so every step — the request, the tool execution, the response — is visible in application code rather than hidden inside a framework abstraction. The full loop is in [`AgentLoopService.java`](src/main/java/com/jreact/agent/AgentLoopService.java).

## Features

- Explicit ReAct loop with a configurable hard iteration cap
- Three tools backed by real data sources (no mocks)
- Structured tool-call trace returned in the API response, in addition to the final answer
- Tool execution errors caught and fed back to the model instead of failing the request
- Iteration-by-iteration console logging of tool calls and, optionally, the full prompt sent to the model
- Automated tests covering tool selection, dependent tool chaining, error handling, and the iteration cap
- Optional **Plan-and-Execute** mode (`POST /agent/plan-and-execute`) that decomposes a request into an ordered step list upfront, then runs the same ReAct loop once per step — see [Plan-and-Execute](#plan-and-execute)

## Architecture

```
User Question (REST endpoint)
        │
        ▼
┌────────────────────────┐
│   AgentController       │  POST /agent/ask
└────────────┬────────────┘
             │
             ▼
┌────────────────────────┐
│   AgentLoopService       │  owns the ReAct loop and message history
└────────────┬────────────┘
             │
        ┌────┴─────┐
        ▼          ▼
┌─────────────┐ ┌───────────────────────┐
│  ChatModel    │ │  Tool implementations  │
│  (OpenAI)     │ │  (@Tool-annotated)      │
└─────────────┘ └───────────────────────┘
```

`AgentLoopService` is the core engine — it owns the ReAct loop shown above and is reused, unmodified, by the **Plan-and-Execute** mode described below. There are two REST entry points into it:

| Entry point | Controller | Behavior |
|---|---|---|
| `POST /agent/ask` | `AgentController` | Runs the ReAct loop above directly against the full user question |
| `POST /agent/plan-and-execute` | `PlanController` | Decomposes the question into an ordered plan first, then runs `AgentLoopService` once per step — see [Plan-and-Execute](#plan-and-execute) for the full diagram |

## Tools

| Tool | Description | Data source |
|---|---|---|
| `calculate` | Arithmetic evaluator (`+ - * /`, parentheses, decimals) | Pure Java (hand-written recursive-descent parser) |
| `getCurrentWeather` | Real-time temperature and conditions for a city | [Open-Meteo](https://open-meteo.com) — free, no API key |
| `getCityInfo` | Factual summary about a place (history, population, notable facts) | Wikipedia REST Summary API — free, no API key |

The model is instructed to call a tool only when the question requires it, and to chain tools when one result depends on another (e.g. resolving a city's weather before performing arithmetic on the temperature).

## Getting Started

### Prerequisites

- Java 25 LTS ([Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or equivalent)
- Maven 3.9+
- An OpenAI API key
- Internet access at runtime (OpenAI API; Open-Meteo and Wikipedia require no key)

### Installation

```bash
git clone <repository-url>
cd JReAct
```

Create `secrets.properties` in the project root (same directory as `pom.xml`). This file is gitignored and is never committed:

```properties
OPENAI_API_KEY=sk-your-key-here
```

`application.yml` imports it via `spring.config.import: optional:file:./secrets.properties`, so the key is never written into a tracked file.

### Running

```bash
mvn spring-boot:run
```

To stop it: `bin/stop-jreact.ps1` (PowerShell — stops whatever is listening on port 8080, without touching unrelated Java processes).

## API Reference

### `POST /agent/ask`

**Request**

| Field | Type | Description |
|---|---|---|
| `question` | string | Natural-language question |

```json
{ "question": "What is Tokyo known for, and what is its current temperature in Fahrenheit?" }
```

**Response**

| Field | Type | Description |
|---|---|---|
| `answer` | string | Final natural-language answer |
| `toolCalls` | array | Ordered trace of tool invocations |
| `toolCalls[].toolName` | string | Name of the tool called |
| `toolCalls[].arguments` | string | JSON arguments the model supplied |
| `toolCalls[].result` | string | Raw tool output (or error message, if the tool failed) |
| `iterationsUsed` | integer | Number of model calls made |
| `hitIterationLimit` | boolean | Whether the loop stopped due to the iteration cap rather than reaching a final answer |

```json
{
  "answer": "What Tokyo is known for: Japan's capital and largest city, home to over 14 million people, known for blending cutting-edge modernity with tradition — from Shibuya Crossing and Tokyo Tower to historic sites like Senso-ji, plus a world-class food and tech scene.\n\nCurrent temperature: 31.3°C, which is about 88.3°F.",
  "toolCalls": [
    {
      "toolName": "getCityInfo",
      "arguments": "{\"city\":\"Tokyo\"}",
      "result": "{\"title\":\"Tokyo\",\"shortDescription\":\"Capital and most populous city in Japan\",\"summary\":\"Tokyo, officially the Tokyo Metropolis, is the capital and most populous city of Japan...\"}"
    },
    {
      "toolName": "getCurrentWeather",
      "arguments": "{\"city\":\"Tokyo\"}",
      "result": "{\"city\":\"Tokyo\",\"temperatureCelsius\":31.3,\"condition\":\"Partly cloudy\"}"
    },
    {
      "toolName": "calculate",
      "arguments": "{\"expression\":\"31.3 * 9 / 5 + 32\"}",
      "result": "88.34"
    }
  ],
  "iterationsUsed": 3,
  "hitIterationLimit": false
}
```

Tool execution errors (e.g. malformed arguments, an unresolvable city name, division by zero) are caught, returned to the model as the tool's result, and surfaced in the final answer rather than failing the request.

## Plan-and-Execute

`AgentLoopService`'s ReAct loop decides tool sequencing turn-by-turn — the model sees each result as it comes back and decides what to do next, with no upfront commitment to a sequence. **Plan-and-Execute** is a second mode built on top of that same loop, added specifically to make a different control-flow pattern visible and comparable: decompose the request into an ordered step list *before* running anything, then execute each step through the unmodified ReAct loop.

```
User Request
      │
      ▼
┌──────────────────┐
│  PlanController   │  POST /agent/plan-and-execute
└─────────┬────────┘
          │
          ▼
┌──────────────────┐
│  PlannerService   │  one LLM call → structured, ordered Plan (no tools called)
└─────────┬────────┘
          │
          ▼
┌───────────────────┐
│  ExecutorService   │  iterates steps sequentially, carries prior results forward
└─────────┬─────────┘
          │
          ▼  (per step)
┌─────────────────────────────────┐
│  AgentLoopService (unmodified)   │  same ReAct loop as /agent/ask, scoped to one step's sub-goal
└────────────────┬────────────────┘
                 │
                 ▼  (after all steps)
┌───────────────────────────────┐
│  ExecutorService — synthesis   │  final LLM call → combines all step results into one answer
└───────────────────────────────┘
```

- **`PlannerService`** makes a single LLM call with a structured-output prompt (Spring AI's `BeanOutputConverter`, which generates a JSON Schema and appends it as a format instruction) and parses the response into an ordered `Plan` of `PlanStep`s. It doesn't call any tools itself — it only reasons about what steps *would* be needed.
- **`ExecutorService`** runs each `PlanStep` in order by calling `AgentLoopService.run(...)` with a sub-goal string built from that step's goal plus a concise summary of prior steps' results (not the full transcript, to avoid bloating context). A step that throws is caught and recorded as `FAILED` with the error as its result — execution continues to the next step rather than aborting or re-planning. Once all steps are done, a final LLM call synthesizes the accumulated per-step results into one answer to the original request.
- **`PlanController`** exposes this as `POST /agent/plan-and-execute`, kept separate from `/agent/ask` so the same question can be sent to both and compared directly.

### `POST /agent/plan-and-execute`

**Request** — same shape as `/agent/ask`: `{ "question": "..." }`

**Response**

| Field | Type | Description |
|---|---|---|
| `finalAnswer` | string | Synthesized answer to the original request |
| `plan.originalRequest` | string | The request that was planned |
| `plan.steps` | array | Ordered steps, each with `order`, `goal`, `status` (`PENDING`/`IN_PROGRESS`/`DONE`/`FAILED`), and `result` |

### Side-by-side example

Same question sent to both endpoints: *"Find the weather in Paris and Tokyo, then tell me which is warmer and by how much."*

**`/agent/ask`** (plain ReAct) — one continuous loop, the model decides each next action as results come in: it requests `getCurrentWeather(Paris)`, then `getCurrentWeather(Tokyo)`, then `calculate("23.2 - 18.7")`, then answers. Nothing about the ordering was decided in advance — the trace *is* the reasoning.

**`/agent/plan-and-execute`** — the planner commits to a 4-step plan **before** anything runs:

```json
{
  "finalAnswer": "Tokyo is warmer. Tokyo: 23.2°C vs Paris: 18.7°C — Tokyo is warmer by 4.5°C.",
  "plan": {
    "originalRequest": "Find the weather in Paris and Tokyo, then tell me which is warmer and by how much",
    "steps": [
      { "order": 1, "goal": "Get the current weather in Paris",  "status": "DONE", "result": "Current weather in Paris: 18.7°C, Clear sky." },
      { "order": 2, "goal": "Get the current weather in Tokyo",  "status": "DONE", "result": "Current weather in Tokyo: 23.2°C, Drizzle." },
      { "order": 3, "goal": "Compare the temperatures of Paris and Tokyo", "status": "DONE", "result": "Tokyo (23.2°C) is warmer than Paris (18.7°C) by 4.5°C." },
      { "order": 4, "goal": "Determine which city is warmer and by how much", "status": "DONE", "result": "Tokyo is warmer. Tokyo: 23.2°C vs Paris: 18.7°C — Tokyo is warmer by 4.5°C." }
    ]
  }
}
```

Captured directly from a real run — see the full request/response trace in `ExecutorServiceTest`.

**What the comparison shows**: the plain ReAct trace shows the model's own moment-to-moment reasoning — compact, but you only see what it decided to do, not what it *could* have done differently. The Plan-and-Execute trace makes the decomposition itself inspectable and fixed upfront — useful when you want to audit or explain the shape of a multi-part task before execution — at the cost of adaptiveness: if step 2 revealed the plan was wrong (e.g. the model's own comparison step turned out redundant with step 3 above), there's no re-planning in this POC — a step just runs, or fails and is recorded, and the final synthesis works with whatever came back.

## Configuration

`application.yml`:

| Property | Default | Description |
|---|---|---|
| `spring.ai.openai.chat.options.model` | `gpt-4o-mini` | OpenAI model used |
| `jreact.agent.max-iterations` | `6` | Hard cap on model calls per request |
| `logging.level.com.jreact` | `INFO` | Set to `DEBUG` to log the full prompt sent on every iteration |

## Logging

At `INFO`, each request logs the incoming question, every tool call requested (name and arguments), each tool's result, and how the loop terminated:

```
INFO  AgentLoopService : New question: "What's the weather in Paris, and what's that temperature times 3?"
INFO  AgentLoopService : [iteration 1] model requested 1 tool call(s)
INFO  AgentLoopService :   -> getCurrentWeather({"city":"Paris"})
INFO  AgentLoopService :   <- getCurrentWeather returned: {"city":"Paris","temperatureCelsius":21.6,"condition":"Partly cloudy"}
INFO  AgentLoopService : Finished after 3 iteration(s), no further tool calls requested
```

At `DEBUG`, the full message list (system prompt, conversation history, tool results) sent to the model on each iteration is also logged.

## Testing

```bash
mvn test
```

Test coverage includes tool-selection correctness (the model calling only the tools a question requires), dependent tool chaining, tool-execution error handling, iteration-cap behavior, and (for Plan-and-Execute) plan decomposition structure and end-to-end plan execution. Integration tests call the real OpenAI, Open-Meteo, and Wikipedia APIs rather than mocks, so live values (temperature, weather condition) vary between runs — assertions check structural correctness and numeric consistency rather than hardcoded results.

## Project Scope

**In scope:** single-agent ReAct loop, explicit tool-calling, structured trace, iteration cap, tool error handling, upfront plan decomposition with sequential step execution (Plan-and-Execute).

**Out of scope:** multi-agent orchestration, RAG/retrieval, persistent memory across sessions, a frontend UI, provider failover across multiple LLM backends, dynamic re-planning mid-execution, parallel step execution.
