/*
Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
Licensed under the Apache License, Version 2.0 (the "License").
You may not use this file except in compliance with the License.
A copy of the License is located at
    http://www.apache.org/licenses/LICENSE-2.0
or in the "license" file accompanying this file. This file is distributed
on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
express or implied. See the License for the specific language governing
permissions and limitations under the License.
*/

package org.apache.tinkerpop.gremlin.driver;

import org.apache.tinkerpop.gremlin.driver.exception.ConnectionException;
import org.apache.tinkerpop.gremlin.driver.message.RequestMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.utils.CollectionUtils;

import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class GremlinClient extends Client implements Refreshable, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(GremlinClient.class);

    private final AtomicReference<EndpointClientCollection> endpointClientCollection = new AtomicReference<>(new EndpointClientCollection());
    private final AtomicLong index = new AtomicLong(0);
    private final AtomicReference<CompletableFuture<Void>> closing = new AtomicReference<>(null);
    private final ConnectionAttemptManager connectionAttemptManager;
    private final ClientClusterCollection clientClusterCollection;
    private final ClusterFactory clusterFactory;
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final EndpointStrategies endpointStrategies;
    private final AcquireConnectionConfig acquireConnectionConfig;

    GremlinClient(Cluster cluster,
                  Settings settings,
                  EndpointClientCollection endpointClientCollection,
                  ClientClusterCollection clientClusterCollection,
                  ClusterFactory clusterFactory,
                  EndpointStrategies endpointStrategies,
                  AcquireConnectionConfig acquireConnectionConfig) {
        super(cluster, settings);

        this.endpointClientCollection.set(endpointClientCollection);
        this.clientClusterCollection = clientClusterCollection;
        this.clusterFactory = clusterFactory;
        this.endpointStrategies = endpointStrategies;
        this.acquireConnectionConfig = acquireConnectionConfig;
        this.connectionAttemptManager = acquireConnectionConfig.createConnectionAttemptManager(this);

        logger.info("availableEndpointFilter: {}", endpointStrategies.endpointFilter());
    }

    /**
     * Refreshes the list of endpoint addresses to which the client connects.
     */
    @Override
    public synchronized void refreshEndpoints(EndpointCollection endpoints) {

        if (closing.get() != null) {
            return;
        }

        EndpointFilter endpointFilter =
                new EmptyEndpointFilter(endpointStrategies.endpointFilter());

        EndpointClientCollection currentEndpointClientCollection = endpointClientCollection.get();

        EndpointCollection enrichedEndpoints = endpoints.getEnrichedEndpoints(endpointFilter);

        EndpointCollection acceptedEndpoints = enrichedEndpoints.getAcceptedEndpoints(endpointFilter);
        EndpointCollection rejectedEndpoints = enrichedEndpoints.getRejectedEndpoints(endpointFilter);

        List<EndpointClient> survivingEndpointClients =
                currentEndpointClientCollection.getSurvivingEndpointClients(acceptedEndpoints);

        EndpointCollection newEndpoints = acceptedEndpoints.getEndpointsWithNoCluster(clientClusterCollection);
        Map<Endpoint, Cluster> newEndpointClusters = clientClusterCollection.createClustersForEndpoints(newEndpoints);
        List<EndpointClient> newEndpointClients = EndpointClient.create(newEndpointClusters);

        EndpointClientCollection newEndpointClientCollection = new EndpointClientCollection(
                CollectionUtils.join(survivingEndpointClients, newEndpointClients),
                rejectedEndpoints
        );

        endpointClientCollection.set(newEndpointClientCollection);
        clientClusterCollection.removeClustersWithNoMatchingEndpoint(newEndpointClientCollection.endpoints());
    }

    @Override
    protected void initializeImplementation() {
        // Do nothing
    }

    @Override
    protected Connection chooseConnection(RequestMessage msg) throws TimeoutException, ConnectionException {

        long start = System.currentTimeMillis();

        logger.debug("Choosing connection");

        Connection connection = null;

        while (connection == null) {

            EndpointClientCollection currentEndpointClientCollection = endpointClientCollection.get();

            while (currentEndpointClientCollection.isEmpty()) {

                if (connectionAttemptManager.maxWaitTimeExceeded(start)) {
                    if (currentEndpointClientCollection.hasRejectedEndpoints()) {
                        throw new EndpointsUnavailableException(currentEndpointClientCollection.rejectionReasons());
                    } else {
                        throw new TimeoutException("Timed-out waiting for connection");
                    }
                }

                if (connectionAttemptManager.eagerRefreshWaitTimeExceeded(start)) {
                    connectionAttemptManager.triggerEagerRefresh(new EagerRefreshContext());
                }

                try {
                    Thread.sleep(acquireConnectionConfig.acquireConnectionBackoffMillis());
                    currentEndpointClientCollection = endpointClientCollection.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            connection = currentEndpointClientCollection.chooseConnection(
                    msg,
                    ec -> ec.get((int) (index.getAndIncrement() % ec.size())));

            if (connection == null) {

                if (connectionAttemptManager.maxWaitTimeExceeded(start)) {
                    throw new TimeoutException("Timed-out waiting for connection");
                }

                if (connectionAttemptManager.eagerRefreshWaitTimeExceeded(start)) {
                    connectionAttemptManager.triggerEagerRefresh(new EagerRefreshContext());
                }

                try {
                    Thread.sleep(acquireConnectionConfig.acquireConnectionBackoffMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        logger.debug("Connection: {} [{} ms]", connection.getConnectionInfo(), System.currentTimeMillis() - start);

        return connection;
    }

    @Override
    public Client alias(String graphOrTraversalSource) {
        return alias(makeDefaultAliasMap(graphOrTraversalSource));
    }

    @Override
    public Client alias(final Map<String, String> aliases) {
        return new GremlinAliasClusterClient(this, aliases, settings, clientClusterCollection);
    }

    @Override
    public boolean isClosing() {
        return closing.get() != null;
    }

    @Override
    public CompletableFuture<Void> closeAsync() {

        if (closing.get() != null)
            return closing.get();

        connectionAttemptManager.shutdownNow();
        executorService.shutdownNow();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (EndpointClient endpointClient : endpointClientCollection.get()) {
            futures.add(endpointClient.closeClientAsync());
        }

        closing.set(CompletableFuture.allOf(futures.toArray(new CompletableFuture[]{})));

        return closing.get();
    }

    @Override
    public synchronized Client init() {
        if (initialized)
            return this;

        logger.debug("Initializing internal clients");

        for (EndpointClient endpointClient : endpointClientCollection.get()) {
            endpointClient.initClient();
        }

        initializeImplementation();

        initialized = true;
        return this;
    }

    @Override
    public String toString() {

        return "Client holder queue: " + System.lineSeparator() +
                endpointClientCollection.get().stream()
                        .map(c -> String.format("  {address: %s, isAvailable: %s}",
                                c.endpoint().getAddress(),
                                !c.client().getCluster().availableHosts().isEmpty()))
                        .collect(Collectors.joining(System.lineSeparator())) +
                System.lineSeparator() +
                "Cluster collection: " + System.lineSeparator() +
                clientClusterCollection.toString();
    }

    public static class GremlinAliasClusterClient extends AliasClusteredClient {

        private final ClientClusterCollection clientClusterCollection;

        GremlinAliasClusterClient(Client client, Map<String, String> aliases, Settings settings, ClientClusterCollection clientClusterCollection) {
            super(client, aliases, settings);
            this.clientClusterCollection = clientClusterCollection;
        }

        @Override
        public Cluster getCluster() {
            Cluster cluster = clientClusterCollection.getFirstOrNull();
            if (cluster != null) {
                logger.trace("Returning: Cluster: {}, Hosts: [{}}",
                        cluster,
                        cluster.availableHosts().stream().map(URI::toString).collect(Collectors.joining(", ")));
                return cluster;
            } else {
                logger.warn("Unable to find cluster with available hosts in cluster collection, so returning parent cluster, which has no hosts.");
                return super.getCluster();
            }
        }
    }
}
