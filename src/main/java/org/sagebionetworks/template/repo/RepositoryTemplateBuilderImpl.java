package org.sagebionetworks.template.repo;


import com.amazonaws.services.cloudformation.model.Output;
import com.amazonaws.services.cloudformation.model.Parameter;
import com.amazonaws.services.cloudformation.model.Stack;
import com.amazonaws.services.cloudformation.model.Tag;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.model.ListPlatformVersionsRequest;
import com.amazonaws.services.elasticbeanstalk.model.ListPlatformVersionsResult;
import com.amazonaws.services.elasticbeanstalk.model.PlatformSummary;
import com.google.inject.Inject;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.sagebionetworks.template.CloudFormationClient;
import org.sagebionetworks.template.ConfigurationPropertyNotFound;
import org.sagebionetworks.template.Constants;
import org.sagebionetworks.template.CreateOrUpdateStackRequest;
import org.sagebionetworks.template.Ec2Client;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.StackTagsProvider;
import org.sagebionetworks.template.config.RepoConfiguration;
import org.sagebionetworks.template.config.TimeToLive;
import org.sagebionetworks.template.repo.beanstalk.ArtifactCopy;
import org.sagebionetworks.template.repo.beanstalk.BeanstalkUtils;
import org.sagebionetworks.template.repo.beanstalk.ElasticBeanstalkSolutionStackNameProvider;
import org.sagebionetworks.template.repo.beanstalk.EnvironmentDescriptor;
import org.sagebionetworks.template.repo.beanstalk.EnvironmentType;
import org.sagebionetworks.template.repo.beanstalk.SecretBuilder;
import org.sagebionetworks.template.repo.beanstalk.SourceBundle;
import org.sagebionetworks.template.repo.cloudwatchlogs.CloudwatchLogsVelocityContextProvider;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static org.sagebionetworks.template.Constants.ADMIN_RULE_ACTION;
import static org.sagebionetworks.template.Constants.BEANSTALK_INSTANCES_SUBNETS;
import static org.sagebionetworks.template.Constants.CAPABILITY_NAMED_IAM;
import static org.sagebionetworks.template.Constants.CLOUDWATCH_LOGS_DESCRIPTORS;
import static org.sagebionetworks.template.Constants.CTXT_ENABLE_ENHANCED_RDS_MONITORING;
import static org.sagebionetworks.template.Constants.CTXT_KEY_DATA_CDN_DOMAIN_NAME;
import static org.sagebionetworks.template.Constants.CTXT_KEY_DATA_CDN_KEYPAIR_ID;
import static org.sagebionetworks.template.Constants.DATABASE_DESCRIPTORS;
import static org.sagebionetworks.template.Constants.DATA_CDN_DOMAIN_NAME_FMT;
import static org.sagebionetworks.template.Constants.DB_ENDPOINT_SUFFIX;
import static org.sagebionetworks.template.Constants.DELETION_POLICY;
import static org.sagebionetworks.template.Constants.EC2_INSTANCE_TYPE;
import static org.sagebionetworks.template.Constants.ENVIRONMENT;
import static org.sagebionetworks.template.Constants.EXCEPTION_THROWER;
import static org.sagebionetworks.template.Constants.GLOBAL_RESOURCES_EXPORT_PREFIX;
import static org.sagebionetworks.template.Constants.INSTANCE;
import static org.sagebionetworks.template.Constants.JSON_INDENT;
import static org.sagebionetworks.template.Constants.MACHINE_TYPES;
import static org.sagebionetworks.template.Constants.NOSNAPSHOT;
import static org.sagebionetworks.template.Constants.OAUTH_ENDPOINT;
import static org.sagebionetworks.template.Constants.OUTPUT_NAME_SUFFIX_REPOSITORY_DB_ENDPOINT;
import static org.sagebionetworks.template.Constants.PARAMETER_MYSQL_PASSWORD;
import static org.sagebionetworks.template.Constants.POOL_TYPES;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_HEALTH_CHECK_URL;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_MAX_INSTANCES;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_MIN_INSTANCES;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_NUMBER;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_SSL_ARN;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_BEANSTALK_VERSION;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_DATA_CDN_KEYPAIR_ID;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_EC2_INSTANCE_TYPE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_AMAZONLINUX;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_JAVA;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_TOMCAT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_ENABLE_RDS_ENHANCED_MONITORING;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_INSTANCE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_OAUTH_ENDPOINT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_RDS_REPO_SNAPSHOT_IDENTIFIER;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_RDS_TABLES_SNAPSHOT_IDENTIFIERS;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_ALLOCATED_STORAGE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_INSTANCE_CLASS;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_IOPS;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_MAX_ALLOCATED_STORAGE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_MULTI_AZ;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_REPO_RDS_STORAGE_TYPE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_ROUTE_53_HOSTED_ZONE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_STACK;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_INSTANCE_COUNT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_RDS_ALLOCATED_STORAGE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_RDS_INSTANCE_CLASS;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_RDS_IOPS;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_RDS_MAX_ALLOCATED_STORAGE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_TABLES_RDS_STORAGE_TYPE;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_VPC_SUBNET_COLOR;
import static org.sagebionetworks.template.Constants.REPO_BEANSTALK_NUMBER;
import static org.sagebionetworks.template.Constants.SHARED_EXPORT_PREFIX;
import static org.sagebionetworks.template.Constants.SHARED_RESOUCES_STACK_NAME;
import static org.sagebionetworks.template.Constants.SOLUTION_STACK_NAME;
import static org.sagebionetworks.template.Constants.STACK;
import static org.sagebionetworks.template.Constants.STACK_CMK_ALIAS;
import static org.sagebionetworks.template.Constants.TEMPALTE_BEAN_STALK_ENVIRONMENT;
import static org.sagebionetworks.template.Constants.TEMPALTE_SHARED_RESOUCES_MAIN_JSON_VTP;
import static org.sagebionetworks.template.Constants.VPC_EXPORT_PREFIX;
import static org.sagebionetworks.template.Constants.VPC_SUBNET_COLOR;

public class RepositoryTemplateBuilderImpl implements RepositoryTemplateBuilder {
	public static final List<String> MACHINE_TYPE_LIST = List.of("Workers", "Repository");
	public static final List<String> POOL_TYPE_LIST = List.of("Idgen", "Main", "Migration", "Tables");

	private final CloudFormationClient cloudFormationClient;
	private final Ec2Client ec2Client;
	private final VelocityEngine velocityEngine;
	private final RepoConfiguration config;
	private final Logger logger;
	private final ArtifactCopy artifactCopy;
	private final SecretBuilder secretBuilder;
	private final Set<VelocityContextProvider> contextProviders;
	private final ElasticBeanstalkSolutionStackNameProvider elasticBeanstalkSolutionStackNameProvider;
	private final StackTagsProvider stackTagsProvider;
	private final CloudwatchLogsVelocityContextProvider cwlContextProvider;
	private final AWSElasticBeanstalk beanstalkClient;
	private final TimeToLive timeToLive;

	@Inject
	public RepositoryTemplateBuilderImpl(CloudFormationClient cloudFormationClient, VelocityEngine velocityEngine,
										 RepoConfiguration configuration, LoggerFactory loggerFactory, ArtifactCopy artifactCopy,
										 SecretBuilder secretBuilder, Set<VelocityContextProvider> contextProviders,
										 ElasticBeanstalkSolutionStackNameProvider elasticBeanstalkDefaultAMIEncrypter,
										 StackTagsProvider stackTagsProvider, CloudwatchLogsVelocityContextProvider cloudwatchLogsVelocityContextProvider,
										 Ec2Client ec2Client, AWSElasticBeanstalk beanstalkClient, TimeToLive ttl) {
		super();
		this.cloudFormationClient = cloudFormationClient;
		this.ec2Client = ec2Client;
		this.velocityEngine = velocityEngine;
		this.config = configuration;
		this.logger = loggerFactory.getLogger(RepositoryTemplateBuilderImpl.class);
		this.artifactCopy = artifactCopy;
		this.secretBuilder = secretBuilder;
		this.contextProviders = contextProviders;
		this.elasticBeanstalkSolutionStackNameProvider = elasticBeanstalkDefaultAMIEncrypter;
		this.stackTagsProvider = stackTagsProvider;
		this.cwlContextProvider = cloudwatchLogsVelocityContextProvider;
		this.beanstalkClient = beanstalkClient;
		this.timeToLive = ttl;
	}

	public String getActualBeanstalkAmazonLinuxPlatform() {
		final String LATEST = "latest";	// default is to request latest version
		// Check AWS Beanstalk current platform vs what we have in config
		String javaVersion = config.getProperty(PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_JAVA);
		String tomcatVersion = config.getProperty(PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_TOMCAT);
		String requestedPlatformVersion = config.getProperty(PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_AMAZONLINUX);
		ListPlatformVersionsRequest lpvReq = BeanstalkUtils.buildListPlatformVersionsRequest(javaVersion, tomcatVersion, null);
		ListPlatformVersionsResult lpvRes = this.beanstalkClient.listPlatformVersions(lpvReq);
		List<PlatformSummary> summaries = lpvRes.getPlatformSummaryList();
		String latestPlatformVersion = BeanstalkUtils.getLatestPlatformVersion(summaries);
		String actualVersion = requestedPlatformVersion;
		if (LATEST.equals(requestedPlatformVersion)) {
			actualVersion = latestPlatformVersion;
		} else {
			if (! latestPlatformVersion.equals(actualVersion)) { // The version specified is not the latest, log
				logger.info(String.format("The latest platform version is %s. Please update the default configuration.", latestPlatformVersion));
			}
		}
		return actualVersion;
	}

	@Override
	public void buildAndDeploy() throws InterruptedException {

		// Create the context from the input
		VelocityContext context = createSharedContext();

		Parameter[] sharedParameters = createSharedParameters();
		// Create the shared-resource stack
		String sharedResourceStackName = createSharedResourcesStackName();

		buildAndDeployStack(context, sharedResourceStackName, TEMPALTE_SHARED_RESOUCES_MAIN_JSON_VTP, sharedParameters);
		// Wait for the shared resources to complete
		Stack sharedStackResults = cloudFormationClient.waitForStackToComplete(sharedResourceStackName).orElseThrow(()->new IllegalStateException("Stack does not exist: "+sharedResourceStackName));
		
		// Build each bean stalk environment.
		List<String> environmentNames = buildEnvironments(sharedStackResults);
	}

	/**
	 * Build all of the environments
	 * @param sharedStackResults
	 */
	public List<String> buildEnvironments(Stack sharedStackResults) {
		// Create the repo/worker secrets
		SourceBundle secretsSouce = secretBuilder.createSecrets();
		
		Parameter ttl = timeToLive.createTimeToLiveParameter().orElse(null);

		List<String> environmentNames = new LinkedList<>();
		// each environment is treated as its own stack.
		for (EnvironmentDescriptor environment : createEnvironments(secretsSouce)) {
			VelocityContext context = createEnvironmentContext(sharedStackResults, environment);
			environmentNames.add(environment.getName());
			// build this type.
			buildAndDeployStack(context, environment.getName(), TEMPALTE_BEAN_STALK_ENVIRONMENT, ttl);
		}
		return environmentNames;
	}
	

	/**
	 * Create the context used for each environment
	 * @param sharedStackResults
	 * @param environment 
	 * 
	 * @return
	 */
	VelocityContext createEnvironmentContext(Stack sharedStackResults, EnvironmentDescriptor environment) {
		VelocityContext context = new VelocityContext();
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		context.put(STACK, stack);
		context.put(INSTANCE, config.getProperty(PROPERTY_KEY_INSTANCE));
		context.put(VPC_SUBNET_COLOR, config.getProperty(PROPERTY_KEY_VPC_SUBNET_COLOR));
		context.put(GLOBAL_RESOURCES_EXPORT_PREFIX, Constants.createGlobalResourcesExportPrefix(stack));
		context.put(VPC_EXPORT_PREFIX, Constants.createVpcExportPrefix(stack));
		context.put(SHARED_EXPORT_PREFIX, createSharedExportPrefix());
		context.put(REPO_BEANSTALK_NUMBER, config.getIntegerProperty(PROPERTY_KEY_BEANSTALK_NUMBER + EnvironmentType.REPOSITORY_SERVICES.getShortName()));
		// Extract the database suffix
		context.put(DB_ENDPOINT_SUFFIX, extractDatabaseSuffix(sharedStackResults));
		context.put(ENVIRONMENT, environment);
		context.put(STACK_CMK_ALIAS, secretBuilder.getCMKAlias());

		//use encrypted copies of the default elasticbeanstalk AMI
		String javaVersion = config.getProperty(PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_JAVA);
		String tomcatVersion = config.getProperty(PROPERTY_KEY_ELASTICBEANSTALK_IMAGE_VERSION_TOMCAT);
		String linuxVersion = getActualBeanstalkAmazonLinuxPlatform();
		String solutionStackName = elasticBeanstalkSolutionStackNameProvider.getSolutionStackName(tomcatVersion, javaVersion, linuxVersion);
		context.put(SOLUTION_STACK_NAME, solutionStackName);

		// oauth
		context.put(OAUTH_ENDPOINT, config.getProperty(PROPERTY_KEY_OAUTH_ENDPOINT));

		// CloudwatchLogs
		context.put(CLOUDWATCH_LOGS_DESCRIPTORS, cwlContextProvider.getLogDescriptors(EnvironmentType.valueOfPrefix(environment.getType())));

		// EC2 instance type
		String ec2InstanceType = config.getProperty(PROPERTY_KEY_EC2_INSTANCE_TYPE);
		context.put(EC2_INSTANCE_TYPE, ec2InstanceType);
		
		// Determine Beanstalk subnets for instances
		List<String> vpcSubnets = getPrivateSubnets(config.getProperty(PROPERTY_KEY_VPC_SUBNET_COLOR));
		List<String> beanstalkSubnets = ec2Client.getAvailableSubnetsForInstanceType(ec2InstanceType, vpcSubnets);
		String beanstalkSubnetsAsString = String.join(",", beanstalkSubnets);
		context.put(BEANSTALK_INSTANCES_SUBNETS, beanstalkSubnetsAsString);

		// Data CDN props (
		String cdnKeyPairId = config.getProperty(PROPERTY_KEY_DATA_CDN_KEYPAIR_ID);
		context.put(CTXT_KEY_DATA_CDN_KEYPAIR_ID, cdnKeyPairId);
		context.put(CTXT_KEY_DATA_CDN_DOMAIN_NAME, String.format(DATA_CDN_DOMAIN_NAME_FMT, stack));

		return context;
	}

	/**
	 * Build and deploy a stack using the provided context and template.
	 * 
	 * @param context
	 * @param stackName
	 * @param templatePath
	 */
	void buildAndDeployStack(VelocityContext context, String stackName, String templatePath, Parameter... parameters) {
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		boolean enableTerminationProtection = ("prod".equals(stack)); // enable on prod stack
		List<Tag> stackTags = stackTagsProvider.getStackTags();

		// Merge the context with the template
		Template template = this.velocityEngine.getTemplate(templatePath);
		StringWriter stringWriter = new StringWriter();
		template.merge(context, stringWriter);
		// Parse the resulting template
		String resultJSON = stringWriter.toString();
		JSONObject templateJson = new JSONObject(resultJSON);
		// Format the JSON
		resultJSON = templateJson.toString(JSON_INDENT);
		this.logger.info("Template for stack: " + stackName);
		this.logger.info(resultJSON);
		// create or update the template
		this.cloudFormationClient.createOrUpdateStack(new CreateOrUpdateStackRequest()
				.withStackName(stackName)
				.withTemplateBody(resultJSON)
				.withParameters(parameters)
				.withCapabilities(CAPABILITY_NAMED_IAM)
				.withTags(stackTags)
				.withEnableTerminationProtection(enableTerminationProtection));
	}

	/**
	 * Create the template context.
	 * 
	 * @return
	 */
	VelocityContext createSharedContext() {
		VelocityContext context = new VelocityContext();
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		context.put(STACK, stack);
		context.put(INSTANCE, config.getProperty(PROPERTY_KEY_INSTANCE));
		context.put(MACHINE_TYPES, MACHINE_TYPE_LIST);
		context.put(POOL_TYPES, POOL_TYPE_LIST);
		context.put(VPC_SUBNET_COLOR, config.getProperty(PROPERTY_KEY_VPC_SUBNET_COLOR));
		context.put(SHARED_RESOUCES_STACK_NAME, createSharedResourcesStackName());
		context.put(GLOBAL_RESOURCES_EXPORT_PREFIX, Constants.createGlobalResourcesExportPrefix(stack));
		context.put(VPC_EXPORT_PREFIX, Constants.createVpcExportPrefix(stack));
		context.put(SHARED_EXPORT_PREFIX, createSharedExportPrefix());
		context.put(EXCEPTION_THROWER, new VelocityExceptionThrower());
		context.put(CTXT_ENABLE_ENHANCED_RDS_MONITORING, config.getProperty(PROPERTY_KEY_ENABLE_RDS_ENHANCED_MONITORING));
		
		context.put(ADMIN_RULE_ACTION, Constants.isProd(stack) ? "Block:{}" : "Count:{}");
		context.put(DELETION_POLICY,
				Constants.isProd(stack) ? DeletionPolicy.Retain.name() : DeletionPolicy.Delete.name());
		
		// Create the descriptors for all of the database.
		context.put(DATABASE_DESCRIPTORS, createDatabaseDescriptors());

		for(VelocityContextProvider provider : contextProviders){
			provider.addToContext(context);
		}
		
		RegularExpressions.bindRegexToContext(context);

		return context;
	}

	/**
	 * Create a descriptor for each database to be created.
	 * 
	 * @return
	 */
	public DatabaseDescriptor[] createDatabaseDescriptors() {
		int numberOfTablesDatabase = config.getIntegerProperty(PROPERTY_KEY_TABLES_INSTANCE_COUNT);
		// one repository database and multiple tables database.
		DatabaseDescriptor[] results = new DatabaseDescriptor[numberOfTablesDatabase + 1];

		String stack = config.getProperty(PROPERTY_KEY_STACK);
		String instance = config.getProperty(PROPERTY_KEY_INSTANCE);

		// Describe the repository database.
		DatabaseDescriptor repoDbDescriptor = new DatabaseDescriptor().withResourceName(stack + instance + "RepositoryDB")
				.withAllocatedStorage(config.getIntegerProperty(PROPERTY_KEY_REPO_RDS_ALLOCATED_STORAGE))
				.withMaxAllocatedStorage(config.getIntegerProperty(PROPERTY_KEY_REPO_RDS_MAX_ALLOCATED_STORAGE))
				.withInstanceIdentifier(stack + "-" + instance + "-db").withDbName(stack + instance)
				.withInstanceClass(config.getProperty(PROPERTY_KEY_REPO_RDS_INSTANCE_CLASS))
				.withDbStorageType(config.getProperty(PROPERTY_KEY_REPO_RDS_STORAGE_TYPE))
				.withDbIops(config.getIntegerProperty(PROPERTY_KEY_REPO_RDS_IOPS))
				.withMultiAZ(config.getBooleanProperty(PROPERTY_KEY_REPO_RDS_MULTI_AZ))
				// 0 indicates no automated backups will be created.
				.withBackupRetentionPeriodDays(Constants.isProd(stack) ? 7 : 0)
				.withDeletionPolicy(Constants.isProd(stack)? DeletionPolicy.Snapshot: DeletionPolicy.Delete);
		

		String repoSnapshotIdentifier = config.getProperty(PROPERTY_KEY_RDS_REPO_SNAPSHOT_IDENTIFIER);
		boolean useSnapshotForRepoDB = ! NOSNAPSHOT.equals(repoSnapshotIdentifier);
		if (useSnapshotForRepoDB) {
			repoDbDescriptor = repoDbDescriptor.withSnapshotIdentifier(repoSnapshotIdentifier);
		}
		results[0] = repoDbDescriptor;

		String[] repoTableSnapshotIdentifiers = config.getComaSeparatedProperty(PROPERTY_KEY_RDS_TABLES_SNAPSHOT_IDENTIFIERS);
		boolean useSnapshotsForTablesDbs = !(repoTableSnapshotIdentifiers.length == 1 && NOSNAPSHOT.equals(repoTableSnapshotIdentifiers[0]));
		if (useSnapshotForRepoDB != useSnapshotsForTablesDbs) {
			throw new IllegalStateException("The repo database is set to use a snapshot but the tables database are not set to use snapshots, or vice-versa");
		}

		// Describe each table database
		for (int i = 0; i < numberOfTablesDatabase; i++) {
			DatabaseDescriptor tableDbDescriptor = new DatabaseDescriptor()
				.withResourceName(stack + instance + "Table" + i + "RepositoryDB")
				.withAllocatedStorage(config.getIntegerProperty(PROPERTY_KEY_TABLES_RDS_ALLOCATED_STORAGE))
				.withMaxAllocatedStorage(config.getIntegerProperty(PROPERTY_KEY_TABLES_RDS_MAX_ALLOCATED_STORAGE))
				.withInstanceIdentifier(stack + "-" + instance + "-table-" + i).withDbName(stack + instance)
				.withDbStorageType(config.getProperty(PROPERTY_KEY_TABLES_RDS_STORAGE_TYPE))
				.withDbIops(config.getIntegerProperty(PROPERTY_KEY_TABLES_RDS_IOPS))
				.withInstanceClass(config.getProperty(PROPERTY_KEY_TABLES_RDS_INSTANCE_CLASS)).withMultiAZ(false)
				// 0 indicates no automated backups will be created.
				.withBackupRetentionPeriodDays(Constants.isProd(stack)? 1 : 0)
				.withDeletionPolicy(Constants.isProd(stack)? DeletionPolicy.Snapshot: DeletionPolicy.Delete);
			if (useSnapshotForRepoDB) {
				String snapshotIdentifier = repoTableSnapshotIdentifiers[i];
				tableDbDescriptor = tableDbDescriptor.withSnapshotIdentifier(snapshotIdentifier);
			}
			results[i+1] = tableDbDescriptor;
		}
		return results;
	}

	/**
	 * Create an Environment descriptor for reop, workers, and portal if they are defined.
	 * @param secrets 
	 * 
	 * @return
	 */
	public List<EnvironmentDescriptor> createEnvironments(SourceBundle secrets) {
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		String instance = config.getProperty(PROPERTY_KEY_INSTANCE);
		List<EnvironmentDescriptor> environmentDescriptors = new LinkedList<>();
		// create each type.
		for (EnvironmentType type : EnvironmentType.values()) {
			try {
				int number = config.getIntegerProperty(PROPERTY_KEY_BEANSTALK_NUMBER + type.getShortName());
				String name = new StringJoiner("-").add(type.getShortName()).add(stack).add(instance).add("" + number)
						.toString();
				String refName = Constants.createCamelCaseName(name, "-");
				String version = config.getProperty(PROPERTY_KEY_BEANSTALK_VERSION + type.getShortName());
				String healthCheckUrl = config.getProperty(PROPERTY_KEY_BEANSTALK_HEALTH_CHECK_URL + type.getShortName());
				int minInstances = config.getIntegerProperty(PROPERTY_KEY_BEANSTALK_MIN_INSTANCES + type.getShortName());
				int maxInstances = config.getIntegerProperty(PROPERTY_KEY_BEANSTALK_MAX_INSTANCES + type.getShortName());
				String sslCertificateARN = config.getProperty(PROPERTY_KEY_BEANSTALK_SSL_ARN + type.getShortName());
				String hostedZone = config.getProperty(PROPERTY_KEY_ROUTE_53_HOSTED_ZONE + type.getShortName());
				String cnamePrefix = name + "-" + hostedZone.replaceAll("\\.", "-");

				// Environment secrets
				SourceBundle environmentSecrets = type.shouldIncludeSecrets() ? secrets : null;

				// Copy the version from artifactory to S3.
				SourceBundle bundle = artifactCopy.copyArtifactIfNeeded(type, version, number);
				environmentDescriptors.add(new EnvironmentDescriptor().withName(name).withRefName(refName).withNumber(number)
						.withHealthCheckUrl(healthCheckUrl).withSourceBundle(bundle).withType(type)
						.withMinInstances(minInstances).withMaxInstances(maxInstances)
						.withVersionLabel(version)
						.withSslCertificateARN(sslCertificateARN)
						.withHostedZone(hostedZone)
						.withCnamePrefix(cnamePrefix)
						.withSecretsSource(environmentSecrets));
			} catch (ConfigurationPropertyNotFound e){
				//The necessary properties to build up the Environment was not fully defined so we choose not to create a stack for it.
				logger.warn("The Environment " + type + " was not created because " + e.getMissingKey() + " was not found");
			}
		}
		return environmentDescriptors;
	}

	/**
	 * Create the parameters to be passed to the template at runtime.
	 * 
	 * @return
	 */
	Parameter[] createSharedParameters() {
		List<Parameter> params = new ArrayList<>(2);
		String passwordValue = secretBuilder.getRepositoryDatabasePassword();
		Parameter databasePassword = new Parameter().withParameterKey(PARAMETER_MYSQL_PASSWORD)
				.withParameterValue(passwordValue);
		params.add(databasePassword);
		timeToLive.createTimeToLiveParameter().ifPresent(ttl -> {
			params.add(ttl);
		});
		return params.toArray(new Parameter[params.size()]);
	}

	/**
	 * Create the name of the stack from the input.
	 * 
	 * @return
	 */
	String createSharedResourcesStackName() {
		StringJoiner joiner = new StringJoiner("-");
		joiner.add(config.getProperty(PROPERTY_KEY_STACK));
		joiner.add(config.getProperty(PROPERTY_KEY_INSTANCE));
		joiner.add("shared-resources");
		return joiner.toString();
	}

	/**
	 * Create the prefix used for all of the VPC stack exports;
	 * 
	 * @return
	 */
	String createSharedExportPrefix() {
		StringJoiner joiner = new StringJoiner("-");
		joiner.add("us-east-1");
		joiner.add(createSharedResourcesStackName());
		return joiner.toString();
	}
	
	/**
	 * Extract the database end point suffix from the shared resources output.
	 * @param sharedResouces
	 * @return
	 */
	String extractDatabaseSuffix(Stack sharedResouces) {
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		String instance = config.getProperty(PROPERTY_KEY_INSTANCE);
		String outputName = stack+instance+OUTPUT_NAME_SUFFIX_REPOSITORY_DB_ENDPOINT;
		// find the database end point suffix
		for(Output output: sharedResouces.getOutputs()) {
			if(outputName.equals(output.getOutputKey())){
				String[] split = output.getOutputValue().split(stack+"-"+instance+"-db.");
				return split[1];
			}
		}
		throw new RuntimeException("Failed to find shared resources output: "+outputName);
	}

	/***
	 * Return the private subnet ids for a color
	 * @param color
	 * @return
	 */
	List<String> getPrivateSubnets(String color) {
		String stack = config.getProperty(PROPERTY_KEY_STACK);
		String privateSubnets = cloudFormationClient.getOutput(
				Constants.createVpcPrivateSubnetsStackName(stack, color),
				Constants.VPC_PRIVATE_SUBNETS_STACK_PRIVATE_SUBNETS_OUPUT_KEY);
		String[] privateSubnetIds = privateSubnets.split(",");
		List<String> trimmedIds = Arrays.stream(privateSubnetIds).map(v -> v.trim()).collect(Collectors.toList());
		return trimmedIds;
	}

}
