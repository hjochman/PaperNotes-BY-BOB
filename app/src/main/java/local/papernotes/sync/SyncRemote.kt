package local.papernotes.sync

import java.io.File

/** Fehler beim Zugriff auf den Server. Bewusst ohne Servertext oder Adressen in der Meldung (nichts davon wird geloggt). */
class RemoteException(val kind: Kind, val code: Int = 0) : Exception("$kind $code") {
    enum class Kind {
        /** Keine Verbindung, Zeitüberschreitung, TLS-Fehler: später erneut versuchen. */
        NETWORK,

        /** 401/403: Anmeldung ungültig oder widerrufen. */
        AUTH,

        /** 507: Speicherplatz oder Kontingent voll. */
        QUOTA,

        /** 409: Elternordner fehlt auf dem Server. */
        CONFLICT,

        /** 404. */
        NOT_FOUND,

        /** 5xx, 423, 429, Wartungsmodus: später erneut versuchen. */
        SERVER,

        /** Unerwartete oder unlesbare Antwort. */
        PROTOCOL,
    }
}

/** Eine Datei auf dem Server im Zielordner: Ordner relativ zum Zielordner, Größe, Änderungszeit in ms. */
data class RemoteFile(val folder: String, val size: Long, val modifiedMs: Long)

/** Was der Server im Zielordner hat (nur Namen, die auch der Sync anlegt: Ordner mit gültigem Namen, `<uuid>.pnote`). */
data class RemoteTree(val folders: Set<String>, val files: Map<String, RemoteFile>)

/**
 * Die Server-Seite des Sync. Alle Methoden blockieren und werfen [RemoteException]. Es gibt absichtlich keine Operation,
 * die vom Server liest, außer [listTree] und [hasChildren] (Abgleich beim ersten Lauf bzw. Schutz vor dem Löschen fremder Inhalte).
 */
interface SyncRemote {
    fun listTree(): RemoteTree

    /** Legt den Ordner an; ein bereits vorhandener Ordner ist kein Fehler. */
    fun makeFolder(path: String)

    /** Verschiebt `<id>.pnote` serverseitig und ersetzt dort ein vorhandenes Ziel. */
    fun move(id: String, fromFolder: String, toFolder: String)

    /** Lädt [source] als `<id>.pnote` in [file]`.folder` hoch und setzt die Änderungszeit auf die der Datei. */
    fun upload(file: LocalFile, source: File)

    /** Löscht `<id>.pnote`; eine fehlende Datei ist kein Fehler. */
    fun delete(id: String, folder: String)

    /** Hat der Ordner irgendwelchen Inhalt (auch Fremdes)? Ein fehlender Ordner hat keinen. */
    fun hasChildren(path: String): Boolean

    /** Löscht den Ordner samt Inhalt; ein fehlender Ordner ist kein Fehler. Nur nach [hasChildren] = false aufrufen. */
    fun deleteFolder(path: String)
}

/** Ablage des Gedächtnisses. [load] liefert null, wenn es fehlt oder unlesbar ist. */
interface SyncStateStore {
    fun load(): SyncState?
    fun save(state: SyncState)
}

sealed class SyncResult {
    /** Alles war schon abgeglichen; es gab keinen Netzzugriff außer ggf. dem ersten Abgleich. */
    object NothingToDo : SyncResult()

    /** Der Plan lief durch. [again] = mindestens eine Notiz änderte sich währenddessen und ist noch ausstehend. */
    data class Done(val executed: Int, val again: Boolean) : SyncResult()

    /** Zu viele Löschungen auf einmal: erst nach Bestätigung (siehe [SyncPolicy]). */
    data class NeedsConfirmation(val plan: SyncPlan) : SyncResult()

    /** Der Notizordner war nicht lesbar (Speicher nicht da, keine Berechtigung). Es wurde nichts gelöscht. */
    object NoStorage : SyncResult()

    /** Kein Konto eingerichtet oder abgeschaltet. */
    object NotConfigured : SyncResult()

    /** Vorübergehend gescheitert (Netz, Server, Abbruch durch das System): erneut versuchen. [remaining] = noch offene Operationen. */
    data class Retry(val kind: RemoteException.Kind, val remaining: Int) : SyncResult()

    /** Anmeldung ungültig: der Nutzer muss sich neu anmelden. */
    object AuthFailed : SyncResult()

    /** Dauerhaft gescheitert (z. B. Speicher voll): nicht automatisch wiederholen. */
    data class Failed(val kind: RemoteException.Kind) : SyncResult()
}
