/*
 * Copyright (c) 2015 Brocade Communications Systems, Inc. and others.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package org.opendaylight.controller.cluster.datastore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.opendaylight.controller.cluster.datastore.DataStoreVersions.CURRENT_VERSION;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.successfulCanCommit;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.successfulCommit;
import static org.opendaylight.controller.cluster.datastore.ShardDataTreeMocking.successfulPreCommit;

import com.google.common.primitives.UnsignedLong;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Uninterruptibles;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pekko.actor.ActorRef;
import org.apache.pekko.actor.PoisonPill;
import org.apache.pekko.actor.Props;
import org.apache.pekko.dispatch.Dispatchers;
import org.apache.pekko.japi.Creator;
import org.apache.pekko.pattern.Patterns;
import org.apache.pekko.testkit.TestActorRef;
import org.apache.pekko.util.Timeout;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.opendaylight.controller.cluster.access.concepts.MemberName;
import org.opendaylight.controller.cluster.access.concepts.TransactionIdentifier;
import org.opendaylight.controller.cluster.datastore.DatastoreContext.Builder;
import org.opendaylight.controller.cluster.datastore.identifiers.ShardIdentifier;
import org.opendaylight.controller.cluster.datastore.messages.BatchedModifications;
import org.opendaylight.controller.cluster.datastore.messages.ForwardedReadyTransaction;
import org.opendaylight.controller.cluster.datastore.modification.MutableCompositeModification;
import org.opendaylight.controller.cluster.datastore.modification.WriteModification;
import org.opendaylight.controller.cluster.datastore.persisted.CommitTransactionPayload;
import org.opendaylight.controller.cluster.datastore.persisted.MetadataShardDataTreeSnapshot;
import org.opendaylight.controller.cluster.datastore.persisted.ShardSnapshotState;
import org.opendaylight.controller.cluster.raft.ReplicatedLogEntry;
import org.opendaylight.controller.cluster.raft.TestActorFactory;
import org.opendaylight.controller.cluster.raft.persisted.Snapshot;
import org.opendaylight.controller.cluster.raft.utils.InMemoryJournal;
import org.opendaylight.controller.cluster.raft.utils.InMemorySnapshotStore;
import org.opendaylight.controller.md.cluster.datastore.model.CarsModel;
import org.opendaylight.controller.md.cluster.datastore.model.TestModel;
import org.opendaylight.yangtools.yang.common.Empty;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier;
import org.opendaylight.yangtools.yang.data.api.YangInstanceIdentifier.NodeIdentifier;
import org.opendaylight.yangtools.yang.data.api.schema.DataContainerChild;
import org.opendaylight.yangtools.yang.data.api.schema.MapEntryNode;
import org.opendaylight.yangtools.yang.data.api.schema.NormalizedNode;
import org.opendaylight.yangtools.yang.data.spi.node.ImmutableNodes;
import org.opendaylight.yangtools.yang.data.tree.api.DataTree;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidate;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidateNode;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeCandidateTip;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeConfiguration;
import org.opendaylight.yangtools.yang.data.tree.api.DataTreeModification;
import org.opendaylight.yangtools.yang.data.tree.api.DataValidationFailedException;
import org.opendaylight.yangtools.yang.data.tree.api.ModificationType;
import org.opendaylight.yangtools.yang.data.tree.impl.di.InMemoryDataTreeFactory;
import org.opendaylight.yangtools.yang.model.api.EffectiveModelContext;
import scala.concurrent.Await;
import scala.concurrent.duration.FiniteDuration;

/**
 * Abstract base for shard unit tests.
 *
 * @author Thomas Pantelis
 */
public abstract class AbstractShardTest extends AbstractActorTest {
    protected static final EffectiveModelContext SCHEMA_CONTEXT = TestModel.createTestContext();

    protected static final AtomicInteger SHARD_NUM = new AtomicInteger();
    protected static final int HEARTBEAT_MILLIS = 100;

    protected final Builder dataStoreContextBuilder = DatastoreContext.newBuilder()
            .shardJournalRecoveryLogBatchSize(3).shardSnapshotBatchCount(5000)
            .shardHeartbeatIntervalInMillis(HEARTBEAT_MILLIS);

    protected final TestActorFactory actorFactory = new TestActorFactory(getSystem());
    protected final int nextShardNum = SHARD_NUM.getAndIncrement();
    protected final ShardIdentifier shardID = ShardIdentifier.create("inventory", MemberName.forName("member-1"),
        "config" + nextShardNum);

    @Before
    public void setUp() throws Exception {
        InMemorySnapshotStore.clear();
        InMemoryJournal.clear();
    }

    @After
    public void tearDown() {
        InMemorySnapshotStore.clear();
        InMemoryJournal.clear();
        actorFactory.close();
    }

    protected DatastoreContext newDatastoreContext() {
        return dataStoreContextBuilder.build();
    }

    protected Props newShardProps() {
        return newShardBuilder().props();
    }

    protected Shard.Builder newShardBuilder() {
        return Shard.builder().id(shardID).datastoreContext(newDatastoreContext())
            .schemaContextProvider(() -> SCHEMA_CONTEXT);
    }

    protected void testRecovery(final Set<Integer> listEntryKeys, final boolean stopActorOnFinish) throws Exception {
        // Create the actor and wait for recovery complete.

        final int nListEntries = listEntryKeys.size();

        final CountDownLatch recoveryComplete = new CountDownLatch(1);

        final Creator<Shard> creator = () -> new Shard(newShardBuilder()) {
            @Override
            protected void onRecoveryComplete() {
                try {
                    super.onRecoveryComplete();
                } finally {
                    recoveryComplete.countDown();
                }
            }
        };

        final TestActorRef<Shard> shard = TestActorRef.create(getSystem(), Props.create(Shard.class,
                new DelegatingShardCreator(creator)).withDispatcher(Dispatchers.DefaultDispatcherId()), "testRecovery");

        assertTrue("Recovery complete", recoveryComplete.await(5, TimeUnit.SECONDS));

        // Verify data in the data store.

        final NormalizedNode outerList = readStore(shard, TestModel.OUTER_LIST_PATH);
        assertNotNull(TestModel.OUTER_LIST_QNAME.getLocalName() + " not found", outerList);
        assertTrue(TestModel.OUTER_LIST_QNAME.getLocalName() + " value is not Iterable",
                outerList.body() instanceof Iterable);
        for (final Object entry: (Iterable<?>) outerList.body()) {
            assertTrue(TestModel.OUTER_LIST_QNAME.getLocalName() + " entry is not MapEntryNode",
                    entry instanceof MapEntryNode);
            final MapEntryNode mapEntry = (MapEntryNode)entry;
            final Optional<DataContainerChild> idLeaf =
                    mapEntry.findChildByArg(new YangInstanceIdentifier.NodeIdentifier(TestModel.ID_QNAME));
            assertTrue("Missing leaf " + TestModel.ID_QNAME.getLocalName(), idLeaf.isPresent());
            final Object value = idLeaf.orElseThrow().body();
            assertTrue("Unexpected value for leaf " + TestModel.ID_QNAME.getLocalName() + ": " + value,
                    listEntryKeys.remove(value));
        }

        if (!listEntryKeys.isEmpty()) {
            fail("Missing " + TestModel.OUTER_LIST_QNAME.getLocalName() + " entries with keys: " + listEntryKeys);
        }

        assertEquals("Last log index", nListEntries,
                shard.underlyingActor().getShardMBean().getLastLogIndex());
        assertEquals("Commit index", nListEntries,
                shard.underlyingActor().getShardMBean().getCommitIndex());
        assertEquals("Last applied", nListEntries,
                shard.underlyingActor().getShardMBean().getLastApplied());

        if (stopActorOnFinish) {
            shard.tell(PoisonPill.getInstance(), ActorRef.noSender());
        }
    }

    protected void verifyLastApplied(final TestActorRef<Shard> shard, final long expectedValue) {
        long lastApplied = -1;
        for (int i = 0; i < 20 * 5; i++) {
            lastApplied = shard.underlyingActor().getShardMBean().getLastApplied();
            if (lastApplied == expectedValue) {
                return;
            }
            Uninterruptibles.sleepUninterruptibly(50, TimeUnit.MILLISECONDS);
        }

        Assert.fail(String.format("Expected last applied: %d, Actual: %d", expectedValue, lastApplied));
    }

    protected DataTree createDelegatingMockDataTree() throws Exception {
        final DataTree actual = new InMemoryDataTreeFactory().create(DataTreeConfiguration.DEFAULT_CONFIGURATION);
        final DataTree mock = mock(DataTree.class);

        doAnswer(invocation -> {
            actual.validate(invocation.getArgument(0));
            return null;
        }).when(mock).validate(any(DataTreeModification.class));

        doAnswer(invocation -> actual.prepare(invocation.getArgument(0))).when(
                mock).prepare(any(DataTreeModification.class));

        doAnswer(invocation -> {
            actual.commit(invocation.getArgument(0));
            return null;
        }).when(mock).commit(any(DataTreeCandidate.class));

        doAnswer(invocation -> {
            actual.setEffectiveModelContext(invocation.getArgument(0));
            return null;
        }).when(mock).setEffectiveModelContext(any(EffectiveModelContext.class));

        doAnswer(invocation -> actual.takeSnapshot()).when(mock).takeSnapshot();

        doAnswer(invocation -> actual.getRootPath()).when(mock).getRootPath();

        return mock;
    }

    protected ShardDataTreeCohort mockShardDataTreeCohort() {
        ShardDataTreeCohort cohort = mock(ShardDataTreeCohort.class);
        DataTreeCandidate candidate = mockCandidate("candidate");
        successfulCanCommit(cohort);
        successfulPreCommit(cohort, candidate);
        successfulCommit(cohort);
        doReturn(candidate).when(cohort).getCandidate();
        return cohort;
    }

    protected Map<TransactionIdentifier, CapturingShardDataTreeCohort> setupCohortDecorator(final Shard shard,
            final TransactionIdentifier... transactionIDs) {
        final Map<TransactionIdentifier, CapturingShardDataTreeCohort> cohortMap = new HashMap<>();
        for (TransactionIdentifier id: transactionIDs) {
            cohortMap.put(id, new CapturingShardDataTreeCohort());
        }

        shard.getCommitCoordinator().setCohortDecorator((transactionID, actual) -> {
            CapturingShardDataTreeCohort cohort = cohortMap.get(transactionID);
            cohort.setDelegate(actual);
            return cohort;
        });

        return cohortMap;
    }

    @Deprecated(since = "11.0.0", forRemoval = true)
    protected static BatchedModifications prepareBatchedModifications(final TransactionIdentifier transactionID,
            final YangInstanceIdentifier path, final NormalizedNode data, final boolean doCommitOnReady) {
        final var modification = new MutableCompositeModification();
        modification.addModification(new WriteModification(path, data));
        final var batchedModifications = new BatchedModifications(transactionID, CURRENT_VERSION);
        batchedModifications.addModification(modification);
        batchedModifications.setReady();
        batchedModifications.setDoCommitOnReady(doCommitOnReady);
        batchedModifications.setTotalMessagesSent(1);
        return batchedModifications;
    }

    @Deprecated(since = "11.0.0", forRemoval = true)
    protected static ForwardedReadyTransaction prepareForwardedReadyTransaction(final TestActorRef<Shard> shard,
            final TransactionIdentifier transactionID, final YangInstanceIdentifier path,
            final NormalizedNode data, final boolean doCommitOnReady) {
        ReadWriteShardDataTreeTransaction rwTx = shard.underlyingActor().getDataStore()
                .newReadWriteTransaction(transactionID);
        rwTx.getSnapshot().write(path, data);
        return new ForwardedReadyTransaction(transactionID, CURRENT_VERSION, rwTx, doCommitOnReady, Optional.empty());
    }

    public static NormalizedNode readStore(final TestActorRef<? extends Shard> shard,
            final YangInstanceIdentifier id) {
        return shard.underlyingActor().getDataStore().readNode(id).orElse(null);
    }

    public static NormalizedNode readStore(final DataTree store, final YangInstanceIdentifier id) {
        return store.takeSnapshot().readNode(id).orElse(null);
    }

    public static void writeToStore(final TestActorRef<Shard> shard, final YangInstanceIdentifier id,
            final NormalizedNode node) throws InterruptedException, ExecutionException {
        final var future = Patterns.ask(shard, newBatchedModifications(nextTransactionId(), id, node, true, true, 1),
            new Timeout(5, TimeUnit.SECONDS));
        try {
            Await.ready(future, FiniteDuration.create(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new ExecutionException(e);
        }
    }

    public static void writeToStore(final DataTree store, final YangInstanceIdentifier id, final NormalizedNode node)
            throws DataValidationFailedException {
        final DataTreeModification transaction = store.takeSnapshot().newModification();

        transaction.write(id, node);
        transaction.ready();
        store.validate(transaction);
        final DataTreeCandidate candidate = store.prepare(transaction);
        store.commit(candidate);
    }

    DataTree setupInMemorySnapshotStore() throws DataValidationFailedException {
        final DataTree testStore = new InMemoryDataTreeFactory().create(
            DataTreeConfiguration.DEFAULT_OPERATIONAL, SCHEMA_CONTEXT);

        writeToStore(testStore, TestModel.TEST_PATH, TestModel.EMPTY_TEST);

        final NormalizedNode root = readStore(testStore, YangInstanceIdentifier.of());

        InMemorySnapshotStore.addSnapshot(shardID.toString(), Snapshot.create(
                new ShardSnapshotState(new MetadataShardDataTreeSnapshot(root)),
                Collections.<ReplicatedLogEntry>emptyList(), 0, 1, -1, -1, 1, null, null));
        return testStore;
    }

    static CommitTransactionPayload payloadForModification(final DataTree source, final DataTreeModification mod,
            final TransactionIdentifier transactionId) throws DataValidationFailedException, IOException {
        source.validate(mod);
        final DataTreeCandidate candidate = source.prepare(mod);
        source.commit(candidate);
        return CommitTransactionPayload.create(transactionId, candidate);
    }

    @Deprecated(since = "11.0.0", forRemoval = true)
    static BatchedModifications newBatchedModifications(final TransactionIdentifier transactionID,
            final YangInstanceIdentifier path, final NormalizedNode data, final boolean ready,
            final boolean doCommitOnReady, final int messagesSent) {
        final BatchedModifications batched = new BatchedModifications(transactionID, CURRENT_VERSION);
        batched.addModification(new WriteModification(path, data));
        if (ready) {
            batched.setReady();
        }
        batched.setDoCommitOnReady(doCommitOnReady);
        batched.setTotalMessagesSent(messagesSent);
        return batched;
    }

    @Deprecated(since = "11.0.0", forRemoval = true)
    static BatchedModifications newReadyBatchedModifications(final TransactionIdentifier transactionID,
            final YangInstanceIdentifier path, final NormalizedNode data,
            final SortedSet<String> participatingShardNames) {
        final BatchedModifications batched = new BatchedModifications(transactionID, CURRENT_VERSION);
        batched.addModification(new WriteModification(path, data));
        batched.setReady(Optional.of(participatingShardNames));
        batched.setTotalMessagesSent(1);
        return batched;
    }

    @SuppressWarnings("unchecked")
    static void verifyOuterListEntry(final TestActorRef<Shard> shard, final Object expIDValue) {
        final NormalizedNode outerList = readStore(shard, TestModel.OUTER_LIST_PATH);
        assertNotNull(TestModel.OUTER_LIST_QNAME.getLocalName() + " not found", outerList);
        assertTrue(TestModel.OUTER_LIST_QNAME.getLocalName() + " value is not Iterable",
                outerList.body() instanceof Iterable);
        final Object entry = ((Iterable<Object>)outerList.body()).iterator().next();
        assertTrue(TestModel.OUTER_LIST_QNAME.getLocalName() + " entry is not MapEntryNode",
                entry instanceof MapEntryNode);
        final MapEntryNode mapEntry = (MapEntryNode)entry;
        final Optional<DataContainerChild> idLeaf =
                mapEntry.findChildByArg(new YangInstanceIdentifier.NodeIdentifier(TestModel.ID_QNAME));
        assertTrue("Missing leaf " + TestModel.ID_QNAME.getLocalName(), idLeaf.isPresent());
        assertEquals(TestModel.ID_QNAME.getLocalName() + " value", expIDValue, idLeaf.orElseThrow().body());
    }

    public static DataTreeCandidateTip mockCandidate(final String name) {
        final DataTreeCandidateTip mockCandidate = mock(DataTreeCandidateTip.class, name);
        final DataTreeCandidateNode mockCandidateNode = mock(DataTreeCandidateNode.class, name + "-node");
        doReturn(ModificationType.WRITE).when(mockCandidateNode).modificationType();
        doReturn(ImmutableNodes.newContainerBuilder()
            .withNodeIdentifier(new NodeIdentifier(CarsModel.CARS_QNAME))
            .build()).when(mockCandidateNode).dataAfter();
        doReturn(CarsModel.BASE_PATH).when(mockCandidate).getRootPath();
        doReturn(mockCandidateNode).when(mockCandidate).getRootNode();
        return mockCandidate;
    }

    static DataTreeCandidateTip mockUnmodifiedCandidate(final String name) {
        final DataTreeCandidateTip mockCandidate = mock(DataTreeCandidateTip.class, name);
        final DataTreeCandidateNode mockCandidateNode = mock(DataTreeCandidateNode.class, name + "-node");
        doReturn(ModificationType.UNMODIFIED).when(mockCandidateNode).modificationType();
        doReturn(YangInstanceIdentifier.of()).when(mockCandidate).getRootPath();
        doReturn(mockCandidateNode).when(mockCandidate).getRootNode();
        return mockCandidate;
    }

    static DataTreeCandidate commitTransaction(final DataTree store, final DataTreeModification modification)
            throws DataValidationFailedException {
        modification.ready();
        store.validate(modification);
        final DataTreeCandidate candidate = store.prepare(modification);
        store.commit(candidate);
        return candidate;
    }

    @SuppressWarnings("serial")
    public static final class DelegatingShardCreator implements Creator<Shard> {
        private final Creator<Shard> delegate;

        DelegatingShardCreator(final Creator<Shard> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Shard create() throws Exception {
            return delegate.create();
        }
    }

    public static class CapturingShardDataTreeCohort extends ShardDataTreeCohort {
        private volatile ShardDataTreeCohort delegate;
        private FutureCallback<Empty> canCommit;
        private FutureCallback<DataTreeCandidate> preCommit;
        private FutureCallback<UnsignedLong> commit;

        public void setDelegate(final ShardDataTreeCohort delegate) {
            this.delegate = delegate;
        }

        public FutureCallback<Empty> getCanCommit() {
            assertNotNull("canCommit was not invoked", canCommit);
            return canCommit;
        }

        public FutureCallback<DataTreeCandidate> getPreCommit() {
            assertNotNull("preCommit was not invoked", preCommit);
            return preCommit;
        }

        public FutureCallback<UnsignedLong> getCommit() {
            assertNotNull("commit was not invoked", commit);
            return commit;
        }

        @Override
        TransactionIdentifier transactionId() {
            return delegate.transactionId();
        }

        @Override
        DataTreeCandidateTip getCandidate() {
            return delegate.getCandidate();
        }

        @Override
        DataTreeModification getDataTreeModification() {
            return delegate.getDataTreeModification();
        }

        @Override
        public void canCommit(final FutureCallback<Empty> callback) {
            canCommit = mockFutureCallback(callback);
            delegate.canCommit(canCommit);
        }

        @Override
        public void preCommit(final FutureCallback<DataTreeCandidate> callback) {
            preCommit = mockFutureCallback(callback);
            delegate.preCommit(preCommit);
        }

        @Override
        public void commit(final FutureCallback<UnsignedLong> callback) {
            commit = mockFutureCallback(callback);
            delegate.commit(commit);
        }

        @SuppressWarnings("unchecked")
        private static <T> FutureCallback<T> mockFutureCallback(final FutureCallback<T> actual) {
            FutureCallback<T> mock = mock(FutureCallback.class);
            doAnswer(invocation -> {
                actual.onFailure(invocation.getArgument(0));
                return null;
            }).when(mock).onFailure(any(Throwable.class));

            doAnswer(invocation -> {
                actual.onSuccess(invocation.getArgument(0));
                return null;
            }).when(mock).onSuccess((T) nullable(Object.class));

            return mock;
        }

        @Override
        public void abort(final FutureCallback<Empty> callback) {
            delegate.abort(callback);
        }

        @Override
        public boolean isFailed() {
            return delegate.isFailed();
        }

        @Override
        public State getState() {
            return delegate.getState();
        }

        @Override
        Optional<SortedSet<String>> getParticipatingShardNames() {
            return delegate.getParticipatingShardNames();
        }
    }
}
