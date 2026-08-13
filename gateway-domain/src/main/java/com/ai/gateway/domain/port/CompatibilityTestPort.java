package com.ai.gateway.domain.port;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.ValidationReport;

/**
 * Port for executing compatibility tests against the Provider.
 *
 * <p>(Drift Detection) specifies that each published capability
 * preserves a protocol signature digest. Before publication and during
 * periodic inspections, the gateway performs metadata or probe calls
 * against the test environment to detect the following changes:</p>
 * <ul>
 * <li>Method does not exist.</li>
 * <li>Parameter count, order, or types changed.</li>
 * <li>Serialization is incompatible, or the Provider's serialization
 * negotiation capability shrank, making the declared whitelist
 * serialization unavailable.</li>
 * <li>Provider-side filters or implicit call-chain contract changes
 * cause standard protocol calls to be rejected or behave
 * abnormally.</li>
 * <li>Response wrapping paths or public Schema do not match.</li>
 * <li>Provider no longer meets identity authentication or tenant
 * isolation requirements.</li>
 * </ul>
 *
 * <p>This is part of the 10-step validation pipeline. Runtime
 * detection of similar errors triggers circuit breaking and automatic
 * suspension suggestions, but a single error must not directly modify
 * the catalog.</p>
 *
 * <p>Serialization negotiation capability must be probed periodically:
 * inspection tasks issue lightweight negotiation probes against target
 * Providers for published capabilities. When whitelist serialization is
 * no longer negotiable, an alert is raised and new requests to that
 * capability are suspended.</p>
 *
 * <p>Adapters implementing this port invoke the target Provider in the
 * test environment. The port is a pure abstraction with no framework
 * dependencies.</p>
 *
 * @see CapabilityManifest
 * @see ValidationReport
 * @since 0.1.0
 */
public interface CompatibilityTestPort {

    /**
     * Runs a compatibility test for the given manifest against the
     * specified test environment.
     *
     * <p>/ step 8: the test verifies that the
     * protocol binding is compatible with the actual Provider, including
     * method existence, parameter signatures, serialization negotiation,
     * response wrapping paths, and public Schema matching.</p>
     *
     * <p>The test must not modify the original manifest content. A report
     * is considered valid only if {@code errors} is empty. Warnings are
     * informational and do not block publication.</p>
     *
     * @param manifest the capability manifest to test
     * @param testEnvironment the test environment identifier
     * @return the validation report; valid only if {@code errors} is empty
     */
    ValidationReport runCompatibilityTest(CapabilityManifest manifest, String testEnvironment);
}
