/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.pipe.connector.protocol.thrift.async;

import org.apache.iotdb.common.rpc.thrift.TEndPoint;
import org.apache.iotdb.commons.client.ClientPoolFactory;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.async.AsyncPipeDataTransferServiceClient;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.builder.IoTDBThriftAsyncPipeTransferBatchReqBuilder;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferHandshakeReq;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferTabletBinaryReq;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferTabletInsertNodeReq;
import org.apache.iotdb.db.pipe.connector.payload.evolvable.request.PipeTransferTabletRawReq;
import org.apache.iotdb.db.pipe.connector.protocol.IoTDBConnector;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.async.handler.PipeTransferTabletBatchEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.async.handler.PipeTransferTabletInsertNodeEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.async.handler.PipeTransferTabletRawEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.async.handler.PipeTransferTsFileInsertionEventHandler;
import org.apache.iotdb.db.pipe.connector.protocol.thrift.sync.IoTDBThriftSyncConnector;
import org.apache.iotdb.db.pipe.event.EnrichedEvent;
import org.apache.iotdb.db.pipe.event.common.heartbeat.PipeHeartbeatEvent;
import org.apache.iotdb.db.pipe.event.common.tablet.PipeInsertNodeTabletInsertionEvent;
import org.apache.iotdb.db.pipe.event.common.tablet.PipeRawTabletInsertionEvent;
import org.apache.iotdb.db.pipe.event.common.tsfile.PipeTsFileInsertionEvent;
import org.apache.iotdb.pipe.api.PipeConnector;
import org.apache.iotdb.pipe.api.customizer.configuration.PipeConnectorRuntimeConfiguration;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameterValidator;
import org.apache.iotdb.pipe.api.customizer.parameter.PipeParameters;
import org.apache.iotdb.pipe.api.event.Event;
import org.apache.iotdb.pipe.api.event.dml.insertion.TabletInsertionEvent;
import org.apache.iotdb.pipe.api.event.dml.insertion.TsFileInsertionEvent;
import org.apache.iotdb.pipe.api.exception.PipeConnectionException;
import org.apache.iotdb.pipe.api.exception.PipeException;
import org.apache.iotdb.rpc.TSStatusCode;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferReq;
import org.apache.iotdb.service.rpc.thrift.TPipeTransferResp;
import org.apache.iotdb.tsfile.utils.Pair;

import org.apache.thrift.async.AsyncMethodCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.apache.iotdb.db.pipe.config.constant.PipeConnectorConstant.CONNECTOR_IOTDB_BATCH_MODE_ENABLE_KEY;
import static org.apache.iotdb.db.pipe.config.constant.PipeConnectorConstant.SINK_IOTDB_BATCH_MODE_ENABLE_KEY;
import static org.apache.iotdb.db.pipe.config.constant.PipeConnectorConstant.SINK_IOTDB_SSL_ENABLE_KEY;

public class IoTDBThriftAsyncConnector extends IoTDBConnector {

  private static final Logger LOGGER = LoggerFactory.getLogger(IoTDBThriftAsyncConnector.class);

  private static final String THRIFT_ERROR_FORMATTER =
      "Failed to borrow client from client pool or exception occurred "
          + "when sending to receiver %s:%s.";

  private static final AtomicReference<
          IClientManager<TEndPoint, AsyncPipeDataTransferServiceClient>>
      ASYNC_PIPE_DATA_TRANSFER_CLIENT_MANAGER_HOLDER = new AtomicReference<>();
  private final IClientManager<TEndPoint, AsyncPipeDataTransferServiceClient>
      asyncPipeDataTransferClientManager;

  private final IoTDBThriftSyncConnector retryConnector = new IoTDBThriftSyncConnector();
  private final PriorityBlockingQueue<Pair<Long, Event>> retryEventQueue =
      new PriorityBlockingQueue<>(11, Comparator.comparing(o -> o.left));

  private final AtomicLong commitIdGenerator = new AtomicLong(0);
  private final AtomicLong lastCommitId = new AtomicLong(0);
  private final PriorityQueue<Pair<Long, Runnable>> commitQueue =
      new PriorityQueue<>(Comparator.comparing(o -> o.left));

  private IoTDBThriftAsyncPipeTransferBatchReqBuilder tabletBatchBuilder;

  public IoTDBThriftAsyncConnector() {
    if (ASYNC_PIPE_DATA_TRANSFER_CLIENT_MANAGER_HOLDER.get() == null) {
      synchronized (IoTDBThriftAsyncConnector.class) {
        if (ASYNC_PIPE_DATA_TRANSFER_CLIENT_MANAGER_HOLDER.get() == null) {
          ASYNC_PIPE_DATA_TRANSFER_CLIENT_MANAGER_HOLDER.set(
              new IClientManager.Factory<TEndPoint, AsyncPipeDataTransferServiceClient>()
                  .createClientManager(
                      new ClientPoolFactory.AsyncPipeDataTransferServiceClientPoolFactory()));
        }
      }
    }
    asyncPipeDataTransferClientManager = ASYNC_PIPE_DATA_TRANSFER_CLIENT_MANAGER_HOLDER.get();
  }

  @Override
  public void validate(PipeParameterValidator validator) throws Exception {
    super.validate(validator);
    retryConnector.validate(validator);

    final PipeParameters parameters = validator.getParameters();
    validator.validate(
        useSSL -> !((boolean) useSSL),
        "IoTDBThriftAsyncConnector does not support SSL transmission currently",
        parameters.getBooleanOrDefault(SINK_IOTDB_SSL_ENABLE_KEY, false));
  }

  @Override
  public void customize(PipeParameters parameters, PipeConnectorRuntimeConfiguration configuration)
      throws Exception {
    super.customize(parameters, configuration);

    // Disable batch mode for retry connector, in case retry events are never sent again
    PipeParameters retryParameters = new PipeParameters(new HashMap<>(parameters.getAttribute()));
    retryParameters.getAttribute().put(SINK_IOTDB_BATCH_MODE_ENABLE_KEY, "false");
    retryParameters.getAttribute().put(CONNECTOR_IOTDB_BATCH_MODE_ENABLE_KEY, "false");
    retryConnector.customize(retryParameters, configuration);

    if (isTabletBatchModeEnabled) {
      tabletBatchBuilder = new IoTDBThriftAsyncPipeTransferBatchReqBuilder(parameters);
    }
  }

  @Override
  // Synchronized to avoid close connector when transfer event
  public synchronized void handshake() throws Exception {
    retryConnector.handshake();
  }

  @Override
  public void heartbeat() {
    retryConnector.heartbeat();
  }

  @Override
  public void transfer(TabletInsertionEvent tabletInsertionEvent) throws Exception {
    transferQueuedEventsIfNecessary();

    if (!(tabletInsertionEvent instanceof PipeInsertNodeTabletInsertionEvent)
        && !(tabletInsertionEvent instanceof PipeRawTabletInsertionEvent)) {
      LOGGER.warn(
          "IoTDBThriftAsyncConnector only support PipeInsertNodeTabletInsertionEvent and PipeRawTabletInsertionEvent. "
              + "Current event: {}.",
          tabletInsertionEvent);
      return;
    }

    if (((EnrichedEvent) tabletInsertionEvent).shouldParsePatternOrTime()) {
      if (tabletInsertionEvent instanceof PipeInsertNodeTabletInsertionEvent) {
        transfer(
            ((PipeInsertNodeTabletInsertionEvent) tabletInsertionEvent).parseEventWithPattern());
      } else { // tabletInsertionEvent instanceof PipeRawTabletInsertionEvent
        transfer(((PipeRawTabletInsertionEvent) tabletInsertionEvent).parseEventWithPattern());
      }
      return;
    }

    if (isTabletBatchModeEnabled) {
      final long requestCommitId = commitIdGenerator.incrementAndGet();
      if (tabletBatchBuilder.onEvent(tabletInsertionEvent, requestCommitId)) {
        final PipeTransferTabletBatchEventHandler pipeTransferTabletBatchEventHandler =
            new PipeTransferTabletBatchEventHandler(tabletBatchBuilder, this);

        transfer(requestCommitId, pipeTransferTabletBatchEventHandler);
        tabletBatchBuilder.onSuccess();
      }
    } else {
      if (tabletInsertionEvent instanceof PipeInsertNodeTabletInsertionEvent) {
        final PipeInsertNodeTabletInsertionEvent pipeInsertNodeTabletInsertionEvent =
            (PipeInsertNodeTabletInsertionEvent) tabletInsertionEvent;
        final TPipeTransferReq pipeTransferReq =
            pipeInsertNodeTabletInsertionEvent.getInsertNodeViaCacheIfPossible() == null
                ? PipeTransferTabletBinaryReq.toTPipeTransferReq(
                    pipeInsertNodeTabletInsertionEvent.getByteBuffer())
                : PipeTransferTabletInsertNodeReq.toTPipeTransferReq(
                    pipeInsertNodeTabletInsertionEvent.getInsertNode());

        final long requestCommitId = commitIdGenerator.incrementAndGet();
        final PipeTransferTabletInsertNodeEventHandler pipeTransferInsertNodeReqHandler =
            new PipeTransferTabletInsertNodeEventHandler(
                requestCommitId, pipeInsertNodeTabletInsertionEvent, pipeTransferReq, this);

        transfer(requestCommitId, pipeTransferInsertNodeReqHandler);
      } else { // tabletInsertionEvent instanceof PipeRawTabletInsertionEvent
        final PipeRawTabletInsertionEvent pipeRawTabletInsertionEvent =
            (PipeRawTabletInsertionEvent) tabletInsertionEvent;
        final PipeTransferTabletRawReq pipeTransferTabletRawReq =
            PipeTransferTabletRawReq.toTPipeTransferReq(
                pipeRawTabletInsertionEvent.convertToTablet(),
                pipeRawTabletInsertionEvent.isAligned());

        final long requestCommitId = commitIdGenerator.incrementAndGet();
        final PipeTransferTabletRawEventHandler pipeTransferTabletReqHandler =
            new PipeTransferTabletRawEventHandler(
                requestCommitId, pipeRawTabletInsertionEvent, pipeTransferTabletRawReq, this);

        transfer(requestCommitId, pipeTransferTabletReqHandler);
      }
    }
  }

  private void transfer(
      long requestCommitId,
      PipeTransferTabletBatchEventHandler pipeTransferTabletBatchEventHandler) {
    final TEndPoint targetNodeUrl = nodeUrls.get((int) (requestCommitId % nodeUrls.size()));

    try {
      final AsyncPipeDataTransferServiceClient client = borrowClient(targetNodeUrl);
      pipeTransferTabletBatchEventHandler.transfer(client);
    } catch (Exception ex) {
      LOGGER.warn(
          String.format(THRIFT_ERROR_FORMATTER, targetNodeUrl.getIp(), targetNodeUrl.getPort()),
          ex);
      pipeTransferTabletBatchEventHandler.onError(ex);
    }
  }

  private void transfer(
      long requestCommitId,
      PipeTransferTabletInsertNodeEventHandler pipeTransferInsertNodeReqHandler) {
    final TEndPoint targetNodeUrl = nodeUrls.get((int) (requestCommitId % nodeUrls.size()));

    try {
      final AsyncPipeDataTransferServiceClient client = borrowClient(targetNodeUrl);
      pipeTransferInsertNodeReqHandler.transfer(client);
    } catch (Exception ex) {
      LOGGER.warn(
          String.format(THRIFT_ERROR_FORMATTER, targetNodeUrl.getIp(), targetNodeUrl.getPort()),
          ex);
      pipeTransferInsertNodeReqHandler.onError(ex);
    }
  }

  private void transfer(
      long requestCommitId, PipeTransferTabletRawEventHandler pipeTransferTabletReqHandler) {
    final TEndPoint targetNodeUrl = nodeUrls.get((int) (requestCommitId % nodeUrls.size()));

    try {
      final AsyncPipeDataTransferServiceClient client = borrowClient(targetNodeUrl);
      pipeTransferTabletReqHandler.transfer(client);
    } catch (Exception ex) {
      LOGGER.warn(
          String.format(THRIFT_ERROR_FORMATTER, targetNodeUrl.getIp(), targetNodeUrl.getPort()),
          ex);
      pipeTransferTabletReqHandler.onError(ex);
    }
  }

  @Override
  public void transfer(TsFileInsertionEvent tsFileInsertionEvent) throws Exception {
    transferQueuedEventsIfNecessary();
    transferBatchedEventsIfNecessary();

    if (!(tsFileInsertionEvent instanceof PipeTsFileInsertionEvent)) {
      LOGGER.warn(
          "IoTDBThriftAsyncConnector only support PipeTsFileInsertionEvent. Current event: {}.",
          tsFileInsertionEvent);
      return;
    }

    final PipeTsFileInsertionEvent pipeTsFileInsertionEvent =
        (PipeTsFileInsertionEvent) tsFileInsertionEvent;
    if (!pipeTsFileInsertionEvent.waitForTsFileClose()) {
      LOGGER.warn(
          "Pipe skipping temporary TsFile which shouldn't be transferred: {}",
          pipeTsFileInsertionEvent.getTsFile());
      return;
    }

    if ((pipeTsFileInsertionEvent).shouldParsePatternOrTime()) {
      try {
        for (final TabletInsertionEvent event :
            pipeTsFileInsertionEvent.toTabletInsertionEvents()) {
          transfer(event);
        }
      } finally {
        pipeTsFileInsertionEvent.close();
      }
      return;
    }

    // Just in case. To avoid the case that exception occurred when constructing the handler.
    if (!pipeTsFileInsertionEvent.getTsFile().exists()) {
      throw new FileNotFoundException(pipeTsFileInsertionEvent.getTsFile().getAbsolutePath());
    }

    final long requestCommitId = commitIdGenerator.incrementAndGet();
    final PipeTransferTsFileInsertionEventHandler pipeTransferTsFileInsertionEventHandler =
        new PipeTransferTsFileInsertionEventHandler(
            requestCommitId, pipeTsFileInsertionEvent, this);

    transfer(requestCommitId, pipeTransferTsFileInsertionEventHandler);
  }

  private void transfer(
      long requestCommitId,
      PipeTransferTsFileInsertionEventHandler pipeTransferTsFileInsertionEventHandler) {
    final TEndPoint targetNodeUrl = nodeUrls.get((int) (requestCommitId % nodeUrls.size()));

    try {
      final AsyncPipeDataTransferServiceClient client = borrowClient(targetNodeUrl);
      pipeTransferTsFileInsertionEventHandler.transfer(client);
    } catch (Exception ex) {
      LOGGER.warn(
          String.format(THRIFT_ERROR_FORMATTER, targetNodeUrl.getIp(), targetNodeUrl.getPort()),
          ex);
      pipeTransferTsFileInsertionEventHandler.onError(ex);
    }
  }

  @Override
  public void transfer(Event event) throws Exception {
    transferQueuedEventsIfNecessary();
    transferBatchedEventsIfNecessary();

    if (!(event instanceof PipeHeartbeatEvent)) {
      LOGGER.warn(
          "IoTDBThriftAsyncConnector does not support transferring generic event: {}.", event);
    }
  }

  private AsyncPipeDataTransferServiceClient borrowClient(TEndPoint targetNodeUrl)
      throws Exception {
    while (true) {
      final AsyncPipeDataTransferServiceClient client =
          asyncPipeDataTransferClientManager.borrowClient(targetNodeUrl);
      if (handshakeIfNecessary(targetNodeUrl, client)) {
        return client;
      }
    }
  }

  /**
   * Handshake with the target if necessary.
   *
   * @param client client to handshake
   * @return true if the handshake is already finished, false if the handshake is not finished yet
   *     and finished in this method
   * @throws Exception if an error occurs.
   */
  private boolean handshakeIfNecessary(
      TEndPoint targetNodeUrl, AsyncPipeDataTransferServiceClient client) throws Exception {
    if (client.isHandshakeFinished()) {
      return true;
    }

    final AtomicBoolean isHandshakeFinished = new AtomicBoolean(false);
    final AtomicReference<Exception> exception = new AtomicReference<>();

    client.pipeTransfer(
        PipeTransferHandshakeReq.toTPipeTransferReq(
            CommonDescriptor.getInstance().getConfig().getTimestampPrecision()),
        new AsyncMethodCallback<TPipeTransferResp>() {
          @Override
          public void onComplete(TPipeTransferResp response) {
            if (response.getStatus().getCode() != TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
              LOGGER.warn(
                  "Handshake error with receiver {}:{}, code: {}, message: {}.",
                  targetNodeUrl.getIp(),
                  targetNodeUrl.getPort(),
                  response.getStatus().getCode(),
                  response.getStatus().getMessage());
              exception.set(
                  new PipeConnectionException(
                      String.format(
                          "Handshake error with receiver %s:%s, code: %d, message: %s.",
                          targetNodeUrl.getIp(),
                          targetNodeUrl.getPort(),
                          response.getStatus().getCode(),
                          response.getStatus().getMessage())));
            } else {
              LOGGER.info(
                  "Handshake successfully with receiver {}:{}.",
                  targetNodeUrl.getIp(),
                  targetNodeUrl.getPort());
              client.markHandshakeFinished();
            }

            isHandshakeFinished.set(true);
          }

          @Override
          public void onError(Exception e) {
            LOGGER.warn(
                "Handshake error with receiver {}:{}.",
                targetNodeUrl.getIp(),
                targetNodeUrl.getPort(),
                e);
            exception.set(e);

            isHandshakeFinished.set(true);
          }
        });

    try {
      while (!isHandshakeFinished.get()) {
        Thread.sleep(10);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PipeException("Interrupted while waiting for handshake response.", e);
    }

    if (exception.get() != null) {
      throw new PipeConnectionException("Failed to handshake.", exception.get());
    }

    return false;
  }

  /**
   * Transfer queued events which are waiting for retry.
   *
   * @throws Exception if an error occurs. The error will be handled by pipe framework, which will
   *     retry the event and mark the event as failure and stop the pipe if the retry times exceeds
   *     the threshold.
   * @see PipeConnector#transfer(Event) for more details.
   * @see PipeConnector#transfer(TabletInsertionEvent) for more details.
   * @see PipeConnector#transfer(TsFileInsertionEvent) for more details.
   */
  private synchronized void transferQueuedEventsIfNecessary() throws Exception {
    while (!retryEventQueue.isEmpty()) {
      final Pair<Long, Event> queuedEventPair = retryEventQueue.peek();
      final long requestCommitId = queuedEventPair.getLeft();
      final Event event = queuedEventPair.getRight();

      if (event instanceof PipeInsertNodeTabletInsertionEvent) {
        retryConnector.transfer((PipeInsertNodeTabletInsertionEvent) event);
      } else if (event instanceof PipeRawTabletInsertionEvent) {
        retryConnector.transfer((PipeRawTabletInsertionEvent) event);
      } else if (event instanceof PipeTsFileInsertionEvent) {
        retryConnector.transfer((PipeTsFileInsertionEvent) event);
      } else {
        LOGGER.warn(
            "IoTDBThriftAsyncConnector does not support transfer generic event: {}.", event);
      }

      commit(requestCommitId, event instanceof EnrichedEvent ? (EnrichedEvent) event : null);

      retryEventQueue.poll();
    }
  }

  /** Try its best to commit data in order. Flush can also be a trigger to transfer batched data. */
  private void transferBatchedEventsIfNecessary() throws IOException {
    if (!isTabletBatchModeEnabled || tabletBatchBuilder.isEmpty()) {
      return;
    }

    // requestCommitId can not be generated by commitIdGenerator because the commit id must
    // be bind to a specific InsertTabletEvent or TsFileInsertionEvent, otherwise the commit
    // process will stuck.
    final long requestCommitId = tabletBatchBuilder.getLastCommitId();
    final PipeTransferTabletBatchEventHandler pipeTransferTabletBatchEventHandler =
        new PipeTransferTabletBatchEventHandler(tabletBatchBuilder, this);

    transfer(requestCommitId, pipeTransferTabletBatchEventHandler);

    tabletBatchBuilder.onSuccess();
  }

  /**
   * Commit the event. Decrease the reference count of the event. If the reference count is 0, the
   * progress index of the event will be recalculated and the resources of the event will be
   * released.
   *
   * <p>The synchronization is necessary because the commit order must be the same as the order of
   * the events. Concurrent commit may cause the commit order to be inconsistent with the order of
   * the events.
   *
   * @param requestCommitId commit id of the request
   * @param enrichedEvent event to commit
   */
  public synchronized void commit(long requestCommitId, @Nullable EnrichedEvent enrichedEvent) {
    commitQueue.offer(
        new Pair<>(
            requestCommitId,
            () ->
                Optional.ofNullable(enrichedEvent)
                    .ifPresent(
                        event ->
                            event.decreaseReferenceCount(
                                IoTDBThriftAsyncConnector.class.getName(), true))));

    while (!commitQueue.isEmpty()) {
      final Pair<Long, Runnable> committer = commitQueue.peek();

      // If the commit id is less than or equals to the last commit id, it means that
      // the event has been committed before, and has been retried. So the event can
      // be ignored.
      if (committer.left <= lastCommitId.get()) {
        commitQueue.poll();
        continue;
      }

      if (committer.left != lastCommitId.get() + 1) {
        break;
      }

      committer.right.run();
      lastCommitId.incrementAndGet();

      commitQueue.poll();
    }
  }

  /**
   * Add failure event to retry queue.
   *
   * @param requestCommitId commit id of the request
   * @param event event to retry
   */
  public void addFailureEventToRetryQueue(long requestCommitId, Event event) {
    retryEventQueue.offer(new Pair<>(requestCommitId, event));
  }

  @Override
  // synchronized to avoid close connector when transfer event
  public synchronized void close() throws Exception {
    retryConnector.close();

    if (tabletBatchBuilder != null) {
      tabletBatchBuilder.close();
    }
  }
}
