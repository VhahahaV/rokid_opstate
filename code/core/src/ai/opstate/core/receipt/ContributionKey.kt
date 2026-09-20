package ai.opstate.core.receipt

import java.security.MessageDigest

/**
 * What a delivered card said, reduced to something two cards can be compared on.
 *
 * The authored-episode path can suppress a repeat by episode handle: the episode is the unit of service
 * and a receipt names it. The streaming path has no such unit - one situation runs for the whole session
 * and every packet carries its own handle - so the only thing left to match a repeat on is the text the
 * wearer actually saw. A card that says what an earlier card said is not a new contribution, however
 * differently the packet that produced it was numbered.
 *
 * Case, spacing and punctuation are not part of what a card says, so they are not part of the key.
 */
object ContributionKey {
    /** Null when there is nothing to compare - an empty card is not a contribution. */
    fun of(title: String?,body: String?): String? {
        if(title.isNullOrBlank() && body.isNullOrBlank()) return null
        val digest=MessageDigest.getInstance("SHA-256").digest(normalize("${title.orEmpty()}\u0000${body.orEmpty()}").toByteArray())
        return digest.joinToString("") {"%02x".format(it)}.take(32)
    }
    internal fun normalize(text: String): String = text.lowercase().filter {it.isLetterOrDigit()}
}
