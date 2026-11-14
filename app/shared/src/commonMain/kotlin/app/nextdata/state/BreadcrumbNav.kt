package app.nextdata.state

import app.nextdata.protocol.Breadcrumb

/**
 * Display-only path helpers. The UI never builds engine paths: typing a path can only jump to one of the
 * ancestors the engine already returned as breadcrumbs (using their opaque `ref`).
 */
object BreadcrumbNav {
    /** "/Documenti/2024" style text for display and copying. */
    fun displayPath(crumbs: List<Breadcrumb>): String {
        if (crumbs.isEmpty()) return "/"
        val names = crumbs.map { it.name }
        val first = names.first()
        return if (first == "/" || first.isEmpty()) "/" + names.drop(1).joinToString("/") else names.joinToString("/")
    }

    private val backslash = 92.toChar()

    private fun normalize(s: String): String =
        s.trim().replace(backslash, '/').replace(Regex("/+"), "/").let { if (it.length > 1) it.trimEnd('/') else it }

    /** The ancestor crumb whose display path equals [input] (after normalisation), or null. */
    fun resolve(input: String, crumbs: List<Breadcrumb>): Breadcrumb? {
        val wanted = normalize(input)
        if (wanted.isEmpty()) return null
        for (i in crumbs.indices) {
            if (normalize(displayPath(crumbs.take(i + 1))) == wanted) return crumbs[i]
        }
        return if (wanted == "/") crumbs.firstOrNull() else null
    }
}
