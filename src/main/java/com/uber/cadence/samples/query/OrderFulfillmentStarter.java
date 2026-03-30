/*
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
 */

package com.uber.cadence.samples.query;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowOptions;
import java.time.Duration;
import java.util.UUID;

/** Starts an {@link OrderFulfillmentWorkflow} execution. Use Cadence Web Query tab → dashboard. */
public final class OrderFulfillmentStarter {

  private OrderFulfillmentStarter() {}

  public static void main(String[] args) {
    try {
      WorkflowClient workflowClient = QuerySampleSupport.newWorkflowClient();

      WorkflowOptions options =
          new WorkflowOptions.Builder()
              .setTaskList(QueryConstants.TASK_LIST)
              .setExecutionStartToCloseTimeout(Duration.ofHours(1))
              .setWorkflowId("order-fulfillment-" + UUID.randomUUID())
              .build();

      OrderFulfillmentWorkflow.WorkflowIface workflow =
          workflowClient.newWorkflowStub(OrderFulfillmentWorkflow.WorkflowIface.class, options);

      // Starts the workflow asynchronously — the starter can exit immediately.
      WorkflowClient.start(workflow::run);
      System.out.println(
          "Started OrderFulfillmentWorkflow. In Cadence Web (v4.0.14+), open the run, Query tab → dashboard.");
      System.exit(0);
    } catch (RuntimeException e) {
      if (QuerySampleSupport.printHintIfDomainMissing(e)) {
        System.exit(1);
      }
      throw e;
    }
  }
}
