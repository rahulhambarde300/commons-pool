/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.pool2.impl;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

import org.apache.commons.pool2.BaseObjectPool;
import org.apache.commons.pool2.ObjectPool;
import org.apache.commons.pool2.PoolUtils;
import org.apache.commons.pool2.PooledObjectFactory;

/**
 * A {@link java.lang.ref.SoftReference SoftReference} based {@link ObjectPool}.
 * <p>
 * This class is intended to be thread-safe.
 * </p>
 *
 * @param <T>
 *            Type of element pooled in this pool.
 * @param <E>
 *            Type of exception thrown by this pool.
 *
 * @since 2.0
 */
public class SoftReferenceObjectPool<T, E extends Exception> extends BaseObjectPool<T, E> {

    /** Factory to source pooled objects. */
    private final PooledObjectFactory<T, E> factory;

    /**
     * Queue of broken references that might be able to be removed from
     * {@code _pool}. This is used to help {@link #getNumIdle()} be more
     * accurate with minimal performance overhead.
     */
    private final ReferenceQueue<T> refQueue = new ReferenceQueue<>();

    /** Count of instances that have been checkout out to pool clients */
    private int numActive; // @GuardedBy("this")

    /** Total number of instances that have been destroyed */
    private long destroyCount; // @GuardedBy("this")


    /** Total number of instances that have been created */
    private long createCount; // @GuardedBy("this")

    /** Idle references - waiting to be borrowed */
    private final LinkedBlockingDeque<PooledSoftReference<T>> idleReferences = new LinkedBlockingDeque<>();

    /** All references - checked out or waiting to be borrowed. */
    private final ArrayList<PooledSoftReference<T>> allReferences = new ArrayList<>();

    /**
     * Constructs a {@code SoftReferenceObjectPool} with the specified factory.
     *
     * @param factory non-null object factory to use.
     */
    public SoftReferenceObjectPool(final PooledObjectFactory<T, E> factory) {
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /**
     * Creates an object, and places it into the pool. addObject() is useful for
     * "pre-loading" a pool with idle objects.
     * <p>
     * Before being added to the pool, the newly created instance is
     * {@link PooledObjectFactory#validateObject(
     * org.apache.commons.pool2.PooledObject) validated} and
     * {@link PooledObjectFactory#passivateObject(
     * org.apache.commons.pool2.PooledObject) passivated}. If
     * validation fails, the new instance is
     * {@link PooledObjectFactory#destroyObject(
     * org.apache.commons.pool2.PooledObject) destroyed}. Exceptions
     * generated by the factory {@code makeObject} or
     * {@code passivate} are propagated to the caller. Exceptions
     * destroying instances are silently swallowed.
     * </p>
     *
     * @throws IllegalStateException
     *             if invoked on a {@link #close() closed} pool
     * @throws E
     *             when the {@link #getFactory() factory} has a problem creating
     *             or passivating an object.
     */
    @Override
    public synchronized void addObject() throws E {
        assertOpen();
        final T obj = factory.makeObject().getObject();
        createCount++;
        // Create and register with the queue
        final PooledSoftReference<T> ref = new PooledSoftReference<>(new SoftReference<>(obj, refQueue));
        allReferences.add(ref);

        boolean success = true;
        if (!factory.validateObject(ref)) {
            success = false;
        } else {
            factory.passivateObject(ref);
        }

        final boolean shouldDestroy = !success;
        if (success) {
            idleReferences.add(ref);
            notifyAll(); // numActive has changed
        }

        if (shouldDestroy) {
            try {
                destroy(ref);
            } catch (final Exception ignored) {
                // ignored
            }
        }
    }

    /**
     * Borrows an object from the pool. If there are no idle instances available
     * in the pool, the configured factory's
     * {@link PooledObjectFactory#makeObject()} method is invoked to create a
     * new instance.
     * <p>
     * All instances are {@link PooledObjectFactory#activateObject(
     * org.apache.commons.pool2.PooledObject) activated}
     * and {@link PooledObjectFactory#validateObject(
     * org.apache.commons.pool2.PooledObject)
     * validated} before being returned by this method. If validation fails or
     * an exception occurs activating or validating an idle instance, the
     * failing instance is {@link PooledObjectFactory#destroyObject(
     * org.apache.commons.pool2.PooledObject)
     * destroyed} and another instance is retrieved from the pool, validated and
     * activated. This process continues until either the pool is empty or an
     * instance passes validation. If the pool is empty on activation or it does
     * not contain any valid instances, the factory's {@code makeObject}
     * method is used to create a new instance. If the created instance either
     * raises an exception on activation or fails validation,
     * {@code NoSuchElementException} is thrown. Exceptions thrown by
     * {@code MakeObject} are propagated to the caller; but other than
     * {@code ThreadDeath} or {@code VirtualMachineError}, exceptions
     * generated by activation, validation or destroy methods are swallowed
     * silently.
     * </p>
     *
     * @throws NoSuchElementException
     *             if a valid object cannot be provided
     * @throws IllegalStateException
     *             if invoked on a {@link #close() closed} pool
     * @throws E
     *             if an exception occurs creating a new instance
     * @return a valid, activated object instance
     */
    @SuppressWarnings("null") // ref cannot be null
    @Override
    public synchronized T borrowObject() throws E {
        assertOpen();
        T obj = null;
        boolean newlyCreated = false;
        PooledSoftReference<T> ref = null;
        while (null == obj) {
            if (idleReferences.isEmpty()) {
                newlyCreated = true;
                obj = factory.makeObject().getObject();
                createCount++;
                // Do not register with the queue
                ref = new PooledSoftReference<>(new SoftReference<>(obj));
                allReferences.add(ref);
            } else {
                ref = idleReferences.pollFirst();
                obj = ref.getObject();
                // Clear the reference so it will not be queued, but replace with a
                // a new, non-registered reference so we can still track this object
                // in allReferences
                ref.getReference().clear();
                ref.setReference(new SoftReference<>(obj));
            }
            if (null != obj) {
                try {
                    factory.activateObject(ref);
                    if (!factory.validateObject(ref)) {
                        throw new Exception("ValidateObject failed");
                    }
                } catch (final Throwable t) {
                    PoolUtils.checkRethrow(t);
                    try {
                        destroy(ref);
                    } catch (final Throwable t2) {
                        PoolUtils.checkRethrow(t2);
                        // Swallowed
                    } finally {
                        obj = null;
                    }
                    if (newlyCreated) {
                        throw new NoSuchElementException("Could not create a validated object, cause: " + t);
                    }
                }
            }
        }
        numActive++;
        ref.allocate();
        return obj;
    }

    /**
     * Clears any objects sitting idle in the pool.
     */
    @Override
    public synchronized void clear() {
        idleReferences.forEach(pooledSoftRef -> {
            try {
                if (null != pooledSoftRef.getObject()) {
                    factory.destroyObject(pooledSoftRef);
                }
            } catch (final Exception ignored) {
                // ignored, keep destroying the rest
            }
        });
        idleReferences.clear();
        pruneClearedReferences();
    }

    /**
     * Closes this pool, and frees any resources associated with it. Invokes
     * {@link #clear()} to destroy and remove instances in the pool.
     * <p>
     * Calling {@link #addObject} or {@link #borrowObject} after invoking this
     * method on a pool will cause them to throw an
     * {@link IllegalStateException}.
     * </p>
     */
    @Override
    public void close() {
        super.close();
        clear();
    }

    /**
     * Destroys a {@code PooledSoftReference} and removes it from the idle and all
     * references pools.
     *
     * @param toDestroy PooledSoftReference to destroy
     *
     * @throws E If an error occurs while trying to destroy the object
     */
    private void destroy(final PooledSoftReference<T> toDestroy) throws E {
        toDestroy.invalidate();
        idleReferences.remove(toDestroy);
        allReferences.remove(toDestroy);
        try {
            factory.destroyObject(toDestroy);
        } finally {
            destroyCount++;
            toDestroy.getReference().clear();
        }
    }

    /**
     * Finds the PooledSoftReference in allReferences that points to obj.
     *
     * @param obj returning object
     * @return PooledSoftReference wrapping a soft reference to obj
     */
    private PooledSoftReference<T> findReference(final T obj) {
        final Optional<PooledSoftReference<T>> first = allReferences.stream()
                .filter(reference -> reference.getObject() != null && reference.getObject().equals(obj)).findFirst();
        return first.orElse(null);
    }

    /**
     * Gets the {@link PooledObjectFactory} used by this pool to create and
     * manage object instances.
     *
     * @return the factory
     */
    public synchronized PooledObjectFactory<T, E> getFactory() {
        return factory;
    }

    /**
     * Gets the number of instances currently borrowed from this pool.
     *
     * @return the number of instances currently borrowed from this pool
     */
    @Override
    public synchronized int getNumActive() {
        return numActive;
    }

    /**
     * Gets an approximation not less than the of the number of idle
     * instances in the pool.
     *
     * @return estimated number of idle instances in the pool
     */
    @Override
    public synchronized int getNumIdle() {
        pruneClearedReferences();
        return idleReferences.size();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void invalidateObject(final T obj) throws E {
        final PooledSoftReference<T> ref = findReference(obj);
        if (ref == null) {
            throw new IllegalStateException("Object to invalidate is not currently part of this pool");
        }
        destroy(ref);
        numActive--;
        notifyAll(); // numActive has changed
    }

    /**
     * If any idle objects were garbage collected, remove their
     * {@link Reference} wrappers from the idle object pool.
     */
    private void pruneClearedReferences() {
        // Remove wrappers for enqueued references from idle and allReferences lists
        removeClearedReferences(idleReferences);
        removeClearedReferences(allReferences);
        while (refQueue.poll() != null) { // NOPMD
        }
    }

    /**
     * Clears cleared references from the collection.
     *
     * @param collection collection of idle/allReferences
     */
    private void removeClearedReferences(final Collection<PooledSoftReference<T>> collection) {
        collection.removeIf(ref -> ref.getReference() == null || ref.getReference().isEnqueued());
    }

    /**
     * Returns an instance to the pool after successful validation and
     * passivation. The returning instance is destroyed if any of the following
     * are true:
     * <ul>
     * <li>the pool is closed</li>
     * <li>{@link PooledObjectFactory#validateObject(
     * org.apache.commons.pool2.PooledObject) validation} fails
     * </li>
     * <li>{@link PooledObjectFactory#passivateObject(
     * org.apache.commons.pool2.PooledObject) passivation}
     * throws an exception</li>
     * </ul>
     * Exceptions passivating or destroying instances are silently swallowed.
     * Exceptions validating instances are propagated to the client.
     *
     * @param obj
     *            instance to return to the pool
     * @throws IllegalArgumentException
     *            if obj is not currently part of this pool
     */
    @Override
    public synchronized void returnObject(final T obj) throws E {
        boolean success = !isClosed();
        final PooledSoftReference<T> ref = findReference(obj);
        if (ref == null) {
            throw new IllegalStateException("Returned object not currently part of this pool");
        }
        if (!factory.validateObject(ref)) {
            success = false;
        } else {
            try {
                factory.passivateObject(ref);
            } catch (final Exception e) {
                success = false;
            }
        }

        final boolean shouldDestroy = !success;
        numActive--;
        if (success) {

            // Deallocate and add to the idle instance pool
            ref.deallocate();
            idleReferences.add(ref);
        }
        notifyAll(); // numActive has changed

        if (shouldDestroy) {
            try {
                destroy(ref);
            } catch (final Exception ignored) {
                // ignored
            }
        }
    }

    @Override
    protected void toStringAppendFields(final StringBuilder builder) {
        super.toStringAppendFields(builder);
        builder.append(", factory=");
        builder.append(factory);
        builder.append(", refQueue=");
        builder.append(refQueue);
        builder.append(", numActive=");
        builder.append(numActive);
        builder.append(", destroyCount=");
        builder.append(destroyCount);
        builder.append(", createCount=");
        builder.append(createCount);
        builder.append(", idleReferences=");
        builder.append(idleReferences);
        builder.append(", allReferences=");
        builder.append(allReferences);
    }
}
