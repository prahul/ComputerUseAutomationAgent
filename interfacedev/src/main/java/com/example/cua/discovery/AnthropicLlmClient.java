package com.example.cua.discovery;

import com.example.cua.core.CuaException;
import com.example.cua.core.Json;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** {@link LlmClient} backed by the Anthropic Messages API (manual tool loop). */
public final class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String model;
    private final long maxTokens;

    public AnthropicLlmClient(String apiKey, String model) {
        this.client = (apiKey == null || apiKey.isBlank())
                ? AnthropicOkHttpClient.fromEnv()
                : AnthropicOkHttpClient.builder().apiKey(apiKey).build();
        this.model = model;
        this.maxTokens = 4096;
    }

    @Override
    public Reply next(String system, List<Turn> history, List<ToolDef> tools) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens)
                .system(system);

        for (ToolDef t : tools) {
            params.addTool(toTool(t));
        }
        for (Turn turn : history) {
            params.addMessage(toParam(turn));
        }

        Message response;
        try {
            response = client.messages().create(params.build());
        } catch (RuntimeException e) {
            throw new CuaException("LLM request failed: " + e.getMessage(), e);
        }

        StringBuilder narration = new StringBuilder();
        List<Block> toolCalls = new ArrayList<>();
        List<Block> rawBlocks = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            if (block.isText()) {
                String t = block.asText().text();
                narration.append(t).append('\n');
                rawBlocks.add(Block.text(t));
            } else if (block.isToolUse()) {
                var tu = block.asToolUse();
                JsonNode input = tu._input().convert(JsonNode.class);
                Block b = Block.toolUse(tu.id(), tu.name(), input);
                toolCalls.add(b);
                rawBlocks.add(b);
            }
        }
        String stop = response.stopReason().map(Object::toString).orElse("end_turn");
        return new Reply(narration.toString().trim(), toolCalls, rawBlocks, stop);
    }

    // --- mapping ---------------------------------------------------------------------------------

    private Tool toTool(ToolDef t) {
        Tool.InputSchema.Properties.Builder props = Tool.InputSchema.Properties.builder();
        JsonNode properties = t.schema().get("properties");
        if (properties != null) {
            properties.fieldNames().forEachRemaining(name ->
                    props.putAdditionalProperty(name, JsonValue.fromJsonNode(properties.get(name))));
        }
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder().properties(props.build());
        JsonNode required = t.schema().get("required");
        if (required != null && required.isArray()) {
            List<String> req = new ArrayList<>();
            required.forEach(n -> req.add(n.asText()));
            schema.required(req);
        }
        return Tool.builder()
                .name(t.name())
                .description(t.description())
                .inputSchema(schema.build())
                .build();
    }

    private MessageParam toParam(Turn turn) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (Block b : turn.blocks()) {
            switch (b.kind()) {
                case TEXT -> blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(b.text()).build()));
                case IMAGE -> blocks.add(ContentBlockParam.ofImage(ImageBlockParam.builder()
                        .source(Base64ImageSource.builder()
                                .mediaType(Base64ImageSource.MediaType.IMAGE_PNG)
                                .data(Base64.getEncoder().encodeToString(b.pngImage()))
                                .build())
                        .build()));
                case TOOL_USE -> {
                    ToolUseBlockParam.Input.Builder ib = ToolUseBlockParam.Input.builder();
                    JsonNode in = b.toolInput();
                    if (in != null && in.isObject()) {
                        in.fieldNames().forEachRemaining(f ->
                                ib.putAdditionalProperty(f, JsonValue.fromJsonNode(in.get(f))));
                    }
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(b.toolUseId()).name(b.toolName()).input(ib.build()).build()));
                }
                case TOOL_RESULT -> blocks.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(b.toolUseId())
                        .content(b.text() == null ? "" : b.text())
                        .isError(b.isError())
                        .build()));
            }
        }
        MessageParam.Role role = turn.role() == Turn.Role.USER ? MessageParam.Role.USER : MessageParam.Role.ASSISTANT;
        return MessageParam.builder().role(role).contentOfBlockParams(blocks).build();
    }

    /** Convenience for building a JSON-Schema object node for a tool. */
    public static JsonNode objectSchema(Map<String, JsonNode> properties, List<String> required) {
        var node = Json.MAPPER.createObjectNode();
        node.put("type", "object");
        var props = node.putObject("properties");
        properties.forEach(props::set);
        var req = node.putArray("required");
        required.forEach(req::add);
        return node;
    }

    public static JsonNode stringProp(String description) {
        var n = Json.MAPPER.createObjectNode();
        n.put("type", "string");
        n.put("description", description);
        return n;
    }
}
