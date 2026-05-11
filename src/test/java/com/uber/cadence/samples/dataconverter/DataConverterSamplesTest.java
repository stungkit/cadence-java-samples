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

package com.uber.cadence.samples.dataconverter;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.uber.cadence.client.WorkflowClient;
import com.uber.cadence.client.WorkflowClientOptions;
import com.uber.cadence.client.WorkflowOptions;
import com.uber.cadence.converter.DataConverterException;
import com.uber.cadence.testing.TestEnvironmentOptions;
import com.uber.cadence.testing.TestWorkflowEnvironment;
import com.uber.cadence.worker.Worker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DataConverterSamplesTest {

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  private TestWorkflowEnvironment testEnv;

  @After
  public void tearDown() {
    if (testEnv != null) {
      testEnv.close();
    }
  }

  @Test
  public void testCompressedConverterRoundTrip() {
    CompressedJsonDataConverter converter = new CompressedJsonDataConverter();
    CompressedDataConverterWorkflow.LargePayload payload =
        CompressedDataConverterWorkflow.createLargePayload();

    byte[] encoded = converter.toData(payload);
    CompressedDataConverterWorkflow.LargePayload decoded =
        converter.fromData(
            encoded,
            CompressedDataConverterWorkflow.LargePayload.class,
            CompressedDataConverterWorkflow.LargePayload.class);

    assertEquals(payload.id, decoded.id);
    assertEquals(payload.name, decoded.name);
    assertEquals(payload.items.size(), decoded.items.size());
    assertEquals(payload.history.size(), decoded.history.size());
  }

  @Test
  public void testCompressedConverterRejectsMalformedPayload() {
    CompressedJsonDataConverter converter = new CompressedJsonDataConverter();

    try {
      converter.fromData(new byte[] {1, 2, 3}, String.class, String.class);
      fail("expected malformed gzip payload to fail");
    } catch (DataConverterException e) {
      assertTrue(e.getMessage().contains("gunzip"));
    }
  }

  @Test
  public void testCompressedConverterRejectsPayloadAboveLimit() {
    CompressedJsonDataConverter encoder = new CompressedJsonDataConverter();
    CompressedJsonDataConverter decoder = new CompressedJsonDataConverter(8);
    byte[] encoded = encoder.toData("this string inflates beyond the configured limit");

    try {
      decoder.fromData(encoded, String.class, String.class);
      fail("expected oversized decompressed payload to fail");
    } catch (DataConverterException e) {
      assertTrue(e.getMessage().contains("maximum size"));
    }
  }

  @Test
  public void testEncryptedConverterRoundTripAndRandomNonce() {
    EncryptedJsonDataConverter converter =
        new EncryptedJsonDataConverter(EncryptionKeyLoader.DEMO_ENCRYPTION_KEY);
    EncryptedDataConverterWorkflow.SensitiveCustomerRecord record =
        EncryptedDataConverterWorkflow.createSensitiveCustomerRecord();

    byte[] first = converter.toData(record);
    byte[] second = converter.toData(record);

    assertFalse(Arrays.equals(first, second));
    EncryptedDataConverterWorkflow.SensitiveCustomerRecord decoded =
        converter.fromData(
            first,
            EncryptedDataConverterWorkflow.SensitiveCustomerRecord.class,
            EncryptedDataConverterWorkflow.SensitiveCustomerRecord.class);
    assertEquals(record.customerId, decoded.customerId);
    assertEquals(record.ssn, decoded.ssn);
    assertEquals(record.medicalNotes, decoded.medicalNotes);
  }

  @Test
  public void testEncryptedConverterRejectsShortCiphertext() {
    EncryptedJsonDataConverter converter =
        new EncryptedJsonDataConverter(EncryptionKeyLoader.DEMO_ENCRYPTION_KEY);

    try {
      converter.fromData(new byte[] {1, 2, 3}, String.class, String.class);
      fail("expected short ciphertext to fail");
    } catch (DataConverterException e) {
      assertTrue(e.getMessage().contains("Ciphertext too short"));
    }
  }

  @Test
  public void testEncryptedConverterWorksInWorkflowEnvironment() {
    EncryptedJsonDataConverter converter =
        new EncryptedJsonDataConverter(EncryptionKeyLoader.DEMO_ENCRYPTION_KEY);
    TestEnvironmentOptions options =
        new TestEnvironmentOptions.Builder()
            .setWorkflowClientOptions(
                WorkflowClientOptions.newBuilder().setDataConverter(converter).build())
            .build();
    testEnv = TestWorkflowEnvironment.newInstance(options);
    Worker worker = testEnv.newWorker(DataConverterConstants.TASK_LIST_ENCRYPTION);
    worker.registerWorkflowImplementationTypes(EncryptedDataConverterWorkflow.WorkflowImpl.class);
    worker.registerActivitiesImplementations(new EncryptedDataConverterWorkflow.ActivitiesImpl());
    testEnv.start();

    WorkflowClient workflowClient =
        testEnv.newWorkflowClient(
            WorkflowClientOptions.newBuilder().setDataConverter(converter).build());
    WorkflowOptions workflowOptions =
        new WorkflowOptions.Builder()
            .setTaskList(DataConverterConstants.TASK_LIST_ENCRYPTION)
            .setExecutionStartToCloseTimeout(Duration.ofMinutes(1))
            .build();
    EncryptedDataConverterWorkflow.WorkflowIface workflow =
        workflowClient.newWorkflowStub(
            EncryptedDataConverterWorkflow.WorkflowIface.class, workflowOptions);

    EncryptedDataConverterWorkflow.SensitiveCustomerRecord result = workflow.run();

    assertEquals("cust_8a7f3b2e", result.customerId);
    assertEquals("workflow-processor-v2 (Encrypted)", result.processedBy);
  }

  @Test
  public void testS3OffloadConverterInlinesBelowThreshold() {
    RecordingBlobStore store = new RecordingBlobStore();
    S3OffloadDataConverter converter = new S3OffloadDataConverter(store, "bucket", 1024);

    byte[] encoded = converter.toData("small");
    String decoded = converter.fromData(encoded, String.class, String.class);

    assertEquals(S3OffloadDataConverter.INLINE_PREFIX, encoded[0]);
    assertEquals("small", decoded);
    assertTrue(store.blobs.isEmpty());
  }

  @Test
  public void testS3OffloadConverterOffloadsAndUsesIdempotentReference() {
    RecordingBlobStore store = new RecordingBlobStore();
    S3OffloadDataConverter converter = new S3OffloadDataConverter(store, "bucket", 1);

    byte[] first = converter.toData("large enough to offload");
    byte[] second = converter.toData("large enough to offload");
    String decoded = converter.fromData(first, String.class, String.class);

    assertEquals(S3OffloadDataConverter.OFFLOAD_PREFIX, first[0]);
    assertArrayEquals(first, second);
    assertEquals("large enough to offload", decoded);
    assertEquals(1, store.blobs.size());
  }

  @Test
  public void testS3OffloadConverterRejectsUnknownPrefix() {
    S3OffloadDataConverter converter =
        new S3OffloadDataConverter(new RecordingBlobStore(), "bucket", 1);

    try {
      converter.fromData(new byte[] {0x7f}, String.class, String.class);
      fail("expected unknown prefix to fail");
    } catch (DataConverterException e) {
      assertTrue(e.getMessage().contains("unknown prefix"));
    }
  }

  @Test
  public void testS3OffloadConverterValidatesConstructorInputs() {
    expectIllegalArgument(() -> new S3OffloadDataConverter(null, "bucket", 1));
    expectIllegalArgument(() -> new S3OffloadDataConverter(new RecordingBlobStore(), " ", 1));
    expectIllegalArgument(() -> new S3OffloadDataConverter(new RecordingBlobStore(), "bucket", -1));
  }

  @Test
  public void testLocalFsBlobStoreHashesUnsafeKeys() throws Exception {
    Path baseDir = temporaryFolder.newFolder("blobs").toPath();
    LocalFsBlobStore store = new LocalFsBlobStore(baseDir);
    byte[] data = new byte[] {1, 2, 3};

    store.put("../escape", data);
    store.put(".", data);
    store.put("bucket\\nested/key", data);

    assertArrayEquals(data, store.get("../escape"));
    assertArrayEquals(data, store.get("."));
    assertArrayEquals(data, store.get("bucket\\nested/key"));
    try (Stream<Path> files = Files.list(baseDir)) {
      assertEquals(3, files.filter(Files::isRegularFile).count());
    }
    try (Stream<Path> files = Files.list(baseDir)) {
      assertTrue(files.allMatch(path -> path.getFileName().toString().matches("[0-9a-f]{64}")));
    }
  }

  private static void expectIllegalArgument(Runnable runnable) {
    try {
      runnable.run();
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static final class RecordingBlobStore implements BlobStore {
    final Map<String, byte[]> blobs = new LinkedHashMap<>();

    @Override
    public void put(String key, byte[] data) {
      blobs.put(key, data);
    }

    @Override
    public byte[] get(String key) throws IOException {
      byte[] data = blobs.get(key);
      if (data == null) {
        throw new IOException("missing key " + key);
      }
      return data;
    }
  }
}
