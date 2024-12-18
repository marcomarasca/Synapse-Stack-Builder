package org.sagebionetworks.template.global;

import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.google.inject.Inject;
import org.apache.logging.log4j.Logger;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.json.JSONObject;
import org.sagebionetworks.template.CloudFormationClient;
import org.sagebionetworks.template.Constants;
import org.sagebionetworks.template.CreateOrUpdateStackRequest;
import org.sagebionetworks.template.LoggerFactory;
import org.sagebionetworks.template.SesClient;
import org.sagebionetworks.template.StackTagsProvider;
import org.sagebionetworks.template.config.Configuration;
import org.sagebionetworks.template.repo.DeletionPolicy;

import java.io.StringWriter;

import static org.sagebionetworks.template.Constants.DELETION_POLICY;
import static org.sagebionetworks.template.Constants.GLOBAL_RESOURCES_STACK_NAME_FORMAT;
import static org.sagebionetworks.template.Constants.IDENTITY_ARN;
import static org.sagebionetworks.template.Constants.JSON_INDENT;
import static org.sagebionetworks.template.Constants.PROPERTY_KEY_STACK;
import static org.sagebionetworks.template.Constants.SES_SYNAPSE_DOMAIN;
import static org.sagebionetworks.template.Constants.STACK;
import static org.sagebionetworks.template.Constants.TEMPLATE_GLOBAL_RESOURCES;
import static org.sagebionetworks.template.Constants.VPC_EXPORT_PREFIX;
import static org.sagebionetworks.template.Constants.CAPABILITY_NAMED_IAM;
import static org.sagebionetworks.template.Constants.GLOBAL_CFSTACK_OUTPUT_KEY_SES_BOUNCE_TOPIC;
import static org.sagebionetworks.template.Constants.GLOBAL_CFSTACK_OUTPUT_KEY_SES_COMPLAINT_TOPIC;

public class GlobalResourcesBuilderImpl implements GlobalResourcesBuilder {

    private CloudFormationClient cloudFormationClient;
    private VelocityEngine velocityEngine;
    private Configuration config;
    private Logger logger;
    private StackTagsProvider stackTagsProvider;
    private SesClient sesClient;
    private AWSSecurityTokenService stsClient;

    @Inject
    public GlobalResourcesBuilderImpl(CloudFormationClient cloudFormationClient,
                                      VelocityEngine velocityEngine,
                                      Configuration config,
                                      LoggerFactory loggerFactory,
                                      StackTagsProvider stackTagsProvider,
                                      SesClient sesClient,
                                      AWSSecurityTokenService stsClient) {
        this.cloudFormationClient = cloudFormationClient;
        this.velocityEngine = velocityEngine;
        this.config = config;
        this.logger = loggerFactory.getLogger(GlobalResourcesBuilderImpl.class);
        this.stackTagsProvider = stackTagsProvider;
        this.sesClient = sesClient;
        this.stsClient = stsClient;
    }

    @Override
    public void buildGlobalResources() throws InterruptedException {
        String stackName = createStackName();
        VelocityContext context = createContext();
        Template template = velocityEngine.getTemplate(TEMPLATE_GLOBAL_RESOURCES);
        StringWriter stringWriter = new StringWriter();
        template.merge(context, stringWriter);
        String resultJSON = stringWriter.toString();
        JSONObject templateJson = new JSONObject(resultJSON);
        resultJSON = templateJson.toString(JSON_INDENT);
        //this.logger.info(resultJSON);
        cloudFormationClient.createOrUpdateStack(new CreateOrUpdateStackRequest()
            .withStackName(stackName)
            .withTemplateBody(resultJSON)
            .withCapabilities(CAPABILITY_NAMED_IAM)
            .withTags(stackTagsProvider.getStackTags())
        );
        cloudFormationClient.waitForStackToComplete(stackName);
        // setup SES notifications on prod stack
        if ("prod".equalsIgnoreCase(config.getProperty(PROPERTY_KEY_STACK))) {
            setupSesTopics(stackName);
        }
    }

    public String createStackName() {
        return String.format(GLOBAL_RESOURCES_STACK_NAME_FORMAT, config.getProperty(PROPERTY_KEY_STACK));
    }

    public VelocityContext createContext() {
        VelocityContext context = new VelocityContext();
        String stack = config.getProperty(PROPERTY_KEY_STACK);
        context.put(STACK, stack);
        context.put(DELETION_POLICY, Constants.isProd(config.getProperty(PROPERTY_KEY_STACK)) ? DeletionPolicy.Retain.name() : DeletionPolicy.Delete.name());
        context.put(IDENTITY_ARN, stsClient.getCallerIdentity(new GetCallerIdentityRequest()).getArn());
        context.put(VPC_EXPORT_PREFIX, Constants.createVpcExportPrefix(stack));
        return context;
    }

    public void setupSesTopics(String stackName) {
        String sesComplaintSnsTopic = this.cloudFormationClient.getOutput(stackName, GLOBAL_CFSTACK_OUTPUT_KEY_SES_COMPLAINT_TOPIC);
        String sesBounceSnsTopic = this.cloudFormationClient.getOutput(stackName, GLOBAL_CFSTACK_OUTPUT_KEY_SES_BOUNCE_TOPIC);
        sesClient.setComplaintNotificationTopic(SES_SYNAPSE_DOMAIN, sesComplaintSnsTopic);
        sesClient.setBounceNotificationTopic(SES_SYNAPSE_DOMAIN, sesBounceSnsTopic);
    }


}
