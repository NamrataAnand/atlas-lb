package org.openstack.atlas.atom.pojo;


import com.rackspace.docs.usage.lbaas.CloudLoadBalancersAccountUsageType;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement(namespace = "http://docs.rackspace.com/core/usage/lbaas", name="accountLoadBalancerUsageRecord")
public class AccountLBaaSUsagePojo extends CloudLoadBalancersAccountUsageType {

}