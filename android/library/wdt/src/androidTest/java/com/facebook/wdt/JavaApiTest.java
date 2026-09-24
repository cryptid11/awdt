/*
 * Copyright (c) 2014-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree.
 */
package com.facebook.wdt;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The API as used from Java. */
@RunWith(AndroidJUnit4.class)
public class JavaApiTest {
  @Test
  public void loopbackFromJava() throws Exception {
    File root =
        new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            "wdt-java-test");
    File src = new File(root, "src");
    File dst = new File(root, "dst");
    src.mkdirs();
    dst.mkdirs();
    byte[] data = new byte[1 << 20];
    new java.util.Random(1).nextBytes(data);
    Files.write(new File(src, "data.bin").toPath(), data);

    WdtOptions options = new WdtOptions();
    options.setStartPort(0);
    options.setNumPorts(2);
    options.setEncryption(WdtOptions.Encryption.AES128_GCM);
    options.set("enable_checksum", true);

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try (WdtReceiver receiver = new WdtReceiver(dst, options)) {
      String url = receiver.start();
      Future<TransferReport> received = executor.submit(receiver::awaitFinish);
      try (WdtSender sender = new WdtSender(url, src, options)) {
        TransferReport report = sender.transfer(progress -> {});
        assertTrue(report.toString(), report.isSuccess());
        assertEquals(WdtErrorCode.OK, report.getErrorCode());
      }
      assertTrue(received.get(60, TimeUnit.SECONDS).isSuccess());
    } finally {
      executor.shutdownNow();
    }
    assertArrayEquals(data, Files.readAllBytes(new File(dst, "data.bin").toPath()));
    assertTrue(Wdt.getVersion().length() > 0);
    Wdt.setLogLevel(Wdt.LogLevel.WARNING);
  }
}
