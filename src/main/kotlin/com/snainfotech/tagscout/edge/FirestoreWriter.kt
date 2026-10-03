package com.snainfotech.tagscout.edge

import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.firestore.FieldValue
import com.google.cloud.firestore.Firestore
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.cloud.FirestoreClient
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.Paths

private val log = KotlinLogging.logger {}

/**
 * Writes deduped TagEvent documents to Firestore.
 *
 * Path: /companies/{companyId}/tag_events/{auto-id}
 *
 * Document shape (one write per TagEvent):
 *   epc            : String   — the EPC in uppercase hex
 *   antennaPort    : Int      — which antenna saw it
 *   rssi           : Int      — peak RSSI in dBm (negative)
 *   firstSeenAt    : Long     — epoch millis of first raw read in window
 *   lastSeenAt     : Long     — epoch millis of last raw read in window
 *   dwellMillis    : Long     — lastSeenAt - firstSeenAt (precomputed for queries)
 *   rawReadCount   : Int      — how many raw reads collapsed into this event
 *   readerId       : String   — which reader produced it (hardcoded for now)
 *   serverCreatedAt: Timestamp — Firestore server-side timestamp (authoritative)
 *
 * Single-tenant for now: companyId = your Firebase user UID.
 * In File 7+ we'll move both companyId and the service-account path into config.
 */
class FirestoreWriter private constructor(
    private val companyId: String,
    private val readerId: String
) {

    private val db: Firestore = FirestoreClient.getFirestore()

    /**
     * Writes one TagEvent to Firestore. Blocking call — the caller is expected
     * to invoke this from a background thread (the dedup pipeline worker).
     *
     * @return the auto-generated document ID on success
     * @throws Exception if the write fails after Firestore's internal retries
     */
    fun write(event: TagEvent): String {
        val doc = mapOf(
            "epc" to event.epc,
            "antennaPort" to event.antennaPort,
            "rssi" to event.rssi,
            "firstSeenAt" to event.firstSeenAt,
            "lastSeenAt" to event.lastSeenAt,
            "dwellMillis" to event.dwellMillis,
            "rawReadCount" to event.rawReadCount,
            "readerId" to readerId,
            "serverCreatedAt" to FieldValue.serverTimestamp()
        )

        val ref = db.collection("companies")
            .document(companyId)
            .collection("tag_events")
            .document()

        // .set() returns an ApiFuture; .get() blocks until the write lands.
        ref.set(doc).get()
        return ref.id
    }

    companion object {
        @Volatile
        private var INSTANCE: FirestoreWriter? = null

        /**
         * Lazy-initializes Firebase Admin SDK on first call. Safe to call from
         * multiple threads — subsequent calls return the same singleton.
         *
         * @param serviceAccountPath absolute or relative path to the Firebase
         *                           service account JSON file
         * @param companyId          the Firebase user UID to scope writes under
         * @param readerId           identifier for the reader this edge PC manages
         */
        fun getInstance(
            serviceAccountPath: String,
            companyId: String,
            readerId: String
        ): FirestoreWriter {
            INSTANCE?.let { return it }

            synchronized(this) {
                INSTANCE?.let { return it }

                val keyPath = Paths.get(serviceAccountPath).toAbsolutePath()
                if (!Files.exists(keyPath)) {
                    throw IllegalStateException(
                        "Service account key not found at $keyPath. " +
                                "Download from Firebase Console → Project Settings → Service accounts, " +
                                "and place at $serviceAccountPath"
                    )
                }

                log.info { "Initializing Firebase Admin SDK with key: $keyPath" }

                FileInputStream(keyPath.toFile()).use { credentialsStream ->
                    val options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.fromStream(credentialsStream))
                        .build()
                    FirebaseApp.initializeApp(options)
                }

                log.info { "Firebase initialized. Writes will target companies/$companyId/tag_events/" }
                INSTANCE = FirestoreWriter(companyId, readerId)
                return INSTANCE!!
            }
        }
    }
}