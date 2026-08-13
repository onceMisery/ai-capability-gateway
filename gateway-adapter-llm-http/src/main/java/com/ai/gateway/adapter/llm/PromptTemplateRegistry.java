package com.ai.gateway.adapter.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Versioned prompt template registry.
 *
 * <p>Prompt templates, model IDs, temperature, and parser versions must be
 * versioned and enter evaluation. This registry maintains an
 * in-memory map of template name to template content. It is pre-loaded with
 * a default system prompt that constrains the LLM to only select from the
 * provided candidates.</p>
 *
 * <p>The default system prompt enforces the security boundary:
 * the model may only select one candidate alias and generate arguments for
 * that candidate's public input Schema. The model must not output protocol
 * bindings, service addresses, interface class names, tenant identity,
 * serialization methods, timeout, or retry configuration.</p>
 *
 * @since 0.1.0
 */
@Component
public class PromptTemplateRegistry {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRegistry.class);

    /**
     * The default system prompt that constrains the LLM to only select from
     * the provided candidates and return one of three decision types.
     */
    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a capability routing assistant for an AI capability gateway.

            You will receive a list of candidate capabilities, each identified by a
            short alias (e.g., cap_7k3m2v6p4a9d1f8q). Each candidate includes:
            - alias: the short identifier you must use in your response
            - displayName: the user-facing capability name
            - description: the single business action this capability performs
            - positiveExamples: queries this capability handles
            - negativeExamples: queries this capability does NOT handle
            - synonyms: key noun synonyms
            - inputSchema: the public JSON Schema (MODEL fields only) for arguments

            You MUST respond with exactly one of three decision types:

            1. SELECT: Choose the alias that best matches the user's request and
               generate arguments conforming to that candidate's inputSchema.
               Response format:
               {"decision":"SELECT","alias":"<alias>","arguments":{...}}

            2. CLARIFY: If the user's request is ambiguous or missing required
               information, ask a clarifying question.
               Response format:
               {"decision":"CLARIFY","question":"<question>"}

            3. NO_MATCH: If no candidate matches the user's request.
               Response format:
               {"decision":"NO_MATCH","reasonCode":"<reason>"}

            Constraints:
            - You may ONLY select from the provided candidate aliases.
            - You must NOT output protocol bindings, service addresses, interface
              class names, tenant identity, serialization methods, timeout, or
              retry configuration.
            - You must NOT declare authorization success or execution success.
            - Arguments must conform to the selected candidate's inputSchema.
            - Return valid JSON only.
            """;

    private static final String DEFAULT_TEMPLATE_NAME = "default-system";

    private final Map<String, String> templates = new ConcurrentHashMap<>();

    /**
     * Constructs a new PromptTemplateRegistry pre-loaded with the default
     * system prompt.
     */
    public PromptTemplateRegistry() {
        registerTemplate(DEFAULT_TEMPLATE_NAME, DEFAULT_SYSTEM_PROMPT);
        log.info("PromptTemplateRegistry initialized with default system prompt");
    }

    /**
     * Retrieves a prompt template by name.
     *
     * @param name the template name
     * @return the template content, or {@code null} if not found
     * @throws java.lang.NullPointerException if name is null
     */
    public String getTemplate(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return templates.get(name);
    }

    /**
     * Registers or replaces a prompt template.
     *
     * <p>Templates are versioned in evaluation. Replacing a
     * template at runtime does not affect already-sent LLM requests.</p>
     *
     * @param name the template name
     * @param content the template content
     * @throws java.lang.NullPointerException if name or content is null
     */
    public void registerTemplate(String name, String content) {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(content, "content must not be null");
        templates.put(name, content);
        log.debug("Registered prompt template: name={}", name);
    }
}
