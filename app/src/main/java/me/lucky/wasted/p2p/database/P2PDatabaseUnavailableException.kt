package me.lucky.wasted.p2p.database

/** Typed failure for an unavailable or unreadable P2P database key. */
class P2PDatabaseUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
