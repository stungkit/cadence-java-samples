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

import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_CANCELLED;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_DELIVERED;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_PAYMENT_APPROVED;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_PENDING_PAYMENT;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_READY_TO_SHIP;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_REFUNDED;
import static com.uber.cadence.samples.query.OrderFulfillmentModels.STATUS_SHIPPED;
import static com.uber.cadence.samples.query.QueryConstants.CLUSTER;
import static com.uber.cadence.samples.query.QueryConstants.DOMAIN;
import static com.uber.cadence.samples.query.QueryConstants.TASK_LIST;

import com.uber.cadence.workflow.QueryMethod;
import com.uber.cadence.workflow.SignalMethod;
import com.uber.cadence.workflow.Workflow;
import com.uber.cadence.workflow.WorkflowMethod;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Demonstrates a state-machine workflow where the Markdoc query named {@code dashboard} acts as an
 * ops admin panel in Cadence Web (v4.0.14+). Buttons change based on order status, and each signal
 * drives a state transition.
 */
public final class OrderFulfillmentWorkflow {

  private OrderFulfillmentWorkflow() {}

  /**
   * Dashboard pattern: one query method renders the full markdown UI (tables, status, action
   * buttons), and multiple signal methods drive state transitions on the order. The {@code name}
   * on each {@code @SignalMethod} must match the {@code signalName} in the Markdoc template;
   * without {@code name}, the Java SDK would default to {@code WorkflowIface::methodName}.
   */
  public interface WorkflowIface {

    @WorkflowMethod(
        name = QueryConstants.ORDER_FULFILLMENT_WORKFLOW_TYPE,
        executionStartToCloseTimeoutSeconds = 3600,
        taskList = TASK_LIST)
    void run();

    /** Visible as "dashboard" in the Cadence Web Query dropdown. */
    @QueryMethod(name = "dashboard")
    MarkdownFormattedResponse dashboardQuery();

    @SignalMethod(name = "approve_payment")
    void approvePayment(OrderFulfillmentModels.ApprovePaymentSignal signal);

    @SignalMethod(name = "reject_payment")
    void rejectPayment(OrderFulfillmentModels.RejectPaymentSignal signal);

    @SignalMethod(name = "mark_ready_to_ship")
    void markReadyToShip(OrderFulfillmentModels.SimpleSignal signal);

    @SignalMethod(name = "ship_order")
    void shipOrder(OrderFulfillmentModels.ShipOrderSignal signal);

    @SignalMethod(name = "issue_refund")
    void issueRefund(OrderFulfillmentModels.RefundSignal signal);

    @SignalMethod(name = "cancel_order")
    void cancelOrder(OrderFulfillmentModels.CancelOrderSignal signal);

    @SignalMethod(name = "mark_delivered")
    void markDelivered(OrderFulfillmentModels.SimpleSignal signal);
  }

  public static final class WorkflowImpl implements WorkflowIface {

    private static final DateTimeFormatter CREATED_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HISTORY_TIME_FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private final OrderFulfillmentModels.Order order = new OrderFulfillmentModels.Order();
    private final List<OrderFulfillmentModels.ActionLogEntry> actionLog = new ArrayList<>();

    /**
     * Inbox for signal-to-main-loop communication. Signal handlers (which execute on the workflow
     * thread but outside the main loop) enqueue messages here. The main {@link #run()} loop
     * drains the inbox one message at a time, keeping state transitions sequential and
     * deterministic.
     */
    private final ArrayDeque<Object> inbox = new ArrayDeque<>();

    /** Cached in constructor (workflow thread); queries must not call {@link Workflow#getWorkflowInfo()}. */
    private final String cachedWorkflowId;

    private final String cachedRunId;

    /** Inbox wrapper so {@code mark_ready_to_ship} vs {@code mark_delivered} are not ambiguous. */
    private static final class ReadyToShipMessage {
      final OrderFulfillmentModels.SimpleSignal signal;

      ReadyToShipMessage(OrderFulfillmentModels.SimpleSignal signal) {
        this.signal = signal;
      }
    }

    private static final class MarkDeliveredMessage {
      final OrderFulfillmentModels.SimpleSignal signal;

      MarkDeliveredMessage(OrderFulfillmentModels.SimpleSignal signal) {
        this.signal = signal;
      }
    }

    public WorkflowImpl() {
      cachedWorkflowId = Workflow.getWorkflowInfo().getWorkflowId();
      cachedRunId = Workflow.getWorkflowInfo().getRunId();
      order.createdAtMillis = Workflow.currentTimeMillis();
      OrderFulfillmentModels.ActionLogEntry created = new OrderFulfillmentModels.ActionLogEntry();
      created.timestampMillis = order.createdAtMillis;
      created.action = "Order Created";
      created.operator = "System";
      created.details =
          String.format(
              Locale.US, "Order %s created for %s", order.orderID, order.customerName);
      actionLog.add(created);
    }

    @Override
    public void run() {
      // Main loop: block until a signal arrives in the inbox, dispatch it to update order state,
      // then check if the order reached a terminal state (delivered, cancelled, or refunded).
      while (!isTerminalState(order.status)) {
        Workflow.await(() -> !inbox.isEmpty());
        Object msg = inbox.removeFirst();
        if (msg instanceof OrderFulfillmentModels.ApprovePaymentSignal) {
          handleApprove((OrderFulfillmentModels.ApprovePaymentSignal) msg);
        } else if (msg instanceof OrderFulfillmentModels.RejectPaymentSignal) {
          handleReject((OrderFulfillmentModels.RejectPaymentSignal) msg);
        } else if (msg instanceof ReadyToShipMessage) {
          handleMarkReady(((ReadyToShipMessage) msg).signal);
        } else if (msg instanceof MarkDeliveredMessage) {
          handleMarkDelivered(((MarkDeliveredMessage) msg).signal);
        } else if (msg instanceof OrderFulfillmentModels.ShipOrderSignal) {
          handleShip((OrderFulfillmentModels.ShipOrderSignal) msg);
        } else if (msg instanceof OrderFulfillmentModels.RefundSignal) {
          handleRefund((OrderFulfillmentModels.RefundSignal) msg);
        } else if (msg instanceof OrderFulfillmentModels.CancelOrderSignal) {
          handleCancel((OrderFulfillmentModels.CancelOrderSignal) msg);
        }
      }
    }

    private void handleApprove(OrderFulfillmentModels.ApprovePaymentSignal signal) {
      if (!STATUS_PENDING_PAYMENT.equals(order.status)) {
        return;
      }
      order.status = STATUS_PAYMENT_APPROVED;
      actionLog.add(
          entry(
              "Payment Approved",
              getOperator(signal.operator),
              String.format(Locale.US, "Payment of $%.2f approved", order.totalAmount)));
    }

    private void handleReject(OrderFulfillmentModels.RejectPaymentSignal signal) {
      if (!STATUS_PENDING_PAYMENT.equals(order.status)) {
        return;
      }
      order.status = STATUS_CANCELLED;
      actionLog.add(
          entry(
              "Payment Rejected",
              getOperator(signal.operator),
              String.format(Locale.US, "Reason: %s", signal.reason)));
    }

    private void handleMarkReady(OrderFulfillmentModels.SimpleSignal signal) {
      if (!STATUS_PAYMENT_APPROVED.equals(order.status)) {
        return;
      }
      order.status = STATUS_READY_TO_SHIP;
      actionLog.add(
          entry(
              "Marked Ready to Ship",
              getOperator(signal.operator),
              "Order prepared and ready for shipping"));
    }

    private void handleShip(OrderFulfillmentModels.ShipOrderSignal signal) {
      if (!STATUS_READY_TO_SHIP.equals(order.status)) {
        return;
      }
      order.status = STATUS_SHIPPED;
      order.trackingNum = signal.trackingNumber;
      order.carrier = signal.carrier;
      actionLog.add(
          entry(
              "Order Shipped",
              getOperator(signal.operator),
              String.format(
                  Locale.US,
                  "Carrier: %s, Tracking: %s",
                  signal.carrier,
                  signal.trackingNumber)));
    }

    private void handleRefund(OrderFulfillmentModels.RefundSignal signal) {
      if (!STATUS_PAYMENT_APPROVED.equals(order.status)
          && !STATUS_SHIPPED.equals(order.status)) {
        return;
      }
      order.status = STATUS_REFUNDED;
      order.refundAmount = signal.amount;
      order.refundReason = signal.reason;
      actionLog.add(
          entry(
              "Refund Issued",
              getOperator(signal.operator),
              String.format(
                  Locale.US, "Amount: $%.2f, Reason: %s", signal.amount, signal.reason)));
    }

    private void handleCancel(OrderFulfillmentModels.CancelOrderSignal signal) {
      if (!STATUS_READY_TO_SHIP.equals(order.status)) {
        return;
      }
      order.status = STATUS_CANCELLED;
      actionLog.add(
          entry(
              "Order Cancelled",
              getOperator(signal.operator),
              String.format(Locale.US, "Reason: %s", signal.reason)));
    }

    private void handleMarkDelivered(OrderFulfillmentModels.SimpleSignal signal) {
      if (!STATUS_SHIPPED.equals(order.status)) {
        return;
      }
      order.status = STATUS_DELIVERED;
      actionLog.add(
          entry(
              "Order Delivered",
              getOperator(signal.operator),
              "Package confirmed delivered to customer"));
    }

    private OrderFulfillmentModels.ActionLogEntry entry(String action, String operator, String details) {
      OrderFulfillmentModels.ActionLogEntry e = new OrderFulfillmentModels.ActionLogEntry();
      e.timestampMillis = Workflow.currentTimeMillis();
      e.action = action;
      e.operator = operator;
      e.details = details;
      return e;
    }

    @Override
    public MarkdownFormattedResponse dashboardQuery() {
      return new MarkdownFormattedResponse(makeOrderDashboard());
    }

    @Override
    public void approvePayment(OrderFulfillmentModels.ApprovePaymentSignal signal) {
      inbox.add(signal);
    }

    @Override
    public void rejectPayment(OrderFulfillmentModels.RejectPaymentSignal signal) {
      inbox.add(signal);
    }

    @Override
    public void markReadyToShip(OrderFulfillmentModels.SimpleSignal signal) {
      inbox.add(new ReadyToShipMessage(signal));
    }

    @Override
    public void shipOrder(OrderFulfillmentModels.ShipOrderSignal signal) {
      inbox.add(signal);
    }

    @Override
    public void issueRefund(OrderFulfillmentModels.RefundSignal signal) {
      inbox.add(signal);
    }

    @Override
    public void cancelOrder(OrderFulfillmentModels.CancelOrderSignal signal) {
      inbox.add(signal);
    }

    @Override
    public void markDelivered(OrderFulfillmentModels.SimpleSignal signal) {
      inbox.add(new MarkDeliveredMessage(signal));
    }

    private String makeOrderDashboard() {
      String workflowId = cachedWorkflowId;
      String runId = cachedRunId;
      String createdAt = CREATED_FMT.format(Instant.ofEpochMilli(order.createdAtMillis));
      String statusBadge = getStatusBadge(order.status);
      String itemsTable = makeItemsTable(order);
      String actionButtons = makeActionButtons(workflowId, runId, order);
      String actionHistory = makeActionHistory(actionLog);

      String trackingRow = "";
      if (order.trackingNum != null && !order.trackingNum.isEmpty()) {
        trackingRow =
            "\n| **Tracking** | "
                + order.carrier
                + " - "
                + order.trackingNum
                + " |";
      }
      String refundRow = "";
      if (order.refundAmount > 0) {
        refundRow =
            "\n| **Refund** | $"
                + String.format(Locale.US, "%.2f", order.refundAmount)
                + " |";
      }

      return "\n## 🛒 Order Dashboard\n\n"
          + "> **Your admin panel** - manage orders directly from Cadence Web.\n\n"
          + "---\n\n"
          + "### ⚡ Available Actions\n"
          + actionButtons
          + "\n---\n\n"
          + "### 📋 Order Details\n\n"
          + "| Field | Value |\n"
          + "|-------|-------|\n"
          + "| **Order ID** | "
          + order.orderID
          + " |\n"
          + "| **Customer** | "
          + order.customerName
          + " |\n"
          + "| **Email** | "
          + order.customerEmail
          + " |\n"
          + "| **Created** | "
          + createdAt
          + " |\n"
          + "| **Status** | "
          + statusBadge
          + " |"
          + trackingRow
          + refundRow
          + "\n\n"
          + "### 📦 Order Items\n\n"
          + "| Item | Qty | Price | Subtotal |\n"
          + "|------|-----|-------|----------|\n"
          + itemsTable
          + "\n**Total: $"
          + String.format(Locale.US, "%.2f", order.totalAmount)
          + "**\n\n"
          + "---\n\n"
          + "### ⏱️ Action History\n\n"
          + "| Timestamp | Action | Operator | Details |\n"
          + "|-----------|--------|----------|---------|\n"
          + actionHistory
          + "\n---\n\n"
          + "*Click query \"Run\" button again to see updated status after taking an action.*\n";
    }

    private static boolean isTerminalState(String status) {
      return STATUS_DELIVERED.equals(status)
          || STATUS_CANCELLED.equals(status)
          || STATUS_REFUNDED.equals(status);
    }

    private static String getOperator(String operator) {
      if (operator == null || operator.isEmpty()) {
        return "ops-user";
      }
      return operator;
    }

    private static String getStatusBadge(String status) {
      switch (status) {
        case STATUS_PENDING_PAYMENT:
          return "🟡 **Pending Payment**";
        case STATUS_PAYMENT_APPROVED:
          return "🟢 **Payment Approved**";
        case STATUS_READY_TO_SHIP:
          return "📦 **Ready to Ship**";
        case STATUS_SHIPPED:
          return "🚚 **Shipped**";
        case STATUS_DELIVERED:
          return "✅ **Delivered**";
        case STATUS_CANCELLED:
          return "❌ **Cancelled**";
        case STATUS_REFUNDED:
          return "💰 **Refunded**";
        default:
          return status;
      }
    }

    private static String makeItemsTable(OrderFulfillmentModels.Order order) {
      StringBuilder table = new StringBuilder();
      for (OrderFulfillmentModels.OrderItem item : order.items) {
        double subtotal = item.quantity * item.price;
        table.append(
            String.format(
                Locale.US,
                "| %s | %d | $%.2f | $%.2f |\n",
                item.name, item.quantity, item.price, subtotal));
      }
      return table.toString();
    }

    private static String makeActionHistory(List<OrderFulfillmentModels.ActionLogEntry> actionLog) {
      StringBuilder history = new StringBuilder();
      for (OrderFulfillmentModels.ActionLogEntry entry : actionLog) {
        String t = HISTORY_TIME_FMT.format(Instant.ofEpochMilli(entry.timestampMillis));
        history
            .append("| ")
            .append(t)
            .append(" | ")
            .append(entry.action)
            .append(" | ")
            .append(entry.operator)
            .append(" | ")
            .append(entry.details)
            .append(" |\n");
      }
      return history.toString();
    }

    /**
     * Key to the "state-driven UI" pattern: the set of rendered Markdoc buttons depends on the
     * current order status. For example, shipping options only appear when the order is
     * {@code ready_to_ship}, and approval buttons only appear when {@code pending_payment}.
     */
    private static String makeActionButtons(
        String workflowId, String runId, OrderFulfillmentModels.Order order) {
      switch (order.status) {
        case STATUS_PENDING_PAYMENT:
          return "\n**Payment Review:**\n\n"
              + sig(
                  "approve_payment",
                  "✓ Approve Payment",
                  workflowId,
                  runId,
                  "{\"operator\":\"ops-user\"}")
              + sig(
                  "reject_payment",
                  "✗ Reject: Policy Violation",
                  workflowId,
                  runId,
                  "{\"reason\":\"Policy Violation\",\"operator\":\"ops-user\"}")
              + sig(
                  "reject_payment",
                  "✗ Reject: Fraud Suspected",
                  workflowId,
                  runId,
                  "{\"reason\":\"Fraud Suspected\",\"operator\":\"ops-user\"}")
              + sig(
                  "reject_payment",
                  "✗ Reject: Customer Request",
                  workflowId,
                  runId,
                  "{\"reason\":\"Customer Request\",\"operator\":\"ops-user\"}")
              + "\n";

        case STATUS_PAYMENT_APPROVED:
          return "\n**Fulfillment Actions:**\n\n"
              + sig(
                  "mark_ready_to_ship",
                  "📦 Mark Ready to Ship",
                  workflowId,
                  runId,
                  "{\"operator\":\"ops-user\"}")
              + "\n**Refund Options:**\n\n"
              + sig(
                  "issue_refund",
                  "💰 Full Refund ($"
                      + String.format(Locale.US, "%.2f", order.totalAmount)
                      + ")",
                  workflowId,
                  runId,
                  String.format(
                      Locale.US,
                      "{\"amount\":%.2f,\"reason\":\"Full refund requested\",\"operator\":\"ops-user\"}",
                      order.totalAmount))
              + sig(
                  "issue_refund",
                  "💰 Partial Refund (50%)",
                  workflowId,
                  runId,
                  String.format(
                      Locale.US,
                      "{\"amount\":%.2f,\"reason\":\"Partial refund - customer goodwill\",\"operator\":\"ops-user\"}",
                      order.totalAmount / 2))
              + "\n";

        case STATUS_READY_TO_SHIP:
          return "\n**Shipping Options:**\n\n"
              + sig(
                  "ship_order",
                  "🚚 Ship via UPS",
                  workflowId,
                  runId,
                  "{\"trackingNumber\":\"1Z999AA10123456784\",\"carrier\":\"UPS\",\"operator\":\"ops-user\"}")
              + sig(
                  "ship_order",
                  "🚚 Ship via FedEx",
                  workflowId,
                  runId,
                  "{\"trackingNumber\":\"794644790126\",\"carrier\":\"FedEx\",\"operator\":\"ops-user\"}")
              + sig(
                  "ship_order",
                  "🚚 Ship via USPS",
                  workflowId,
                  runId,
                  "{\"trackingNumber\":\"9400111899223456789012\",\"carrier\":\"USPS\",\"operator\":\"ops-user\"}")
              + "\n**Cancel Order:**\n\n"
              + sig(
                  "cancel_order",
                  "❌ Cancel Order",
                  workflowId,
                  runId,
                  "{\"reason\":\"Cancelled before shipping\",\"operator\":\"ops-user\"}")
              + "\n";

        case STATUS_SHIPPED:
          return "\n**Delivery Confirmation:**\n\n"
              + sig(
                  "mark_delivered",
                  "✅ Mark as Delivered",
                  workflowId,
                  runId,
                  "{\"operator\":\"ops-user\"}")
              + "\n**Refund Options:**\n\n"
              + sig(
                  "issue_refund",
                  "💰 Full Refund ($"
                      + String.format(Locale.US, "%.2f", order.totalAmount)
                      + ")",
                  workflowId,
                  runId,
                  String.format(
                      Locale.US,
                      "{\"amount\":%.2f,\"reason\":\"Full refund requested\",\"operator\":\"ops-user\"}",
                      order.totalAmount))
              + "\n";

        default:
          return "\n*No actions available - order has been completed.*\n";
      }
    }

    /** Builds a Markdoc {@code {%- signal -%}} tag targeting this workflow execution. */
    private static String sig(
        String signalName, String label, String workflowId, String runId, String jsonInput) {
      return "{% signal \n"
          + "\tsignalName=\""
          + signalName
          + "\" \n"
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
  }
}
