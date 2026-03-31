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

/** Domain model and signal payloads for {@link OrderFulfillmentWorkflow}. */
public final class OrderFulfillmentModels {

  private OrderFulfillmentModels() {}

  /*
   * Order state machine transitions:
   *
   *   pending_payment ──► payment_approved ──► ready_to_ship ──► shipped ──► delivered
   *        │                    │                    │               │
   *        ▼                    ▼                    ▼               ▼
   *    cancelled            refunded            cancelled        refunded
   *
   *   pending_payment → cancelled is via reject_payment (not cancel_order).
   */
  public static final String STATUS_PENDING_PAYMENT = "pending_payment";
  public static final String STATUS_PAYMENT_APPROVED = "payment_approved";
  public static final String STATUS_READY_TO_SHIP = "ready_to_ship";
  public static final String STATUS_SHIPPED = "shipped";
  public static final String STATUS_DELIVERED = "delivered";
  public static final String STATUS_CANCELLED = "cancelled";
  public static final String STATUS_REFUNDED = "refunded";

  /**
   * Mutable workflow state held in memory. Cadence replays the event history on recovery to
   * reconstruct this object, so it does not need to be persisted externally.
   */
  public static class Order {
    public String orderID = "ORD-2024-001234";
    public String customerName = "Alice Johnson";
    public String customerEmail = "alice.johnson@example.com";
    public OrderItem[] items =
        new OrderItem[] {
          new OrderItem("Wireless Headphones", 2, 79.99),
          new OrderItem("Phone Case", 1, 19.99),
        };
    public double totalAmount = 179.97;
    public String status = STATUS_PENDING_PAYMENT;
    public String trackingNum = "";
    public String carrier = "";
    public double refundAmount;
    public String refundReason = "";
    public long createdAtMillis;
  }

  public static class OrderItem {
    public String name;
    public int quantity;
    public double price;

    public OrderItem() {}

    public OrderItem(String name, int quantity, double price) {
      this.name = name;
      this.quantity = quantity;
      this.price = price;
    }
  }

  public static class ActionLogEntry {
    public long timestampMillis;
    public String action;
    public String operator;
    public String details;
  }

  /**
   * Signal POJOs below use public fields so the Cadence JSON data converter can deserialize them.
   * Field names must match the JSON keys in each Markdoc {@code input=} attribute; for example
   * {@code input={"operator":"admin","reason":"Fraud"}} maps to {@link #operator} and
   * {@link #reason}.
   */
  public static class RejectPaymentSignal {
    public String reason;
    public String operator;
  }

  public static class ApprovePaymentSignal {
    public String operator;
  }

  public static class ShipOrderSignal {
    public String trackingNumber;
    public String carrier;
    public String operator;
  }

  public static class RefundSignal {
    public double amount;
    public String reason;
    public String operator;
  }

  public static class CancelOrderSignal {
    public String reason;
    public String operator;
  }

  public static class SimpleSignal {
    public String operator;
  }
}
