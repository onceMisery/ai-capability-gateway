package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.port.CandidateRetriever;

import java.util.List;

/** Retrieval contract bound to the immutable catalog view used by a request. */
public interface CatalogBoundCandidateRetriever {

    List<CandidateRetriever.ScoredCapability> retrieve(
            String normalizedText,
            ActiveCatalogView view,
            List<CapabilityManifest> authorizedCapabilities,
            int topK);
}
