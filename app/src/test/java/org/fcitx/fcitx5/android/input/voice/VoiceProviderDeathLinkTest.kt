package org.fcitx.fcitx5.android.input.voice

import android.app.Application
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VoiceProviderDeathLinkTest {
    private class RecordingBinder : Binder() {
        val recipients = mutableListOf<IBinder.DeathRecipient>()
        var unlinkCount = 0
        var alreadyDead = false
        var unlinkThrows = false
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            if (alreadyDead) throw RemoteException("already dead")
            recipients.add(recipient)
        }
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean {
            unlinkCount++
            if (unlinkThrows) throw NoSuchElementException("binder already removed it")
            return recipients.remove(recipient)
        }
    }

    @Test fun clearUnlinksExactlyOnce() {
        val binder = RecordingBinder()
        val link = VoiceProviderDeathLink()
        link.attach(binder, { it() }) { fail("unexpected death") }
        link.clear(); link.clear()
        assertEquals(1, binder.unlinkCount)
        assertTrue(binder.recipients.isEmpty())
    }

    @Test fun queuedOldDeathCannotAbortReplacementBinder() {
        val pending = ArrayDeque<() -> Unit>()
        val old = RecordingBinder()
        val replacement = RecordingBinder()
        val link = VoiceProviderDeathLink()
        var deaths = 0
        link.attach(old, { pending.addLast(it) }) { fail("old binder affected replacement") }
        old.recipients.single().binderDied()
        link.attach(replacement, { pending.addLast(it) }) { deaths++ }
        pending.removeFirst().invoke()
        assertEquals(0, deaths)
        assertEquals(1, old.unlinkCount)
        replacement.recipients.single().binderDied()
        pending.removeFirst().invoke()
        assertEquals(1, deaths)
    }

    @Test fun rebindingTheSameBinderStillInvalidatesItsOldRecipient() {
        val pending = ArrayDeque<() -> Unit>()
        val binder = RecordingBinder()
        val link = VoiceProviderDeathLink()
        link.attach(binder, { pending.addLast(it) }) { fail("old registration ran") }
        val oldRecipient = binder.recipients.single()
        link.attach(binder, { pending.addLast(it) }) { }
        oldRecipient.binderDied()
        pending.removeFirst().invoke()
        assertEquals(1, binder.recipients.size)
    }

    @Test fun clearInvalidatesAlreadyPostedCallbacks() {
        val pending = ArrayDeque<() -> Unit>()
        val binder = RecordingBinder()
        val link = VoiceProviderDeathLink()
        link.attach(binder, { pending.addLast(it) }) { fail("cleared registration ran") }
        binder.recipients.single().binderDied()
        link.clear()
        pending.removeFirst().invoke()
    }

    @Test fun duplicateDeathNotificationsAreConsumedOnce() {
        val pending = ArrayDeque<() -> Unit>()
        val binder = RecordingBinder()
        val link = VoiceProviderDeathLink()
        var deaths = 0
        link.attach(binder, { pending.addLast(it) }) { deaths++ }
        val recipient = binder.recipients.single()
        recipient.binderDied(); recipient.binderDied()
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertEquals(1, deaths)
        assertEquals(1, binder.unlinkCount)
    }

    @Test fun alreadyDeadReplacementDoesNotRetainEitherRegistration() {
        val old = RecordingBinder()
        val dead = RecordingBinder().apply { alreadyDead = true }
        val link = VoiceProviderDeathLink()
        link.attach(old, { it() }) { }
        assertTrue(runCatching { link.attach(dead, { it() }) { } }.exceptionOrNull() is RemoteException)
        link.clear()
        assertTrue(old.recipients.isEmpty())
        assertEquals(1, dead.unlinkCount)
    }

    @Test fun unlinkFailureStillInvalidatesTheOldCallback() {
        val pending = ArrayDeque<() -> Unit>()
        val binder = RecordingBinder().apply { unlinkThrows = true }
        val link = VoiceProviderDeathLink()
        link.attach(binder, { pending.addLast(it) }) { fail("failed unlink retained an active callback") }
        val recipient = binder.recipients.single()
        link.clear()
        recipient.binderDied()
        pending.removeFirst().invoke()
    }
}
