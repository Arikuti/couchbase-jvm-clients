package com.couchbase.client.kotlin.kv

import com.couchbase.client.core.msg.kv.DurabilityLevel
import com.couchbase.client.core.msg.kv.DurabilityLevel.MAJORITY
import com.couchbase.client.core.msg.kv.DurabilityLevel.MAJORITY_AND_PERSIST_TO_ACTIVE
import com.couchbase.client.core.msg.kv.DurabilityLevel.PERSIST_TO_MAJORITY
import com.couchbase.client.core.service.kv.Observe.ObservePersistTo
import com.couchbase.client.core.service.kv.Observe.ObserveReplicateTo

/**
 * Specifies the durability requirements for a mutation.
 */
public sealed class Durability {
    internal abstract fun isPersistent(): Boolean

    public object Disabled : Durability() {
        override fun isPersistent(): Boolean = false
        override fun toString(): String = "Disabled"
    }

    public data class Synchronous internal constructor(
        val level: DurabilityLevel,
    ) : Durability() {
        override fun isPersistent(): Boolean =
            level == MAJORITY_AND_PERSIST_TO_ACTIVE || level == PERSIST_TO_MAJORITY
    }

    public data class ClientVerified internal constructor(
        val persistTo: PersistTo,
        val replicateTo: ReplicateTo,
    ) : Durability() {
        override fun isPersistent(): Boolean = persistTo != PersistTo.NONE
    }

    public companion object {
        /**
         * The SDK will report success as soon as the node hosting the
         * active partition has the mutation in memory (but not necessarily
         * persisted to disk).
         */
        public fun disabled(): Durability = Disabled

        /**
         * The client will poll the server until the specified durability
         * requirements are observed.
         *
         * This strategy is supported by all Couchbase Server versions,
         * but has drawbacks. When using Couchbase Server 6.5 or later,
         * the other durability options may be preferable.
         */
        public fun clientVerified(persistTo: PersistTo, replicateTo: ReplicateTo = ReplicateTo.NONE): Durability =
            if (replicateTo == ReplicateTo.NONE && persistTo == PersistTo.NONE) Disabled
            else ClientVerified(persistTo, replicateTo)

        /**
         * The mutation must be replicated to (that is, held in the memory
         * allocated to the bucket on) a majority of the Data Service nodes.
         *
         * Requires Couchbase Server 6.5 or later.
         */
        public fun majority(): Durability = Synchronous(MAJORITY)

        /**
         * The mutation must be persisted to a majority of the Data Service nodes.
         *
         * Requires Couchbase Server 6.5 or later.
         */
        public fun persistToMajority(): Durability = Synchronous(PERSIST_TO_MAJORITY)

        /**
         * The mutation must be replicated to a majority of the Data Service nodes.
         *
         * Additionally, it must be persisted (that is, written and synchronised to disk)
         * on the node hosting the active partition (vBucket) for the data.
         *
         * Requires Couchbase Server 6.5 or later.
         */
        public fun majorityAndPersistToActive(): Durability = Synchronous(MAJORITY_AND_PERSIST_TO_ACTIVE)
    }
}

public enum class ReplicateTo(internal val coreHandle: ObserveReplicateTo) {
    NONE(ObserveReplicateTo.NONE),
    ONE(ObserveReplicateTo.ONE),
    TWO(ObserveReplicateTo.TWO),
    THREE(ObserveReplicateTo.THREE);
}

public enum class PersistTo(internal val coreHandle: ObservePersistTo) {
    NONE(ObservePersistTo.NONE),
    ACTIVE(ObservePersistTo.ACTIVE),
    ONE(ObservePersistTo.ONE),
    TWO(ObservePersistTo.TWO),
    THREE(ObservePersistTo.THREE),
    FOUR(ObservePersistTo.FOUR);
}
