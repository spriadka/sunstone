package org.wildfly.extras.sunstone.api.impl.azurearm;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.domain.Hardware;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.OsFamily;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.TemplateOptions;
import org.slf4j.Logger;
import org.wildfly.extras.sunstone.api.impl.AbstractJCloudsNode;
import org.wildfly.extras.sunstone.api.impl.Config;
import org.wildfly.extras.sunstone.api.impl.ObjectProperties;
import org.wildfly.extras.sunstone.api.impl.SunstoneCoreLogger;

import com.google.common.base.Strings;
import com.google.common.collect.Iterables;

/**
 * Azure ARM implementation of {@link org.wildfly.extras.sunstone.api.Node}. This implementation uses JClouds internally.
 */
public class AzureArmNode extends AbstractJCloudsNode<AzureArmCloudProvider> {
    private static final Logger LOGGER = SunstoneCoreLogger.DEFAULT;

    private final String imageId;
    private final NodeMetadata initialNodeMetadata;

    public AzureArmNode(AzureArmCloudProvider azureCloudProvider, String name, Map<String, String> configOverrides) {
        super(azureCloudProvider, name, configOverrides);
        String imageType = objectProperties.getProperty(Config.Node.AzureArm.IMAGE_TYPE, "");
        switch (imageType) {
            case "classic-vm":
                this.imageId = lookupClassicVMImageIdForNode(azureCloudProvider, name);
                break;
            case "image":
            default:
                this.imageId = lookupImageIdForNode(azureCloudProvider, name);
                break;
        }

        String size = objectProperties.getProperty(Config.Node.AzureArm.SIZE);
        if (size != null) {
            LOGGER.debug("Looking up virtual machine size '{}' for node '{}'", size, name);
            Set<? extends Hardware> hardwareProfiles = computeService.listHardwareProfiles();
            if (!hardwareProfiles.stream().map(Hardware::getId).anyMatch(size::equals)) {
                throw new IllegalArgumentException("Azure virtual machine size doesn't exist: " + size);
            }
            LOGGER.debug("Found virtual machine size '{}' for node '{}'", size, name);
        }

        TemplateOptions templateOptions = buildTemplateOptions(objectProperties);

        LOGGER.debug("Creating JClouds Template with options: {}", templateOptions);

        final OsFamily osFamily = objectProperties.getPropertyAsBoolean(Config.Node.AzureArm.IMAGE_IS_WINDOWS, false)
                ? OsFamily.WINDOWS
                : OsFamily.LINUX;
        Template template = computeService.templateBuilder()
                .imageId(this.imageId)
                .hardwareId(size)
                .osFamily(osFamily)
                .options(templateOptions)
                .build();

        LOGGER.debug("Creating {} node from template: {}",
                cloudProvider.getCloudProviderType().getHumanReadableName(), template);
        try {
            this.initialNodeMetadata = createNode(template);
            String publicAddress = Iterables.getFirst(initialNodeMetadata.getPublicAddresses(), null);
            LOGGER.info("Started {} node '{}' from image {}, its public IP address is {}",
                    cloudProvider.getCloudProviderType().getHumanReadableName(), name, imageId, publicAddress);
        } catch (RunNodesException e) {
            throw new RuntimeException("Unable to create " + cloudProvider.getCloudProviderType().getHumanReadableName()
                    + " node from template " + template, e);
        }
    }

    private String lookupImageIdForNode(AzureArmCloudProvider azureArmCloudProvider, String nodeName) {
        String resourceGroup = objectProperties.getProperty(Config.Node.AzureArm.Image.RESOURCE_GROUP);
        String imageName = objectProperties.getProperty(Config.Node.AzureArm.Image.IMAGE);
        if (Strings.isNullOrEmpty(resourceGroup)) {
            throw new IllegalArgumentException("Azure virtual machine image resource group was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        if (Strings.isNullOrEmpty(imageName)) {
            throw new IllegalArgumentException("Azure virtual machine image name was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        org.jclouds.azurecompute.arm.features.ImageApi imageApi = computeServiceContext.unwrapApi(org.jclouds.azurecompute.arm.AzureComputeApi.class).getVirtualMachineImageApi(resourceGroup);
        org.jclouds.azurecompute.arm.domain.Image image = Objects.requireNonNull(imageApi.get(imageName), "Image must exist: " + imageName);
        LOGGER.debug("Found virtual machine image '{}' for node '{}'", image, nodeName);
        return image.id();
    }

    private  String lookupClassicVMImageIdForNode(AzureArmCloudProvider azureArmCloudProvider, String nodeName) {
        String location = objectProperties.getProperty(Config.Node.AzureArm.VMImage.LOCATION);
        String publisher = objectProperties.getProperty(Config.Node.AzureArm.VMImage.PUBLISHER);
        String offer = objectProperties.getProperty(Config.Node.AzureArm.VMImage.OFFER);
        String sku = objectProperties.getProperty(Config.Node.AzureArm.VMImage.SKU);
        if (Strings.isNullOrEmpty(location)) {
            throw new IllegalArgumentException("Azure classic virtual machine image location was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        if (Strings.isNullOrEmpty(publisher)) {
            throw new IllegalArgumentException("Azure classic virtual machine image publisher was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        if (Strings.isNullOrEmpty(offer)) {
            throw new IllegalArgumentException("Azure classic virtual machine image offer was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        if (Strings.isNullOrEmpty(sku)) {
            throw new IllegalArgumentException("Azure classic virtual machine image sku was not provided for node " + nodeName
                + " in cloud provider " + azureArmCloudProvider.getName());
        }
        String version = objectProperties.getProperty(Config.Node.AzureArm.VMImage.VERSION, "latest");
        org.jclouds.azurecompute.arm.features.OSImageApi vmImageApi = computeServiceContext.unwrapApi(org.jclouds.azurecompute.arm.AzureComputeApi.class).getOSImageApi(location);
        org.jclouds.azurecompute.arm.domain.Version classicVmImage = Objects.requireNonNull(vmImageApi.getVersion(publisher, offer, sku, version),
            "Classic VM image must exist: { \"publisher\" : \"" + publisher + "\", \"offer\" : \"" + offer + "\", \"sku\" : \"" + sku + "\", \"version\" : \"" + version + "\"} for node " + nodeName);
        LOGGER.debug("Found classic virtual machine image { \"publisher\" : {}, \"offer\" : {}, \"sku\" : {}, \"version\" : \"{}\"} for node {}", publisher, offer, sku, version, nodeName);
        return classicVmImage.id();
    }

    private static TemplateOptions buildTemplateOptions(ObjectProperties objectProperties) {
        TemplateOptions templateOptions = new TemplateOptions();

        String loginName = objectProperties.getProperty(Config.Node.AzureArm.SSH_USER);
        if (Strings.isNullOrEmpty(loginName)) {
            throw new IllegalArgumentException("SSH user name for Azure virtual machine must be set");
        }
        templateOptions.overrideLoginUser(loginName);

        String loginPassword = objectProperties.getProperty(Config.Node.AzureArm.SSH_PASSWORD);
        if (Strings.isNullOrEmpty(loginPassword)) {
            throw new IllegalArgumentException("SSH password for Azure virtual machine must be set");
        }
        templateOptions.overrideLoginPassword(loginPassword);

        int[] inboundPorts = Pattern.compile(",")
                .splitAsStream(objectProperties.getProperty(Config.Node.AzureArm.INBOUND_PORTS, "22"))
                .filter(s -> !Strings.isNullOrEmpty(s))
                .mapToInt(Integer::parseInt)
                .toArray();
        templateOptions.inboundPorts(inboundPorts);

        return templateOptions;
    }

    @Override
    public NodeMetadata getInitialNodeMetadata() {
        return initialNodeMetadata;
    }

    @Override
    public NodeMetadata getFreshNodeMetadata() {
        return computeService.getNodeMetadata(initialNodeMetadata.getId());
    }

    @Override
    public String getImageName() {
        return imageId;
    }

    // TODO getPublicTcpPort() - does Azure even support port mappings? probably yes, but I don't know how currently
}
