package org.openstack.atlas.adapter.stm;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.adapter.LoadBalancerEndpointConfiguration;
import org.openstack.atlas.adapter.exceptions.InsufficientRequestException;
import org.openstack.atlas.adapter.exceptions.StmRollBackException;
import org.openstack.atlas.adapter.helpers.ResourceTranslator;
import org.openstack.atlas.adapter.helpers.StmConstants;
import org.openstack.atlas.adapter.helpers.TrafficScriptHelper;
import org.openstack.atlas.adapter.helpers.ZxtmNameBuilder;
import org.openstack.atlas.adapter.zxtm.ZxtmAdapterImpl;
import org.openstack.atlas.service.domain.entities.LoadBalancer;
import org.rackspace.stingray.client.StingrayRestClient;
import org.rackspace.stingray.client.bandwidth.Bandwidth;
import org.rackspace.stingray.client.exception.StingrayRestClientException;
import org.rackspace.stingray.client.exception.StingrayRestClientObjectNotFoundException;
import org.rackspace.stingray.client.list.Child;
import org.rackspace.stingray.client.monitor.Monitor;
import org.rackspace.stingray.client.persistence.Persistence;
import org.rackspace.stingray.client.persistence.PersistenceBasic;
import org.rackspace.stingray.client.persistence.PersistenceProperties;
import org.rackspace.stingray.client.pool.Pool;
import org.rackspace.stingray.client.protection.Protection;
import org.rackspace.stingray.client.ssl.keypair.Keypair;
import org.rackspace.stingray.client.traffic.ip.TrafficIp;
import org.rackspace.stingray.client.virtualserver.VirtualServer;
import org.rackspace.stingray.client.virtualserver.VirtualServerBasic;
import org.rackspace.stingray.client.virtualserver.VirtualServerConnectionError;
import org.rackspace.stingray.client.virtualserver.VirtualServerProperties;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class StmAdapterResources {
    public static Log LOG = LogFactory.getLog(StmAdapterResources.class.getName());

    public StingrayRestClient loadSTMRestClient(LoadBalancerEndpointConfiguration config) throws InsufficientRequestException {
        LOG.debug("Building new STM client using endpoint: " + config.getRestEndpoint());
        return new StingrayRestClient(config.getRestEndpoint(), config.getUsername(), config.getPassword());
    }

    public void updateVirtualServer(LoadBalancerEndpointConfiguration config, StingrayRestClient client, String vsName, VirtualServer virtualServer) throws StmRollBackException {
        LOG.debug(String.format("Updating virtual server '%s'...", vsName));

        VirtualServer curVs = null;
        try {
            curVs = client.getVirtualServer(vsName);
        } catch (Exception e) {
            LOG.warn(String.format("Error retrieving virtual server: %s, attempting to recreate... ", vsName));
        }

        try {
            client.updateVirtualServer(vsName, virtualServer);
        } catch (Exception ex) {
            String em = String.format("Error updating virtual server: %s Attempting to RollBack... \n Exception: %s ",
                    vsName, ex);

            LOG.error(em);
            if (curVs != null) {
                LOG.debug(String.format("Updating virtual server to previous configuration for rollback '%s'...", vsName));
                try {
                    client.updateVirtualServer(vsName, virtualServer);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating virtual server while attempting to previous configuration" +
                            ": %s RollBack aborted \n Exception: %s "
                            , vsName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Virtual server was not rolled back as no previous configuration was available. '%s' ", vsName));
            }
            throw new StmRollBackException(em, ex);
        }
        LOG.debug(String.format("Successfully updated virtual server '%s'...", vsName));
    }

    public void updateKeypair(LoadBalancerEndpointConfiguration config, StingrayRestClient client, String vsName, Keypair keypair) throws StmRollBackException {
        LOG.debug(String.format("Updating keypair '%s'...", vsName));

        Keypair curKeypair = null;
        try {
            curKeypair = client.getKeypair(vsName);
        } catch (Exception e) {
            LOG.warn(String.format("Error retrieving keypair: %s, attempting to update...", vsName));
        }
        try {
            client.updateKeypair(vsName, keypair);
        } catch (Exception ex) {
            String em = String.format("Error updating keypair: %s Attempting to roll back... \n Exception: %s ",
                    vsName, ex);
            LOG.error(em);
            if (keypair != null) {
                LOG.debug(String.format("Updating keypair to previous configuration for rollback '%s'...", vsName));
                try {
                    client.updateKeypair(vsName, curKeypair);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating keypair '%s' to previous configuration, Roll back aborted \n Exception: %s ",
                            vsName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Keypair '%s' was not rolled back since no previous configuration was available.", vsName));
            }
            throw new StmRollBackException(em, ex);
        }
        LOG.debug(String.format("Successfully updated keypair '%s'", vsName));
    }

    public void deleteKeypair(LoadBalancerEndpointConfiguration config, StingrayRestClient client, String vsName) throws StmRollBackException {
        LOG.info(String.format("Removing the keypair of SSL cert used on virtual server '%s'...", vsName));
        Keypair keypair = null;

        try {
            keypair = client.getKeypair(vsName);
            client.deleteKeypair(vsName);
        } catch (StingrayRestClientObjectNotFoundException notFoundException) {
            LOG.error(String.format("Keypair '%s' not found during deletion attempt, continue...", vsName));
        } catch (StingrayRestClientException clientException) {
            String em = String.format("Error removing keypair '%s', Attempting to roll back... \n Exception: %s ",
                    vsName, clientException);
            LOG.error(em);
            if (keypair != null) {
                LOG.debug(String.format("Updating Keypair to keep previous configuration for '%s'...", vsName));
                updateKeypair(config, client, vsName, keypair);
            } else {
                LOG.warn(String.format("Keypair was not rolled back as keypair '%s' was not retrieved.", vsName));
            }
            throw new StmRollBackException(em, clientException);
        }
    }

    public void deleteVirtualServer(LoadBalancerEndpointConfiguration config,
                                     StingrayRestClient client, String vsName)
            throws StmRollBackException {

        LOG.info(String.format("Removing  virtual server '%s'...", vsName));

        VirtualServer curVs = null;
        try {
            curVs = client.getVirtualServer(vsName);
            client.deleteVirtualServer(vsName);
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.error(String.format("Object not found when removing virtual server: %s, continue...", vsName));
        } catch (StingrayRestClientException ex) {
            String em = String.format("Error removing virtual server: %s Attempting to RollBack... \n Exception: %s "
                    , vsName, ex);
            LOG.error(em);
            if (curVs != null) {
                LOG.debug(String.format("Updating virtual server to set previous configuration for rollback '%s'", vsName));
                updateVirtualServer(config, client, vsName, curVs);
            } else {
                LOG.warn(String.format("Virtual server was not rolled back as no previous configuration was available. '%s' ", vsName));
            }
            throw new StmRollBackException(em, ex);
        }
        LOG.info(String.format("Successfully removed virtual server '%s'...", vsName));
    }

    public void updatePool(LoadBalancerEndpointConfiguration config,
                            StingrayRestClient client, String poolName, Pool pool)
            throws StmRollBackException {

        LOG.debug(String.format("Updating pool '%s' and setting nodes...", poolName));

        Pool curPool = null;
        try {
            curPool = client.getPool(poolName);
        } catch (Exception e) {
            LOG.warn(String.format("Could not load pool: %s, attempting to recreate...", poolName));
        }

        try {
            client.updatePool(poolName, pool);
            LOG.debug(String.format("Successfully updated pool '%s'...", poolName));

        } catch (Exception ex) {

            String em = String.format("Error updating node pool: %s Attempting to RollBack... \n Exception: %s", poolName, ex);

            LOG.error(em);
            if (curPool != null) {
                LOG.debug(String.format("Updating pool to previous configuration for rollback '%s'", poolName));
                try {
                    client.updatePool(poolName, curPool);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating node pool while attempting to previous configuration" +
                            ": %s RollBack aborted \n Exception: %s "
                            , poolName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Node Pool was not rolled back as no previous configuration was available. '%s' ", poolName));
            }
            throw new StmRollBackException(em, ex);
        }
    }

    public void deletePool(LoadBalancerEndpointConfiguration config,
                            StingrayRestClient client, String poolName)
            throws StmRollBackException {

        LOG.debug(String.format("Attempting to remove pool '%s'...", poolName));

        Pool curPool = null;
        try {
            curPool = client.getPool(poolName);
        } catch (Exception e) {
            LOG.warn(String.format("Could not load current pool: %s, continuing...", poolName));

        }

        try {
            client.deletePool(poolName);
            LOG.info(String.format("Successfully removed pool '%s'...", poolName));
        } catch (StingrayRestClientObjectNotFoundException one) {
            LOG.warn(String.format("Pool object already removed: %s, continue...", poolName));

        } catch (Exception ex) {
            String em = String.format("Error removing node pool: %s Attempting to RollBack... \n Exception: %s ", poolName, ex);
            LOG.error(em);
            if (curPool != null) {
                LOG.debug(String.format("Updating pool to previous configuration for rollback '%s'", poolName));
                try {
                    client.updatePool(poolName, curPool);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating node pool while attempting to previous configuration" +
                            ": %s RollBack aborted \n Exception: %s "
                            , poolName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Node Pool was not rolled back as no previous configuration was available. '%s' ", poolName));
            }
            throw new StmRollBackException(em, ex);
        }
    }

    public void updateVirtualIps(LoadBalancerEndpointConfiguration config,
                                  StingrayRestClient client, String vsName, Map<String, TrafficIp> tigmap)
            throws StmRollBackException {

        LOG.debug(String.format("Updating virtual ips for '%s'...", vsName));

        TrafficIp curTig = null;

        for (Map.Entry<String, TrafficIp> tm : tigmap.entrySet()) {
            try {
                curTig = client.getTrafficIp(tm.getKey());
                vsName = tm.getKey();
            } catch (Exception e) {
                LOG.warn(String.format("Could not load virtual ips for: %s, attempting to recreate...", vsName));
            }

            try {
                client.updateTrafficIp(tm.getKey(), tm.getValue());
            } catch (Exception ex) {
                String em = String.format("Error updating virtual ips: %s Attempting to RollBack... \n Exception: %s "
                        , vsName, ex);

                LOG.error(em);
                if (curTig != null) {
                    LOG.debug(String.format("Updating virtual ips to previous configuration for rollback '%s'", vsName));
                    try {
                        client.updateTrafficIp(vsName, curTig);
                    } catch (Exception ex2) {
                        String em2 = String.format("Error updating virtual ips while attempting to set previous configuration" +
                                ": %s RollBack aborted \n Exception: %s"
                                , vsName, ex2);
                        LOG.error(em2);
                    }
                } else {
                    LOG.warn(String.format("Virtual ips was not rolled back as no previous configuration was available. '%s' ", vsName));
                }
                throw new StmRollBackException(em, ex);
            }
            LOG.debug(String.format("Successfully updated virtual ips for '%s'...", vsName));
        }
    }

    public void updateHealthMonitor(LoadBalancerEndpointConfiguration config,
                                     StingrayRestClient client, String monitorName, Monitor monitor)
            throws StmRollBackException {

        LOG.debug(String.format("Update Monitor '%s' ...", monitorName));

        Monitor curMon = null;
        try {
            curMon = client.getMonitor(monitorName);
        } catch (Exception e) {
            LOG.warn(String.format("Could not locate: %s, attempting to recreate...", monitorName));
        }

        try {
            client.updateMonitor(monitorName, monitor);
        } catch (Exception ex) {
            String em = String.format("Error updating monitor: %s Attempting to RollBack... \n Exception: %s ", monitorName, ex);
            LOG.error(em);
            if (curMon != null) {
                LOG.debug(String.format("Updating monitor to previous configuration for rollback '%s'", monitorName));
                try {
                    client.updateMonitor(monitorName, curMon);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating monitor while attempting to set previous configuration" +
                            ": %s RollBack aborted \n Exception: %s", monitorName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Monitor was not rolled back as no previous configuration was available. '%s' ", monitorName));
            }
            throw new StmRollBackException(em, ex);
        }
        LOG.debug(String.format("Successfully updated Monitor '%s' ...", monitorName));
    }

    public void deleteHealthMonitor(LoadBalancerEndpointConfiguration config,
                                     StingrayRestClient client, String monitorName)
            throws StmRollBackException {

        LOG.info(String.format("Removing  monitor '%s'...", monitorName));

        Monitor curMon = null;
        try {
            curMon = client.getMonitor(monitorName);
            client.deleteMonitor(monitorName);
            LOG.info(String.format("Successfully removed monitor '%s'...", monitorName));
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.error(String.format("Monitor already removed: %s, continue...", monitorName));
        } catch (StingrayRestClientException ex) {
            String em = String.format("Error removing monitor: %s Attempting to RollBack... \n Exception: %s "
                    , monitorName, ex);
            LOG.error(em);
            if (curMon != null) {
                LOG.debug(String.format("Updating virtual server to set previous configuration for rollback '%s'", monitorName));
                updateHealthMonitor(config, client, monitorName, curMon);
            } else {
                LOG.warn(String.format("Monitor was not rolled back as no previous configuration was available. '%s' ", monitorName));
            }
            throw new StmRollBackException(em, ex);
        }
    }

    public void updateProtection(LoadBalancerEndpointConfiguration config, StingrayRestClient client, String protectionName, Protection protection) throws InsufficientRequestException, StmRollBackException {
        LOG.debug(String.format("Updating protection class on '%s'...", protectionName));

        Protection curProtection = null;
        try {
            curProtection = client.getProtection(protectionName);
        } catch (Exception e) {
            LOG.warn(String.format("Could not load protection class: %s, attempting to recreate...", protectionName));
        }

        try {
            LOG.debug(String.format("Updating protection for %s...", protectionName));
            client.updateProtection(protectionName, protection);
        } catch (Exception ex) {
            String em = String.format("Error updating protection: %s Attempting to RollBack... \n Exception: %s"
                    , protectionName, ex);

            LOG.error(em);
            if (curProtection != null) {
                LOG.debug(String.format("Updating monitor to previous configuration for rollback '%s'", protectionName));
                try {
                    client.updateProtection(protectionName, curProtection);
                } catch (Exception ex2) {
                    String em2 = String.format("Error updating protection while attempting to set previous configuration" +
                            ": %s RollBack aborted \n Exception: %s"
                            , protectionName, ex2);
                    LOG.error(em2);
                }
            } else {
                LOG.warn(String.format("Protection was not rolled back as no previous configuration was available. '%s' ", protectionName));
            }
            throw new StmRollBackException(em, ex);
        }

        LOG.debug(String.format("Successfully updated protection for %s!", protectionName));
    }

    public void deleteProtection(LoadBalancerEndpointConfiguration config,
                                  StingrayRestClient client, String protectionName)
            throws StmRollBackException, InsufficientRequestException {

        LOG.info(String.format("Removing  protection class '%s'...", protectionName));

        Protection curPro = null;
        try {
            curPro = client.getProtection(protectionName);
            client.deleteProtection(protectionName);
            LOG.info(String.format("Successfully removed protection class '%s'...", protectionName));
        } catch (StingrayRestClientObjectNotFoundException ex) {
            LOG.error(String.format("Protection already removed: %s, continue...", protectionName));
        } catch (StingrayRestClientException ex) {
            String em = String.format("Error removing protection: %s. Attempting to RollBack... \n Exception: %s "
                    , protectionName, ex);
            LOG.error(em);
            if (curPro != null) {
                LOG.debug(String.format("Updating virtual server to set previous configuration for rollback '%s'", protectionName));
                updateProtection(config, client, protectionName, curPro);
            } else {
                LOG.warn(String.format("Protection was not rolled back as no previous configuration was available. '%s' ", protectionName));
            }
            throw new StmRollBackException(em, ex);
        }
    }

    public void createPersistentClasses(LoadBalancerEndpointConfiguration config) throws InsufficientRequestException {
        StingrayRestClient client = loadSTMRestClient(config);
        try {
            client.getPersistence(StmConstants.HTTP_COOKIE);
        } catch (StingrayRestClientException clientException) {
            LOG.error(String.format("Error retrieving Persistence Class '%s'.\n%s", StmConstants.HTTP_COOKIE,
                    Arrays.toString(clientException.getStackTrace())));
        } catch (StingrayRestClientObjectNotFoundException notFoundException) {
            Persistence persistence = new Persistence();
            PersistenceProperties properties = new PersistenceProperties();
            PersistenceBasic basic = new PersistenceBasic();

            basic.setType(StmConstants.HTTP_COOKIE);
            properties.setBasic(basic);
            persistence.setProperties(properties);
            try {
                LOG.info(String.format("Updating Persistence type %s...", StmConstants.HTTP_COOKIE));
                client.createPersistence(StmConstants.HTTP_COOKIE, persistence);
                LOG.info(String.format("Successfully updated Persistence type %s.", StmConstants.HTTP_COOKIE));
            } catch (Exception ex) {
                LOG.error(String.format("Error creating Persistence Class '%s'.\n%s", StmConstants.HTTP_COOKIE,
                        Arrays.toString(ex.getStackTrace())));
            }
        }

        try {
            client.getPersistence(StmConstants.SOURCE_IP);
        } catch (StingrayRestClientException clientException) {
            LOG.error(String.format("Error retrieving Persistence Class '%s'.\n%s", StmConstants.SOURCE_IP,
                    Arrays.toString(clientException.getStackTrace())));
        } catch (StingrayRestClientObjectNotFoundException notFoundException) {
            Persistence persistence = new Persistence();
            PersistenceProperties properties = new PersistenceProperties();
            PersistenceBasic basic = new PersistenceBasic();

            basic.setType(StmConstants.SOURCE_IP);
            properties.setBasic(basic);
            persistence.setProperties(properties);
            try {
                LOG.info(String.format("Updating Persistence type %s...", StmConstants.SOURCE_IP));
                client.createPersistence(StmConstants.SOURCE_IP, persistence);
                LOG.info(String.format("Successfully updated Persistence type %s.", StmConstants.SOURCE_IP));
            } catch (Exception ex) {
                LOG.error(String.format("Error creating Persistence Class '%s'.\n%s", StmConstants.SOURCE_IP,
                        Arrays.toString(ex.getStackTrace())));
            }
        }
        client.destroy();
    }

    public void setRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = loadSTMRestClient(config);
        ResourceTranslator rt = new ResourceTranslator();

        try {
            LOG.debug(String.format("Adding a rate limit to load balancer...'%s'...", vsName));

            rt.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
            Bandwidth bandwidth = rt.getcBandwidth();
            VirtualServer virtualServer = rt.getcVServer();
            virtualServer.getProperties().getBasic().setBandwidth_class(vsName);

            client.createBandwidth(vsName, bandwidth);
            TrafficScriptHelper.addRateLimitScriptsIfNeeded(client);
            updateVirtualServer(config, client, vsName, virtualServer);


            LOG.info("Successfully added a rate limit to the rate limit pool.");
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.error(String.format("Failed to add rate limit for virtual server '%s' -- Object not found", vsName));
            throw new StmRollBackException("Add rate limit request canceled.", e);
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Failed to add rate limit for virtual server %s -- REST Client exception", vsName));
            throw new StmRollBackException("Add rate limit request canceled.", e);
        } catch (IOException e) {
            LOG.error(String.format("Failed to add rate limit for virtual server %s -- IOException", vsName));
            throw new StmRollBackException("Add rate limit request canceled.", e);
        }
        client.destroy();
    }

    public void updateRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName) throws InsufficientRequestException, StmRollBackException {
        StingrayRestClient client = loadSTMRestClient(config);
        ResourceTranslator rt = new ResourceTranslator();

        try {
            LOG.debug(String.format("Updating the rate limit for load balancer...'%s'...", vsName));

            TrafficScriptHelper.addRateLimitScriptsIfNeeded(client);

            rt.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
            Bandwidth bandwidth = rt.getcBandwidth();

            client.updateBandwidth(vsName, bandwidth);

            LOG.info(String.format("Successfully updated the rate limit for load balancer...'%s'...", vsName));
        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.error(String.format("Failed to update rate limit for virtual server %s -- REST Client exception", vsName));
            throw new StmRollBackException("Update rate limit request canceled.", e);
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Failed to update rate limit for virtual server %s -- REST Client exception", vsName));
            throw new StmRollBackException("Update rate limit request canceled.", e);
        } catch (IOException e) {
            LOG.error(String.format("Failed to update rate limit for virtual server %s -- IOException", vsName));
            throw new StmRollBackException("Update rate limit request canceled.", e);
        }
        client.destroy();
    }

    public void deleteRateLimit(LoadBalancerEndpointConfiguration config, LoadBalancer loadBalancer, String vsName) throws StmRollBackException, InsufficientRequestException {
        StingrayRestClient client = loadSTMRestClient(config);

        try {
            ResourceTranslator rt = new ResourceTranslator();
            rt.translateLoadBalancerResource(config, vsName, loadBalancer, loadBalancer);
            VirtualServer virtualServer = rt.getcVServer();
            VirtualServerProperties properties = virtualServer.getProperties();
            VirtualServerBasic basic = properties.getBasic();

            basic.setBandwidth_class("");
            client.deleteBandwidth(vsName);
            updateVirtualServer(config, client, vsName, virtualServer);

        } catch (StingrayRestClientObjectNotFoundException e) {
            LOG.warn(String.format("Cannot delete rate limit '%s', it does not exist. Ignoring...", vsName));
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("Failed to delete rate limit for virtual server %s -- REST Client exception", vsName));
            throw new StmRollBackException("Delete rate limit request canceled.", e);
        }
        client.destroy();
    }

    public void setErrorFile(LoadBalancerEndpointConfiguration config, StingrayRestClient client, LoadBalancer loadBalancer, String vsName, String content) throws InsufficientRequestException, StmRollBackException {
        File errorFile;
        String errorFileName = ZxtmNameBuilder.generateErrorPageName(vsName);

        ResourceTranslator rt = new ResourceTranslator();
        rt.translateVirtualServerResource(config, vsName, loadBalancer);
        VirtualServer vs = rt.getcVServer();

        try {
            LOG.debug(String.format("Attempting to upload the error file for %s (%s)", vsName, errorFileName));
            errorFile = getFileWithContent(content);
            client.createExtraFile(errorFileName, errorFile);
            LOG.info(String.format("Successfully uploaded the error file for %s (%s)", vsName, errorFileName));
        } catch (Exception e) {
            // Failed to create file, use "Default"
            // Failed to create error file, error out..
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) Exception: %s -- exception", vsName, errorFileName, e));
            errorFileName = "Default";
            throw new StmRollBackException(String.format("Failed creating error page %s for: %s.", errorFileName, vsName), e);
        }

        if (errorFile != null) errorFile.delete();

        try {
            LOG.debug(String.format("Attempting to set the error file for %s (%s)", vsName, errorFileName));
            // Update client with new properties
            updateVirtualServer(config, client, vsName, vs);

            LOG.info(String.format("Successfully set the error file for %s (%s)", vsName, errorFileName));
        } catch (StmRollBackException re) {
            // REST failure...
            LOG.error(String.format("Failed to set ErrorFile for %s (%s) -- Rolling back.", vsName, errorFileName));
            throw re;
        }
    }

    public void deleteErrorFile(LoadBalancerEndpointConfiguration config, StingrayRestClient client, LoadBalancer loadBalancer, String vsName)
            throws InsufficientRequestException, StmRollBackException {

        ResourceTranslator rt = new ResourceTranslator();
        rt.translateVirtualServerResource(config, vsName, loadBalancer);
        VirtualServer vs = rt.getcVServer();
        String fileToDelete = ZxtmNameBuilder.generateErrorPageName(vsName);
        try {
            LOG.debug(String.format("Attempting to delete a custom error file for %s (%s)", vsName, fileToDelete));

            // Update client with new properties
            VirtualServerProperties properties = vs.getProperties();
            VirtualServerConnectionError ce = new VirtualServerConnectionError();
            ce.setError_file("Default");
            properties.setConnection_errors(ce); // this will set the default error page
            updateVirtualServer(config, client, vsName, vs);

            // Delete the old error file
            client.deleteExtraFile(fileToDelete);

            LOG.info(String.format("Successfully deleted a custom error file for %s (%s)", vsName, fileToDelete));
        } catch (StingrayRestClientObjectNotFoundException onf) {
            LOG.warn(String.format("Cannot delete custom error page as, %s, it does not exist. Ignoring...", fileToDelete));
        } catch (StmRollBackException re) {
            LOG.error(String.format("Failed deleting the error file for: %s Exception: %s", vsName, re.getMessage()));
            throw re;
        } catch (StingrayRestClientException e) {
            LOG.error(String.format("There was a unexpected error deleting the error file for: %s Exception: %s", vsName, e.getMessage()));
            throw new StmRollBackException("Deleting error file cancelled.", e);
        }
    }

    public File getFileWithContent(String content) throws IOException {
        File file = File.createTempFile("StmAdapterImpl_", ".err");
        BufferedWriter out = new BufferedWriter(new FileWriter(file));
        out.write('\n' + content);
        out.close();
        return file;
    }
}