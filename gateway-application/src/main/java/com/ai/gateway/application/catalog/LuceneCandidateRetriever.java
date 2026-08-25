package com.ai.gateway.application.catalog;

import com.ai.gateway.domain.model.CapabilityManifest;
import com.ai.gateway.domain.model.CatalogSnapshot;
import com.ai.gateway.domain.port.CandidateRetriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Lucene-based BM25 candidate retriever for natural-language routing
 *
 * <p>This adapter implements {@link CandidateRetriever} using an embedded
 * Apache Lucene in-memory index with {@link BM25Similarity}. The index
 * is rebuilt whenever a new catalog snapshot is published.</p>
 *
 * <p><strong>Index fields:</strong></p>
 * <ul>
 * <li>{@code displayName} — the user-facing capability name (boost: 3.0).</li>
 * <li>{@code description} — the business action description (boost: 2.0).</li>
 * <li>{@code positiveExamples} — concatenated positive examples (boost: 1.5).</li>
 * <li>{@code negativeExamples} — concatenated negative examples (indexed but
 * not queried, used for model disambiguation context).</li>
 * <li>{@code synonyms} — concatenated key noun synonyms (boost: 1.5).</li>
 * <li>{@code tags} — concatenated controlled tags (boost: 1.0).</li>
 * <li>{@code fieldNames} — public field names from the input schema
 * (boost: 1.0).</li>
 * <li>{@code capKey} — the {@code "id:version"} composite key used for
 * non-bypassable authorization filtering.</li>
 * </ul>
 *
 * <p><strong>Authorization filtering:</strong> the retriever
 * applies a non-bypassable authorization filter during search. Only
 * capabilities in the {@code authorizedCapabilities} set participate in
 * scoring and Top-K truncation. The filter is implemented as a Lucene
 * {@code BooleanQuery} with {@code Occur.FILTER}, which limits the document
 * set without contributing to the BM25 score.</p>
 *
 * <p><strong>Fixed analyzer version:</strong> the analyzer and
 * stopword list version are fixed and recorded as {@link #ANALYZER_VERSION}.
 * This prevents unexplainable routing differences across instances or before
 * and after publication. Chinese text segmentation uses
 * {@link SmartChineseAnalyzer} from the {@code lucene-analysis-smartcn}
 * module.</p>
 *
 * <p><strong>Thread safety:</strong> uses a {@link ReadWriteLock} to allow
 * concurrent reads during retrieval while ensuring exclusive access during
 * index rebuilds. The current searcher reference is volatile for visibility.</p>
 *
 * <p><strong>Physical index security:</strong> the index
 * contains only user-facing metadata — no protocol addresses, internal
 * interface details, or secrets.</p>
 *
 * <p>This class uses constructor injection and contains no Spring annotations.
 * Lucene is embedded (in-memory), so the constructor takes no external
 * dependencies.</p>
 *
 * @see CandidateRetriever
 * @see CatalogSnapshot
 * @since 0.1.0
 */
public final class LuceneCandidateRetriever
        implements CandidateRetriever, CatalogBoundCandidateRetriever {

    private static final Logger log = LoggerFactory.getLogger(LuceneCandidateRetriever.class);

    /**
     * The fixed analyzer and stopword list version identifier, recorded in
     * the snapshot and evaluation report to prevent unexplainable routing
     * differences.
     */
    public static final String ANALYZER_VERSION = "smartcn-1.0-default-stopwords";

    // Index field names
    private static final String FIELD_CAP_KEY = "capKey";
    private static final String FIELD_DISPLAY_NAME = "displayName";
    private static final String FIELD_DESCRIPTION = "description";
    private static final String FIELD_POSITIVE_EXAMPLES = "positiveExamples";
    private static final String FIELD_NEGATIVE_EXAMPLES = "negativeExamples";
    private static final String FIELD_SYNONYMS = "synonyms";
    private static final String FIELD_TAGS = "tags";
    private static final String FIELD_FIELD_NAMES = "fieldNames";

    // Fields searched by MultiFieldQueryParser with their boost values
    private static final String[] SEARCH_FIELDS = {
            FIELD_DISPLAY_NAME, FIELD_DESCRIPTION, FIELD_POSITIVE_EXAMPLES,
            FIELD_SYNONYMS, FIELD_TAGS, FIELD_FIELD_NAMES
    };

    private static final Map<String, Float> FIELD_BOOSTS;

    static {
        Map<String, Float> boosts = new HashMap<>();
        boosts.put(FIELD_DISPLAY_NAME, 3.0f);
        boosts.put(FIELD_DESCRIPTION, 2.0f);
        boosts.put(FIELD_POSITIVE_EXAMPLES, 1.5f);
        boosts.put(FIELD_SYNONYMS, 1.5f);
        boosts.put(FIELD_TAGS, 1.0f);
        boosts.put(FIELD_FIELD_NAMES, 1.0f);
        FIELD_BOOSTS = Map.copyOf(boosts);
    }

    private final Analyzer analyzer;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // Volatile references for safe publication after write lock release
    private volatile IndexHandle currentIndex;
    private volatile long indexedSnapshotVersion = -1;

    /**
     * Constructs a new LuceneCandidateRetriever with an embedded
     * SmartChineseAnalyzer. No external dependencies are required — Lucene
     * runs entirely in-memory.
     */
    public LuceneCandidateRetriever() {
        this.analyzer = new SmartChineseAnalyzer();
        log.info("LuceneCandidateRetriever initialized with analyzer version: {}", ANALYZER_VERSION);
    }

    /**
     * Rebuilds the BM25 index from the given catalog snapshot.
     *
     * <p>This method acquires a write lock, closes the old index resources,
     * creates a new in-memory Lucene index, indexes all capabilities from
     * the snapshot, and atomically swaps the searcher reference. During
     * rebuild, concurrent {@link #retrieve} calls block until the rebuild
     * completes.</p>
     *
     * <p>The index contains only user-facing metadata — no protocol
     * addresses, internal interface details, or secrets.</p>
     *
     * @param snapshot the catalog snapshot to index
     * @throws NullPointerException if {@code snapshot} is null
     */
    public void rebuildIndex(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        log.info("Rebuilding BM25 index for snapshot version {}, {} capabilities",
                snapshot.snapshotVersion(), snapshot.capabilities().size());

        IndexHandle newIndex = buildIndex(snapshot);
        lock.writeLock().lock();
        try {
            IndexHandle oldIndex = this.currentIndex;
            publishIndexLocked(newIndex);
            closeIndex(oldIndex);

            log.info("BM25 index rebuilt: {} documents indexed, snapshot version={}",
                    newIndex.capabilities().size(), snapshot.snapshotVersion());
        } catch (Exception e) {
            closeIndex(newIndex);
            log.error("Failed to rebuild BM25 index for snapshot version {}: {}",
                    snapshot.snapshotVersion(), e.getMessage(), e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Publishes a prebuilt handle for an atomic catalog-view refresh. */
    public void publishIndex(IndexHandle index) {
        Objects.requireNonNull(index, "index must not be null");
        lock.writeLock().lock();
        try {
            publishIndexLocked(index);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void publishIndexLocked(IndexHandle index) {
        this.currentIndex = index;
        this.indexedSnapshotVersion = index.snapshotVersion();
    }

    /** Builds an immutable index without changing the currently active index. */
    public IndexHandle buildIndex(CatalogSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Directory newDirectory = new ByteBuffersDirectory();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
            config.setSimilarity(new BM25Similarity());

            Map<String, CapabilityManifest> capabilities = new HashMap<>();
            try (IndexWriter writer = new IndexWriter(newDirectory, config)) {
                for (CapabilityManifest manifest : snapshot.capabilities()) {
                    String capKey = manifest.metadata().id() + ":"
                            + manifest.metadata().version();
                    if (capabilities.put(capKey, manifest) != null) {
                        throw new IllegalArgumentException("duplicate capability binding: " + capKey);
                    }
                    writer.addDocument(createDocument(manifest, capKey));
                }
            }
            DirectoryReader reader = DirectoryReader.open(newDirectory);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            long sizeBytes = 0L;
            for (String file : newDirectory.listAll()) {
                sizeBytes = Math.addExact(sizeBytes, newDirectory.fileLength(file));
            }
            return new IndexHandle(snapshot.snapshotVersion(), newDirectory, reader,
                    searcher, Map.copyOf(capabilities), sizeBytes, reader.numDocs());
        } catch (Exception e) {
            try {
                newDirectory.close();
            } catch (Exception closeFailure) {
                log.warn("Failed to close incomplete BM25 index: {}", closeFailure.getMessage());
            }
            throw new IllegalStateException("failed to build BM25 index", e);
        }
    }

    /**
     * Returns the snapshot version currently indexed, or -1 if no index
     * has been built.
     *
     * @return the indexed snapshot version
     */
    public long getIndexedSnapshotVersion() {
        return indexedSnapshotVersion;
    }

    @Override
    public long indexedCatalogVersion() {
        return indexedSnapshotVersion;
    }

    /** Exercises the real query path before a newly built view becomes ready. */
    boolean warmUp(ActiveCatalogView view) {
        Objects.requireNonNull(view, "view must not be null");
        if (view.indexHandle() == null || view.capabilities().isEmpty()) {
            return false;
        }
        String probe = view.capabilities().get(0).spec().displayName();
        return !retrieve(probe, view, view.capabilities(), 1).isEmpty();
    }

    @Override
    public List<ScoredCapability> retrieve(String normalizedText,
                                            List<CapabilityManifest> authorizedCapabilities,
                                            int topK) {
        Objects.requireNonNull(normalizedText, "normalizedText must not be null");
        Objects.requireNonNull(authorizedCapabilities, "authorizedCapabilities must not be null");

        if (normalizedText.isBlank() || authorizedCapabilities.isEmpty() || topK <= 0) {
            return List.of();
        }

        lock.readLock().lock();
        try {
            IndexHandle index = currentIndex;
            if (index == null) {
                log.warn("BM25 index not yet built; returning empty results");
                return List.of();
            }
            return retrieve(index, normalizedText, authorizedCapabilities, topK);
        } catch (Exception e) {
            log.error("BM25 retrieval failed: {}", e.getMessage(), e);
            return List.of();
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<ScoredCapability> retrieve(String normalizedText,
                                           ActiveCatalogView view,
                                           List<CapabilityManifest> authorizedCapabilities,
                                           int topK) {
        Objects.requireNonNull(view, "view must not be null");
        if (view.indexHandle() == null) {
            return List.of();
        }
        return retrieve(view.indexHandle(), normalizedText, authorizedCapabilities, topK);
    }

    private List<ScoredCapability> retrieve(IndexHandle index,
                                            String normalizedText,
                                            List<CapabilityManifest> authorizedCapabilities,
                                            int topK) {
        try {
            // TermInSetQuery avoids Boolean max-clause failures for large ACL sets.
            List<BytesRef> authorizedKeys = authorizedCapabilities.stream()
                    .map(manifest -> manifest.metadata().id() + ":"
                            + manifest.metadata().version())
                    .distinct()
                    .map(BytesRef::new)
                    .toList();
            Query authFilter = new TermInSetQuery(FIELD_CAP_KEY, authorizedKeys);
            MultiFieldQueryParser parser = new MultiFieldQueryParser(SEARCH_FIELDS, analyzer, FIELD_BOOSTS);
            Query textQuery = parser.parse(QueryParser.escape(normalizedText));
            BooleanQuery.Builder fullQuery = new BooleanQuery.Builder();
            fullQuery.add(textQuery, BooleanClause.Occur.MUST);
            fullQuery.add(authFilter, BooleanClause.Occur.FILTER);

            TopDocs topDocs = index.searcher().search(fullQuery.build(), topK);
            List<ScoredCapability> results = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = index.searcher().doc(scoreDoc.doc);
                CapabilityManifest manifest = index.capabilities().get(doc.get(FIELD_CAP_KEY));
                if (manifest != null) {
                    results.add(new ScoredCapability(manifest, scoreDoc.score));
                }
            }
            log.debug("BM25 retrieval: text='{}', authorized={}, topK={}, results={}",
                    normalizedText, authorizedCapabilities.size(), topK, results.size());
            return results;
        } catch (Exception e) {
            log.error("BM25 retrieval failed for catalog version {}: {}",
                    index.snapshotVersion(), e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Creates a Lucene Document for a capability manifest.
     *
     * <p>The document contains only user-facing metadata — no protocol
     * addresses, internal interface details, or secrets.</p>
     *
     * @param manifest the capability manifest
     * @param capKey the composite "id:version" key
     * @return the Lucene document
     */
    private Document createDocument(CapabilityManifest manifest, String capKey) {
        Document doc = new Document();

        doc.add(new StringField(FIELD_CAP_KEY, capKey, Field.Store.YES));

        CapabilityManifest.Spec spec = manifest.spec();
        doc.add(new TextField(FIELD_DISPLAY_NAME, spec.displayName(), Field.Store.YES));
        doc.add(new TextField(FIELD_DESCRIPTION, spec.description(), Field.Store.YES));

        // Positive examples
        doc.add(new TextField(FIELD_POSITIVE_EXAMPLES,
                String.join(" ", spec.examples().positive()), Field.Store.NO));

        // Negative examples (indexed but not queried; for model disambiguation context)
        doc.add(new TextField(FIELD_NEGATIVE_EXAMPLES,
                String.join(" ", spec.examples().negative()), Field.Store.NO));

        // Synonyms
        doc.add(new TextField(FIELD_SYNONYMS,
                String.join(" ", spec.examples().synonyms()), Field.Store.NO));

        // Tags
        if (manifest.metadata().tags() != null && !manifest.metadata().tags().isEmpty()) {
            doc.add(new TextField(FIELD_TAGS,
                    String.join(" ", manifest.metadata().tags()), Field.Store.NO));
        }

        // Field names from input schema
        String fieldNames = extractFieldNames(spec.inputSchema());
        if (!fieldNames.isEmpty()) {
            doc.add(new TextField(FIELD_FIELD_NAMES, fieldNames, Field.Store.NO));
        }

        return doc;
    }

    /**
     * Extracts the property field names from the input JSON Schema.
     *
     * @param inputSchema the model-visible JSON Schema
     * @return a space-separated string of field names
     */
    @SuppressWarnings("unchecked")
    private String extractFieldNames(Map<String, Object> inputSchema) {
        Object properties = inputSchema.get("properties");
        if (properties instanceof Map) {
            Map<String, Object> props = (Map<String, Object>) properties;
            if (!props.isEmpty()) {
                return String.join(" ", props.keySet());
            }
        }
        return "";
    }

    private void closeIndex(IndexHandle index) {
        if (index == null) {
            return;
        }
        try {
            index.close();
        } catch (Exception e) {
            log.warn("Failed to close old index reader: {}", e.getMessage());
        }
    }

    /** Immutable Lucene resources associated with one catalog version. */
    public static final class IndexHandle implements AutoCloseable {
        private final long snapshotVersion;
        private final Directory directory;
        private final DirectoryReader reader;
        private final IndexSearcher searcher;
        private final Map<String, CapabilityManifest> capabilities;
        private final long sizeBytes;
        private final int documentCount;
        private final AtomicBoolean closed = new AtomicBoolean();

        private IndexHandle(long snapshotVersion, Directory directory,
                            DirectoryReader reader, IndexSearcher searcher,
                            Map<String, CapabilityManifest> capabilities,
                            long sizeBytes, int documentCount) {
            this.snapshotVersion = snapshotVersion;
            this.directory = directory;
            this.reader = reader;
            this.searcher = searcher;
            this.capabilities = capabilities;
            this.sizeBytes = sizeBytes;
            this.documentCount = documentCount;
        }

        public long snapshotVersion() {
            return snapshotVersion;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        public int documentCount() {
            return documentCount;
        }

        public boolean isClosed() {
            return closed.get();
        }

        private IndexSearcher searcher() {
            if (closed.get()) {
                throw new IllegalStateException("catalog index handle is closed");
            }
            return searcher;
        }

        private Map<String, CapabilityManifest> capabilities() {
            return capabilities;
        }

        @Override
        public void close() throws Exception {
            if (closed.compareAndSet(false, true)) {
                try {
                    reader.close();
                } finally {
                    directory.close();
                }
            }
        }
    }
}
