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

package org.apache.hadoop.yarn.server.resourcemanager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.ipc.Server;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CommonConfigurationKeys;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityInfo;
import org.apache.hadoop.yarn.api.AMRMProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.records.AMResponse;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationMaster;
import org.apache.hadoop.yarn.api.records.ApplicationStatus;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.exceptions.YarnRemoteException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.ipc.RPCUtil;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.security.ApplicationTokenSecretManager;
import org.apache.hadoop.yarn.security.SchedulerSecurityInfo;
import org.apache.hadoop.yarn.server.resourcemanager.ResourceManager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.AMFinishEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.AMRegistrationEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.AMStatusUpdateEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ASMEvent;
import org.apache.hadoop.yarn.server.resourcemanager.applicationsmanager.events.ApplicationTrackerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Allocation;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.YarnScheduler;
import org.apache.hadoop.yarn.service.AbstractService;

@Private
public class ApplicationMasterService extends AbstractService implements 
AMRMProtocol, EventHandler<ASMEvent<ApplicationTrackerEventType>> {
  private static final Log LOG = LogFactory.getLog(ApplicationMasterService.class);
  private YarnScheduler rScheduler;
  private ApplicationTokenSecretManager appTokenManager;
  private InetSocketAddress masterServiceAddress;
  private Server server;
  private final RecordFactory recordFactory = RecordFactoryProvider.getRecordFactory(null);
  private final Map<ApplicationId, AMResponse> responseMap =
      new HashMap<ApplicationId, AMResponse>();
  private final AMResponse reboot = recordFactory.newRecordInstance(AMResponse.class);
  private final RMContext context;
  
  public ApplicationMasterService(
      ApplicationTokenSecretManager appTokenManager, YarnScheduler scheduler,
      RMContext asmContext) {
    super(ApplicationMasterService.class.getName());
    this.appTokenManager = appTokenManager;
    this.rScheduler = scheduler;
    this.reboot.setReboot(true);
//    this.reboot.containers = new ArrayList<Container>();
    this.context = asmContext;
  }

  @Override
  public void init(Configuration conf) {
    String bindAddress =
      conf.get(YarnConfiguration.SCHEDULER_ADDRESS,
          YarnConfiguration.DEFAULT_SCHEDULER_BIND_ADDRESS);
    masterServiceAddress =  NetUtils.createSocketAddr(bindAddress);
    this.context.getDispatcher().register(ApplicationTrackerEventType.class, this);
    super.init(conf);
  }

  @Override
  public void start() {
    YarnRPC rpc = YarnRPC.create(getConfig());
    Configuration serverConf = new Configuration(getConfig());
    serverConf.setClass(
        YarnConfiguration.YARN_SECURITY_INFO,
        SchedulerSecurityInfo.class, SecurityInfo.class);
    this.server =
      rpc.getServer(AMRMProtocol.class, this, masterServiceAddress,
          serverConf, this.appTokenManager,
          serverConf.getInt(RMConfig.RM_AM_THREADS, 
              RMConfig.DEFAULT_RM_AM_THREADS));
    this.server.start();
    super.start();
  }
  
  @Override
  public RegisterApplicationMasterResponse registerApplicationMaster(RegisterApplicationMasterRequest request) throws YarnRemoteException {
    // TODO: What if duplicate register due to lost RPCs

    ApplicationMaster applicationMaster = request.getApplicationMaster();
    LOG.info("AM registration " + applicationMaster.getApplicationId());
    context.getDispatcher().getEventHandler().handle(
        new AMRegistrationEvent(applicationMaster));
    
    // Pick up min/max resource from scheduler...
    RegisterApplicationMasterResponse response = 
      recordFactory.newRecordInstance(RegisterApplicationMasterResponse.class);
    response.setMinimumResourceCapability(
        rScheduler.getMinimumResourceCapability());
    response.setMaximumResourceCapability(
        rScheduler.getMaximumResourceCapability());
    return response;
  }

  @Override
  public FinishApplicationMasterResponse finishApplicationMaster(
      FinishApplicationMasterRequest request) throws YarnRemoteException {
    // TODO: What if duplicate finish due to lost RPCs
    ApplicationMaster applicationMaster = request.getApplicationMaster();
    context.getDispatcher().getEventHandler().handle(
        new AMFinishEvent(applicationMaster.getApplicationId(),
            applicationMaster.getState(), applicationMaster.getTrackingUrl(),
            applicationMaster.getDiagnostics()));
    FinishApplicationMasterResponse response = recordFactory
        .newRecordInstance(FinishApplicationMasterResponse.class);
    return response;
  }

  @Override
  public AllocateResponse allocate(AllocateRequest request) throws YarnRemoteException {
    ApplicationStatus status = request.getApplicationStatus();
    List<ResourceRequest> ask = request.getAskList();
    List<Container> release = request.getReleaseList();
    // TODO: This is a hack. Arbitrary changing state can screw things up.
    // Set Container state as complete, as this will be returned back to AM as
    // is to inform AM of the list of completed containers.
    for (Container container : release) {
      container.setState(ContainerState.COMPLETE);
    }
    try {
      /* check if its in cache */
      synchronized(responseMap) {
        AllocateResponse allocateResponse = recordFactory.newRecordInstance(AllocateResponse.class);
        AMResponse lastResponse = responseMap.get(status.getApplicationId());
        if (lastResponse == null) {
          LOG.error("Application doesnt exist in cache " + status.getApplicationId());
          allocateResponse.setAMResponse(reboot);
          return allocateResponse;
        }
        if ((status.getResponseId() + 1) == lastResponse.getResponseId()) {
          /* old heartbeat */
          allocateResponse.setAMResponse(lastResponse);
          return allocateResponse;
        } else if (status.getResponseId() + 1 < lastResponse.getResponseId()) {
          LOG.error("Invalid responseid from application " + status.getApplicationId());
          allocateResponse.setAMResponse(reboot);
          return allocateResponse;
        }

        // Send the heart-beat to the application.
        context.getDispatcher().getEventHandler().handle(
            new AMStatusUpdateEvent(status));

        Allocation allocation = 
          rScheduler.allocate(status.getApplicationId(), ask, release); 
        AMResponse  response = recordFactory.newRecordInstance(AMResponse.class);
        response.addAllContainers(allocation.getContainers());
        response.setResponseId(lastResponse.getResponseId() + 1);
        response.setAvailableResources(allocation.getResourceLimit());
        responseMap.put(status.getApplicationId(), response);
        allocateResponse.setAMResponse(response);
        return allocateResponse;
      }
    } catch(IOException ie) {
      LOG.info("Error in allocation for " + status.getApplicationId(), ie);
      throw RPCUtil.getRemoteException(ie);
    }
  } 

  @Override
  public void stop() {
    if (this.server != null) {
      this.server.close();
    }
    super.stop();
  }

  @Override
  public void handle(ASMEvent<ApplicationTrackerEventType> appEvent) {
    ApplicationTrackerEventType event = appEvent.getType();
    ApplicationId id = appEvent.getApplication().getApplicationID();
    synchronized(responseMap) {
      switch (event) {
      case ADD:
        AMResponse response = recordFactory.newRecordInstance(AMResponse.class);
//        response.containers = null;
        response.setResponseId(0);
        responseMap.put(id, response);
        break;
      case REMOVE:
        responseMap.remove(id);
        break;
      case EXPIRE:
        responseMap.remove(id);
        break;
      }
    }
  }
}