# JReAct

A ReAct (Reasoning + Acting) agent loop, built from scratch in Java/Spring Boot — no LangChain-style prebuilt agent abstraction.

This is a **learning-focused proof of concept**, not a production framework and not a replacement for LangGraph or Spring AI's own higher-level agent helpers (`ToolCallingAdvisor`, etc.). The goal is to hand-roll the loop that those tools normally hide, in order to understand the mechanic — not to ship something reusable.

> Status: in progress. This README will grow alongside the build; see the build order below for what's done vs. planned.

## What is ReAct?

ReAct (Reason + Act) is a pattern for how an LLM-driven agent solves a task that requires more than one step:

1. The model is given a question plus a list of tools it's allowed to call (e.g. a calculator, a weather lookup).
2. Instead of answering immediately, the model can **reason** about what it needs, then **act** by requesting a tool call — the model doesn't run the tool itself, it just asks for it.
3. Your code executes the requested tool and feeds the result back into the conversation.
4. The model sees that result and decides: ask for another tool call, or give a final answer.
5. This repeats until the model returns a final answer, or a hard iteration cap is hit (to guard against infinite loops).

The interesting part — and the part this project makes deliberately visible — is that there's no magic here: it's just a `while` loop appending messages to a growing conversation history, calling the LLM again each time, and branching on whether the response contains a tool-call request or a final answer.

## Tech stack

- **Java 25 LTS**
- **Spring Boot 4.0** (first-class Java 25 support)
- **Spring AI 2.0**, using `ChatClient` with a manually-driven tool-calling loop (Spring AI 2.0 dropped the automatic tool-execution loop from `ChatModel` — by design, we don't use `ToolCallingAdvisor` here, since that would hide the exact mechanic this project exists to show)
- **Ollama** — local LLM runtime, no cloud API keys required. Model: `qwen3:8b` (current default recommendation for native tool-calling support in Ollama)
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
│ ChatClient │ │  Tool implementations   │
│ (Ollama)   │ │  (@Tool-annotated methods)│
└───────────┘ └────────────────────────┘
```

## Tools

1. `calculate(expression: String)` — basic arithmetic evaluator (+, -, *, /, parentheses)
2. `getMockWeather(city: String)` — hardcoded/mock weather data per city (no real API key needed)
3. *(stretch)* `searchKnowledgeBase(query: String)` — mock stub only, not a real RAG integration

## Setup

Prerequisites:

- Java 25 LTS ([Oracle JDK](https://www.oracle.com/java/technologies/downloads/) or equivalent)
- Maven 3.9+
- [Ollama](https://ollama.com/) installed and running locally (verify with `curl http://localhost:11434` — should get a response, not a connection error)
- Pull the model this project uses:
  ```
  ollama pull qwen3:8b
  ```

Build and run instructions will be added here once the Maven project is scaffolded (see build order below).

## Build order

This project is built incrementally so each mechanic is verified working before the next is layered on:

1. ⬜ Plain Ollama chat call via `ChatClient` — no tools yet (confirms Spring AI ↔ Ollama wiring)
2. ⬜ One tool (`calculate`) — confirm the model correctly decides to call it
3. ⬜ Second tool (`getMockWeather`) — confirm the model picks the right tool for the question
4. ⬜ Full explicit iteration loop with hard stop + error handling
5. ⬜ Reasoning/tool-call trace exposed in the response payload
6. ⬜ Example request/response + demo

## Example exchange

*(To be added once the full loop is working — will show a question requiring two chained tool calls, e.g. "What's the weather in Paris, and what's that temperature times 3?", with the intermediate tool-call trace.)*

## Out of scope

This POC deliberately does not include: multi-agent orchestration, RAG/retrieval, persistent memory across sessions, a frontend UI, or provider failover across multiple LLM backends. See the full spec for details if you have access to it.