package org.fossify.gallery.helpers

object AiIndexState {
    @Volatile var running = false
    @Volatile var indexed = 0
    @Volatile var total = 0
    @Volatile var lastError = ""
    @Volatile var lastMessage = ""
    @Volatile var cancelRequested = false

    fun reset() {
        running = false
        indexed = 0
        total = 0
        lastError = ""
        lastMessage = ""
        cancelRequested = false
    }
}
