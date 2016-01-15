/*
 * Copyright 2015-2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
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
package org.hawkular.inventory.base;

import java.io.InputStream;
import java.util.Iterator;

import org.hawkular.inventory.api.Configuration;
import org.hawkular.inventory.api.EntityNotFoundException;
import org.hawkular.inventory.api.Interest;
import org.hawkular.inventory.api.Inventory;
import org.hawkular.inventory.api.Query;
import org.hawkular.inventory.api.Relationships;
import org.hawkular.inventory.api.Tenants;
import org.hawkular.inventory.api.TransactionFrame;
import org.hawkular.inventory.api.filters.With;
import org.hawkular.inventory.api.model.AbstractElement;
import org.hawkular.inventory.api.model.CanonicalPath;
import org.hawkular.inventory.api.model.Entity;
import org.hawkular.inventory.api.model.Relationship;
import org.hawkular.inventory.api.model.Tenant;
import org.hawkular.inventory.api.paging.Page;
import org.hawkular.inventory.api.paging.Pager;
import org.hawkular.inventory.base.spi.ElementNotFoundException;
import org.hawkular.inventory.base.spi.InventoryBackend;

import rx.Observable;

/**
 * An implementation of the {@link Inventory} that converts the API traversals into trees of filters that it then passes
 * for evaluation to a {@link InventoryBackend backend}.
 *
 * <p>This class is meant to be inherited by the implementation that should provide the initialization and cleanup
 * logic.
 *
 * @param <E> the type of the backend-specific class representing entities and relationships.
 *
 * @author Lukas Krejci
 * @since 0.1.0
 */
public abstract class BaseInventory<E> implements Inventory {

    public static final Configuration.Property TRANSACTION_RETRIES = Configuration.Property.builder()
            .withPropertyNameAndSystemProperty("hawkular.inventory.transaction.retries")
            .withEnvironmentVariables("HAWKULAR_INVENTORY_TRANSACTION_RETRIES").build();

    private InventoryBackend<E> backend;
    private final ObservableContext observableContext;
    private Configuration configuration;
    private TraversalContext<E, Tenant> tenantContext;
    private TraversalContext<E, Relationship> relationshipContext;
    private final TransactionConstructor<E> transactionConstructor;

    /**
     * This is a sort of copy constructor.
     *  @param backend           the backend
     * @param observableContext the observable context
     * @param transactionConstructor the transaction constructor to use
     */
    BaseInventory(InventoryBackend<E> backend, ObservableContext observableContext,
                  Configuration configuration, TransactionConstructor<E> transactionConstructor) {
        this.backend = backend;
        this.observableContext = observableContext;
        this.configuration = configuration;
        this.transactionConstructor = transactionConstructor;
    }

    protected BaseInventory() {
        observableContext = new ObservableContext();
        transactionConstructor = null;
    }

    /**
     * Mainly here for testing purposes
     * @param txCtor transaction constructor to use - useful to supply some test-enabled impl
     */
    protected BaseInventory(TransactionConstructor<E> txCtor) {
        observableContext = new ObservableContext();
        transactionConstructor = txCtor;
    }

    @Override
    public final void initialize(Configuration configuration) {
        this.backend = doInitialize(configuration);

        tenantContext = new TraversalContext<>(this, Query.empty(),
                Query.path().with(With.type(Tenant.class)).get(), backend, Tenant.class, configuration,
                observableContext, transactionConstructor);

        relationshipContext = new TraversalContext<>(this, Query.empty(), Query.path().get(), backend,
                Relationship.class, configuration, observableContext, transactionConstructor);
        this.configuration = configuration;
    }

    @Override
    public TransactionFrame newTransactionFrame() {
        return new BaseTransactionFrame<>(backend, observableContext, tenantContext);
    }

    Initialized<E> keepTransaction() {
        return new Initialized<>(new TransactionIgnoringBackend<>(backend), observableContext, configuration,
                TransactionConstructor.ignoreBackend());
    }

    /**
     * This method is called during {@link #initialize(Configuration)} and provides the instance of the backend
     * initialized from the configuration.
     *
     * @param configuration the configuration provided by the user
     * @return a backend implementation that will be used to access the backend store of the inventory
     */
    protected abstract InventoryBackend<E> doInitialize(Configuration configuration);

    @Override
    public final void close() throws Exception {
        if (backend != null) {
            backend.close();
            backend = null;
        }
    }

    @Override
    public Tenants.ReadWrite tenants() {
        return new BaseTenants.ReadWrite<>(tenantContext);
    }

    @Override
    public Relationships.Read relationships() {
        return new BaseRelationships.Read<>(relationshipContext);
    }

    /**
     * <b>WARNING</b>: This is not meant for general consumption but primarily for testing purposes. You can render
     * the inventory inconsistent and/or unusable with unwise modifications done directly through the backend.
     *
     * @return the backend this inventory is using for persistence and querying.
     */
    public InventoryBackend<E> getBackend() {
        return backend;
    }

    @Override
    public boolean hasObservers(Interest<?, ?> interest) {
        return observableContext.isObserved(interest);
    }

    @Override
    public <C, V> Observable<C> observable(Interest<C, V> interest) {
        return observableContext.getObservableFor(interest);
    }

    @Override
    public InputStream getGraphSON(String tenantId) {
        return getBackend().getGraphSON(tenantId);
    }

    @Override
    public AbstractElement getElement(CanonicalPath path) {
        try {
            return (AbstractElement) getBackend().find(path);
        } catch (ElementNotFoundException e) {
            throw new EntityNotFoundException("No element found on path: " + path.toString());
        }
    }

    @Override
    public <T extends Entity<?, ?>> Iterator<T> getTransitiveClosureOver(CanonicalPath startingPoint,
                                                                    Relationships.Direction direction, Class<T> clazz,
                                                                    String... relationshipNames) {

        return getBackend().getTransitiveClosureOver(startingPoint, direction, clazz, relationshipNames);
    }

    static class Initialized<E> extends BaseInventory<E> {
        Initialized(InventoryBackend<E> backend, ObservableContext observableContext,
                    Configuration configuration, TransactionConstructor<E> transactionConstructor) {
            super(backend, observableContext, configuration, transactionConstructor);
            initialize(configuration);
        }

        @Override
        protected InventoryBackend<E> doInitialize(Configuration configuration) {
            return getBackend();
        }
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public <T extends AbstractElement> Page<T> execute(Query query, Class<T> requestedEntity, Pager pager) {

        Page<T> page = backend.query(query, pager, e -> backend.convert(e, requestedEntity), null);

        return page;
    }
}
