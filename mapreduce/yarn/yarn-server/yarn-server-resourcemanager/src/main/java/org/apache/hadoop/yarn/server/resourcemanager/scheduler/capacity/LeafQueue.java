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

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.authorize.AccessControlList;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerToken;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueState;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.security.ContainerTokenIdentifier;
import org.apache.hadoop.yarn.server.resourcemanager.resource.Resources;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerState;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.NodeType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNode;
import org.apache.hadoop.yarn.server.security.ContainerTokenSecretManager;
import org.apache.hadoop.yarn.util.BuilderUtils;

@Private
@Unstable
public class LeafQueue implements Queue {
  private static final Log LOG = LogFactory.getLog(LeafQueue.class);

  private final String queueName;
  private Queue parent;
  private float capacity;
  private float absoluteCapacity;
  private float maximumCapacity;
  private float absoluteMaxCapacity;
  private int userLimit;
  private float userLimitFactor;

  private int maxApplications;
  private int maxApplicationsPerUser;
  private Resource usedResources = Resources.createResource(0);
  private float utilization = 0.0f;
  private float usedCapacity = 0.0f;
  private volatile int numContainers;

  Set<SchedulerApp> applications;
  Map<ApplicationAttemptId, SchedulerApp> applicationsMap = 
      new HashMap<ApplicationAttemptId, SchedulerApp>();
  
  public final Resource minimumAllocation;

  private ContainerTokenSecretManager containerTokenSecretManager;

  private Map<String, User> users = new HashMap<String, User>();
  
  private final QueueMetrics metrics;

  private QueueInfo queueInfo; 

  private QueueState state;

  private Map<QueueACL, AccessControlList> acls = 
    new HashMap<QueueACL, AccessControlList>();

  private final RecordFactory recordFactory = 
    RecordFactoryProvider.getRecordFactory(null);

  private CapacitySchedulerContext scheduler;
  
  public LeafQueue(CapacitySchedulerContext cs, 
      String queueName, Queue parent, 
      Comparator<SchedulerApp> applicationComparator, Queue old) {
    this.scheduler = cs;
    this.queueName = queueName;
    this.parent = parent;
    // must be after parent and queueName are initialized
    this.metrics = old != null ? old.getMetrics() :
        QueueMetrics.forQueue(getQueuePath(), parent,
        cs.getConfiguration().getEnableUserMetrics());
    
    this.minimumAllocation = cs.getMinimumResourceCapability();
    this.containerTokenSecretManager = cs.getContainerTokenSecretManager();

    float capacity = 
      (float)cs.getConfiguration().getCapacity(getQueuePath()) / 100;
    float absoluteCapacity = parent.getAbsoluteCapacity() * capacity;

    float maximumCapacity = cs.getConfiguration().getMaximumCapacity(getQueuePath());
    float absoluteMaxCapacity = 
      (maximumCapacity == CapacitySchedulerConfiguration.UNDEFINED) ? 
          Float.MAX_VALUE : (parent.getAbsoluteCapacity() * maximumCapacity) / 100;

    int userLimit = cs.getConfiguration().getUserLimit(getQueuePath());
    float userLimitFactor = 
      cs.getConfiguration().getUserLimitFactor(getQueuePath());

    int maxSystemJobs = cs.getConfiguration().getMaximumSystemApplications();
    int maxApplications = (int)(maxSystemJobs * absoluteCapacity);
    int maxApplicationsPerUser = 
      (int)(maxApplications * (userLimit / 100.0f) * userLimitFactor);

    this.queueInfo = recordFactory.newRecordInstance(QueueInfo.class);
    this.queueInfo.setQueueName(queueName);
    this.queueInfo.setChildQueues(new ArrayList<QueueInfo>());

    QueueState state = cs.getConfiguration().getState(getQueuePath());

    Map<QueueACL, AccessControlList> acls = 
      cs.getConfiguration().getAcls(getQueuePath());

    setupQueueConfigs(capacity, absoluteCapacity, 
        maximumCapacity, absoluteMaxCapacity, 
        userLimit, userLimitFactor, 
        maxApplications, maxApplicationsPerUser,
        state, acls);

    LOG.info("DEBUG --- LeafQueue:" +
        " name=" + queueName + 
        ", fullname=" + getQueuePath());

    this.applications = new TreeSet<SchedulerApp>(applicationComparator);
  }

  private synchronized void setupQueueConfigs(
      float capacity, float absoluteCapacity, 
      float maxCapacity, float absoluteMaxCapacity,
      int userLimit, float userLimitFactor,
      int maxApplications, int maxApplicationsPerUser,
      QueueState state, Map<QueueACL, AccessControlList> acls)
  {
    this.capacity = capacity; 
    this.absoluteCapacity = parent.getAbsoluteCapacity() * capacity;

    this.maximumCapacity = maxCapacity;
    this.absoluteMaxCapacity = absoluteMaxCapacity;

    this.userLimit = userLimit;
    this.userLimitFactor = userLimitFactor;

    this.maxApplications = maxApplications;
    this.maxApplicationsPerUser = maxApplicationsPerUser;

    this.state = state;

    this.acls = acls;

    this.queueInfo.setCapacity(capacity);
    this.queueInfo.setMaximumCapacity(maximumCapacity);
    this.queueInfo.setQueueState(state);

    StringBuilder aclsString = new StringBuilder();
    for (Map.Entry<QueueACL, AccessControlList> e : acls.entrySet()) {
      aclsString.append(e.getKey() + ":" + e.getValue().getAclString());
    }

    LOG.info("Initializing " + queueName +
        ", capacity=" + capacity + 
        ", asboluteCapacity=" + absoluteCapacity + 
        ", maxCapacity=" + maxCapacity +
        ", asboluteMaxCapacity=" + absoluteMaxCapacity +
        ", userLimit=" + userLimit + ", userLimitFactor=" + userLimitFactor + 
        ", maxApplications=" + maxApplications + 
        ", maxApplicationsPerUser=" + maxApplicationsPerUser + 
        ", state=" + state +
        ", acls=" + aclsString);
  }
  
  @Override
  public synchronized float getCapacity() {
    return capacity;
  }

  @Override
  public synchronized float getAbsoluteCapacity() {
    return absoluteCapacity;
  }

  @Override
  public synchronized float getMaximumCapacity() {
    return maximumCapacity;
  }

  @Override
  public synchronized float getAbsoluteMaximumCapacity() {
    return absoluteMaxCapacity;
  }

  @Override
  public Queue getParent() {
    return parent;
  }

  @Override
  public String getQueueName() {
    return queueName;
  }

  @Override
  public String getQueuePath() {
    return parent.getQueuePath() + "." + getQueueName();
  }

  @Override
  public synchronized float getUsedCapacity() {
    return usedCapacity;
  }

  @Override
  public synchronized Resource getUsedResources() {
    return usedResources;
  }

  @Override
  public synchronized float getUtilization() {
    return utilization;
  }

  @Override
  public List<Queue> getChildQueues() {
    return null;
  }

  synchronized void setUtilization(float utilization) {
    this.utilization = utilization;
  }

  synchronized void setUsedCapacity(float usedCapacity) {
    this.usedCapacity = usedCapacity;
  }

  /**
   * Set maximum capacity - used only for testing.
   * @param maximumCapacity new max capacity
   */
  synchronized void setMaxCapacity(float maximumCapacity) {
    this.maximumCapacity = maximumCapacity;
    this.absoluteMaxCapacity = 
      (maximumCapacity == CapacitySchedulerConfiguration.UNDEFINED) ? 
          Float.MAX_VALUE : 
          (parent.getAbsoluteCapacity() * maximumCapacity);
  }
  
  /**
   * Set user limit - used only for testing.
   * @param userLimit new user limit
   */
  synchronized void setUserLimit(int userLimit) {
    this.userLimit = userLimit;
  }

  /**
   * Set user limit factor - used only for testing.
   * @param userLimitFactor new user limit factor
   */
  synchronized void setUserLimitFactor(int userLimitFactor) {
    this.userLimitFactor = userLimitFactor;
  }

  synchronized void setParentQueue(Queue parent) {
    this.parent = parent;
  }
  
  public synchronized int getNumApplications() {
    return applications.size();
  }

  public synchronized int getNumContainers() {
    return numContainers;
  }

  @Override
  public synchronized QueueState getState() {
    return state;
  }

  @Override
  public synchronized Map<QueueACL, AccessControlList> getQueueAcls() {
    return new HashMap<QueueACL, AccessControlList>(acls);
  }

  @Override
  public synchronized QueueInfo getQueueInfo(
      boolean includeChildQueues, boolean recursive) {
    queueInfo.setCurrentCapacity(usedCapacity);
    return queueInfo;
  }

  @Override
  public synchronized List<QueueUserACLInfo> 
  getQueueUserAclInfo(UserGroupInformation user) {
    QueueUserACLInfo userAclInfo = 
      recordFactory.newRecordInstance(QueueUserACLInfo.class);
    List<QueueACL> operations = new ArrayList<QueueACL>();
    for (Map.Entry<QueueACL, AccessControlList> e : acls.entrySet()) {
      QueueACL operation = e.getKey();
      AccessControlList acl = e.getValue();

      if (acl.isUserAllowed(user)) {
        operations.add(operation);
      }
    }

    userAclInfo.setQueueName(getQueueName());
    userAclInfo.setUserAcls(operations);
    return Collections.singletonList(userAclInfo);
  }

  public String toString() {
    return queueName + ":" + capacity + ":" + absoluteCapacity + ":" + 
    getUsedCapacity() + ":" + getUtilization() + ":" + 
    getNumApplications() + ":" + getNumContainers();
  }

  private synchronized User getUser(String userName) {
    User user = users.get(userName);
    if (user == null) {
      user = new User();
      users.put(userName, user);
    }
    return user;
  }

  @Override
  public synchronized void reinitialize(Queue queue, Resource clusterResource) 
  throws IOException {
    // Sanity check
    if (!(queue instanceof LeafQueue) || 
        !queue.getQueuePath().equals(getQueuePath())) {
      throw new IOException("Trying to reinitialize " + getQueuePath() + 
          " from " + queue.getQueuePath());
    }

    LeafQueue leafQueue = (LeafQueue)queue;
    setupQueueConfigs(leafQueue.capacity, leafQueue.absoluteCapacity, 
        leafQueue.maximumCapacity, leafQueue.absoluteMaxCapacity, 
        leafQueue.userLimit, leafQueue.userLimitFactor, 
        leafQueue.maxApplications, leafQueue.maxApplicationsPerUser,
        leafQueue.state, leafQueue.acls);
    
    updateResource(clusterResource);
  }

  @Override
  public boolean hasAccess(QueueACL acl, UserGroupInformation user) {
    // Check if the leaf-queue allows access
    synchronized (this) {
      if (acls.get(acl).isUserAllowed(user)) {
        return true;
      }
    }

    // Check if parent-queue allows access
    return parent.hasAccess(acl, user);
  }

  @Override
  public void submitApplication(SchedulerApp application, String userName,
      String queue)  throws AccessControlException {
    // Careful! Locking order is important!

    // Check queue ACLs
    UserGroupInformation userUgi;
    try {
      userUgi = UserGroupInformation.getCurrentUser();
    } catch (IOException ioe) {
      throw new AccessControlException(ioe);
    }
    if (!hasAccess(QueueACL.SUBMIT_JOB, userUgi)) {
      throw new AccessControlException("User " + userName + " cannot submit" +
          " jobs to queue " + getQueuePath());
    }

    User user = null;
    synchronized (this) {

      // Check if the queue is accepting jobs
      if (state != QueueState.RUNNING) {
        throw new AccessControlException("Queue " + getQueuePath() +
            " is STOPPED. Cannot accept submission of application: " +
            application.getApplicationId());
      }

      // Check submission limits for queues
      if (getNumApplications() >= maxApplications) {
        throw new AccessControlException("Queue " + getQueuePath() + 
            " already has " + getNumApplications() + " applications," +
            " cannot accept submission of application: " + 
            application.getApplicationId());
      }

      // Check submission limits for the user on this queue
      user = getUser(userName);
      if (user.getApplications() >= maxApplicationsPerUser) {
        throw new AccessControlException("Queue " + getQueuePath() + 
            " already has " + user.getApplications() + 
            " applications from user " + userName + 
            " cannot accept submission of application: " + 
            application.getApplicationId());
      }

      // Add the application to our data-structures
      addApplication(application, user);
    }

    metrics.submitApp(userName);

    // Inform the parent queue
    try {
      parent.submitApplication(application, userName, queue);
    } catch (AccessControlException ace) {
      LOG.info("Failed to submit application to parent-queue: " + 
          parent.getQueuePath(), ace);
      removeApplication(application, user);
      throw ace;
    }
  }

  private synchronized void addApplication(SchedulerApp application, User user) {
    // Accept 
    user.submitApplication();
    applications.add(application);
    applicationsMap.put(application.getApplicationAttemptId(), application);

    LOG.info("Application added -" +
        " appId: " + application.getApplicationId() +
        " user: " + user + "," + " leaf-queue: " + getQueueName() +
        " #user-applications: " + user.getApplications() + 
        " #queue-applications: " + getNumApplications());
  }

  @Override
  public void finishApplication(SchedulerApp application, String queue) {
    // Careful! Locking order is important!
    synchronized (this) {
      removeApplication(application, getUser(application.getUser()));
    }

    // Inform the parent queue
    parent.finishApplication(application, queue);
  }

  public synchronized void removeApplication(SchedulerApp application, User user) {
    applications.remove(application);
    applicationsMap.remove(application.getApplicationAttemptId());

    user.finishApplication();
    if (user.getApplications() == 0) {
      users.remove(application.getUser());
    }

    LOG.info("Application removed -" +
        " appId: " + application.getApplicationId() + 
        " user: " + application.getUser() + 
        " queue: " + getQueueName() +
        " #user-applications: " + user.getApplications() + 
        " #queue-applications: " + getNumApplications());
  }
  
  private synchronized SchedulerApp getApplication(
      ApplicationAttemptId applicationAttemptId) {
    return applicationsMap.get(applicationAttemptId);
  }

  @Override
  public synchronized Resource 
  assignContainers(Resource clusterResource, SchedulerNode node) {

    LOG.info("DEBUG --- assignContainers:" +
        " node=" + node.getHostName() + 
        " #applications=" + applications.size());
    
    // Check for reserved resources
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      SchedulerApp application = 
          getApplication(reservedContainer.getApplicationAttemptId());
      return assignReservedContainer(application, node, reservedContainer, 
          clusterResource);
    }
    
    // Try to assign containers to applications in order
    for (SchedulerApp application : applications) {
      
      LOG.info("DEBUG --- pre-assignContainers for application "
          + application.getApplicationId());
      application.showRequests();

      synchronized (application) {
        Resource userLimit = 
          computeUserLimit(application, clusterResource, Resources.none());
        setUserResourceLimit(application, userLimit);
        
        for (Priority priority : application.getPriorities()) {

          // Do we need containers at this 'priority'?
          if (!needContainers(application, priority)) {
            continue;
          }

          // Are we going over limits by allocating to this application?
          ResourceRequest required = 
            application.getResourceRequest(priority, RMNode.ANY);

          // Maximum Capacity of the queue
          if (!assignToQueue(clusterResource, required.getCapability())) {
            return Resources.none();
          }

          // User limits
          userLimit = 
            computeUserLimit(application, clusterResource, 
                required.getCapability());
          if (!assignToUser(application.getUser(), userLimit)) {
            break; 
          }

          // Inform the application it is about to get a scheduling opportunity
          application.addSchedulingOpportunity(priority);
          
          // Try to schedule
          Resource assigned = 
            assignContainersOnNode(clusterResource, node, application, priority, 
                null);
  
          // Did we schedule or reserve a container?
          if (Resources.greaterThan(assigned, Resources.none())) {
            Resource assignedResource = 
              application.getResourceRequest(priority, RMNode.ANY).getCapability();

            // Book-keeping
            allocateResource(clusterResource, 
                application.getUser(), assignedResource);
            
            // Reset scheduling opportunities
            application.resetSchedulingOpportunities(priority);
            
            // Done
            return assignedResource; 
          } else {
            // Do not assign out of order w.r.t priorities
            break;
          }
        }
      }

      LOG.info("DEBUG --- post-assignContainers for application "
          + application.getApplicationId());
      application.showRequests();
    }
  
    return Resources.none();

  }

  private synchronized Resource assignReservedContainer(SchedulerApp application, 
      SchedulerNode node, RMContainer rmContainer, Resource clusterResource) {
    // Do we still need this reservation?
    Priority priority = rmContainer.getReservedPriority();
    if (application.getTotalRequiredResources(priority) == 0) {
      // Release
      Container container = rmContainer.getContainer();
      completedContainer(clusterResource, application, node, 
          rmContainer, RMContainerEventType.RELEASED);
      return container.getResource();
    }

    // Try to assign if we have sufficient resources
    assignContainersOnNode(clusterResource, node, application, priority, rmContainer);
    
    // Doesn't matter... since it's already charged for at time of reservation
    // "re-reservation" is *free*
    return org.apache.hadoop.yarn.server.resourcemanager.resource.Resource.NONE;
  }

  private synchronized boolean assignToQueue(Resource clusterResource, 
      Resource required) {
    // Check how of the cluster's absolute capacity we are currently using...
    float potentialNewCapacity = 
      (float)(usedResources.getMemory() + required.getMemory()) / 
        clusterResource.getMemory();
    if (potentialNewCapacity > absoluteMaxCapacity) {
      LOG.info(getQueueName() + 
          " usedResources: " + usedResources.getMemory() + 
          " currentCapacity " + ((float)usedResources.getMemory())/clusterResource.getMemory() + 
          " required " + required.getMemory() +
          " potentialNewCapacity: " + potentialNewCapacity + " ( " +
          " > max-capacity (" + absoluteMaxCapacity + ")");
      return false;
    }
    return true;
  }

  private void setUserResourceLimit(SchedulerApp application, 
      Resource resourceLimit) {
    application.setAvailableResourceLimit(resourceLimit);
    metrics.setAvailableResourcesToUser(application.getUser(), resourceLimit);
  }
  
  private int roundUp(int memory) {
    return divideAndCeil(memory, minimumAllocation.getMemory()) * 
        minimumAllocation.getMemory();
  }
  
  private Resource computeUserLimit(SchedulerApp application, 
      Resource clusterResource, Resource required) {
    // What is our current capacity? 
    // * It is equal to the max(required, queue-capacity) if
    //   we're running below capacity. The 'max' ensures that jobs in queues
    //   with miniscule capacity (< 1 slot) make progress
    // * If we're running over capacity, then its
    //   (usedResources + required) (which extra resources we are allocating)

    // Allow progress for queues with miniscule capacity
    final int queueCapacity = 
      Math.max(
          roundUp((int)(absoluteCapacity * clusterResource.getMemory())), 
          required.getMemory());

    final int consumed = usedResources.getMemory();
    final int currentCapacity = 
      (consumed < queueCapacity) ? 
          queueCapacity : (consumed + required.getMemory());

    // Never allow a single user to take more than the 
    // queue's configured capacity * user-limit-factor.
    // Also, the queue's configured capacity should be higher than 
    // queue-hard-limit * ulMin

    String userName = application.getUser();
    
    final int activeUsers = users.size();  
    User user = getUser(userName);

    int limit = 
      roundUp(
          Math.min(
              Math.max(divideAndCeil(currentCapacity, activeUsers), 
                       divideAndCeil((int)userLimit*currentCapacity, 100)),
              (int)(queueCapacity * userLimitFactor)
              )
          );

    if (LOG.isDebugEnabled()) {
      LOG.debug("User limit computation for " + userName + 
          " in queue " + getQueueName() +
          " userLimit=" + userLimit +
          " userLimitFactor=" + userLimitFactor +
          " required: " + required + 
          " consumed: " + user.getConsumedResources() + 
          " limit: " + limit +
          " queueCapacity: " + queueCapacity + 
          " qconsumed: " + consumed +
          " currentCapacity: " + currentCapacity +
          " activeUsers: " + activeUsers +
          " clusterCapacity: " + clusterResource.getMemory()
      );
    }

    return Resources.createResource(limit);
  }
  
  private synchronized boolean assignToUser(String userName, Resource limit) {

    User user = getUser(userName);
    
    // Note: We aren't considering the current request since there is a fixed
    // overhead of the AM, but it's a >= check, so... 
    if ((user.getConsumedResources().getMemory()) > limit.getMemory()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("User " + userName + " in queue " + getQueueName() + 
            " will exceed limit - " +  
            " consumed: " + user.getConsumedResources() + 
            " limit: " + limit
        );
      }
      return false;
    }

    return true;
  }

  private static int divideAndCeil(int a, int b) {
    if (b == 0) {
      LOG.info("divideAndCeil called with a=" + a + " b=" + b);
      return 0;
    }
    return (a + (b - 1)) / b;
  }

  boolean needContainers(SchedulerApp application, Priority priority) {
    int requiredContainers = application.getTotalRequiredResources(priority);
    int reservedContainers = application.getNumReservedContainers(priority);
    return ((requiredContainers - reservedContainers) > 0);
  }

  private Resource assignContainersOnNode(Resource clusterResource, 
      SchedulerNode node, SchedulerApp application, 
      Priority priority, RMContainer reservedContainer) {

    Resource assigned = Resources.none();

    // Data-local
    assigned = 
        assignNodeLocalContainers(clusterResource, node, application, priority,
            reservedContainer); 
    if (Resources.greaterThan(assigned, Resources.none())) {
      return assigned;
    }

    // Rack-local
    assigned = 
        assignRackLocalContainers(clusterResource, node, application, priority, 
            reservedContainer);
    if (Resources.greaterThan(assigned, Resources.none())) {
      return assigned;
    }
    
    // Off-switch
    return assignOffSwitchContainers(clusterResource, node, application, 
        priority, reservedContainer);
  }

  private Resource assignNodeLocalContainers(Resource clusterResource, 
      SchedulerNode node, SchedulerApp application, 
      Priority priority, RMContainer reservedContainer) {
    ResourceRequest request = 
        application.getResourceRequest(priority, node.getHostName());
    if (request != null) {
      if (canAssign(application, priority, node, NodeType.NODE_LOCAL, 
          reservedContainer)) {
        return assignContainer(clusterResource, node, application, priority, 
            request, NodeType.NODE_LOCAL, reservedContainer);
      }
    }
    
    return Resources.none();
  }

  private Resource assignRackLocalContainers(Resource clusterResource,  
      SchedulerNode node, SchedulerApp application, Priority priority,
      RMContainer reservedContainer) {
    ResourceRequest request = 
      application.getResourceRequest(priority, node.getRackName());
    if (request != null) {
      if (canAssign(application, priority, node, NodeType.RACK_LOCAL, 
          reservedContainer)) {
        return assignContainer(clusterResource, node, application, priority, request, 
            NodeType.RACK_LOCAL, reservedContainer);
      }
    }
    return Resources.none();
  }

  private Resource assignOffSwitchContainers(Resource clusterResource, SchedulerNode node, 
      SchedulerApp application, Priority priority, 
      RMContainer reservedContainer) {
    ResourceRequest request = 
      application.getResourceRequest(priority, RMNode.ANY);
    if (request != null) {
      if (canAssign(application, priority, node, NodeType.OFF_SWITCH, 
          reservedContainer)) {
        return assignContainer(clusterResource, node, application, priority, request, 
            NodeType.OFF_SWITCH, reservedContainer);
      }
    }
    
    return Resources.none();
  }

  boolean canAssign(SchedulerApp application, Priority priority, 
      SchedulerNode node, NodeType type, RMContainer reservedContainer) {

    // Reserved... 
    if (reservedContainer != null) {
      return true;
    }
    
    // Clearly we need containers for this application...
    if (type == NodeType.OFF_SWITCH) {
      // 'Delay' off-switch
      ResourceRequest offSwitchRequest = 
          application.getResourceRequest(priority, RMNode.ANY);
      long missedOpportunities = application.getSchedulingOpportunities(priority);
      long requiredContainers = offSwitchRequest.getNumContainers(); 
      
      float localityWaitFactor = 
        application.getLocalityWaitFactor(priority, 
            scheduler.getNumClusterNodes());
      
      return ((requiredContainers * localityWaitFactor) > missedOpportunities);
    }

    // Check if we need containers on this rack 
    ResourceRequest rackLocalRequest = 
      application.getResourceRequest(priority, node.getRackName());
    if (type == NodeType.RACK_LOCAL) {
      if (rackLocalRequest == null) {
        return false;
      } else {
        return rackLocalRequest.getNumContainers() > 0;      
      }
    }

    // Check if we need containers on this host
    if (type == NodeType.NODE_LOCAL) {
      // First: Do we need containers on this rack?
      if (rackLocalRequest != null && rackLocalRequest.getNumContainers() == 0) {
        return false;
      }
      
      // Now check if we need containers on this host...
      ResourceRequest nodeLocalRequest = 
        application.getResourceRequest(priority, node.getHostName());
      if (nodeLocalRequest != null) {
        return nodeLocalRequest.getNumContainers() > 0;
      }
    }

    return false;
  }
  
  private Container getContainer(RMContainer rmContainer, 
      SchedulerApp application, SchedulerNode node, Resource capability) {
    return (rmContainer != null) ? rmContainer.getContainer() :
      createContainer(application, node, capability);
  }
  
  public Container createContainer(SchedulerApp application, SchedulerNode node, 
      Resource capability) {
    Container container = 
          BuilderUtils.newContainer(this.recordFactory,
              application.getApplicationAttemptId(),
              application.getNewContainerId(),
              node.getNodeID(),
              node.getHttpAddress(), capability);

    // If security is enabled, send the container-tokens too.
    if (UserGroupInformation.isSecurityEnabled()) {
      ContainerToken containerToken = 
          this.recordFactory.newRecordInstance(ContainerToken.class);
      ContainerTokenIdentifier tokenidentifier =
          new ContainerTokenIdentifier(container.getId(),
              container.getNodeId().toString(), container.getResource());
      containerToken.setIdentifier(
          ByteBuffer.wrap(tokenidentifier.getBytes()));
      containerToken.setKind(ContainerTokenIdentifier.KIND.toString());
      containerToken.setPassword(
          ByteBuffer.wrap(
              containerTokenSecretManager.createPassword(tokenidentifier))
          );
      containerToken.setService(container.getNodeId().toString());
      container.setContainerToken(containerToken);
    }

    return container;
  }
  
  private Resource assignContainer(Resource clusterResource, SchedulerNode node, 
      SchedulerApp application, Priority priority, 
      ResourceRequest request, NodeType type, RMContainer rmContainer) {
    if (LOG.isDebugEnabled()) {
      LOG.info("DEBUG --- assignContainers:" +
          " node=" + node.getHostName() + 
          " application=" + application.getApplicationId().getId() + 
          " priority=" + priority.getPriority() + 
          " request=" + request + " type=" + type);
    }
    Resource capability = request.getCapability();

    Resource available = node.getAvailableResource();

    assert (available.getMemory() >  0);

    // Create the container if necessary
    Container container = 
        getContainer(rmContainer, application, node, capability);

    // Can we allocate a container on this node?
    int availableContainers = 
        available.getMemory() / capability.getMemory();         
    if (availableContainers > 0) {
      // Allocate...

      // Did we previously reserve containers at this 'priority'?
      if (rmContainer != null){
        unreserve(application, priority, node, rmContainer);
      }

      // Inform the application
      RMContainer allocatedContainer = 
          application.allocate(type, node, priority, request, container);
      if (allocatedContainer == null) {
        // Did the application need this resource?
        return Resources.none();
      }

      // Inform the node
      node.allocateContainer(application.getApplicationId(), 
          allocatedContainer);

      LOG.info("assignedContainer" +
          " application=" + application.getApplicationId() +
          " container=" + container + 
          " containerId=" + container.getId() + 
          " queue=" + this + 
          " util=" + getUtilization() + 
          " used=" + usedResources + 
          " cluster=" + clusterResource);

      return container.getResource();
    } else {
      // Reserve by 'charging' in advance...
      reserve(application, priority, node, rmContainer, container);

      LOG.info("Reserved container " + 
          " application=" + application.getApplicationId() +
          " resource=" + request.getCapability() + 
          " queue=" + this.toString() + 
          " util=" + getUtilization() + 
          " used=" + usedResources + 
          " cluster=" + clusterResource);

      return request.getCapability();
    }
  }

  private void reserve(SchedulerApp application, Priority priority, 
      SchedulerNode node, RMContainer rmContainer, Container container) {
    rmContainer = application.reserve(node, priority, rmContainer, container);
    node.reserveResource(application, priority, rmContainer);
    
    // Update reserved metrics if this is the first reservation
    if (rmContainer == null) {
      getMetrics().reserveResource(
          application.getUser(), container.getResource());
    }
  }

  private void unreserve(SchedulerApp application, Priority priority, 
      SchedulerNode node, RMContainer rmContainer) {
    // Done with the reservation?
    application.unreserve(node, priority);
    node.unreserveResource(application);
      
      // Update reserved metrics
    getMetrics().unreserveResource(
        application.getUser(), rmContainer.getContainer().getResource());
  }


  @Override
  public void completedContainer(Resource clusterResource, 
      SchedulerApp application, SchedulerNode node, RMContainer rmContainer, 
      RMContainerEventType event) {
    if (application != null) {
      // Careful! Locking order is important!
      synchronized (this) {

        Container container = rmContainer.getContainer();
        
        // Inform the application & the node
        // Note: It's safe to assume that all state changes to RMContainer
        // happen under scheduler's lock... 
        // So, this is, in effect, a transaction across application & node
        if (rmContainer.getState() == RMContainerState.RESERVED) {
          application.unreserve(node, rmContainer.getReservedPriority());
          node.unreserveResource(application);
        } else {
          application.containerCompleted(rmContainer, event);
          node.releaseContainer(container);
        }


        // Book-keeping
        releaseResource(clusterResource, 
            application.getUser(), container.getResource());

        LOG.info("completedContainer" +
            " container=" + container +
            " resource=" + container.getResource() +
        		" queue=" + this + 
            " util=" + getUtilization() + 
            " used=" + usedResources + 
            " cluster=" + clusterResource);
      }

      // Inform the parent queue
      parent.completedContainer(clusterResource, application, 
          node, rmContainer, event);
    }
  }

  synchronized void allocateResource(Resource clusterResource, 
      String userName, Resource resource) {
    // Update queue metrics
    Resources.addTo(usedResources, resource);
    updateResource(clusterResource);
    ++numContainers;

    // Update user metrics
    User user = getUser(userName);
    user.assignContainer(resource);
    
    LOG.info(getQueueName() + 
        " used=" + usedResources + " numContainers=" + numContainers + 
        " user=" + userName + " resources=" + user.getConsumedResources());
  }

  synchronized void releaseResource(Resource clusterResource, 
      String userName, Resource resource) {
    // Update queue metrics
    Resources.subtractFrom(usedResources, resource);
    updateResource(clusterResource);
    --numContainers;

    // Update user metrics
    User user = getUser(userName);
    user.releaseContainer(resource);
    
    LOG.info(getQueueName() + 
        " used=" + usedResources + " numContainers=" + numContainers + 
        " user=" + userName + " resources=" + user.getConsumedResources());
  }

  @Override
  public synchronized void updateResource(Resource clusterResource) {
    float queueLimit = clusterResource.getMemory() * absoluteCapacity; 
    setUtilization(usedResources.getMemory() / queueLimit);
    setUsedCapacity(
        usedResources.getMemory() / (clusterResource.getMemory() * capacity));
    
    Resource resourceLimit = 
      Resources.createResource((int)queueLimit);
    metrics.setAvailableResourcesToQueue(
        Resources.subtractFrom(resourceLimit, usedResources));
  }

  @Override
  public QueueMetrics getMetrics() {
    return metrics;
  }

  static class User {
    Resource consumed = Resources.createResource(0);
    int applications = 0;

    public Resource getConsumedResources() {
      return consumed;
    }

    public int getApplications() {
      return applications;
    }

    public synchronized void submitApplication() {
      ++applications;
    }

    public synchronized void finishApplication() {
      --applications;
    }

    public synchronized void assignContainer(Resource resource) {
      Resources.addTo(consumed, resource);
    }

    public synchronized void releaseContainer(Resource resource) {
      Resources.subtractFrom(consumed, resource);
    }
  }

  @Override
  public void recoverContainer(Resource clusterResource,
      SchedulerApp application, Container container) {
    // Careful! Locking order is important! 
    synchronized (this) {
      allocateResource(clusterResource, application.getUser(), container.getResource());
    }
    parent.recoverContainer(clusterResource, application, container);

  }
}