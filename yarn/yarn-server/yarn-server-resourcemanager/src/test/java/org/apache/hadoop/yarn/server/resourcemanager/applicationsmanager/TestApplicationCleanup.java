/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.net.NodeBase;
import org.apache.hadoop.yarn.ApplicationID;
import org.apache.hadoop.yarn.ApplicationState;
import org.apache.hadoop.yarn.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.Container;
import org.apache.hadoop.yarn.NodeID;
import org.apache.hadoop.yarn.Priority;
import org.apache.hadoop.yarn.Resource;
import org.apache.hadoop.yarn.ResourceRequest;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.security.ApplicationTokenSecretManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager.ASMContext;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ASMEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.AMLauncherEventType;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.ApplicationEventType;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.ApplicationTrackerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationMasterEvents.SNEventType;
import org.apache.hadoop.yarn.server.resourcemanager.resourcetracker.NodeInfo;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ClusterTracker;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ClusterTracker.NodeResponse;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ClusterTrackerImpl;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fifo.FifoScheduler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Testing application cleanup (notifications to nodemanagers).
 *
 */
public class TestApplicationCleanup extends TestCase {
  private static final Log LOG = LogFactory.getLog(TestApplicationCleanup.class);
  private AtomicInteger waitForState = new AtomicInteger(0);
  private ResourceScheduler scheduler;
  private ClusterTracker clusterTracker;
  private final int memoryCapability = 1024;
  private ExtASM asm;
  private static final int memoryNeeded = 100;

  private final ASMContext context = new ResourceManager.ASMContextImpl();

  private class ExtFifoScheduler extends FifoScheduler {
    @Override
    protected ClusterTracker createClusterTracker()  {
      clusterTracker = new ClusterTrackerImpl();
      return clusterTracker;
    }
  }

  @Before
  public void setUp() {
    new DummyApplicationTracker();
    scheduler = new ExtFifoScheduler();
    asm = new ExtASM(new ApplicationTokenSecretManager(), scheduler);
    asm.init(new Configuration());
  }

  @After
  public void tearDown() {

  }


  private class DummyApplicationTracker implements EventHandler<ASMEvent
  <ApplicationTrackerEventType>> {

    public DummyApplicationTracker() { 
      context.getDispatcher().register(ApplicationTrackerEventType.class, this);
    }
    @Override
    public void handle(ASMEvent<ApplicationTrackerEventType> event) {  
    }

  }
  private class ExtASM extends ApplicationsManagerImpl {
    boolean schedulerCleanupCalled = false;
    boolean launcherLaunchCalled = false;
    boolean launcherCleanupCalled = false;
    boolean schedulerScheduleCalled = false;

    private class DummyApplicationMasterLauncher implements EventHandler<ASMEvent<AMLauncherEventType>> {
      private AtomicInteger notify = new AtomicInteger(0);
      private AppContext appContext;

      public DummyApplicationMasterLauncher(ASMContext context) {
        context.getDispatcher().register(AMLauncherEventType.class, this);
        new Responder().start();
      }

      private class Responder extends Thread {
        public void run() {
          synchronized(notify) {
            try {
              while (notify.get() == 0) {   
                notify.wait();
              }
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
          context.getDispatcher().getEventHandler().
          handle(new ASMEvent<ApplicationEventType>(
          ApplicationEventType.LAUNCHED, appContext));
          synchronized(waitForState) {
            waitForState.addAndGet(1);
            waitForState.notify();
          }
        }
      }

      @Override
      public void handle(ASMEvent<AMLauncherEventType> appEvent) {
        AMLauncherEventType event = appEvent.getType();
        switch (event) {
        case CLEANUP:
          launcherCleanupCalled = true;
          break;
        case LAUNCH:
          LOG.info("Launcher Launch called");
          launcherLaunchCalled = true;
          appContext = appEvent.getAppContext();
          synchronized (notify) {
            notify.addAndGet(1);
            notify.notify();
            LOG.info("Done notifying launcher ");
          }
          break;
        default:
          break;
        }
      }
    }

    private class DummySchedulerNegotiator implements EventHandler<ASMEvent<SNEventType>> {
      private AtomicInteger snnotify = new AtomicInteger(0);
      AppContext acontext;
      public  DummySchedulerNegotiator(ASMContext context) {
        context.getDispatcher().register(SNEventType.class, this);
        new Responder().start();
      }

      private class Responder extends Thread {
        public void run() {
          LOG.info("Waiting for notify");
          synchronized(snnotify) {
            try {
              while(snnotify.get() == 0) {
                snnotify.wait();
              }
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
          context.getDispatcher().getEventHandler().
          handle(new ASMEvent<ApplicationEventType>(
          ApplicationEventType.ALLOCATED, acontext));
        }
      }

      @Override
      public void handle(ASMEvent<SNEventType> appEvent) {
        SNEventType event = appEvent.getType();
        switch (event) {
        case CLEANUP:
          schedulerCleanupCalled = true;
          break;
        case SCHEDULE:
          schedulerScheduleCalled = true;
          acontext = appEvent.getAppContext();
          LOG.info("Schedule received");
          synchronized(snnotify) {
            snnotify.addAndGet(1);
            snnotify.notify();
          }
        default:
          break;
        }
      }

    }
    public ExtASM(ApplicationTokenSecretManager applicationTokenSecretManager,
    YarnScheduler scheduler) {
      super(applicationTokenSecretManager, scheduler, context);
    }

    @Override
    protected EventHandler<ASMEvent<SNEventType>> createNewSchedulerNegotiator(
    YarnScheduler scheduler) {
      return new DummySchedulerNegotiator(context);
    }

    @Override
    protected EventHandler<ASMEvent<AMLauncherEventType>> createNewApplicationMasterLauncher(
    ApplicationTokenSecretManager tokenSecretManager) {
      return new DummyApplicationMasterLauncher(context);
    }

  }

  private void waitForState(ApplicationState state, ApplicationMasterInfo masterInfo) {
    synchronized(waitForState) {
      try {
        while(waitForState.get() == 0) {
          waitForState.wait(10000L);
        }
      } catch (InterruptedException e) {
        LOG.info("Interrupted thread " , e);
      }
    }
    Assert.assertEquals(state, masterInfo.getState());
  }

  private ResourceRequest createNewResourceRequest(int capability, int i) {
    ResourceRequest request = new ResourceRequest();
    request.capability = new Resource();
    request.capability.memory = capability;
    request.numContainers = 1;
    request.priority = new Priority();
    request.priority.priority = i;
    request.hostName = "*";
    return request;
  }

  protected NodeInfo addNodes(String commonName, int i, int memoryCapability) {
    NodeID nodeId = new NodeID();
    nodeId.id = i;
    String hostName = commonName + "_" + i;
    Node node = new NodeBase(hostName, NetworkTopology.DEFAULT_RACK);
    Resource capability = new Resource();
    capability.memory = 1024;
    return scheduler.addNode(nodeId, hostName, node, capability);
  }

  @Test
  public void testApplicationCleanUp() throws Exception {
    ApplicationID appID = asm.getNewApplicationID();
    ApplicationSubmissionContext context = new ApplicationSubmissionContext();
    context.applicationId = appID;
    context.queue = "queuename";
    context.user = "dummyuser";
    asm.submitApplication(context);
    waitForState(ApplicationState.LAUNCHED, asm.getApplicationMasterInfo(appID));
    List<ResourceRequest> reqs = new ArrayList<ResourceRequest>();
    ResourceRequest req = createNewResourceRequest(100, 1);
    reqs.add(req);
    reqs.add(createNewResourceRequest(memoryNeeded, 2));
    List<Container> release = new ArrayList<Container>();
    scheduler.allocate(appID, reqs, release);
    ArrayList<NodeInfo> nodesAdded = new ArrayList<NodeInfo>();
    for (int i = 0; i < 10; i++) {
      nodesAdded.add(addNodes("localhost", i, memoryCapability));
    }
    /* let one node heartbeat */
    Map<CharSequence, List<Container>> containers = new HashMap<CharSequence, List<Container>>();
    NodeInfo firstNode = nodesAdded.get(0);
    int firstNodeMemory = firstNode.getAvailableResource().memory;
    NodeInfo secondNode = nodesAdded.get(1);
    scheduler.nodeUpdate(firstNode, containers);
    scheduler.nodeUpdate(secondNode, containers);
    LOG.info("Available resource on first node" + firstNode.getAvailableResource());
    LOG.info("Available resource on second node" + secondNode.getAvailableResource());
    /* only allocate the containers to the first node */
    assertTrue(firstNode.getAvailableResource().memory == 
      (firstNodeMemory - (2*memoryNeeded)));
    ApplicationMasterInfo masterInfo = asm.getApplicationMasterInfo(appID);
    asm.finishApplication(appID);
    assertTrue(asm.launcherCleanupCalled == true);
    assertTrue(asm.launcherLaunchCalled == true);
    assertTrue(asm.schedulerCleanupCalled == true);
    assertTrue(asm.schedulerScheduleCalled == true);
    /* check for update of completed application */
    NodeResponse response = scheduler.nodeUpdate(firstNode, containers);
    assertTrue(response.getFinishedApplications().contains(appID));
    LOG.info("The containers to clean up " + response.getContainersToCleanUp().size());
    assertTrue(response.getContainersToCleanUp().size() == 2);
  }
}