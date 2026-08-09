package com.jreact.agent;

/**
 * One tool invocation the model requested during the loop, in the order it happened.
 */
public record ToolCallTrace(String toolName, String arguments, String result) {
}
