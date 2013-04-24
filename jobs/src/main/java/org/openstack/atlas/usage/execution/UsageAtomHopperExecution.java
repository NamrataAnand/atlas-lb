package org.openstack.atlas.usage.execution;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openstack.atlas.restclients.atomhopper.util.AtomHopperUtil;
import org.openstack.atlas.service.domain.entities.JobName;
import org.openstack.atlas.service.domain.entities.Usage;
import org.openstack.atlas.service.domain.events.repository.AlertRepository;
import org.openstack.atlas.service.domain.events.repository.LoadBalancerEventRepository;
import org.openstack.atlas.service.domain.repository.LoadBalancerRepository;
import org.openstack.atlas.service.domain.repository.UsageRepository;
import org.openstack.atlas.usage.BatchAction;
import org.openstack.atlas.usage.ExecutionUtilities;
import org.openstack.atlas.usage.thread.UsageThread;
import org.springframework.beans.factory.annotation.Required;

import java.util.*;

public class UsageAtomHopperExecution extends AbstractAtomHopperUsageExecution {
    private final Log LOG = LogFactory.getLog(UsageAtomHopperExecution.class);
    private AlertRepository alertRepository;
    private UsageRepository usageRepository;
    private LoadBalancerRepository loadBalancerRepository;
    private LoadBalancerEventRepository loadBalancerEventRepository;

    @Required
    public void setUsageRepository(UsageRepository usageRepository) {
        this.usageRepository = usageRepository;
    }

    @Required
    public void setLoadBalancerRepository(LoadBalancerRepository loadBalancerRepository) {
        this.loadBalancerRepository = loadBalancerRepository;
    }

    @Required
    public void setLoadBalancerEventRepository(LoadBalancerEventRepository loadBalancerEventRepository) {
        this.loadBalancerEventRepository = loadBalancerEventRepository;
    }

    @Required
    public void setAlertRepository(AlertRepository alertRepository) {
        this.alertRepository = alertRepository;
    }

    @Override
    public JobName getJobName() {
        return JobName.ATOM_LOADBALANCER_USAGE_POLLER;
    }

    @Override
    public void pushUsageToAtomHopper() throws Exception {
        final List<Usage> allUsages = loadBalancerRepository
                .getUsageNeedsPushed(AtomHopperUtil.getStartCal(), AtomHopperUtil.getNow(), NUM_ATTEMPTS);

        if (!allUsages.isEmpty()) {
            //Sort usages...
            Collections.sort(allUsages, new Comparator<Usage>() {
                public int compare(Usage s1, Usage s2) {
                    return s1.getId().compareTo(s2.getId());
                }
            });

            BatchAction<Usage> batchAction = new BatchAction<Usage>() {
                public void execute(Collection<Usage> allUsages) throws Exception {
                    executeTasks(allUsages);
                }
            };

            ExecutionUtilities.ExecuteInBatches(allUsages, BATCH_SIZE, batchAction);
        } else {
            LOG.debug("No usage found for processing at this time...");
        }
    }

    private void executeTasks(Collection<Usage> allUsages) throws Exception {
        ArrayList<Usage> usageList = new ArrayList<Usage>();
        usageList.addAll(allUsages);
        poolExecutor.execute(new UsageThread(usageList, ahuslClient,
                identityClient, usageRepository, loadBalancerEventRepository, alertRepository));
    }
}