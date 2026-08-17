package com.ai.gateway.domain.service;

import com.ai.gateway.domain.model.ArgumentBinding;
import com.ai.gateway.domain.model.ArgumentSource;
import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ConverterType;
import com.ai.gateway.domain.model.FieldBinding;
import com.ai.gateway.domain.model.PayloadLimits;
import com.ai.gateway.domain.model.Principal;
import com.ai.gateway.domain.model.ProtocolBinding;
import com.ai.gateway.domain.model.SystemContext;
import com.ai.gateway.domain.port.SchemaValidator;
import com.ai.gateway.domain.port.TypeConverterRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministically binds protocol parameters from model output, Principal,
 * Manifest constants, and system context.
 *
 * <p>The binding follows the 8-step processing order defined in
 * :</p>
 * <ol>
 * <li>Parse model output JSON, rejecting duplicate keys and non-finite
 * numbers.</li>
 * <li>Validate against the public input Schema (delegated to
 * {@link SchemaValidator}).</li>
 * <li>Execute format/length/enum/business pre-constraints.</li>
 * <li>Resolve non-model fields from Principal and Manifest constants.</li>
 * <li>Construct protocol parameters by static mapping.</li>
 * <li>Execute type and size validation on complete parameters.</li>
 * <li>Authorization is done by the caller (not the binder).</li>
 * <li>Protocol adapter call is done by the caller (not the binder).</li>
 * </ol>
 *
 * <p>Binding modes:</p>
 * <ul>
 * <li><strong>Simple binding</strong> — uses {@code source} and
 * {@code sourcePath} to read a value from a single controlled source
 * (MODEL, PRINCIPAL, CONSTANT, or SYSTEM).</li>
 * <li><strong>Composite binding</strong> — uses a map of JSON Pointer to
 * {@link FieldBinding} for DTOs containing both business and trusted
 * fields. This is a static mapping; no SpEL, scripts, or arbitrary
 * expressions are allowed.</li>
 * </ul>
 *
 * <p>Reject rules:</p>
 * <ul>
 * <li>Duplicate positions, non-contiguous positions, or positions
 * inconsistent with the protocol signature.</li>
 * <li>PRINCIPAL paths that do not exist, have mismatched types, or have
 * null values.</li>
 * <li>Undeclared fields in the model output.</li>
 * <li>The same target field assigned by multiple sources.</li>
 * <li>Assignment to reserved fields: {@code class}, {@code @type}.</li>
 * <li>Constants incompatible with the target type.</li>
 * </ul>
 *
 * <p>The binder uses JSON Pointer (RFC 6901) for path resolution. It throws
 * {@link IllegalArgumentException} with a descriptive message on any
 * validation failure.</p>
 *
 * <p>String concatenation, script engines, reflective execution of user
 * expressions, and uncontrolled object deserialization are prohibited
 *.</p>
 *
 * @since 0.1.0
 */
public final class ArgumentBinder {

    /**
     * Reserved field names that must not be assigned by composite bindings
     */
    private static final Set<String> RESERVED_FIELDS = Set.of(
            "class",
            "@type",
            "@class",
            "proto"
    );

    private final TypeConverterRegistry typeConverters;
    private final SchemaValidator schemaValidator;
    private final Principal principal;
    private final SystemContext systemContext;
    private final CapabilityManifest manifest;
    private final PayloadTreeGuard payloadTreeGuard;

    /**
     * Constructs a new ArgumentBinder with the required dependencies and
     * context.
     *
     * @param typeConverters the controlled type converter registry; used for
     * applying converters from the whitelist
     *
     * @param schemaValidator the JSON Schema validator; used for validating
     * model output against the public input Schema
     *
     * @param principal the authenticated Principal; source for
     * PRINCIPAL-sourced arguments
     * @param systemContext the platform execution context; source for
     * SYSTEM-sourced arguments
     * @param manifest the capability manifest containing the protocol
     * binding and input schema
     * @throws NullPointerException if any argument is null
     */
    public ArgumentBinder(TypeConverterRegistry typeConverters,
                          SchemaValidator schemaValidator,
                          Principal principal,
                          SystemContext systemContext,
                          CapabilityManifest manifest) {
        this(typeConverters, schemaValidator, principal, systemContext, manifest,
                PayloadLimits.defaults());
    }

    /**
     * 使用统一 Payload 预算创建参数绑定器。
     *
     * @param typeConverters 类型转换器注册表
     * @param schemaValidator 输入 Schema 校验器
     * @param principal 当前主体
     * @param systemContext 当前系统上下文
     * @param manifest 能力 Manifest
     * @param payloadLimits 输入 Payload 预算
     */
    public ArgumentBinder(TypeConverterRegistry typeConverters,
                          SchemaValidator schemaValidator,
                          Principal principal,
                          SystemContext systemContext,
                          CapabilityManifest manifest,
                          PayloadLimits payloadLimits) {
        this.typeConverters = java.util.Objects.requireNonNull(
                typeConverters, "typeConverters must not be null");
        this.schemaValidator = java.util.Objects.requireNonNull(
                schemaValidator, "schemaValidator must not be null");
        this.principal = java.util.Objects.requireNonNull(
                principal, "principal must not be null");
        this.systemContext = java.util.Objects.requireNonNull(
                systemContext, "systemContext must not be null");
        this.manifest = java.util.Objects.requireNonNull(
                manifest, "manifest must not be null");
        this.payloadTreeGuard = new PayloadTreeGuard(java.util.Objects.requireNonNull(
                payloadLimits, "payloadLimits must not be null"));
    }

    /**
     * Binds the model output arguments to an ordered list of protocol
     * parameters following the 8-step processing order.
     *
     * <p>Steps 7 (authorization) and 8 (protocol adapter call) are the
     * caller's responsibility.</p>
     *
     * @param modelArguments the model's structured output keyed by argument
     * name; must not be null
     * @return the ordered list of bound protocol arguments, matching the
     * parameter types declared in the manifest's protocol binding
     * @throws IllegalArgumentException if any validation step fails, with a
     * descriptive message identifying the
     * failure
     * @throws NullPointerException if {@code modelArguments} is null
     */
    public List<Object> bind(Map<String, Object> modelArguments) {
        java.util.Objects.requireNonNull(modelArguments, "modelArguments must not be null");

        // 在 Schema 校验和递归业务处理前先执行统一结构预算，避免深层输入耗尽栈。
        payloadTreeGuard.validateInput(modelArguments);

        // Step 1: Parse model output, reject duplicate keys and non-finite numbers
        Map<String, Object> parsedModel = parseModelOutput(modelArguments);

        // Step 2: Validate against public input Schema
        validateAgainstInputSchema(parsedModel);

        // Step 3: Execute format/length/enum/business pre-constraints
        executePreConstraints(parsedModel);

        // Step 4 & 5: Resolve non-model fields and construct protocol parameters
        List<Object> boundArguments = resolveAndConstructParameters(parsedModel);

        // Step 6: Execute type and size validation on complete parameters
        validateCompleteParameters(boundArguments);

        // Steps 7 (authorization) and 8 (adapter call) are done by the caller
        return boundArguments;
    }

    // -------------------------------------------------------------------------
    // Step 1: Parse model output
    // -------------------------------------------------------------------------

    /**
     * Parses the model output, rejecting non-finite numbers (NaN, Infinity).
     *
     * <p>Since the input is already a {@code Map}, duplicate keys cannot
     * exist at this stage (they were resolved during JSON parsing). However,
     * the values may contain non-finite floating-point numbers, which are
     * rejected per .</p>
     *
     * @param modelArguments the raw model output
     * @return the validated model output (defensive copy)
     * @throws IllegalArgumentException if a non-finite number is found
     */
    private Map<String, Object> parseModelOutput(Map<String, Object> modelArguments) {
        Map<String, Object> result = new LinkedHashMap<>(modelArguments.size());
        for (Map.Entry<String, Object> entry : modelArguments.entrySet()) {
            Object value = entry.getValue();
            checkNonFiniteNumbers(value, entry.getKey());
            result.put(entry.getKey(), value);
        }
        return result;
    }

    /**
     * Recursively checks for non-finite numbers (NaN, Infinity) in the
     * value tree.
     *
     * @param value the value to check
     * @param path the JSON path for error reporting
     * @throws IllegalArgumentException if a non-finite number is found
     */
    private void checkNonFiniteNumbers(Object value, String path) {
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException(
                        "Non-finite number at '" + path + "': " + d
                );
            }
        } else if (value instanceof Float f) {
            if (f.isNaN() || f.isInfinite()) {
                throw new IllegalArgumentException(
                        "Non-finite number at '" + path + "': " + f
                );
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                checkNonFiniteNumbers(entry.getValue(), path + "/" + entry.getKey());
            }
        } else if (value instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                checkNonFiniteNumbers(list.get(i), path + "/" + i);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 2: Validate against input Schema
    // -------------------------------------------------------------------------

    /**
     * Validates the parsed model output against the capability's public
     * input Schema.
     *
     * @param parsedModel the parsed model output
     * @throws IllegalArgumentException if the schema validation fails
     */
    private void validateAgainstInputSchema(Map<String, Object> parsedModel) {
        Map<String, Object> inputSchema = manifest.spec().inputSchema();
        com.ai.gateway.domain.model.ValidationReport report =
                schemaValidator.validate(parsedModel, inputSchema);
        if (!report.valid()) {
            throw new IllegalArgumentException(
                    "Model output failed input Schema validation: " + report.errors()
            );
        }
    }

    // -------------------------------------------------------------------------
    // Step 3: Execute pre-constraints
    // -------------------------------------------------------------------------

    /**
     * Executes format, length, enum, and business pre-constraints on the
     * model output.
     *
     * <p>In the current implementation, JSON Schema validation (step 2)
     * already covers format, length, and enum constraints. Additional
     * business constraints beyond JSON Schema are checked here. This is
     * a simplified implementation; full business constraint validation
     * is delegated to the SchemaValidator adapter.</p>
     *
     * @param parsedModel the parsed model output
     */
    private void executePreConstraints(Map<String, Object> parsedModel) {
        // Reject undeclared model fields not present in the input Schema
        Map<String, Object> inputSchema = manifest.spec().inputSchema();
        Object properties = inputSchema.get("properties");
        if (properties instanceof Map<?, ?> schemaProperties) {
            for (String key : parsedModel.keySet()) {
                if (!schemaProperties.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "Undeclared model field '" + key
                                    + "': not present in the public input Schema properties"
                    );
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Step 4 & 5: Resolve and construct protocol parameters
    // -------------------------------------------------------------------------

    /**
     * Resolves non-model fields from Principal and Manifest constants, then
     * constructs the ordered list of protocol parameters by static mapping
     *
     * @param parsedModel the parsed and validated model output
     * @return the ordered list of bound protocol arguments
     * @throws IllegalArgumentException if any reject rule is triggered
     */
    private List<Object> resolveAndConstructParameters(Map<String, Object> parsedModel) {
        ProtocolBinding binding = manifest.spec().invocation();
        List<ArgumentBinding> arguments = binding.arguments();
        List<String> parameterTypes = binding.parameterTypes();

        // Validate positions: no duplicates, contiguous, consistent with parameterTypes
        validatePositions(arguments, parameterTypes);

        List<Object> result = new ArrayList<>(arguments.size());
        for (ArgumentBinding argBinding : arguments) {
            Object value;
            if (argBinding.isComposite()) {
                value = bindCompositeArgument(argBinding, parsedModel);
            } else {
                value = bindSimpleArgument(argBinding, parsedModel);
            }
            result.add(value);
        }
        return result;
    }

    /**
     * Validates that argument positions are unique, contiguous, and
     * consistent with the declared parameter types.
     *
     * @param arguments the argument bindings
     * @param parameterTypes the declared parameter type names
     * @throws IllegalArgumentException if positions are invalid
     */
    private void validatePositions(List<ArgumentBinding> arguments,
                                   List<String> parameterTypes) {
        if (arguments.size() != parameterTypes.size()) {
            throw new IllegalArgumentException(
                    "Argument count " + arguments.size()
                            + " does not match parameter type count " + parameterTypes.size()
            );
        }

        Set<Integer> seenPositions = new java.util.HashSet<>();
        for (int i = 0; i < arguments.size(); i++) {
            ArgumentBinding arg = arguments.get(i);
            if (arg.position() != i) {
                throw new IllegalArgumentException(
                        "Non-contiguous position: expected " + i
                                + " but got " + arg.position() + " for argument '" + arg.name() + "'"
                );
            }
            if (!seenPositions.add(arg.position())) {
                throw new IllegalArgumentException(
                        "Duplicate position: " + arg.position() + " for argument '" + arg.name() + "'"
                );
            }
        }
    }

    /**
     * Binds a simple (non-composite) argument by reading from the
     * appropriate source.
     *
     * @param argBinding the argument binding
     * @param parsedModel the parsed model output
     * @return the bound value
     * @throws IllegalArgumentException if the source value cannot be resolved
     */
    private Object bindSimpleArgument(ArgumentBinding argBinding,
                                      Map<String, Object> parsedModel) {
        ArgumentSource source = argBinding.source();
        Object rawValue;

        switch (source) {
            case MODEL:
                rawValue = resolveModelPath(argBinding.sourcePath(), parsedModel);
                break;
            case PRINCIPAL:
                rawValue = resolvePrincipalPath(argBinding.sourcePath());
                break;
            case CONSTANT:
                rawValue = argBinding.constantValue();
                validateConstantType(argBinding);
                break;
            case SYSTEM:
                rawValue = resolveSystemPath(argBinding.sourcePath());
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown argument source: " + source + " for argument '" + argBinding.name() + "'"
                );
        }

        return applyConverter(rawValue, argBinding.converter());
    }

    /**
     * Binds a composite (object) argument by constructing a DTO map from
     * multiple field bindings.
     *
     * @param argBinding the argument binding containing object bindings
     * @param parsedModel the parsed model output
     * @return a map representing the constructed DTO
     * @throws IllegalArgumentException if any reject rule is triggered
     */
    private Object bindCompositeArgument(ArgumentBinding argBinding,
                                         Map<String, Object> parsedModel) {
        Map<String, FieldBinding> objectBindings = argBinding.objectBindings();
        Map<String, Object> result = new LinkedHashMap<>(objectBindings.size());
        Set<String> assignedFields = new java.util.HashSet<>();

        for (Map.Entry<String, FieldBinding> entry : objectBindings.entrySet()) {
            String targetPath = entry.getKey();
            FieldBinding fieldBinding = entry.getValue();

            // Check reserved field assignment
            checkReservedField(targetPath);

            // Check same target field assigned by multiple sources
            if (!assignedFields.add(targetPath)) {
                throw new IllegalArgumentException(
                        "Duplicate target field assignment: " + targetPath
                                + " in composite binding for argument '" + argBinding.name() + "'"
                );
            }

            Object value;
            switch (fieldBinding.source()) {
                case MODEL:
                    value = resolveModelPath(fieldBinding.sourcePath(), parsedModel);
                    break;
                case PRINCIPAL:
                    value = resolvePrincipalPath(fieldBinding.sourcePath());
                    break;
                case CONSTANT:
                    value = fieldBinding.constantValue();
                    validateFieldConstantType(fieldBinding);
                    break;
                case SYSTEM:
                    value = resolveSystemPath(fieldBinding.sourcePath());
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Unknown field source: " + fieldBinding.source()
                    );
            }

            value = applyConverter(value, fieldBinding.converter());

            // Place the value at the target path in the result map
            setNestedValue(result, targetPath, value);
        }

        return result;
    }

    // -------------------------------------------------------------------------
    // Source resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a MODEL source path from the parsed model output using
     * JSON Pointer (RFC 6901).
     *
     * @param pointer the JSON Pointer into the model output
     * @param parsedModel the parsed model output
     * @return the resolved value
     * @throws IllegalArgumentException if the path is not found
     */
    private Object resolveModelPath(String pointer, Map<String, Object> parsedModel) {
        List<String> tokens = parseJsonPointer(pointer);
        if (tokens.isEmpty()) {
            return parsedModel;
        }

        Object current = parsedModel;
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw new IllegalArgumentException(
                            "MODEL path not found: '" + pointer
                                    + "' — segment '" + token + "' does not exist"
                    );
                }
                current = map.get(token);
            } else if (current instanceof List<?> list) {
                int idx;
                try {
                    idx = Integer.parseInt(token);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "MODEL path type mismatch: expected array index but got '" + token
                                    + "' in path '" + pointer + "'"
                    );
                }
                if (idx < 0 || idx >= list.size()) {
                    throw new IllegalArgumentException(
                            "MODEL path not found: array index " + idx
                                    + " out of bounds in path '" + pointer + "'"
                    );
                }
                current = list.get(idx);
            } else {
                throw new IllegalArgumentException(
                        "MODEL path type mismatch: cannot resolve '" + token
                                + "' from non-container value in path '" + pointer + "'"
                );
            }
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "MODEL path value is null: '" + pointer + "'"
            );
        }
        return current;
    }

    /**
     * Resolves a PRINCIPAL source path using JSON Pointer (RFC 6901).
     *
     * @param pointer the JSON Pointer into the Principal
     * @return the resolved value
     * @throws IllegalArgumentException if the path is not found or the
     * value is null
     */
    private Object resolvePrincipalPath(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            throw new IllegalArgumentException(
                    "PRINCIPAL source path must not be empty"
            );
        }

        List<String> tokens = parseJsonPointer(pointer);
        if (tokens.size() == 1) {
            return resolvePrincipalField(tokens.get(0), pointer);
        }

        // Multi-segment pointers: navigate into nested Principal values
        Object current = resolvePrincipalField(tokens.get(0), pointer);
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (current instanceof Map<?, ?> map) {
                if (!map.containsKey(token)) {
                    throw new IllegalArgumentException(
                            "PRINCIPAL path not found: '" + pointer
                                    + "' — segment '" + token + "' does not exist"
                    );
                }
                current = map.get(token);
            } else {
                throw new IllegalArgumentException(
                        "PRINCIPAL path type mismatch: cannot resolve '" + token
                                + "' from " + current.getClass().getName()
                );
            }
        }

        if (current == null) {
            throw new IllegalArgumentException(
                    "PRINCIPAL path value is null: '" + pointer + "'"
            );
        }
        return current;
    }

    /**
     * Resolves a single Principal field by name.
     *
     * @param fieldName the field name
     * @param pointer the full pointer for error reporting
     * @return the resolved value
     * @throws IllegalArgumentException if the field does not exist or is null
     */
    private Object resolvePrincipalField(String fieldName, String pointer) {
        Object value = switch (fieldName) {
            case "subject" -> principal.subject();
            case "orgId" -> principal.orgId();
            case "roles" -> principal.roles();
            case "permissions" -> principal.permissions();
            case "authTime" -> principal.authTime();
            case "authMethod" -> principal.authMethod();
            default -> throw new IllegalArgumentException(
                    "PRINCIPAL path not found: '" + pointer
                            + "' — Principal has no field '" + fieldName + "'"
            );
        };

        if (value == null) {
            throw new IllegalArgumentException(
                    "PRINCIPAL path value is null: '" + pointer + "'"
            );
        }
        return value;
    }

    /**
     * Resolves a SYSTEM source path using JSON Pointer (RFC 6901).
     *
     * @param pointer the JSON Pointer into the SystemContext
     * @return the resolved value
     * @throws IllegalArgumentException if the path is not found, not
     * whitelisted, or the value is null
     */
    private Object resolveSystemPath(String pointer) {
        if (!SystemContext.allowedPaths().contains(pointer)) {
            throw new IllegalArgumentException(
                    "SYSTEM path not in whitelist: '" + pointer
                            + "'; allowed paths are: " + SystemContext.allowedPaths()
            );
        }

        Object value = switch (pointer) {
            case "/traceId" -> systemContext.traceId();
            case "/deadlineEpochMs" -> systemContext.deadlineEpochMs();
            case "/idempotencyKey" -> systemContext.idempotencyKey();
            case "/locale" -> systemContext.locale();
            default -> throw new IllegalArgumentException(
                    "Unknown SYSTEM path: '" + pointer + "'"
            );
        };

        if (value == null) {
            throw new IllegalArgumentException(
                    "SYSTEM path value is null: '" + pointer + "'"
            );
        }
        return value;
    }

    // -------------------------------------------------------------------------
    // Type conversion
    // -------------------------------------------------------------------------

    /**
     * Applies a type converter if one is specified, otherwise returns the
     * value unchanged.
     *
     * @param value the raw value
     * @param converterName the converter name; may be null
     * @return the converted value, or the original value if no converter
     * @throws IllegalArgumentException if the converter is not registered or
     * the conversion fails
     */
    private Object applyConverter(Object value, String converterName) {
        if (converterName == null || converterName.isEmpty()) {
            return value;
        }

        ConverterType converterType;
        try {
            converterType = ConverterType.valueOf(converterName);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown converter type: '" + converterName
                            + "'; not in the controlled converter whitelist"
            );
        }

        if (!typeConverters.isRegistered(converterType)) {
            throw new IllegalArgumentException(
                    "Converter not registered: '" + converterName
                            + "'; not in the controlled converter whitelist"
            );
        }

        return typeConverters.convert(converterType, value);
    }

    // -------------------------------------------------------------------------
    // Validation helpers
    // -------------------------------------------------------------------------

    /**
     * Validates that a CONSTANT value in a simple binding is compatible
     * with the declared protocol type.
     *
     * @param argBinding the argument binding
     * @throws IllegalArgumentException if the constant value is null or
     * incompatible with the protocol type
     */
    private void validateConstantType(ArgumentBinding argBinding) {
        if (argBinding.constantValue() == null) {
            throw new IllegalArgumentException(
                    "CONSTANT value must not be null for argument '" + argBinding.name() + "'"
            );
        }
        // Type compatibility check: verify the constant value's type is
        // compatible with the declared protocolType string
        String protocolType = argBinding.protocolType();
        Object constantValue = argBinding.constantValue();
        if (!isTypeCompatible(constantValue, protocolType)) {
            throw new IllegalArgumentException(
                    "CONSTANT type incompatibility for argument '" + argBinding.name()
                            + "': constant value is " + constantValue.getClass().getName()
                            + " but protocolType is '" + protocolType + "'"
            );
        }
    }

    /**
     * Validates that a CONSTANT value in a composite field binding is
     * compatible.
     *
     * @param fieldBinding the field binding
     * @throws IllegalArgumentException if the constant value is null
     */
    private void validateFieldConstantType(FieldBinding fieldBinding) {
        if (fieldBinding.constantValue() == null) {
            throw new IllegalArgumentException(
                    "CONSTANT value must not be null for field binding with sourcePath '"
                            + fieldBinding.sourcePath() + "'"
            );
        }
    }

    /**
     * Checks whether a value's Java type is compatible with the declared
     * protocol type name string.
     *
     * @param value the value
     * @param protocolType the fully-qualified protocol type name
     * @return {@code true} if compatible
     */
    private boolean isTypeCompatible(Object value, String protocolType) {
        if (protocolType == null) {
            return true;
        }
        String typeName = value.getClass().getName();

        // Common compatible mappings
        if (protocolType.equals("java.lang.String")) {
            return value instanceof String;
        }
        if (protocolType.equals("java.lang.Long") || protocolType.equals("long")) {
            return value instanceof Long || value instanceof Integer
                    || value instanceof Number;
        }
        if (protocolType.equals("java.lang.Integer") || protocolType.equals("int")) {
            return value instanceof Integer || value instanceof Number;
        }
        if (protocolType.equals("java.lang.Boolean") || protocolType.equals("boolean")) {
            return value instanceof Boolean;
        }
        if (protocolType.equals("java.lang.Double") || protocolType.equals("double")) {
            return value instanceof Double || value instanceof Number;
        }
        if (protocolType.equals("java.lang.Object")) {
            return true;
        }
        // For unknown types, allow the value — the adapter will perform
        // runtime type checking during generic invocation
        return true;
    }

    /**
     * Checks that the target field path in a composite binding is not a
     * reserved field name.
     *
     * @param targetPath the target JSON Pointer
     * @throws IllegalArgumentException if the target is a reserved field
     */
    private void checkReservedField(String targetPath) {
        List<String> tokens = parseJsonPointer(targetPath);
        for (String token : tokens) {
            if (RESERVED_FIELDS.contains(token)) {
                throw new IllegalArgumentException(
                        "Reserved field assignment: '" + token
                                + "' is a reserved field and must not be assigned by bindings"
                );
            }
        }
    }

    /**
     * Executes type and size validation on the complete parameter list
     *
     * @param boundArguments the fully bound protocol arguments
     * @throws IllegalArgumentException if any argument is null or
     * size validation fails
     */
    private void validateCompleteParameters(List<Object> boundArguments) {
        ProtocolBinding binding = manifest.spec().invocation();
        List<String> parameterTypes = binding.parameterTypes();

        for (int i = 0; i < boundArguments.size(); i++) {
            Object arg = boundArguments.get(i);
            if (arg == null) {
                throw new IllegalArgumentException(
                        "Bound argument at position " + i + " is null; "
                                + "all protocol parameters must be non-null"
                );
            }
        }
    }

    // -------------------------------------------------------------------------
    // JSON Pointer utilities
    // -------------------------------------------------------------------------

    /**
     * Sets a nested value in the result map using a JSON Pointer path.
     *
     * @param result the result map
     * @param pointer the JSON Pointer (e.g., "/orgId" or "/address/city")
     * @param value the value to set
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> result, String pointer, Object value) {
        List<String> tokens = parseJsonPointer(pointer);
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException(
                    "Empty target path in composite binding"
            );
        }

        Map<String, Object> current = result;
        for (int i = 0; i < tokens.size() - 1; i++) {
            String token = tokens.get(i);
            Object child = current.get(token);
            if (child == null) {
                child = new LinkedHashMap<String, Object>();
                current.put(token, child);
            } else if (!(child instanceof Map)) {
                throw new IllegalArgumentException(
                        "Cannot navigate to '" + pointer
                                + "': intermediate segment '" + token
                                + "' is not a map"
                );
            }
            current = (Map<String, Object>) child;
        }
        current.put(tokens.get(tokens.size() - 1), value);
    }

    /**
     * Parses a JSON Pointer (RFC 6901) string into reference tokens.
     *
     * @param pointer the JSON Pointer string
     * @return the list of reference tokens
     */
    private List<String> parseJsonPointer(String pointer) {
        if (pointer == null || pointer.isEmpty() || pointer.equals("/")) {
            return List.of();
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException(
                    "Invalid JSON Pointer: must start with '/' or be empty: " + pointer
            );
        }
        String[] parts = pointer.split("/", -1);
        List<String> tokens = new ArrayList<>(parts.length - 1);
        for (int i = 1; i < parts.length; i++) {
            tokens.add(unescapeToken(parts[i]));
        }
        return tokens;
    }

    /**
     * Unescapes a JSON Pointer reference token per RFC 6901.
     *
     * @param token the raw token
     * @return the unescaped token
     */
    private String unescapeToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}
