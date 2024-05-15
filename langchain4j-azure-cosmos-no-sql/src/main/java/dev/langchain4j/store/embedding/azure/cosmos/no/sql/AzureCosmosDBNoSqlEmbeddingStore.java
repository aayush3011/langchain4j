package dev.langchain4j.store.embedding.azure.cosmos.no.sql;
import com.azure.cosmos.CosmosClient;
import com.azure.cosmos.CosmosContainer;
import com.azure.cosmos.CosmosDatabase;
import com.azure.cosmos.models.CosmosBulkOperations;
import com.azure.cosmos.models.CosmosContainerProperties;
import com.azure.cosmos.models.CosmosItemOperation;
import com.azure.cosmos.models.CosmosQueryRequestOptions;
import com.azure.cosmos.models.CosmosVectorIndexSpec;
import com.azure.cosmos.models.CosmosVectorEmbeddingPolicy;
import com.azure.cosmos.models.PartitionKey;
import com.azure.cosmos.models.PartitionKeyDefinition;
import com.azure.cosmos.util.CosmosPagedIterable;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.Utils.isNullOrBlank;
import static dev.langchain4j.internal.Utils.isNullOrEmpty;
import static dev.langchain4j.internal.Utils.randomUUID;
import static dev.langchain4j.internal.ValidationUtils.ensureTrue;
import static dev.langchain4j.store.embedding.azure.cosmos.no.sql.MappingUtils.toEmbeddingMatch;
import static dev.langchain4j.store.embedding.azure.cosmos.no.sql.MappingUtils.toNoSqlDbDocument;
import static java.util.Collections.singletonList;

public class AzureCosmosDBNoSqlEmbeddingStore  implements EmbeddingStore<TextSegment> {

    private static final Logger log = LoggerFactory.getLogger(AzureCosmosDBNoSqlEmbeddingStore.class);

    private final CosmosClient cosmosClient;
    private final CosmosDatabase database ;
    private final CosmosContainer container;
    private final String databaseName;
    private final String containerName;
    private final CosmosVectorEmbeddingPolicy cosmosVectorEmbeddingPolicy;
    private final List<CosmosVectorIndexSpec> cosmosVectorIndexes;
    private final CosmosContainerProperties containerProperties;

    public AzureCosmosDBNoSqlEmbeddingStore(CosmosClient cosmosClient,
                                            String databaseName,
                                            String containerName,
                                            CosmosVectorEmbeddingPolicy cosmosVectorEmbeddingPolicy,
                                            List<CosmosVectorIndexSpec> cosmosVectorIndexes,
                                            CosmosContainerProperties containerProperties) {
        this.cosmosClient = cosmosClient;
        this.databaseName = databaseName;
        this.containerName = containerName;
        this.cosmosVectorEmbeddingPolicy = cosmosVectorEmbeddingPolicy;
        this.cosmosVectorIndexes = cosmosVectorIndexes;
        this.containerProperties = containerProperties;

        if (cosmosClient == null) {
            throw new IllegalArgumentException("cosmosClient cannot be null or empty for Azure CosmosDB NoSql Embedding Store.");
        }

        if (isNullOrBlank(databaseName) || isNullOrBlank(containerName)) {
            throw new IllegalArgumentException("databaseName and containerName needs to be provided.");
        }

        if (cosmosVectorEmbeddingPolicy == null || cosmosVectorEmbeddingPolicy.getVectorEmbeddings() == null ||
                cosmosVectorEmbeddingPolicy.getVectorEmbeddings().isEmpty()) {
            throw new IllegalArgumentException("cosmosVectorEmbeddingPolicy cannot be null or empty for Azure CosmosDB NoSql Embedding Store.");
        }

        if (cosmosVectorIndexes == null || cosmosVectorIndexes.isEmpty()) {
            throw new IllegalArgumentException("cosmosVectorIndexes cannot be null or empty for Azure CosmosDB NoSql Embedding Store.");
        }


        cosmosClient.createDatabaseIfNotExists(this.databaseName);
        this.database = this.cosmosClient.getDatabase(this.databaseName);

        containerProperties.setVectorEmbeddingPolicy(this.cosmosVectorEmbeddingPolicy);
        containerProperties.getIndexingPolicy().setVectorIndexes(this.cosmosVectorIndexes);

        this.database.createContainerIfNotExists(this.containerProperties);
        this.container = this.database.getContainer(this.containerName);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String add(Embedding embedding) {
        String id = randomUUID();
        add(id, embedding);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        addInternal(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        String id = randomUUID();
        addInternal(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        List<String> ids = embeddings.stream()
                .map(ignored -> randomUUID())
                .collect(Collectors.toList());
        addAllInternal(ids, embeddings, null);
        return ids;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> embedded) {
        List<String> ids = embeddings.stream()
                .map(ignored -> randomUUID())
                .collect(Collectors.toList());
        addAllInternal(ids, embeddings, embedded);
        return ids;
    }

    @Override
    public List<EmbeddingMatch<TextSegment>> findRelevant(Embedding referenceEmbedding, int maxResults, double minScore) {

        String embeddingKey = this.cosmosVectorEmbeddingPolicy.getVectorEmbeddings().get(0).getPath().substring(1);
        String referenceEmbeddingString = Arrays.stream(referenceEmbedding.vectorAsList().toArray())
                .map(Object::toString)
                .collect(Collectors.joining(","));

        String query = String.format("SELECT TOP %d *, VectorDistance(c.%s,[%s]) AS SimilarityScore FROM c ORDER By " +
                "VectorDistance(c.%s,[%s])", maxResults, embeddingKey, referenceEmbeddingString, embeddingKey, referenceEmbeddingString);

        CosmosPagedIterable<AzureCosmosDBNoSqlMatchedDocument> results = this.container.queryItems(query,
                new CosmosQueryRequestOptions(), AzureCosmosDBNoSqlMatchedDocument.class);

        return results.stream()
                .map( item -> toEmbeddingMatch(null))
                .collect(Collectors.toList());
    }

    private void addInternal(String id, Embedding embedding, TextSegment embedded) {
        addAllInternal(singletonList(id), singletonList(embedding), embedded == null ? null : singletonList(embedded));
    }

    private void addAllInternal(List<String> ids, List<Embedding> embeddings, List<TextSegment> embedded) {
        if (isNullOrEmpty(ids) || isNullOrEmpty(embeddings)) {
            log.info("do not add empty embeddings to Azure CosmosDB  Mongo vCore");
            return;
        }

        ensureTrue(ids.size() == embeddings.size(), "ids size is not equal to embeddings size");
        ensureTrue(embedded == null || embeddings.size() == embedded.size(), "embeddings size is not equal to embedded size");

        PartitionKeyDefinition partitionKey = this.containerProperties.getPartitionKeyDefinition();
        List<CosmosItemOperation> operations = new ArrayList<>(ids.size());
        for (int i=0;i<ids.size();i++) {
            operations.add(CosmosBulkOperations.getCreateItemOperation(
                    toNoSqlDbDocument(ids.get(i), embeddings.get(i), embedded == null ? null : embedded.get(i)),
                            new PartitionKey(ids.get(i))));
        }

        this.container.executeBulkOperations(operations);
    }

    public static class Builder {
        private  CosmosClient cosmosClient;
        private  String databaseName;
        private  String containerName;
        private  CosmosVectorEmbeddingPolicy cosmosVectorEmbeddingPolicy;
        private  List<CosmosVectorIndexSpec> cosmosVectorIndexes;
        private  CosmosContainerProperties containerProperties;

        public Builder cosmosClient(CosmosClient cosmosClient) {
            this.cosmosClient = cosmosClient;
            return this;
        }

        public Builder databaseName(String  databaseName) {
            this.databaseName = databaseName;
            return this;
        }

        public Builder containerName(String containerName) {
            this.containerName = containerName;
            return this;
        }

        public Builder cosmosVectorEmbeddingPolicy(CosmosVectorEmbeddingPolicy cosmosVectorEmbeddingPolicy) {
            this.cosmosVectorEmbeddingPolicy = cosmosVectorEmbeddingPolicy;
            return this;
        }

        public Builder cosmosVectorIndexes(List<CosmosVectorIndexSpec> cosmosVectorIndexes) {
            this.cosmosVectorIndexes = cosmosVectorIndexes;
            return this;
        }

        public Builder containerProperties(CosmosContainerProperties containerProperties) {
            this.containerProperties = containerProperties;
            return this;
        }

        public AzureCosmosDBNoSqlEmbeddingStore build() {
            return new AzureCosmosDBNoSqlEmbeddingStore(cosmosClient, databaseName, containerName,
                    cosmosVectorEmbeddingPolicy, cosmosVectorIndexes, containerProperties);
        }
    }
}
