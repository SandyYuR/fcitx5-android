package org.fcitx.fcitx5.android.input.dependency

import org.junit.Assert.*
import org.junit.Test
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import org.mechdancer.dependency.manager.must
import org.mechdancer.dependency.plusAssign

class InputComponentTypeTest {
    private open class Producer : InputUniqueComponent<Producer>(Producer::class)
    private class DerivedProducer : Producer()
    private class Consumer : InputUniqueComponent<Consumer>(Consumer::class), Dependent,
        ManagedHandler by managedHandler() {
        val producer: Producer by manager.must()
    }

    @Test fun explicitBaseIdentityStillResolvesDerivedComponentsAndRejectsDuplicates() {
        val scope = DynamicScope()
        val producer = DerivedProducer()
        val consumer = Consumer()
        assertEquals(Producer::class, producer.type)
        assertEquals(Producer::class.hashCode(), producer.hashCode())
        scope += producer
        scope += consumer
        assertSame(producer, consumer.producer)
        assertFalse(scope.setup(Producer()))
        scope.clear()
    }
}
