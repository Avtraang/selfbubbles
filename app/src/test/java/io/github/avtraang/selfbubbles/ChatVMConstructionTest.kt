package io.github.avtraang.selfbubbles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ChatVM's init block is the last thing in the class.
 *
 * The block starts the first load of the thread list, and viewModelScope
 * (Dispatchers.Main.immediate) runs a coroutine started on the main thread in
 * place up to its first suspension. So whatever the load does before it first
 * suspends runs inside the constructor, and a load that ends without ever
 * suspending runs all of refreshThreads() there. No suspend function promises
 * to suspend. With a property declared below the init block, a load that ends
 * at once (the unparsable address of a build with no relay, raised before any
 * hand-off to another thread) writes a field the constructor has not reached
 * yet, and the app dies at every launch before "Connect to your relay" shows.
 *
 * Kotlin initialises a class top to bottom, so with the init block below every
 * property there is nothing left for it to find uninitialised, whatever a load
 * does. The ViewModel cannot be built on the JVM (it is an AndroidViewModel),
 * so this pins the order in its source.
 */
class ChatVMConstructionTest {

    /** A property declared in the class body itself (one indent), with any modifiers or annotations before it. */
    private val property = Regex("""^ {4}(?:@\S+ )*(?:(?:private|internal|protected|public|override|lateinit|open|final) )*(?:val|var)\b""")

    private fun source(): List<String> {
        // Unit tests run in the app module's directory.
        val path = "src/main/java/io/github/avtraang/selfbubbles/ChatVM.kt"
        return generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, path), File(it, "app/$path")) }
            .first { it.isFile }
            .readLines()
    }

    @Test fun theInitBlock_comesAfterEveryProperty() {
        val lines = source()
        val start = lines.indexOfFirst { it.startsWith("class ChatVM(") }
        assertTrue("class ChatVM not found", start >= 0)
        val end = (start + 1 until lines.size).first { lines[it] == "}" }
        val body = start + 1 until end

        val inits = body.filter { lines[it].trimEnd() == "    init {" }
        assertEquals("one init block", 1, inits.size)
        val init = inits.single()

        // The pattern does find this file's properties: the check below cannot pass on a format it does not read.
        val above = body.filter { it < init && property.containsMatchIn(lines[it]) }
        assertTrue("found ${above.size} properties", above.size >= 30)
        for (name in listOf("threadsLoad", "threadsLoadSeq", "threadsLoaded", "threadsLoadSlow", "wsm", "current", "sending")) {
            assertTrue(name, above.any { Regex("""(?:val|var) $name\b""").containsMatchIn(lines[it]) })
        }

        val below = body.filter { it > init && property.containsMatchIn(lines[it]) }
        assertEquals("declared below the init block", emptyList<String>(), below.map { "${it + 1}: ${lines[it].trim()}" })

        // And nothing but the end of the class follows the block.
        val blockEnd = (init + 1 until end).first { lines[it] == "    }" }
        assertEquals(emptyList<String>(), (blockEnd + 1 until end).map { lines[it] }.filter { it.isNotBlank() })
    }

    @Test fun thePattern_readsADeclaration_howeverItIsWritten() {
        for (line in listOf(
            "    val threads = MutableStateFlow<List<Thread>>(emptyList())",
            "    var current by mutableStateOf<Thread?>(null); private set",
            "    private val threadsLoaded = MutableStateFlow(false)",
            "    private var handledLaunchSeq: Long",
            "    @Volatile private var stopped = false",
            "    private lateinit var later: String",
            "    override val size: Int get() = 0",
        )) assertTrue(line, property.containsMatchIn(line))
        for (line in listOf(
            "        val load = threadsLoadStarted()",                    // a local, two indents in
            "    fun refreshThreads() = viewModelScope.launch {",
            "    // var in a comment",
            "    init {",
            "private const val STATE_HANDLED_LAUNCH_SEQ = \"handled_launch_seq\"",   // outside the class
        )) assertTrue(line, !property.containsMatchIn(line))
    }
}
