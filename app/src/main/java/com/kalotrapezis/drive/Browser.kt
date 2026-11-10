package com.kalotrapezis.drive

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// Browser (the user, 2 Nov): logins, forms and cards kept encrypted; the history forgotten; Private keeps nothing;
// Firefox's engine and extensions (BrowserEngine.kt, uBlock Origin for the ads); Import from Firefox. The rules here are pure (BrowserTest); BrowserScreen.kt draws.

internal data class Visit(val url: String, val title: String, val at: Long)
// pinned: at the start page's front; folder: a path of folders, "Radios" or "Imported/Bookmarks Toolbar/News", "" for none
// (a saved name with no "/" is a top-level folder, as before)
internal data class Bookmark(val url: String, val title: String, val pinned: Boolean = false, val folder: String = "")

/**
 * The bookmarks and the folders kept even when empty (the manager's, 3 Nov: "a proper bookmarks manager, Firefox-like"):
 * a folder is a path, its parents before it. What the start page shows is [start]; imports go under [IMPORTED].
 * [moves]: the folders this change renamed or moved (from → to), so sync keeps each folder's id (BrowserSync).
 */
internal data class BookmarkLibrary(val marks: List<Bookmark>, val extra: List<String> = emptyList(), val moves: List<Pair<String, String>> = emptyList()) {
    /** Every folder, its parents too, in the order first met. */
    val folders: List<String> get() = (marks.map { it.folder } + extra).filter { it.isNotEmpty() }
        .flatMap { p -> p.split('/').let { s -> s.indices.map { s.take(it + 1).joinToString("/") } } }.distinct()

    fun subfolders(parent: String) = folders.filter { parentOf(it) == parent }

    /** Every folder depth first, each under its parent: the manager's tree and the Move list. */
    fun tree(): List<String> {
        val all = folders
        fun walk(p: String): List<String> = all.filter { parentOf(it) == p }.flatMap { listOf(it) + walk(it) }
        return walk("")
    }

    fun count(folder: String) = marks.count { under(it.folder, folder) }

    /**
     * The start page in [folder]: its favourites in the start page's order and its folders; at the top the imported ones
     * stay out (the user: "if I import, it dumps everything on the front page"), save those pinned.
     */
    fun start(folder: String): Pair<List<Bookmark>, List<String>> =
        BrowserRules.front(marks).filter { it.folder == folder || (folder.isEmpty() && it.pinned && under(it.folder, IMPORTED)) } to
            subfolders(folder).filter { it != IMPORTED }

    /** An import: the file's folders under Imported, a page already kept left where it is. */
    fun imported(news: List<Bookmark>) = copy(marks = (marks + news.map { it.copy(folder = join(IMPORTED, it.folder)) }).distinctBy { it.url })

    fun find(q: String) = q.trim().let { t -> marks.filter { it.title.contains(t, true) || it.url.contains(t, true) } to folders.filter { nameOf(it).contains(t, true) } }

    /** The chosen pages and folders into [to]; a folder into itself or its own is left. Their folders stay, though empty. */
    fun move(urls: Set<String>, dirs: Set<String>, to: String): BookmarkLibrary {
        var l = BookmarkLibrary(marks.map { if (it.url in urls) it.copy(folder = to) else it }, (folders + to).filter { it.isNotEmpty() }.distinct(), moves)
        for (d in dirs) if (!under(to, d)) l = l.relocate(d, join(to, nameOf(d)))
        return l
    }

    fun rename(dir: String, name: String) = clean(name).let { if (it.isEmpty()) this else relocate(dir, join(parentOf(dir), it)) }

    fun delete(urls: Set<String>, dirs: Set<String>) = BookmarkLibrary(marks.filter { it.url !in urls && dirs.none { d -> under(it.folder, d) } },
        folders.filter { p -> dirs.none { under(p, it) } })

    fun add(folder: String) = if (folder.isEmpty() || folder in folders) this else copy(extra = extra + folder)

    /** All the chosen pinned, or all unpinned when they all were. */
    fun pin(urls: Set<String>) = marks.filter { it.url in urls }.all { it.pinned }.let { was -> copy(marks = marks.map { if (it.url in urls) it.copy(pinned = !was) else it }) }

    fun edit(url: String, title: String, to: String) = copy(marks = marks.map { if (it.url == url) it.copy(title = title, url = to) else it })

    private fun relocate(from: String, to: String): BookmarkLibrary {
        fun re(p: String) = if (under(p, from)) to + p.removePrefix(from) else p
        return BookmarkLibrary(marks.map { it.copy(folder = re(it.folder)) }, folders.map(::re).distinct(), moves + (from to to))
    }

    companion object {
        const val IMPORTED = "Imported"
        fun parentOf(p: String) = p.substringBeforeLast('/', "")
        fun nameOf(p: String) = p.substringAfterLast('/')
        fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a/$b"
        /** [p] is [folder] or in it, however deep. */
        fun under(p: String, folder: String) = p == folder || p.startsWith("$folder/")
        /** A folder's one name: no "/", which divides a path. */
        fun clean(name: String) = name.replace('/', '∕').trim()
        /** A path typed ("Radios/Greek"): its names cleaned, empty ones out. */
        fun path(typed: String) = typed.split('/').map(String::trim).filter(String::isNotEmpty).joinToString("/")
        fun json(folders: List<String>) = JSONArray(folders).toString()
        fun folders(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map(a::getString) } }.getOrDefault(emptyList())
    }
}

internal object BrowserRules {
    /** The history lasts 24 hours from each visit (the user: "maybe keep it for 24 hours"), so yesterday's tabs are still found today. */
    const val KEEP = 24 * 3600_000L
    const val SEARCH = "https://duckduckgo.com/?q="

    /** Settings › Browser (the user, 2 Nov: "clear history on exit 24h or never"): "exit" lives only while the browser is open, "day" 24 hours, "never" until cleared. */
    const val ON_EXIT = "exit"; const val DAY = "day"; const val NEVER = "never"

    fun fresh(visits: List<Visit>, now: Long, keep: String = DAY) = if (keep == NEVER) visits else visits.filter { now - it.at in 0 until KEEP }

    /** The newest first, a page once (its latest visit), the old ones gone. */
    fun visit(visits: List<Visit>, v: Visit, keep: String = DAY) = fresh(listOf(v) + visits.filter { it.url != v.url }, v.at, keep).take(if (keep == NEVER) 5000 else 500)

    /** What the address bar goes to: a web address as it is, a bare name with a dot on https, anything else searched. */
    fun target(input: String): String {
        val t = input.trim()
        return when {
            Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t) -> t
            Regex("^(localhost|[^\\s/:]+\\.[a-z]{2,}|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/\\S*)?$", RegexOption.IGNORE_CASE).matches(t) -> "https://$t"
            else -> SEARCH + URLEncoder.encode(t, "UTF-8")
        }
    }

    /** HTML only (the user, 2 Nov: "no javascript … just html Css"): kept per site, so a site's subdomains and its pages follow. */
    fun htmlOnly(sites: Set<String>, url: String?) = host(url).takeIf { it.isNotEmpty() }?.let { site(it) in sites } == true

    /**
     * Player mode's ‹ › on a page with none of its own (a radio station's, live24.gr's): the next or the previous favourite of
     * the same site, round the list (the user, 2 Nov: "a favorite radios list with a heart so I am not pressing next on
     * thousands of stations"); in a page's folder, that folder's ("Radios"), whatever their sites; null when there is none but this page.
     */
    fun hop(bookmarks: List<Bookmark>, url: String, forward: Boolean): String? {
        val here = site(host(url))
        val folder = bookmarks.firstOrNull { it.url == url }?.folder.orEmpty()
        val list = if (folder.isNotEmpty()) bookmarks.filter { it.folder == folder }.map { it.url } else bookmarks.map { it.url }.filter { site(host(it)) == here }
        val others = list.filter { it != url }.ifEmpty { return null }
        val i = list.indexOf(url)
        if (i < 0) return if (forward) others.first() else others.last()
        return list[(i + (if (forward) 1 else -1) + list.size) % list.size]
    }

    fun host(url: String?) = url?.substringAfter("://", "")?.substringBefore('/')?.substringBefore('?')?.substringAfterLast('@')?.substringBefore(':')?.lowercase().orEmpty()

    /** shortcut: a site without the Public Suffix List — the last two labels, or three under com.gr, co.uk and their like; use the PSL if a site is split wrongly. */
    fun site(host: String): String {
        val p = host.split('.')
        val n = if (p.size >= 3 && p.last().length == 2 && p[p.size - 2] in SECOND) 3 else 2
        return p.takeLast(n).joinToString(".")
    }
    private val SECOND = setOf("com", "co", "org", "net", "gov", "edu", "ac", "or", "ne", "go", "gob")

    /** A saved login fits a page of the same host (www. or not); only the user's tap fills it. */
    fun sameHost(a: String, b: String) = a.removePrefix("www.") == b.removePrefix("www.")

    fun visitsJson(v: List<Visit>) = JSONArray(v.map { JSONObject().put("url", it.url).put("title", it.title).put("at", it.at) }).toString()
    fun visits(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).run { Visit(getString("url"), optString("title"), getLong("at")) } } } }.getOrDefault(emptyList())
    fun bookmarksJson(b: List<Bookmark>) = JSONArray(b.map { JSONObject().put("url", it.url).put("title", it.title).apply { if (it.pinned) put("pinned", true); if (it.folder.isNotEmpty()) put("folder", it.folder) } }).toString()
    fun bookmarks(s: String?) = runCatching { JSONArray(s).let { a -> (0 until a.length()).map { a.getJSONObject(it).run { Bookmark(getString("url"), optString("title"), optBoolean("pinned"), optString("folder")) } } } }.getOrDefault(emptyList())

    /** The start page's order: the pinned at the front, then the newest first. */
    fun front(b: List<Bookmark>) = b.reversed().sortedByDescending { it.pinned }
}

/** [note]: what the user wrote of it (the Passwords applet, Google's export's "note"); a page's form never has one. [pinned]: ♥, at the top. */
internal data class SavedLogin(val host: String, val user: String, val password: String, val note: String = "", val pinned: Boolean = false)
internal data class SavedCard(val name: String, val number: String, val month: String, val year: String) {
    val last4 get() = number.takeLast(4)
}

/** What the browser keeps encrypted (BrowserSecrets): passwords, addresses (autocomplete names → values) and cards; never the CVC. */
internal data class BrowserVault(val logins: List<SavedLogin> = emptyList(), val addresses: List<Map<String, String>> = emptyList(), val cards: List<SavedCard> = emptyList()) {
    /** Only what is new or changed: the same login, address or card again asks nothing. */
    fun news(o: BrowserVault) = BrowserVault(
        o.logins.filter { n -> logins.none { it.host == n.host && it.user == n.user && it.password == n.password } }, o.addresses.filter { it !in addresses }, o.cards.filter { c -> cards.none { it.number == c.number && it.month == c.month && it.year == c.year } })

    /** A login replaces the same host and user's (an import may hold one twice, for http and https); a card the same number's. */
    fun with(o: BrowserVault) = BrowserVault(
        (logins + o.logins).associateBy { it.host to it.user }.values.map { n -> // a page's save keeps the note written
            logins.firstOrNull { it.host == n.host && it.user == n.user }?.let { o -> n.copy(note = n.note.ifEmpty { o.note }, pinned = n.pinned || o.pinned) } ?: n },
        (addresses + o.addresses).distinct(),
        (cards + o.cards).associateBy { it.number }.values.toList())

    val empty get() = logins.isEmpty() && addresses.isEmpty() && cards.isEmpty()

    fun json(): String = JSONObject()
        .put("logins", JSONArray(logins.map { JSONObject().put("host", it.host).put("user", it.user).put("password", it.password).apply { if (it.note.isNotEmpty()) put("note", it.note); if (it.pinned) put("pinned", true) } }))
        .put("addresses", JSONArray(addresses.map { JSONObject(it) }))
        .put("cards", JSONArray(cards.map { JSONObject().put("name", it.name).put("number", it.number).put("month", it.month).put("year", it.year) }))
        .toString()

    companion object {
        val ADDRESS = setOf("name", "given-name", "additional-name", "family-name", "email", "tel", "organization", "street-address",
            "address-line1", "address-line2", "address-level1", "address-level2", "postal-code", "country", "country-name")

        fun of(s: String?): BrowserVault = runCatching {
            val o = JSONObject(s!!)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
            BrowserVault(
                list("logins").map { SavedLogin(it.getString("host"), it.optString("user"), it.getString("password"), it.optString("note"), it.optBoolean("pinned")) },
                list("addresses").map { a -> a.keys().asSequence().associateWith { a.getString(it) } },
                list("cards").map { SavedCard(it.optString("name"), it.getString("number"), it.optString("month"), it.optString("year")) })
        }.getOrDefault(BrowserVault())

        /** A form the page sent (the injected script's {user, pass, fields}) as what could be kept for [host]. */
        fun fromForm(host: String, form: JSONObject): BrowserVault {
            val f = form.optJSONObject("fields")?.let { o -> o.keys().asSequence().associateWith { o.getString(it).trim() }.filterValues { it.isNotEmpty() } }.orEmpty()
            val pass = form.optString("pass")
            val login = if (pass.isNotEmpty() && host.isNotEmpty()) listOf(SavedLogin(host, form.optString("user").trim(), pass)) else emptyList()
            val address = f.filterKeys { it in ADDRESS }.takeIf { it.size >= 2 } // one field alone is a search or a newsletter, not an address
            val number = f["cc-number"]?.filter(Char::isDigit).orEmpty()
            val exp = f["cc-exp"]?.split('/', '-', ' ')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
            val card = if (number.length in 12..19) SavedCard(f["cc-name"].orEmpty(), number,
                (f["cc-exp-month"] ?: exp.getOrNull(0)).orEmpty().padStart(2, '0').takeLast(2), (f["cc-exp-year"] ?: exp.getOrNull(1)).orEmpty()) else null
            return BrowserVault(login, listOfNotNull(address), listOfNotNull(card))
        }
    }
}

/** The vault in the keystore's encryption (Mail's way); never synced. */
internal object BrowserSecrets : KeystoreSecrets("tetra_browser_key", "browser_secrets") {
    fun vault(c: Context) = BrowserVault.of(get(c, "vault"))
    fun save(c: Context, v: BrowserVault) = set(c, "vault", if (v.empty) null else v.json())
}

/**
 * Import from Firefox: what desktop Firefox exports (Firefox on Android exports nothing) — Passwords › Export (logins.csv),
 * and Bookmarks › Export to HTML or Backup (.json).
 */
internal object FirefoxImport {
    data class Result(val logins: List<SavedLogin>, val bookmarks: List<Bookmark>)

    fun read(text: String): Result? {
        val t = text.trimStart('﻿', ' ', '\n', '\r', '\t')
        return when {
            t.startsWith("<!DOCTYPE NETSCAPE-Bookmark", true) -> Result(emptyList(), bookmarksHtml(t))
            t.startsWith("{") -> runCatching { Result(emptyList(), bookmarksJson(t)) }.getOrNull()
            else -> logins(t)
        }
    }

    /**
     * Firefox's logins.csv: "url","username","password",… with a header; a line break may be inside quotes. Google Password
     * Manager's (Settings › Export passwords) is name,url,username,password,note: its app logins' url is android://…@package,
     * so their host is the package; a row with no url falls back to its name.
     */
    fun logins(text: String): Result? {
        val rows = csv(text)
        val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return null
        val u = head.indexOf("url"); val n = head.indexOf("username"); val p = head.indexOf("password")
        val name = head.indexOf("name"); val note = head.indexOf("note")
        if (u < 0 || p < 0) return null
        val logins = rows.drop(1).mapNotNull { r ->
            val host = BrowserRules.host(r.getOrNull(u)).ifEmpty { r.getOrNull(name)?.trim()?.lowercase().orEmpty() }
            val pass = r.getOrNull(p).orEmpty()
            if (host.isEmpty() || pass.isEmpty()) null else SavedLogin(host, r.getOrNull(n).orEmpty(), pass, r.getOrNull(note).orEmpty().trim())
        }
        return Result(logins, emptyList())
    }

    fun csv(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotEmpty() }) rows += row
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotEmpty() }) rows += row
        return rows
    }

    /**
     * The HTML export (Firefox's, Chrome's, Edge's: Netscape's format), its folders kept: an `<H3>` names the `<DL>` after it,
     * each page in the folders it is in. Firefox's Bookmarks Menu is the file's own list, so its pages are at the top.
     */
    fun bookmarksHtml(html: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        val open = mutableListOf<String?>() // one per <DL> open: the folder it is, or null for the file's own list
        var named: String? = null
        Regex("<H3\\b[^>]*>(.*?)</H3>|<A\\s([^>]*)>(.*?)</A>|<DL\\b[^>]*>|</DL\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html).forEach { m ->
            val tag = m.value.take(3).uppercase()
            when {
                tag == "<H3" -> named = BookmarkLibrary.clean(entities(m.groupValues[1]))
                tag == "<DL" -> { open += named; named = null }
                tag == "</D" -> open.removeLastOrNull()
                else -> Regex("HREF=\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(m.groupValues[2])?.let { h ->
                    out += Bookmark(entities(h.groupValues[1]), entities(m.groupValues[3]).trim(), folder = open.filterNotNull().filter { it.isNotEmpty() }.joinToString("/"))
                }
            }
        }
        return out.filter { it.url.startsWith("http", true) }
    }

    /** Firefox's backup: folders of children, each bookmark a "text/x-moz-place" with its uri; the four roots named as the HTML names them. */
    fun bookmarksJson(json: String): List<Bookmark> {
        val out = mutableListOf<Bookmark>()
        fun walk(o: JSONObject, path: String, depth: Int) {
            o.optString("uri").takeIf { it.startsWith("http", true) }?.let { out += Bookmark(it, o.optString("title"), folder = path) }
            val title = o.optString("title")
            val name = when { depth == 0 -> ""; depth == 1 -> ROOTS[title] ?: BookmarkLibrary.clean(title); else -> BookmarkLibrary.clean(title) }
            o.optJSONArray("children")?.let { a -> for (i in 0 until a.length()) walk(a.getJSONObject(i), BookmarkLibrary.join(path, name), depth + 1) }
        }
        walk(JSONObject(json), "", 0)
        return out
    }
    private val ROOTS = mapOf("menu" to "", "toolbar" to "Bookmarks Toolbar", "unfiled" to "Other Bookmarks", "mobile" to "Mobile Bookmarks")

    private fun entities(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

/**
 * Player mode and background play (the user, 2 Nov: "play youtube videos on the background", "like Youtube music"): any page
 * that plays media works through its video/audio and its own navigator.mediaSession keys; a site here only when it needs its
 * own buttons, or for AI Assist's search-and-play. A new site is one line.
 */
internal data class MediaSite(val id: String, val hosts: Set<String>, val next: String = "", val prev: String = "", val search: String = "", val first: String = "",
    /** Its search for playlists only, and a playlist's link that starts it playing (empty: the plain search). */
    val lists: String = "", val firstList: String = "",
    /** [desktop]: opened as the desktop site; [press]: its result's page needs Play pressed; [greeklish]: nothing found in Greek, asked again in Latin letters. */
    val desktop: Boolean = false, val press: Boolean = false, val greeklish: Boolean = false,
    /** [like]: what plays now added to the user's favourites (Spotify's Liked Songs); [mixes]: the page with the user's own mixes. */
    val like: String = "", val mixes: String = "")

internal object MediaSites {
    // shortcut: these selectors are YouTube's and YouTube Music's own markup as of Nov 2026, untried on a phone; mend them when a click misses
    val ALL = listOf(
        MediaSite("youtube", setOf("youtube.com", "m.youtube.com", "www.youtube.com"), ".ytp-next-button, .player-controls-next-button, ytm-playlist-panel-renderer [aria-selected=true] + * a",
            ".ytp-prev-button, .player-controls-previous-button", "https://m.youtube.com/results?search_query=", "a[href*=\"/watch?v=\"]",
            // sp=EgIQAw: YouTube's "Playlists" filter; each result's /watch?v=…&list=… plays the list from its first song (4 Nov)
            "https://m.youtube.com/results?sp=EgIQAw%3D%3D&search_query=", "a[href*=\"/watch?v=\"][href*=\"list=\"]"),
        MediaSite("youtube_music", setOf("music.youtube.com"), ".next-button", ".previous-button", "https://music.youtube.com/search?q=", "a[href*=\"watch?v=\"]"),
        // Spotify's web player (the user, 9 Nov): its desktop site (phones are sent to its app); a song's or a list's page, then its Play;
        // its search finds little in Greek ("θα πρέπει να κάνει 2 αναζητήσεις"), so Greeklish after
        MediaSite("spotify", setOf("open.spotify.com"), "[data-testid=control-button-skip-forward]", "[data-testid=control-button-skip-back]",
            "https://open.spotify.com/search/{q}/tracks", "a[href*=\"/track/\"]", "https://open.spotify.com/search/{q}/playlists", "a[href*=\"/playlist/\"]",
            desktop = true, press = true, greeklish = true,
            // shortcut: Spotify's own markup as of Nov 2026, not tried logged in; mend when a click misses
            like = "[data-testid=now-playing-widget] button[aria-checked=false]", mixes = "https://open.spotify.com/"),
    )

    fun of(host: String) = ALL.firstOrNull { host.lowercase() in it.hosts }
    fun byId(id: String?) = ALL.firstOrNull { it.id == id } ?: ALL.first() // plain YouTube is the default

    /** AI Assist's search on [site]: its results page, the first result played from there. */
    fun searchUrl(site: MediaSite, query: String, list: Boolean = false): String {
        val t = if (list && site.lists.isNotEmpty()) site.lists else site.search
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        return if ("{q}" in t) t.replace("{q}", q.replace("+", "%20")) else t + q // in a path, a space is %20
    }

    private val LATIN = mapOf('α' to "a", 'β' to "v", 'γ' to "g", 'δ' to "d", 'ε' to "e", 'ζ' to "z", 'η' to "i", 'θ' to "th", 'ι' to "i", 'κ' to "k",
        'λ' to "l", 'μ' to "m", 'ν' to "n", 'ξ' to "x", 'ο' to "o", 'π' to "p", 'ρ' to "r", 'σ' to "s", 'ς' to "s", 'τ' to "t", 'υ' to "i", 'φ' to "f",
        'χ' to "h", 'ψ' to "ps", 'ω' to "o")

    /**
     * [query] in Greeklish as Spotify writes Greek names and titles, by their sound («Yannis Parios», «Haris & Panos Katsimihas»,
     * «Na Gini To Nero Fotia», «An Den Iha Ke Sena»): ει, οι, υ, η → i, αι → e, χ → h; null when it has no Greek.
     * Not [sound]: letter by letter, as Spotify writes others («Lavrentis Machairitsas», «Pyx Lax»): ει, οι, αι kept, υ → y, χ → ch.
     */
    fun greeklish(query: String, sound: Boolean = true): String? {
        val s = java.text.Normalizer.normalize(query.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        if (s.none { it in LATIN }) return null
        val t = s.replace("ου", "ou").replace(Regex("([αε])υ(?=[θκξπστφχψ])")) { if (it.groupValues[1] == "α") "af" else "ef" }.replace("αυ", "av").replace("ευ", "ev")
            .let { if (sound) it.replace("ει", "i").replace("οι", "i").replace("υι", "i").replace("αι", "e") else it.replace("υ", "y").replace("χ", "ch") }
            .replace("γγ", "ng").replace("γκ", "gk")
            .replace(Regex("(?<!\\p{L})μπ"), "b").replace(Regex("(?<!\\p{L})ντ"), "d").replace(Regex("(?<!\\p{L})gk"), "g")
        return buildString { for (c in t) append(LATIN[c] ?: c.toString()) }.trim()
    }

    /** The searches after the Greek one, each only when the one before found nothing: by sound, then letter by letter. */
    fun retries(query: String): List<String> = listOfNotNull(greeklish(query), greeklish(query, sound = false)).distinct()

    /**
     * [url] is [site]'s results page: by its host and path, not the address it was sent to — a tablet's desktop site
     * (www.youtube.com/results?app=desktop…) and the way back from the consent page change the address (4 Nov).
     */
    fun isSearch(site: MediaSite, url: String) = runCatching { java.net.URI(url).let { u -> u.host?.lowercase() in site.hosts && (u.path in setOf("/results", "/search") || u.path.startsWith("/search/")) } }.getOrDefault(false)

    /**
     * A saved station (a ♥ in Player mode is a bookmark) by the words said: the one whose name or address has the most
     * of them, a radio folder's first on a tie; null when none has any (AI Assist's "play Kosmos").
     */
    fun station(marks: List<Bookmark>, said: String): Bookmark? {
        val words = said.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in setOf("fm", "radio", "ράδιο", "ραδιόφωνο", "the", "το") }
        if (words.isEmpty()) return null
        fun hits(b: Bookmark) = (b.title + " " + b.url).lowercase().let { t -> words.count { it in t } }
        return marks.filter { hits(it) > 0 }.maxWithOrNull(compareBy<Bookmark> { hits(it) }.thenBy { "radio" in it.folder.lowercase() || "ραδιό" in it.folder.lowercase() })
    }

    /** A playlist or a queue in the address (YouTube's `list=`; a radio mix too): Player mode is offered, once per list. */
    fun listId(url: String?) = url?.let { Regex("[?&]list=([^&#]+)").find(it)?.groupValues?.get(1) }

    /** The site's own buttons for [site], for the page script (empty: the page's media keys alone). */
    fun buttons(site: MediaSite?) = JSONObject().put("next", site?.next.orEmpty()).put("prev", site?.prev.orEmpty()).put("first", site?.first.orEmpty()).put("like", site?.like.orEmpty()).toString()
}

/**
 * Which tools are on the bar and which in ☰'s sheet (the user, 2 Nov: "by long press you can edit what button is in the menu
 * or on the bar"); one list for a phone, one for a tablet. Order is the order chosen.
 */
internal object BrowserTools {
    val ALL = listOf("fill", "extensions", "private", "desktop", "html", "bookmarks", "history", "saved", "copy", "share", "split", "forward", "player", "import", "autohide", "background", "tabs_run", "close")
    /** uBlock Origin's button, where the ad blocker's shield was (the user, 3 Nov): its count of the page's blocked requests. */
    const val UBLOCK = "ext:" + BrowserEngine.UBLOCK
    val PHONE = listOf("fill", UBLOCK, "private", "bookmarks", "copy", "share") // two columns of three small (the user, 2 Nov: "fit 2 more tools")
    val TABLET = listOf("fill", UBLOCK, "private", "desktop", "html", "bookmarks", "history", "saved", "copy", "extensions", "split")
    fun max(wide: Boolean) = if (wide) 11 else 6

    /** The bar as kept; an extension's button is "ext:" and its id; the old ad blocker's place goes to uBlock Origin. */
    fun parse(saved: String?, wide: Boolean) = saved?.split(',')?.map { if (it == "ads") UBLOCK else it }?.filter { it in ALL || it.startsWith("ext:") }?.distinct()?.take(max(wide))
        ?: if (wide) TABLET else PHONE

    /** A tool tapped in edit mode: off the bar, or onto its end; null when the bar is full. */
    fun toggle(bar: List<String>, id: String, wide: Boolean): List<String>? = when {
        id in bar -> bar - id
        bar.size >= max(wide) -> null
        else -> bar + id
    }
}

/** A tab as the tab row needs it: pinned ones are circles at the start; a group is one chip, its tabs after it while unfolded. */
internal data class TabInfo(val id: Int, val pinned: Boolean, val group: Int?)

internal sealed class TabRowItem {
    data class Tab(val id: Int, val pinned: Boolean, val grouped: Boolean) : TabRowItem()
    data class Group(val id: Int, val count: Int, val open: Boolean) : TabRowItem()
}

internal object BrowserTabRow {
    /** The row in order (the user, 2 Nov: "pinned tabs, just a circle"; "tab groups … you click the group tab and it unfolds"). */
    fun items(tabs: List<TabInfo>, open: Set<Int>): List<TabRowItem> {
        val out = tabs.filter { it.pinned }.map<TabInfo, TabRowItem> { TabRowItem.Tab(it.id, true, false) }.toMutableList()
        val seen = mutableSetOf<Int>()
        for (t in tabs.filter { !it.pinned }) {
            val g = t.group
            if (g == null) { out += TabRowItem.Tab(t.id, false, false); continue }
            if (!seen.add(g)) continue
            val members = tabs.filter { !it.pinned && it.group == g }
            out += TabRowItem.Group(g, members.size, g in open)
            if (g in open) members.forEach { out += TabRowItem.Tab(it.id, false, true) }
        }
        return out
    }
}

/**
 * A tab kept from one time to the next: pinned and grouped ones always (the user, 2 Nov: "groups and pinned tabs keep them");
 * the others as long as the history is ("gone according to the setting: end of session, 24 h, never"), [at] when last kept.
 * Never Private's.
 */
internal data class SavedTab(val url: String, val title: String, val pinned: Boolean, val group: Int?, val at: Long = 0)

internal object SavedTabs {
    fun json(tabs: List<SavedTab>, groups: Map<Int, String>, now: Long = System.currentTimeMillis()): String = JSONObject()
        .put("tabs", JSONArray(tabs.filter { it.url.isNotEmpty() }.map {
            JSONObject().put("url", it.url).put("title", it.title).put("pinned", it.pinned).put("at", now).apply { it.group?.let { g -> put("group", g) } }
        }))
        .put("groups", JSONObject(groups.mapKeys { it.key.toString() }))
        .toString()

    /** The kept tabs [keep] lets back (BrowserRules' ON_EXIT, DAY, NEVER) and the names of the groups they are in (a group left with no tab is dropped). */
    fun read(s: String?, keep: String = BrowserRules.ON_EXIT, now: Long = System.currentTimeMillis()): Pair<List<SavedTab>, Map<Int, String>> = runCatching {
        val o = JSONObject(s!!)
        val a = o.getJSONArray("tabs")
        val tabs = (0 until a.length()).map { a.getJSONObject(it).run { SavedTab(getString("url"), optString("title"), optBoolean("pinned"), if (has("group")) getInt("group") else null, optLong("at")) } }
            .filter { it.pinned || it.group != null || keep == BrowserRules.NEVER || (keep == BrowserRules.DAY && now - it.at in 0 until BrowserRules.KEEP) }
        val g = o.optJSONObject("groups")
        val names = g?.keys()?.asSequence()?.associate { it.toInt() to g.getString(it) }.orEmpty().filterKeys { k -> tabs.any { it.group == k } }
        tabs to names
    }.getOrDefault(emptyList<SavedTab>() to emptyMap())
}
