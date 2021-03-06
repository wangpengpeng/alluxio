/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.testutils;

import static alluxio.util.network.NetworkAddressUtils.ServiceType;

import alluxio.AlluxioURI;
import alluxio.ClientContext;
import alluxio.Constants;
import alluxio.client.file.FileSystem;
import alluxio.client.file.FileSystemMasterClient;
import alluxio.conf.PropertyKey;
import alluxio.conf.ServerConfiguration;
import alluxio.grpc.GetStatusPOptions;
import alluxio.heartbeat.HeartbeatContext;
import alluxio.heartbeat.HeartbeatScheduler;
import alluxio.master.MasterClientContext;
import alluxio.master.MasterProcess;
import alluxio.util.CommonUtils;
import alluxio.util.WaitForOptions;
import alluxio.util.network.NetworkAddressUtils;
import alluxio.worker.block.BlockHeartbeatReporter;
import alluxio.worker.block.BlockWorker;

import com.google.common.base.Throwables;
import org.powermock.reflect.Whitebox;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Util methods for writing integration tests.
 */
public final class IntegrationTestUtils {

  /**
   * Convenience method for calling
   * {@link #waitForPersist(LocalAlluxioClusterResource, AlluxioURI, int)} with a default timeout.
   *
   * @param localAlluxioClusterResource the cluster for the worker that will persist the file
   * @param uri the file uri to wait to be persisted
   */
  public static void waitForPersist(LocalAlluxioClusterResource localAlluxioClusterResource,
      AlluxioURI uri) throws TimeoutException, InterruptedException {
    waitForPersist(localAlluxioClusterResource, uri, 15 * Constants.SECOND_MS);
  }

  /**
   * Blocks until the specified file is persisted or a timeout occurs.
   *
   * @param localAlluxioClusterResource the cluster for the worker that will persist the file
   * @param uri the uri to wait to be persisted
   * @param timeoutMs the number of milliseconds to wait before giving up and throwing an exception
   */
  public static void waitForPersist(final LocalAlluxioClusterResource localAlluxioClusterResource,
      final AlluxioURI uri, int timeoutMs) throws InterruptedException, TimeoutException {
    try (FileSystemMasterClient client =
        FileSystemMasterClient.Factory.create(MasterClientContext
            .newBuilder(ClientContext.create(ServerConfiguration.global())).build())) {
      CommonUtils.waitFor(uri + " to be persisted", () -> {
        try {
          return client.getStatus(uri, GetStatusPOptions.getDefaultInstance()).isPersisted();
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      }, WaitForOptions.defaults().setTimeoutMs(timeoutMs));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Blocks until the specified file is full cached in Alluxio or a timeout occurs.
   *
   * @param fileSystem the filesystem client
   * @param uri the uri to wait to be persisted
   * @param timeoutMs the number of milliseconds to wait before giving up and throwing an exception
   */
  public static void waitForFileCached(final FileSystem fileSystem, final AlluxioURI uri,
      int timeoutMs) throws TimeoutException, InterruptedException {
    CommonUtils.waitFor(uri + " to be cached", () -> {
      try {
        return fileSystem.getStatus(uri).getInAlluxioPercentage() == 100;
      } catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }, WaitForOptions.defaults().setTimeoutMs(timeoutMs));
  }

  /**
   * Triggers two heartbeats to wait for a given list of blocks to be removed from both master and
   * worker.
   * Blocks until the master and block are in sync with the state of the blocks.
   *
   * @param bw the block worker that will remove the blocks
   * @param blockIds a list of blockIds to be removed
   */
  public static void waitForBlocksToBeFreed(final BlockWorker bw, final Long... blockIds)
      throws TimeoutException {
    try {
      // Execute 1st heartbeat from worker.
      HeartbeatScheduler.execute(HeartbeatContext.WORKER_BLOCK_SYNC);

      // Waiting for the blocks to be added into the heartbeat reportor, so that they will be
      // removed from master in the next heartbeat.
      CommonUtils.waitFor("blocks to be removed", () -> {
        BlockHeartbeatReporter reporter = Whitebox.getInternalState(bw, "mHeartbeatReporter");
        List<Long> blocksToRemove = Whitebox.getInternalState(reporter, "mRemovedBlocks");
        return blocksToRemove.containsAll(Arrays.asList(blockIds));
      }, WaitForOptions.defaults().setTimeoutMs(100 * Constants.SECOND_MS));

      // Execute 2nd heartbeat from worker.
      HeartbeatScheduler.execute(HeartbeatContext.WORKER_BLOCK_SYNC);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  /**
   * Creates a map of {@link ServiceType} to sockets. When each entry is created, it will create
   * a {@link ServerSocket} for each service respecting the current state of the
   * {@link ServerConfiguration}. Essentially this "reserves" the port on each socket so that
   * no other thread or process can use the port. Each socket can then be passed to an
   * {@link MasterProcess} which will close the original socket then use the original addressto
   * bind and listen on a server for the service.
   *
   * @return a map {@link ServiceType} to {@link ServerSocket}
   */
  public static Map<ServiceType, ServerSocket> createMasterServiceMapping() {
    Map<ServiceType, ServerSocket> serviceMapping = new HashMap<>();
    MasterProcess.MASTER_PROCESS_PORT_SERVICE_LIST.forEach((ServiceType st) -> {
      PropertyKey pk = st.getPortKey();
      InetSocketAddress bindAddr = NetworkAddressUtils.getBindAddress(st,
          ServerConfiguration.global());
      try {
        ServerSocket bindSocket = new ServerSocket(0, 50, bindAddr.getAddress());
        ServerConfiguration.set(pk, bindSocket.getLocalPort());
        serviceMapping.put(st, bindSocket);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    });
    return serviceMapping;
  }

  private IntegrationTestUtils() {} // This is a utils class not intended for instantiation
}
