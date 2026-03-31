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

import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Demonstrates interactive voting via Cadence Web (v4.0.14+). Signals accumulate votes and the
 * query named {@code options} renders current results and vote buttons as markdown.
 */
public final class LunchVoteWorkflow {

  private LunchVoteWorkflow() {}

  /**
   * Signal payload for a lunch vote. Public fields are required so Cadence's JSON data converter
   * can deserialize the signal input. Field names must match the JSON keys in the Markdoc
   * {@code input=} attribute (e.g. {@code input={"location":"Farmhouse","meal":"Red Thai Curry"}}).
   */
  public static class LunchOrder {
    public String location;
    public String meal;
    public String requests;

    /** No-arg constructor required for JSON deserialization. */
    public LunchOrder() {}

    public LunchOrder(String location, String meal, String requests) {
      this.location = location;
      this.meal = meal;
      this.requests = requests;
    }
  }

  /**
   * The voting pattern: the workflow method keeps the execution open for a voting window, signals
   * mutate state (accumulate votes), and the query reads that state to render the current results.
   */
  public interface WorkflowIface {

    @WorkflowMethod(
        name = QueryConstants.LUNCH_VOTE_WORKFLOW_TYPE,
        executionStartToCloseTimeoutSeconds = 700,
        taskList = TASK_LIST)
    void run();

    /** Visible as "options" in the Cadence Web Query dropdown. */
    @QueryMethod(name = "options")
    MarkdownFormattedResponse optionsQuery();

    /**
     * {@code name} sets the signal type string the worker listens for. It must match the
     * {@code signalName} attribute in the Markdoc template so Cadence Web sends the right signal.
     */
    @SignalMethod(name = "lunch_order")
    void lunchOrder(LunchOrder vote);
  }

  public static final class WorkflowImpl implements WorkflowIface {

    private final List<LunchOrder> votes = new ArrayList<>();

    private String cachedWorkflowId = "";
    private String cachedRunId = "";

    @Override
    public void run() {
      cachedWorkflowId = Workflow.getWorkflowInfo().getWorkflowId();
      cachedRunId = Workflow.getWorkflowInfo().getRunId();
      // Keep the workflow open for the voting period. Votes arrive via the lunchOrder signal
      // while the workflow sleeps; Workflow.sleep is interruptible by signals.
      Workflow.sleep(Duration.ofMinutes(10));
    }

    @Override
    public MarkdownFormattedResponse optionsQuery() {
      String workflowId = cachedWorkflowId;
      String runId = cachedRunId;
      String voteTable = makeLunchVoteTable(votes);
      String menuTable = makeLunchMenu();

      String data =
          "\n## Lunch Options\n\n"
              + "We're voting on where to order lunch today. Select the option you want to vote for.\n\n"
              + "---\n\n"
              + "### Current Votes\n\n"
              + voteTable
              + "\n"
              + "### Menu Options\n\n"
              + menuTable
              + "\n\n---\n\n"
              + "### Cast Your Vote\n\n"
              + signalBlock(
                  workflowId,
                  runId,
                  "Farmhouse - Red Thai Curry",
                  "{\"location\":\"Farmhouse\",\"meal\":\"Red Thai Curry\",\"requests\":\"spicy\"}")
              + signalBlock(
                  workflowId,
                  runId,
                  "Ethiopian Wat",
                  "{\"location\":\"Ethiopian\",\"meal\":\"Wat with Injera\",\"requests\":\"\"}")
              + signalBlock(
                  workflowId,
                  runId,
                  "Ler Ros - Tofu Bahn Mi",
                  "{\"location\":\"Ler Ros\",\"meal\":\"Tofu Bahn Mi\",\"requests\":\"\"}")
              + "\n{% br /%}\n\n"
              + "*Vote closes when workflow times out (10 minutes)*\n\t";

      return new MarkdownFormattedResponse(data);
    }

    /** Builds a Markdoc {@code {%- signal -%}} tag. Every attribute is required for Cadence Web
     *  to route the signal to the correct workflow execution. */
    private static String signalBlock(
        String workflowId, String runId, String label, String jsonInput) {
      return "{% signal \n"
          + "\tsignalName=\"lunch_order\" \n"
          + "\tlabel=\""
          + label
          + "\"\n"
          + "\tdomain=\""
          + DOMAIN
          + "\"\n"
          + "\tcluster=\""
          + CLUSTER
          + "\"\n"
          + "\tworkflowId=\""
          + workflowId
          + "\"\n"
          + "\trunId=\""
          + runId
          + "\"\n"
          + "\tinput="
          + jsonInput
          + "\n/%}\n";
    }

    @Override
    public void lunchOrder(LunchOrder vote) {
      votes.add(vote);
    }

    private static String makeLunchVoteTable(List<LunchOrder> votes) {
      if (votes.isEmpty()) {
        return "| Location | Meal | Requests |\n|----------|------|----------|\n| *No votes yet* | | |\n";
      }
      StringBuilder table = new StringBuilder();
      table.append("| Location | Meal | Requests |\n|----------|------|----------|\n");
      for (LunchOrder vote : votes) {
        table
            .append("| ")
            .append(vote.location != null ? vote.location : "")
            .append(" | ")
            .append(vote.meal != null ? vote.meal : "")
            .append(" | ")
            .append(vote.requests != null ? vote.requests : "")
            .append(" |\n");
      }
      return table.toString();
    }

    private static String makeLunchMenu() {
      return "| Picture | Description |\n"
          + "|---------|-------------|\n"
          + "| {% image src=\"https://upload.wikimedia.org/wikipedia/commons/thumb/e/e2/Red_roast_duck_curry.jpg/200px-Red_roast_duck_curry.jpg\" alt=\"Red Thai Curry\" width=\"200\" /%} | **Farmhouse - Red Thai Curry**: A dish in Thai cuisine made from curry paste, coconut milk, meat, seafood, vegetables, and herbs. |\n"
          + "| {% image src=\"https://upload.wikimedia.org/wikipedia/commons/thumb/0/0c/B%C3%A1nh_m%C3%AC_th%E1%BB%8Bt_n%C6%B0%E1%BB%9Bng.png/200px-B%C3%A1nh_m%C3%AC_th%E1%BB%8Bt_n%C6%B0%E1%BB%9Bng.png\" alt=\"Tofu Bahn Mi\" width=\"200\" /%} | **Ler Ros - Tofu Bahn Mi**: A Vietnamese sandwich with a baguette filled with lemongrass tofu, vegetables, and fresh herbs. |\n"
          + "| {% image src=\"https://upload.wikimedia.org/wikipedia/commons/thumb/5/54/Ethiopian_wat.jpg/960px-Ethiopian_wat.jpg\" alt=\"Ethiopian Wat\" width=\"200\" /%} | **Ethiopian Wat**: A traditional Ethiopian stew made from spices, vegetables, and legumes, served with injera flatbread. |\n"
          + "\n*(source: wikipedia)*";
    }
  }
}
