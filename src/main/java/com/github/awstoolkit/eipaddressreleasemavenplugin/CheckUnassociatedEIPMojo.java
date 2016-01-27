package com.github.awstoolkit.eipaddressreleasemavenplugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesFileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.Address;
import com.amazonaws.services.ec2.model.DescribeAddressesRequest;
import com.amazonaws.services.ec2.model.DescribeAddressesResult;

/**
 * Goal which touches a timestamp file.
 * 
 * @author Oh Chin Boon
 * @since 1.0.0
 */
@Mojo(name = "checkUnassociatedEip")
public class CheckUnassociatedEIPMojo extends AbstractMojo {
	/**
	 * The default AWS Crendentials Properties File.
	 * 
	 * @author Oh Chin Boon
	 * @since 1.0.0
	 */
	public static final String DEFAULT_AWS_CREDENTIALS_PROPERTIES_FILE = "/usr/eipaddressreleasemavenplugin/awsCredentials.properties";

	/**
	 * EIP Exclusion File.
	 * 
	 * @author Oh Chin Boon
	 * @since 1.0.0
	 */
	public static final String EIP_EXCLUSION_FILE = "/usr/eipaddressreleasemavenplugin/eipExclusionList.properties";

	private AmazonEC2Client amazonEc2Client;

	private Set<String> eipExclusionSet;

	/**
	 * Calling this constructor will initialize DocumentTable using an Aws
	 * Credentials Properties file located at
	 * {@link DocumentTable#DEFAULT_AWS_CREDENTIALS_PROPERTIES_FILE}.
	 * 
	 * @author Oh Chin Boon
	 * @since 1.0.0
	 */
	public CheckUnassociatedEIPMojo() {
		this.initializeAwsClient(DEFAULT_AWS_CREDENTIALS_PROPERTIES_FILE);
	}

	/**
	 * Initializes the {@link DocumentTable} with a specified AWS Credentials
	 * properties file path.
	 * 
	 * @author Oh Chin Boon
	 * @param awsCredentialPropertiesFilePath
	 * @since 1.0.0
	 */
	private void initializeAwsClient(final String awsCredentialPropertiesFilePath) {
		final AWSCredentials credentials = new PropertiesFileCredentialsProvider(awsCredentialPropertiesFilePath)
				.getCredentials();

		this.initializeAwsClient(credentials);
	}

	private void initializeAwsClient(final AWSCredentials awsCredentials) {
		final AmazonEC2Client ec2Client = new AmazonEC2Client(awsCredentials);

		this.amazonEc2Client = ec2Client;
	}

	private void loadEipExclusionList() {
		getLog().info("Loading EIP Exclusion File (Optional) from [" + EIP_EXCLUSION_FILE + "]");

		try {
			final PropertiesConfiguration configuration = new PropertiesConfiguration(EIP_EXCLUSION_FILE);

			final String[] eipExclusionArr = configuration.getStringArray("excludeFromCheck");

			if (eipExclusionArr == null || eipExclusionArr.length == 0) {
				getLog().info("No excluded EIPs found");
			}

			this.eipExclusionSet = new HashSet<String>(Arrays.asList(eipExclusionArr));

		} catch (final ConfigurationException e) {
			e.printStackTrace();
		}
	}

	public void execute() throws MojoExecutionException, MojoFailureException {
		getLog().info("Running checkUnassociatedEip...");

		this.loadEipExclusionList();

		final DescribeAddressesRequest request = new DescribeAddressesRequest();

		final Regions[] regionsArr = Regions.values();

		int unallocatedEipInAccount = 0;

		for (final Regions region : regionsArr) {
			getLog().info("Checking for unassociated EIPs in the [" + region.getName() + "] region.");

			this.amazonEc2Client.setRegion(Region.getRegion(region));

			DescribeAddressesResult result;
			try {
				result = this.amazonEc2Client.describeAddresses(request);

			} catch (final AmazonServiceException exception) {
				getLog().warn(
						"An error has occurred while execusing describeAddresses in this region. Make sure you have the permission.");

				// skip this region
				continue;
			}

			if (result == null || result.getAddresses() == null || result.getAddresses().isEmpty()) {
				getLog().info("There are no EIPs in this region");
			}

			int unallocatedEip = 0;

			// there are addresses
			final List<Address> addressList = result.getAddresses();

			for (final Address address : addressList) {
				if (address == null) {
					// TODO not likely to happen, but can handle
				}

				if (!this.isAddressAssociatedWithEc2Instance(address)
						&& !this.isAddressAssociatedWithNetworkInterface(address)
						&& !isAddressExcludedFromCheck(address.getPublicIp())) {
					unallocatedEip++;

					getLog().warn("Unallocated EIP found.");
					getLog().warn("EIP Public Ip: " + address.getPublicIp());
					getLog().warn("EIP Allocation Ip: " + address.getAllocationId()
							+ " (Allocation Id may not be present for some EIPs)");
					getLog().warn("---------------------------------");
				}
			}

			if (unallocatedEip > 0) {
				unallocatedEipInAccount += unallocatedEip;

				getLog().warn(unallocatedEip + " unassociated EIP(s) found in " + region.getName());
				getLog().warn("Releasing these unallocated EIPs in this region could save you USD$"
						+ (unallocatedEip * 0.12) + " a day.");

			}
		}

		// account basis
		if (unallocatedEipInAccount > 0) {
			getLog().warn(unallocatedEipInAccount + " unassociated EIP(s) found in all Regions");
			getLog().warn("Releasing these unallocated EIPs in all regions could save you USD$"
					+ (unallocatedEipInAccount * 0.12) + " a day.");

			throw new MojoFailureException("One or more unassociated EIP(s) found.");
		}
	}

	private boolean isAddressExcludedFromCheck(final String eipPublicIp) {
		if (this.eipExclusionSet == null || this.eipExclusionSet.isEmpty()) {
			return false;
		}

		return this.eipExclusionSet.contains(eipPublicIp);

	}

	private boolean isAddressAssociatedWithEc2Instance(final Address address) {
		return !StringUtils.isEmpty(address.getInstanceId());
	}

	private boolean isAddressAssociatedWithNetworkInterface(final Address address) {
		return !StringUtils.isEmpty(address.getNetworkInterfaceId());
	}
}