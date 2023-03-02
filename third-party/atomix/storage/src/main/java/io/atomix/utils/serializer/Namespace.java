/*
 * Copyright 2014-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.utils.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Registration;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;
import com.esotericsoftware.kryo.pool.KryoCallback;
import com.esotericsoftware.kryo.pool.KryoFactory;
import com.esotericsoftware.kryo.pool.KryoPool;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Pool of Kryo instances, with classes pre-registered.
 */
//@ThreadSafe
public final class Namespace implements KryoFactory, KryoPool {

  /**
   * Default buffer size used for serialization.
   *
   * @see #serialize(Object)
   */
  public static final int DEFAULT_BUFFER_SIZE = 4096;

  /**
   * ID to use if this KryoNamespace does not define registration id.
   */
  private static final int FLOATING_ID = -1;

  /**
   * Smallest ID free to use for user defined registrations.
   */
  private static final int INITIAL_ID = 16;

  static final String NO_NAME = "(no name)";

  private static final Logger LOGGER = LoggerFactory.getLogger(Namespace.class);

  private final KryoPool kryoPool = new KryoPool.Builder(this).softReferences().build();

  private final KryoOutputPool kryoOutputPool = new KryoOutputPool();
  private final KryoInputPool kryoInputPool = new KryoInputPool();

  private final ImmutableList<RegistrationBlock> registeredBlocks;

  private final ClassLoader classLoader;
  private final String friendlyName;

  /**
   * KryoNamespace builder.
   */
  //@NotThreadSafe
  public static final class Builder {
    private int blockHeadId = INITIAL_ID;
    private List<Entry<Class<?>[], Serializer<?>>> types = new ArrayList<>();
    private List<RegistrationBlock> blocks = new ArrayList<>();
    private ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    /**
     * Builds a {@link Namespace} instance.
     *
     * @return KryoNamespace
     */
    public Namespace build() {
      return build(NO_NAME);
    }

    /**
     * Builds a {@link Namespace} instance.
     *
     * @param friendlyName friendly name for the namespace
     * @return KryoNamespace
     */
    public Namespace build(String friendlyName) {
      if (!types.isEmpty()) {
        blocks.add(new RegistrationBlock(this.blockHeadId, types));
      }
      return new Namespace(blocks, classLoader, friendlyName);
    }

    /**
     * Registers serializer for the given set of classes.
     * <p>
     * When multiple classes are registered with an explicitly provided serializer, the namespace guarantees
     * all instances will be serialized with the same type ID.
     *
     * @param classes    list of classes to register
     * @param serializer serializer to use for the class
     * @return this
     */
    public Builder register(Serializer<?> serializer, final Class<?>... classes) {
      types.add(Map.entry(classes, serializer));
      return this;
    }

    /**
     * Sets the namespace class loader.
     *
     * @param classLoader the namespace class loader
     * @return the namespace builder
     */
    public Builder setClassLoader(ClassLoader classLoader) {
      this.classLoader = classLoader;
      return this;
    }
  }

  /**
   * Creates a new {@link Namespace} builder.
   *
   * @return builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * Creates a Kryo instance pool.
   *
   * @param registeredTypes      types to register
   * @param registrationRequired whether registration is required
   * @param friendlyName         friendly name for the namespace
   */
  private Namespace(
      final List<RegistrationBlock> registeredTypes,
      ClassLoader classLoader,
      String friendlyName) {
    this.registeredBlocks = ImmutableList.copyOf(registeredTypes);
    this.classLoader = classLoader;
    this.friendlyName = requireNonNull(friendlyName);

    // Pre-populate with a single instance
    release(create());
  }

  /**
   * Serializes given object to byte array using Kryo instance in pool.
   * <p>
   * Note: Serialized bytes must be smaller than {@link #MAX_BUFFER_SIZE}.
   *
   * @param obj Object to serialize
   * @return serialized bytes
   */
  public byte[] serialize(final Object obj) {
    return serialize(obj, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Serializes given object to byte array using Kryo instance in pool.
   *
   * @param obj        Object to serialize
   * @param bufferSize maximum size of serialized bytes
   * @return serialized bytes
   */
  public byte[] serialize(final Object obj, final int bufferSize) {
    return kryoOutputPool.run(output -> {
      return kryoPool.run(kryo -> {
        kryo.writeClassAndObject(output, obj);
        output.flush();
        return output.getByteArrayOutputStream().toByteArray();
      });
    }, bufferSize);
  }

  /**
   * Serializes given object to byte buffer using Kryo instance in pool.
   *
   * @param obj    Object to serialize
   * @param buffer to write to
   */
  public void serialize(final Object obj, final ByteBuffer buffer) {
    ByteBufferOutput out = new ByteBufferOutput(buffer);
    Kryo kryo = borrow();
    try {
      kryo.writeClassAndObject(out, obj);
      out.flush();
    } finally {
      release(kryo);
    }
  }

  /**
   * Serializes given object to OutputStream using Kryo instance in pool.
   *
   * @param obj    Object to serialize
   * @param stream to write to
   */
  public void serialize(final Object obj, final OutputStream stream) {
    serialize(obj, stream, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Serializes given object to OutputStream using Kryo instance in pool.
   *
   * @param obj        Object to serialize
   * @param stream     to write to
   * @param bufferSize size of the buffer in front of the stream
   */
  public void serialize(final Object obj, final OutputStream stream, final int bufferSize) {
    ByteBufferOutput out = new ByteBufferOutput(stream, bufferSize);
    Kryo kryo = borrow();
    try {
      kryo.writeClassAndObject(out, obj);
      out.flush();
    } finally {
      release(kryo);
    }
  }

  /**
   * Deserializes given byte array to Object using Kryo instance in pool.
   *
   * @param bytes serialized bytes
   * @param <T>   deserialized Object type
   * @return deserialized Object
   */
  public <T> T deserialize(final byte[] bytes) {
    return kryoInputPool.run(input -> {
      input.setInputStream(new ByteArrayInputStream(bytes));
      return kryoPool.run(kryo -> {
        @SuppressWarnings("unchecked")
        T obj = (T) kryo.readClassAndObject(input);
        return obj;
      });
    }, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Deserializes given byte buffer to Object using Kryo instance in pool.
   *
   * @param buffer input with serialized bytes
   * @param <T>    deserialized Object type
   * @return deserialized Object
   */
  public <T> T deserialize(final ByteBuffer buffer) {
    ByteBufferInput in = new ByteBufferInput(buffer);
    Kryo kryo = borrow();
    try {
      @SuppressWarnings("unchecked")
      T obj = (T) kryo.readClassAndObject(in);
      return obj;
    } finally {
      release(kryo);
    }
  }

  /**
   * Deserializes given InputStream to an Object using Kryo instance in pool.
   *
   * @param stream input stream
   * @param <T>    deserialized Object type
   * @return deserialized Object
   */
  public <T> T deserialize(final InputStream stream) {
    return deserialize(stream, DEFAULT_BUFFER_SIZE);
  }

  /**
   * Deserializes given InputStream to an Object using Kryo instance in pool.
   *
   * @param stream     input stream
   * @param <T>        deserialized Object type
   * @param bufferSize size of the buffer in front of the stream
   * @return deserialized Object
   */
  public <T> T deserialize(final InputStream stream, final int bufferSize) {
    ByteBufferInput in = new ByteBufferInput(stream, bufferSize);
    Kryo kryo = borrow();
    try {
      @SuppressWarnings("unchecked")
      T obj = (T) kryo.readClassAndObject(in);
      return obj;
    } finally {
      release(kryo);
    }
  }

  /**
   * Creates a Kryo instance.
   *
   * @return Kryo instance
   */
  @Override
  public Kryo create() {
    LOGGER.trace("Creating Kryo instance for {}", this);
    Kryo kryo = new Kryo();
    kryo.setClassLoader(classLoader);
    kryo.setRegistrationRequired(true);

    // TODO rethink whether we want to use StdInstantiatorStrategy
    kryo.setInstantiatorStrategy(
        new Kryo.DefaultInstantiatorStrategy(new StdInstantiatorStrategy()));

    for (RegistrationBlock block : registeredBlocks) {
      int id = block.begin();
      if (id == FLOATING_ID) {
        id = kryo.getNextRegistrationId();
      }
      for (Entry<Class<?>[], Serializer<?>> entry : block.types()) {
        register(kryo, entry.getKey(), entry.getValue(), id++);
      }
    }
    return kryo;
  }

  /**
   * Register {@code type} and {@code serializer} to {@code kryo} instance.
   *
   * @param kryo       Kryo instance
   * @param types      types to register
   * @param serializer Specific serializer to register or null to use default.
   * @param id         type registration id to use
   */
  private void register(Kryo kryo, Class<?>[] types, Serializer<?> serializer, int id) {
    Registration existing = kryo.getRegistration(id);
    if (existing != null) {
      boolean matches = false;
      for (Class<?> type : types) {
        if (existing.getType() == type) {
          matches = true;
          break;
        }
      }

      if (!matches) {
        LOGGER.error("{}: Failed to register {} as {}, {} was already registered.",
            friendlyName, types, id, existing.getType());

        throw new IllegalStateException(String.format(
            "Failed to register %s as %s, %s was already registered.",
            Arrays.toString(types), id, existing.getType()));
      }
      // falling through to register call for now.
      // Consider skipping, if there's reasonable
      // way to compare serializer equivalence.
    }

    for (Class<?> type : types) {
      Registration r = null;
      if (serializer == null) {
        r = kryo.register(type, id);
      } else if (type.isInterface()) {
        kryo.addDefaultSerializer(type, serializer);
      } else {
        r = kryo.register(type, serializer, id);
      }
      if (r != null) {
        if (r.getId() != id) {
          LOGGER.debug("{}: {} already registered as {}. Skipping {}.",
              friendlyName, r.getType(), r.getId(), id);
        }
        LOGGER.trace("{} registered as {}", r.getType(), r.getId());
      }
    }
  }

  @Override
  public Kryo borrow() {
    return kryoPool.borrow();
  }

  @Override
  public void release(Kryo kryo) {
    kryoPool.release(kryo);
  }

  @Override
  public <T> T run(KryoCallback<T> callback) {
    return kryoPool.run(callback);
  }

  @Override
  public String toString() {
    if (!NO_NAME.equals(friendlyName)) {
      return MoreObjects.toStringHelper(getClass())
          .omitNullValues()
          .add("friendlyName", friendlyName)
          // omit lengthy detail, when there's a name
          .toString();
    }
    return MoreObjects.toStringHelper(getClass())
        .add("registeredBlocks", registeredBlocks)
        .toString();
  }

  static final class RegistrationBlock {
    private final int begin;
    private final ImmutableList<Entry<Class<?>[], Serializer<?>>> types;

    RegistrationBlock(int begin, List<Entry<Class<?>[], Serializer<?>>> types) {
      this.begin = begin;
      this.types = ImmutableList.copyOf(types);
    }

    public int begin() {
      return begin;
    }

    public ImmutableList<Entry<Class<?>[], Serializer<?>>> types() {
      return types;
    }

    @Override
    public String toString() {
      return MoreObjects.toStringHelper(getClass())
          .add("begin", begin)
          .add("types", types)
          .toString();
    }

    @Override
    public int hashCode() {
      return types.hashCode();
    }

    // Only the registered types are used for equality.
    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }

      if (obj instanceof RegistrationBlock) {
        RegistrationBlock that = (RegistrationBlock) obj;
        return Objects.equals(this.types, that.types);
      }
      return false;
    }
  }
}
