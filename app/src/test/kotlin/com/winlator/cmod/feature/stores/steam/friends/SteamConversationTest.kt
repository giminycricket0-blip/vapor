package com.winlator.cmod.feature.stores.steam.friends

import com.winlator.cmod.feature.stores.steam.data.SteamChatMessage
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SteamConversationTest {
    private fun ack(text: String, ordinal: Int = 1) = SteamChatMessage(true, text, 1234, ordinal)

    @Test fun staleConnectedFlagCannotMakeGameHandoffLookLive() {
        assertEquals(SteamChatAvailability.IN_GAME, SteamChatAvailability.from(true, true, true, true))
        assertEquals(SteamChatAvailability.SIGNED_OUT, SteamChatAvailability.from(false, true, true, true))
        assertEquals(SteamChatAvailability.DISABLED, SteamChatAvailability.from(true, false, false, true))
        assertEquals(SteamChatAvailability.OFFLINE, SteamChatAvailability.from(true, true, false, false))
        assertEquals(SteamChatAvailability.READY, SteamChatAvailability.from(true, true, false, true))
    }

    @Test fun offlineDraftDoesNotSendOrCreateAMessage() = runBlocking {
        val chat = SteamConversation().apply { input = "Keep this" }
        chat.send(false) { error("Offline send must not reach transport") }
        assertEquals("Keep this", chat.input)
        assertTrue(chat.messages.isEmpty())
        assertFalse(chat.sending)
    }

    @Test fun missingAcknowledgementKeepsDraftAndDoesNotInventHistory() = runBlocking {
        val chat = SteamConversation().apply { input = "  Hello  " }
        chat.send(true) { assertEquals("Hello", it); null }
        assertEquals("  Hello  ", chat.input)
        assertTrue(chat.sendFailed)
        assertFalse(chat.sending)
        assertTrue(chat.messages.isEmpty())
    }

    @Test fun exceptionResetsSendControlAndAllowsAnExplicitRetry() = runBlocking {
        val chat = SteamConversation().apply { input = "Hello" }
        chat.send(true) { throw IllegalStateException("test transport failed") }
        assertTrue(chat.sendFailed)
        assertFalse(chat.sending)
        chat.send(true) { ack(it) }
        assertFalse(chat.sendFailed)
        assertEquals("", chat.input)
        assertEquals(listOf(ack("Hello")), chat.messages.toList())
    }

    @Test fun cancellationPropagatesWithoutDeletingTheDraft() = runBlocking {
        val chat = SteamConversation().apply { input = "Hello" }
        try {
            chat.send(true) { throw CancellationException("closed") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
        assertEquals("Hello", chat.input)
        assertFalse(chat.sending)
        assertTrue(chat.messages.isEmpty())
    }

    @Test fun acknowledgementEchoAndReconnectHistoryProduceOneMessage() = runBlocking {
        val chat = SteamConversation().apply { input = "Hello" }
        chat.send(true) {
            chat.merge(listOf(ack(it))) // live echo can beat the send response
            ack(it)
        }
        chat.merge(listOf(ack("Hello")))
        assertEquals(1, chat.messages.size)
        assertEquals("", chat.input)
    }

    @Test fun delayedHistoryKeepsNewMessagesAndSameSecondOppositeSender() {
        val chat = SteamConversation()
        val incoming = SteamChatMessage(false, "Reply", 1234, 1)
        chat.merge(listOf(incoming))
        chat.merge(listOf(ack("Hello"), SteamChatMessage(false, "Earlier", 1200, 0)))
        assertEquals(3, chat.messages.size)
        assertEquals("Earlier", chat.messages.first().text)
        assertTrue(chat.messages.contains(incoming))
    }

    @Test fun rapidDuplicateSendIsIgnoredAndNewerDraftIsPreserved() = runBlocking {
        val chat = SteamConversation().apply { input = "First" }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sending = launch {
            chat.send(true) { started.complete(Unit); release.await(); ack(it) }
        }
        started.await()
        chat.input = "Next draft"
        chat.send(true) { error("Duplicate send must be ignored") }
        release.complete(Unit)
        sending.join()
        assertEquals("Next draft", chat.input)
        assertEquals(listOf(ack("First")), chat.messages.toList())
    }
}
