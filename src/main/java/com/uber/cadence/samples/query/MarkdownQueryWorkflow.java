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

import static com.uber.cadence.samples.query.QueryConstants.CLUSTER;
import static com.uber.cadence.samples.query.QueryConstants.DOMAIN;
import static com.uber.cadence.samples.query.QueryConstants.TASK_LIST;

import com.uber.cadence.activity.ActivityMethod;
import com.uber.cadence.activity.ActivityOptions;
import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;

/**
 * Demonstrates a workflow whose query response is interactive markdown rendered by Cadence Web
 * (v4.0.14+). The query named {@code Signal} returns Markdoc with buttons that can signal this
 * workflow or start a new one.
 */
public final class MarkdownQueryWorkflow {

  private MarkdownQueryWorkflow() {}

  /**
   * Workflow contract combining all three Cadence interaction patterns used in this sample:
   *
   * <ul>
   *   <li>{@code @WorkflowMethod} — the entry point; loops waiting for signals.
   *   <li>{@code @QueryMethod} — returns a {@link MarkdownFormattedResponse} so Cadence Web
   *       renders interactive markdown instead of raw JSON.
   *   <li>{@code @SignalMethod} — receives external input (from Markdoc buttons or the CLI).
   * </ul>
   */
  public interface WorkflowIface {

    @WorkflowMethod(
        name = QueryConstants.MARKDOWN_QUERY_WORKFLOW_TYPE,
        executionStartToCloseTimeoutSeconds = 3600,
        taskList = TASK_LIST)
    void run();

    /**
     * The {@code name} attribute sets the query type visible in the Cadence Web "Query" dropdown.
     * Users select "Signal" and click Run to see the markdown.
     */
    @QueryMethod(name = "Signal")
    MarkdownFormattedResponse signalQuery();

    /**
     * Receives the "complete" / "continue" signal from Markdoc buttons. {@code true} means finish
     * the workflow; {@code false} means continue looping.
     *
     * <p>The Markdoc {@code signalName} must use the qualified form {@link
     * QueryConstants#MARKDOWN_QUERY_COMPLETE_SIGNAL_MARKDOC} ({@code WorkflowIface::complete})
     * because the Java SDK registers signals as {@code InterfaceName::methodName} by default.
     */
    @SignalMethod
    void complete(boolean value);
  }

  public interface MarkdownQueryActivities {
    @ActivityMethod(scheduleToCloseTimeoutSeconds = 3600)
    String markdownQueryActivity(boolean complete);
  }

  public static final class WorkflowImpl implements WorkflowIface {

    private final MarkdownQueryActivities activities =
        Workflow.newActivityStub(
            MarkdownQueryActivities.class,
            new ActivityOptions.Builder()
                .setScheduleToStartTimeout(Duration.ofHours(1))
                .setStartToCloseTimeout(Duration.ofHours(1))
                .build());

    private boolean hasCompleteSignal;
    private boolean completeFlag;

    /**
     * Query handlers run on a different thread in the Java SDK and must not call {@link
     * Workflow#getWorkflowInfo()} or {@link Workflow#currentTimeMillis()}. Cache these on the
     * workflow thread in {@link #run()}.
     */
    private String cachedWorkflowId = "";

    private String cachedRunId = "";

    /** Suggested id for {% start %}; refreshed on the workflow thread after each activity. */
    private String suggestedNewWorkflowId = "markdown-pending";

    @Override
    public void run() {
      cacheExecutionIds();
      refreshSuggestedStartWorkflowId();

      // Signal-wait-activity loop:
      //  1. Block until a signal arrives (Complete or Continue).
      //  2. Run an activity to acknowledge the signal.
      //  3. If the signal was "complete" (true), exit; otherwise loop back.
      while (true) {
        Workflow.await(() -> hasCompleteSignal);
        hasCompleteSignal = false;
        activities.markdownQueryActivity(completeFlag);
        refreshSuggestedStartWorkflowId();
        if (completeFlag) {
          return;
        }
      }
    }

    private void cacheExecutionIds() {
      cachedWorkflowId = Workflow.getWorkflowInfo().getWorkflowId();
      cachedRunId = Workflow.getWorkflowInfo().getRunId();
    }

    private void refreshSuggestedStartWorkflowId() {
      suggestedNewWorkflowId = "markdown-" + Workflow.currentTimeMillis();
    }

    @Override
    public MarkdownFormattedResponse signalQuery() {
      String workflowId = cachedWorkflowId;
      String runId = cachedRunId;
      String newWorkflowId = suggestedNewWorkflowId;

      // Build the markdown string using Markdoc tags that Cadence Web renders as interactive
      // controls:
      //   {% signal %}  — button that sends a signal to a running workflow
      //   {% start %}   — button that starts a new workflow execution
      //   {% br %}      — line break
      //   {% image %}   — inline image
      // Each tag requires domain, workflowId, and runId so Cadence Web targets the right
      // execution.
      String data =
          "\n\t## Markdown Query Workflow\n\t\n\t"
              + "You can use markdown as your query response, which also supports starting and signaling workflows.\n\t\n\t"
              + "* Use the Complete button to complete this workflow.\n\t"
              + "* Use the Continue button just to send a signal to continue this workflow.\n\t"
              + "* Or you can use the \"Start Another\" button to start another workflow of this type.\n\t\n\t"
              + "{% signal \n\t\tsignalName=\""
              + QueryConstants.MARKDOWN_QUERY_COMPLETE_SIGNAL_MARKDOC
              + "\" \n\t\tlabel=\"Complete\"\n\t\tdomain=\""
              + DOMAIN
              + "\"\n\t\tcluster=\""
              + CLUSTER
              + "\"\n\t\tworkflowId=\""
              + workflowId
              + "\"\n\t\trunId=\""
              + runId
              + "\"\n\t\tinput=true\n\t/%}\n\t"
              + "{% signal\n\t\tsignalName=\""
              + QueryConstants.MARKDOWN_QUERY_COMPLETE_SIGNAL_MARKDOC
              + "\" \n\t\tlabel=\"Continue\"\n\t\tdomain=\""
              + DOMAIN
              + "\"\n\t\tcluster=\""
              + CLUSTER
              + "\"\n\t\tworkflowId=\""
              + workflowId
              + "\"\n\t\trunId=\""
              + runId
              + "\"\n\t\tinput=false\n\t/%}\n\t"
              + "{% start\n\t\tworkflowType=\""
              + QueryConstants.MARKDOWN_QUERY_WORKFLOW_TYPE
              + "\" \n\t\tlabel=\"Start Another\"\n\t\tdomain=\""
              + DOMAIN
              + "\"\n\t\tcluster=\""
              + CLUSTER
              + "\"\n\t\ttaskList=\""
              + TASK_LIST
              + "\"\n\t\tworkflowId=\""
              + newWorkflowId
              + "\"\n\t\ttimeoutSeconds=60\n\t/%}\n\t\n\t"
              + "{% br /%} \n\t"
              + "{% image src=\"https://cadenceworkflow.io/img/cadence-logo.svg\" alt=\"Cadence Logo\" height=\"100\" /%}\n\t\t";

      return new MarkdownFormattedResponse(data);
    }

    @Override
    public void complete(boolean value) {
      this.completeFlag = value;
      this.hasCompleteSignal = true;
    }
  }

  public static final class MarkdownQueryActivitiesImpl implements MarkdownQueryActivities {
    @Override
    public String markdownQueryActivity(boolean complete) {
      if (complete) {
        return "Workflow will complete now";
      }
      return "Workflow will continue to run";
    }
  }
}
