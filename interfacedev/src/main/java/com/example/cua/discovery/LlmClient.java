package com.example.cua.discovery;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Minimal seam over the LLM so the agent loop is provider-agnostic and testable. The agent owns
 * the conversation; the client just turns "system + history + tools" into the next assistant turn.
 */
public interface LlmClient {

    /** A tool the model may call. {@code schema} is a JSON-Schema object node. */
    record ToolDef(String name, String description, JsonNode schema) {}

    /** One message in the running conversation. */
    record Turn(Role role, List<Block> blocks) {
        public enum Role { USER, ASSISTANT }
        public static Turn user(List<Block> b) { return new Turn(Role.USER, b); }
        public static Turn assistant(List<Block> b) { return new Turn(Role.ASSISTANT, b); }
    }

    /** A content block: text, a screenshot, a tool call, or a tool result. */
    record Block(
            Kind kind,
            String text,
            byte[] pngImage,
            String toolUseId,
            String toolName,
            JsonNode toolInput,
            boolean isError
    ) {
        public enum Kind { TEXT, IMAGE, TOOL_USE, TOOL_RESULT }
        public static Block text(String t) { return new Block(Kind.TEXT, t, null, null, null, null, false); }
        public static Block image(byte[] png) { return new Block(Kind.IMAGE, null, png, null, null, null, false); }
        public static Block toolResult(String id, String text, boolean isError) {
            return new Block(Kind.TOOL_RESULT, text, null, id, null, null, isError);
        }
        public static Block toolUse(String id, String name, JsonNode input) {
            return new Block(Kind.TOOL_USE, null, null, id, name, input, false);
        }
    }

    /** The model's reply: any narration text plus the tool calls it made. */
    record Reply(String assistantText, List<Block> toolCalls, List<Block> rawAssistantBlocks, String stopReason) {}

    Reply next(String system, List<Turn> history, List<ToolDef> tools);
}
