package org.sagebionetworks.template.repo.bedrock;

import java.io.IOException;
import java.util.Optional;

import org.apache.logging.log4j.Logger;
import org.opensearch.client.opensearch.indices.OpenSearchIndicesClient;
import org.sagebionetworks.template.Constants;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.OpenSearchClientFactory;
import org.sagebionetworks.template.WaitConditionHandler;
import org.sagebionetworks.template.config.RepoConfiguration;

import com.amazonaws.services.cloudformation.model.StackEvent;
import com.google.inject.Inject;

import software.amazon.awssdk.services.opensearchserverless.OpenSearchServerlessClient;
import software.amazon.awssdk.services.opensearchserverless.model.CollectionDetail;
import software.amazon.awssdk.services.opensearchserverless.model.CollectionStatus;

/**
 * A bedrock knowledge base that uses an open search collection requires the index to exists before its creation, since
 * the index creation is part of the opensearch API operations and there is no cloudformation resource for it we need to
 * invoke the opensearch API as part of a wait condition in the stack. Note that a wait condition is only processed during
 * the stack creation, so the index cannot be updated. 
 */
public class SynapseHelpCollectionIndexCreation implements WaitConditionHandler {
	
	private Logger logger;
	
	private RepoConfiguration config;
	
	private OpenSearchServerlessClient ossManagementClient;
	
	private OpenSearchClientFactory openSearchClientFactory;
	
	
	@Inject
	public SynapseHelpCollectionIndexCreation(LoggerFactory loggerFactory, RepoConfiguration config, OpenSearchServerlessClient ossClient, OpenSearchClientFactory openSearchClientFactory) {
		this.logger = loggerFactory.getLogger(SynapseHelpCollectionIndexCreation.class);
		this.config = config;
		this.ossManagementClient = ossClient;
		this.openSearchClientFactory = openSearchClientFactory;
	}
	
	@Override
	public String getWaitConditionId() {
		return "SynapseHelpCollectionCreateIndexWaitCondition";
	}
	
	@Override
	public Optional<String> handle(StackEvent stackEvent) {
		String stack = config.getProperty(Constants.PROPERTY_KEY_STACK);
		String collectionName = stack + "-synhelp";
		
		CollectionDetail collection = ossManagementClient.batchGetCollection(req -> req
			.names(collectionName)
		).collectionDetails().stream().findFirst().orElseThrow();
		
		if (!CollectionStatus.ACTIVE.equals(collection.status())) {
			logger.warn("Collection {} not ready, status: {}", collectionName, collection.status());
			return Optional.empty();
		}
		
		String instance = config.getProperty(Constants.PROPERTY_KEY_INSTANCE);
		
		String indexName = stack + "-" + instance + "-vector-idx";
		
		OpenSearchIndicesClient client = openSearchClientFactory.getIndicesClient(collection.collectionEndpoint());
		
		try {	
			if (client.exists(req -> req.index(indexName)).value()) {
				logger.warn("Index {} already exists.", indexName);
				return Optional.of("index-already-exists");
			}
			
			logger.info("Index {} does not exist, creating...", indexName);
			
			client.create(req -> req
				.index(indexName)
				.settings(settings -> settings.knn(true).knnAlgoParamEfSearch(512))
				.mappings(mappings -> mappings
					.properties("text_vector", p -> p
						.knnVector(vector -> vector
							.dimension(1024)
							.method(method -> method
								.name("hnsw")
								.engine("faiss")
								.spaceType("l2")
							)
						)
					)
					.properties("text_raw", p -> p.text(text -> text.index(true)))
					.properties("text_metadata", p -> p.text(text -> text.index(false)))
				)
			);
			
			logger.info("Index {} creation completed.", indexName);
			
			return Optional.of("index-creation-complete");
			
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
		
	}
}
