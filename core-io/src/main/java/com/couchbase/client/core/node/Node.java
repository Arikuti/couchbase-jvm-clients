/*
 * Copyright (c) 2018 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.client.core.node;

import com.couchbase.client.core.CoreContext;
import com.couchbase.client.core.cnc.Event;
import com.couchbase.client.core.cnc.events.node.NodeConnectedEvent;
import com.couchbase.client.core.cnc.events.node.NodeDisconnectIgnoredEvent;
import com.couchbase.client.core.cnc.events.node.NodeDisconnectedEvent;
import com.couchbase.client.core.cnc.events.service.ServiceAddIgnoredEvent;
import com.couchbase.client.core.cnc.events.service.ServiceAddedEvent;
import com.couchbase.client.core.cnc.events.service.ServiceRemoveIgnoredEvent;
import com.couchbase.client.core.cnc.events.service.ServiceRemovedEvent;
import com.couchbase.client.core.env.CoreEnvironment;
import com.couchbase.client.core.env.Credentials;
import com.couchbase.client.core.io.NetworkAddress;
import com.couchbase.client.core.msg.Request;
import com.couchbase.client.core.msg.Response;
import com.couchbase.client.core.msg.ScopedRequest;
import com.couchbase.client.core.retry.RetryOrchestrator;
import com.couchbase.client.core.service.AnalyticsService;
import com.couchbase.client.core.service.KeyValueService;
import com.couchbase.client.core.service.ManagerService;
import com.couchbase.client.core.service.QueryService;
import com.couchbase.client.core.service.SearchService;
import com.couchbase.client.core.service.Service;
import com.couchbase.client.core.service.ServiceScope;
import com.couchbase.client.core.service.ServiceType;
import com.couchbase.client.core.service.ViewService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

public class Node {

  /**
   * Identifier for global scope services, there is no bucket name like this.
   */
  private static final String GLOBAL_SCOPE = "_$GLOBAL$_";

  private final NetworkAddress address;
  private final NodeContext ctx;
  private final Credentials credentials;
  private final Map<String, Map<ServiceType, Service>> services;
  private final AtomicBoolean disconnect;

  /**
   * Contains the enabled {@link Service}s on a node level.
   */
  private final AtomicInteger enabledServices = new AtomicInteger(0);

  public static Node create(final CoreContext ctx, final NetworkAddress address) {
    return new Node(ctx, address);
  }

  protected Node(final CoreContext ctx, final NetworkAddress address) {
    this.address = address;
    this.ctx = new NodeContext(ctx, address);
    this.credentials = ctx.environment().credentials();
    this.services = new ConcurrentHashMap<>();
    this.disconnect = new AtomicBoolean(false);

    ctx.environment().eventBus().publish(new NodeConnectedEvent(Duration.ZERO, this.ctx));
  }

  /**
   * Instruct this {@link Node} to disconnect.
   *
   * <p>This method is async and will return immediately. Use the other methods available to
   * inspect the current state of the node, signaling potential successful disconnection
   * attempts.</p>
   */
  public synchronized Mono<Void> disconnect() {
    return Mono.defer(() -> {
      if (disconnect.compareAndSet(false, true)) {
        final AtomicLong start = new AtomicLong();
        return Flux
          .fromIterable(services.entrySet())
          .flatMap(entry -> {
            start.set(System.nanoTime());
            return Flux
              .fromIterable(entry.getValue().keySet())
              .flatMap(serviceType ->
                removeService(serviceType, Optional.of(entry.getKey()), true)
              );
          })
          .then()
          .doOnTerminate(() ->
            ctx.environment().eventBus().publish(new NodeDisconnectedEvent(
              Duration.ofNanos(System.nanoTime() - start.get()),
              ctx
            ))
          );
      } else {
        ctx.environment().eventBus().publish(new NodeDisconnectIgnoredEvent(
          Event.Severity.DEBUG,
          NodeDisconnectIgnoredEvent.Reason.DISCONNECTED,
          ctx
        ));
      }
      return Mono.empty();
    });
  }

  /**
   * Adds a {@link Service} to this {@link Node}.
   *
   * @param type the type of the service.
   * @param port the port of the service.
   * @param bucket the bucket name (if present).
   * @return a {@link Mono} that completes once the service is added.
   */
  public synchronized Mono<Void> addService(final ServiceType type, final int port,
                                            final Optional<String> bucket) {
    return Mono.defer(() -> {
      if (disconnect.get()) {
        ctx.environment().eventBus().publish(new ServiceAddIgnoredEvent(
          Event.Severity.DEBUG,
          ServiceAddIgnoredEvent.Reason.DISCONNECTED,
          ctx
        ));
        return Mono.empty();
      }

      String name = type.scope() == ServiceScope.CLUSTER ? GLOBAL_SCOPE : bucket.get();
      Map<ServiceType, Service> localMap = services.get(name);
      if (localMap == null) {
        localMap = new ConcurrentHashMap<>();
        services.put(name, localMap);
      }
      if (!localMap.containsKey(type)) {
        long start = System.nanoTime();
        Service service = createService(type, port, bucket);
        localMap.put(type, service);
        enabledServices.set(enabledServices.get() | 1 << type.ordinal());
        // todo: only return once the service is connected?
        service.connect();
        long end = System.nanoTime();
        ctx.environment().eventBus().publish(
          new ServiceAddedEvent(Duration.ofNanos(end - start), service.context())
        );
        return Mono.empty();
      } else {
        ctx.environment().eventBus().publish(new ServiceAddIgnoredEvent(
          Event.Severity.VERBOSE,
          ServiceAddIgnoredEvent.Reason.ALREADY_ADDED,
          ctx
        ));
        return Mono.empty();
      }
    });
  }

  /**
   * Removes a {@link Service} from this {@link Node}.
   *
   * @param type the type of service.
   * @param bucket the bucket name if present.
   * @return a mono once completed.
   */
  public Mono<Void> removeService(final ServiceType type, final Optional<String> bucket) {
    return removeService(type, bucket, false);
  }

  private synchronized Mono<Void> removeService(final ServiceType type,
                                                final Optional<String> bucket,
                                                boolean ignoreDisconnect) {
    return Mono.defer(() -> {
      if (disconnect.get() && !ignoreDisconnect) {
        ctx.environment().eventBus().publish(new ServiceRemoveIgnoredEvent(
          Event.Severity.DEBUG,
          ServiceRemoveIgnoredEvent.Reason.DISCONNECTED,
          ctx
        ));
        return Mono.empty();
      }

      String name = type.scope() == ServiceScope.CLUSTER ? GLOBAL_SCOPE : bucket.get();
      Map<ServiceType, Service> localMap = services.get(name);
      if (localMap == null || !localMap.containsKey(type)) {
        ctx.environment().eventBus().publish(new ServiceRemoveIgnoredEvent(
          Event.Severity.DEBUG,
          ServiceRemoveIgnoredEvent.Reason.NOT_PRESENT,
          ctx
        ));
        return Mono.empty();
      }

      Service service = localMap.remove(type);
      long start = System.nanoTime();
      enabledServices.set(enabledServices.get() & ~(1 << service.type().ordinal()));
      // todo: only return once the service is disconnected?
      service.disconnect();
      long end = System.nanoTime();
      ctx.environment().eventBus().publish(
        new ServiceRemovedEvent(Duration.ofNanos(end - start), service.context())
      );
      return Mono.empty();
    });
  }


  private synchronized List<Service> services() {
    return this.services.values().stream()
      .flatMap(m -> m.values().stream())
      .collect(Collectors.toList());
  }

  public NodeState state() {
    // todo: this is a bit wasteful :/
    List<Service> currentServices = services();

    if (currentServices.isEmpty()) {
      return NodeState.DISCONNECTED;
    }

    int connected = 0;
    int connecting = 0;
    int disconnecting = 0;
    int idle = 0;
    int degraded = 0;
    for (Service service : currentServices) {
      switch (service.state()) {
        case CONNECTED:
          connected++;
          break;
        case CONNECTING:
          connecting++;
          break;
        case DISCONNECTING:
          disconnecting++;
          break;
        case DEGRADED:
          degraded++;
          break;
        case IDLE:
          idle++;
        case DISCONNECTED:
          // Intentionally ignored.
          break;
        default:
          throw new IllegalStateException("Unknown unhandled state " + service.state()
            + ", this is a bug!");
      }
    }
    if (currentServices.size() == idle) {
      return NodeState.IDLE;
    } else if (currentServices.size() == (connected + idle)) {
      return NodeState.CONNECTED;
    } else if (connected > 0 || degraded > 0) {
      return NodeState.DEGRADED;
    } else if (connecting > 0) {
      return NodeState.CONNECTING;
    } else if (disconnecting > 0) {
      return NodeState.DISCONNECTING;
    } else {
      return NodeState.DISCONNECTED;
    }
  }

  /**
   * Sends the request into this {@link Node}.
   *
   * <p>Note that there is no guarantee that the request will actually dispatched, based on the
   * state this node is in.</p>
   *
   * @param request the request to send.
   */
  public <R extends Request<? extends Response>> void send(final R request) {
    String bucket = request.serviceType().scope() == ServiceScope.BUCKET
      ? ((ScopedRequest) request).bucket()
      : GLOBAL_SCOPE;

    Map<ServiceType, Service> scope = services.get(bucket);
    if (scope == null) {
      sendIntoRetry(request);
      return;
    }

    Service service = scope.get(request.serviceType());
    if (service == null) {
      sendIntoRetry(request);
      return;
    }

    request.context().dispatchedTo(address);
    service.send(request);
  }

  /**
   * Retries the request.
   *
   * <p>This is a separate method because in test it is overriden to do easy assertions.</p>
   *
   * @param request the request to retry.
   */
  protected <R extends Request<? extends Response>> void sendIntoRetry(final R request) {
    RetryOrchestrator.maybeRetry(ctx, request);
  }

  /**
   * Returns the address of the current node.
   */
  public NetworkAddress address() {
    return address;
  }

  /**
   * If a given {@link ServiceType} is enabled on this node.
   *
   * @param type the service type to check.
   * @return true if enabled, false otherwise.
   */
  public boolean serviceEnabled(final ServiceType type) {
    return (enabledServices.get() & (1 << type.ordinal())) != 0;
  }

  public boolean hasServicesEnabled() {
    return enabledServices.get() != 0;
  }

  /**
   * Helper method to create the {@link Service} based on the service type provided.
   *
   * @param serviceType the type of service to create.
   * @param port the port for that service.
   * @param bucket optionally the bucket name.
   * @return a created service, but not yet connected or anything.
   */
  protected Service createService(final ServiceType serviceType, final int port,
                                final Optional<String> bucket) {
    CoreEnvironment env = ctx.environment();
    switch (serviceType) {
      case KV:
        if (bucket.isPresent()) {
          return new KeyValueService(env.keyValueServiceConfig(), ctx, address, port,
            bucket.get(), credentials);
        } else {
          throw new IllegalStateException("Bucket needs to be present when the " +
            "KeyValueService is created, this is a bug!");
        }
      case MANAGER:
        return new ManagerService(ctx, address, port);
      case QUERY:
        return new QueryService(env.queryServiceConfig(), ctx, address, port);
      case VIEWS:
        return new ViewService(env.viewServiceConfig(), ctx, address, port);
      case SEARCH:
        return new SearchService(env.searchServiceConfig(), ctx, address, port);
      case ANALYTICS:
        return new AnalyticsService(env.analyticsServiceConfig(), ctx, address, port);
      default:
        throw new IllegalArgumentException("Unsupported ServiceType: " + serviceType);
    }
  }
}