package org.fitchfamily.android.dejavu

/*
*    Local NLP Backend / DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*    Copyright (C) 2023 Helium314
*
*    This program is Free Software: you can redistribute it and/or modify
*    it under the terms of the GNU General Public License as
*    published by the Free Software Foundation, either version 3 of the
*    License, or (at your option) any later version.
*
*    This program is distributed in the hope that it will be useful,
*    but WITHOUT ANY WARRANTY; without even the implied warranty of
*    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
*    GNU General Public License for more details.
*
*    You should have received a copy of the GNU General Public License
*    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

import android.location.Location
import android.util.Log
import org.fitchfamily.android.dejavu.EmitterType.*
import kotlin.math.abs

/**
 * Created by tfitch on 8/27/17.
 * modified by helium314 in 2022
 */
/**
 * Models everything we know about an RF emitter: Its identification, most recently received
 * signal level, an estimate of its coverage (center point and radius), etc.
 *
 * Starting with v2 of the database, we store a north-south radius and an east-west radius which
 * allows for a rectangular bounding box rather than a square one.
 *
 * When an RF emitter is first observed we create a new object and, if information exists in
 * the database, populate it from saved information.
 *
 * Periodically we sync our current information about the emitter back to the flash memory
 * based storage.
 */
class RfEmitter(val type: EmitterType, val id: String) {
    internal constructor(identification: RfIdentification) : this(identification.rfType, identification.rfId)

    internal constructor(identification: RfIdentification, emitterInfo: EmitterInfo) : this(identification.rfType, identification.rfId, emitterInfo)

    internal constructor(type: EmitterType, id: String, emitterInfo: EmitterInfo) : this(type, id) {
        if (emitterInfo.radius_ew < 0) {
            coverage = null
            status = EmitterStatus.STATUS_BLACKLISTED
        } else {
            coverage = BoundingBox(emitterInfo)
            status = EmitterStatus.STATUS_CACHED
        }
        note = emitterInfo.note
        // this is only for emitters that were created using old versions, with new ones too large emitters can't be in db
        if (emitterInfo.radius_ew > type.getRfCharacteristics().maximumRange || emitterInfo.radius_ns > type.getRfCharacteristics().maximumRange)
            changeStatus(EmitterStatus.STATUS_BLACKLISTED, "$logString: loaded from db, but radius too large")
    }

    private val ourCharacteristics = type.getRfCharacteristics()
    var coverage: BoundingBox? = null // null for new or blacklisted emitters
    var note: String = ""
        set(value) {
            if (field == value)
                return
            field = value
            if (isBlacklisted())
                changeStatus(EmitterStatus.STATUS_BLACKLISTED, "$logString: emitter blacklisted")
        }
    var lastObservation: Observation? = null // null if we haven't seen this emitter
        set(value) {
            field = value
            note = value?.note ?: ""
        }
    var status: EmitterStatus = EmitterStatus.STATUS_UNKNOWN
        private set

    val uniqueId: String get() = rfIdentification.uniqueId
    val rfIdentification: RfIdentification = RfIdentification(id, type)
    val lat: Double get() = coverage?.center_lat ?: 0.0
    val lon: Double get() = coverage?.center_lon ?: 0.0
    private val radius: Double get() = coverage?.radius ?: 0.0
    val radiusNS: Double get() = coverage?.radius_ns ?: 0.0
    val radiusEW: Double get() = coverage?.radius_ew ?: 0.0

    /**
     * All RfEmitter objects are managed through a cache. The cache needs ages out
     * emitters that have not been seen (or used) in a while. To do that it needs
     * to maintain age information for each RfEmitter object. Having the RfEmitter
     * object itself store the cache age is a bit of a hack, but we do it anyway.
     *
     * @return The current cache age (number of periods since last observation).
     */
    var age = 0
        private set

    /**
     * On equality check, we only check that our type and ID match as that
     * uniquely identifies our RF emitter.
     *
     * @param other The object to check for equality
     * @return True if the objects should be considered the same.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is RfEmitter) return rfIdentification == other.rfIdentification
        if (other is RfIdentification) return rfIdentification == other
        return false
    }

    /**
     * Hash code is used to determine unique objects. Our "uniqueness" is
     * based on which "real life" RF emitter we model, not our current
     * coverage, etc. So our hash code should be the same as the hash
     * code of our identification.
     *
     * @return A hash code for this object.
     */
    override fun hashCode(): Int {
        return rfIdentification.hashCode()
    }

    /**
     * Resets the cache age to zero.
     */
    fun resetAge() {
        age = 0
    }

    /**
     * Increment the cache age for this object.
     */
    fun incrementAge() {
        age++
    }

    /**
     * Periodically the cache sync's all dirty objects to the flash database.
     * This routine is called by the cache to determine if it needs to be sync'd.
     *
     * @return True if this RfEmitter needs to be written to flash.
     */
    fun syncNeeded(): Boolean {
        return (status == EmitterStatus.STATUS_NEW
                    || status == EmitterStatus.STATUS_CHANGED
                    || (status == EmitterStatus.STATUS_BLACKLISTED
                        && coverage != null)
                )
    }

    /**
     * Synchronize this object to the flash based database. This method is called
     * by the cache when it is an appropriate time to assure the flash based
     * database is up to date with our current coverage, etc.
     *
     * @param db The database we should write our data to.
     */
    fun sync(db: Database) {
        var newStatus = status
        when (status) {
            EmitterStatus.STATUS_UNKNOWN -> { }
            EmitterStatus.STATUS_BLACKLISTED ->
                // If our coverage value is not null it implies that we exist in the
                // database as "normal" emitter. If so we ought to either remove the entry (for
                // blacklisted SSIDs) or set invalid radius (for too large coverage).
                if (coverage != null) {
                    if (isBlacklisted()) {
                        db.drop(this)
                        if (DEBUG) Log.d(TAG, "sync('$logString') - Blacklisted dropping from database.")
                    } else {
                        db.setInvalid(this)
                        if (DEBUG) Log.d(TAG, "sync('$logString') - Blacklisted setting to invalid, radius too large: $radius, $radiusEW, $radiusNS.")
                    }
                    coverage = null
                }
            EmitterStatus.STATUS_NEW -> {
                // Not in database, we have location. Add to database
                db.insert(this)
                newStatus = EmitterStatus.STATUS_CACHED
            }
            EmitterStatus.STATUS_CHANGED -> {
                // In database but we have changes
                db.update(this)
                newStatus = EmitterStatus.STATUS_CACHED
            }
            EmitterStatus.STATUS_CACHED -> { }
        }
        changeStatus(newStatus, "sync('$logString')")
    }

    val logString get() = if (DEBUG) "RF Emitter: Type=$type, ID='$id', Note='$note'" else ""

    /**
     * Update our estimate of the coverage and location of the emitter based on a
     * position report from the GPS system.
     *
     * @param gpsLoc A position report from a trusted (non RF emitter) source
     */
    fun updateLocation(gpsLoc: Location) {
        if (status == EmitterStatus.STATUS_BLACKLISTED) return
        val l = lastObservation ?: return // can't update emitters we haven't seen
        val cov = coverage
        // determine whether emitter will grow to unrealistic size if updated
        val tooLarge = if (cov != null) approximateDistance(gpsLoc.latitude, gpsLoc.longitude, cov.center_lat, cov.center_lon) > (type.getRfCharacteristics().maximumRange + gpsLoc.accuracy) * 2
            else false
        if (l.suspicious && !tooLarge) { // if it will be too large, always update (effectively blacklists this emitter)
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - No update because last observation is suspicious")
            return
        }

        // Don't update location if there is more than 10 sec difference between last observation
        // and gps location, or even less if we are moving really fast compared to the emitter range
        // (because we might have moved considerably during this time).
        // This can occur e.g. if a WiFi scan takes very long to complete or old scan results are reported
        val tDiff = abs(l.elapsedRealtimeNanos - gpsLoc.elapsedRealtimeNanos) * 1e-9
        val tDiffMax = if (gpsLoc.hasSpeed() && gpsLoc.speed > 0)
                // time we need to move through half the maximum range, but at most 10s
                (ourCharacteristics.maximumRange / 2 / gpsLoc.speed).coerceAtMost(10.0)
            else 10.0
        if (tDiff > tDiffMax) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - No update because location and observation " +
                    "differ too much: ${(l.elapsedRealtimeNanos - gpsLoc.elapsedRealtimeNanos)/1e6}ms")
            return
        }

        // don't update coverage if gps too inaccurate
        // except if if emitter would grow too large after updating, in which case we want to blacklist it
        if (gpsLoc.accuracy > ourCharacteristics.requiredGpsAccuracy && !tooLarge) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - No update because location inaccurate. accuracy ${gpsLoc.accuracy}, required ${ourCharacteristics.requiredGpsAccuracy}")
            return
        }
        if (cov == null) {
            if (DEBUG) Log.d(TAG, "updateLocation($logString) - Emitter is new.")
            coverage = BoundingBox(gpsLoc.latitude, gpsLoc.longitude)
            changeStatus(EmitterStatus.STATUS_NEW, "updateLocation($logString) New")
            return
        }

        // Add the GPS sample to the known bounding box of the emitter.
        if (cov.update(gpsLoc.latitude, gpsLoc.longitude)) {
            // Bounding box has increased, see if it is now unbelievably large
            if (cov.radius > ourCharacteristics.maximumRange)
                changeStatus(EmitterStatus.STATUS_BLACKLISTED, "updateLocation($logString) too large radius")
            else
                changeStatus(EmitterStatus.STATUS_CHANGED, "updateLocation($logString) BBOX update")
        }
    }

    /**
     * RfLocation for backendService. Differs from internal one in that we don't report
     * locations that are guarded due to being new or moved.
     *
     * @return The coverage estimate and further information for our RF emitter or null if
     * we don't trust our information.
     */
    val location: RfLocation?
        get() {
            // If we have no observation of the emitter we ought not give a
            // position estimate based on it.
            val observation = lastObservation ?: return null

            if (status == EmitterStatus.STATUS_BLACKLISTED) return null

            // If we don't have a coverage estimate we will get back a null location
            val cov = coverage ?: return null

            // If we are unbelievably close to null island, don't report location
            if (!notNullIsland(cov.center_lat, cov.center_lon)) return null

            // Use time and asu based on most recent observation
            return RfLocation(observation.lastUpdateTimeMs, observation.elapsedRealtimeNanos,
                cov.center_lat, cov.center_lon, radius, observation.asu, rfIdentification, observation.suspicious)
        }

    /**
     * As part of our effort to not use mobile emitters in estimating or location
     * we blacklist ones that match observed patterns.
     *
     * @return True if the emitter is blacklisted (should not be used in position computations).
     */
    private fun isBlacklisted(): Boolean =
        if (note.isEmpty()) false
        else
             when (type) {
                 WLAN2, WLAN5, WLAN6 -> ssidBlacklisted()
                 BT -> false // if ever added, there should be a BT blacklist too
                 else -> false // Not expecting mobile towers to move around.
            }

    /**
     * Checks the note field (where the SSID is saved) to see if it appears to be
     * an AP that is likely to be moving. Typical checks are to see if substrings
     * in the SSID match that of cell phone manufacturers or match known patterns
     * for public transport (busses, trains, etc.) or in car WLAN defaults.
     *
     * @return True if emitter should be blacklisted.
     */
    private fun ssidBlacklisted(): Boolean {
        val lc = note.lowercase()

        // split lc into continuous occurrences of a-z
        // most 'contains' checks only make sense if the string is a separate word
        // this accelerates comparison a lot, at the risk of missing some WiFis
        val lcSplit = lc.split(splitRegex).toHashSet()

        // Seen a large number of WiFi networks where the SSID is the last
        // three octets of the MAC address. Often in rural areas where the
        // only obvious source would be other automobiles. So suspect that
        // this is the default setup for a number of vehicle manufactures.
        val macSuffix =
            id.substring(id.length - 8).lowercase().replace(":", "")

        val blacklisted =
            lcSplit.any { blacklistWords.contains(it) }
                || blacklistStartsWith.any { lc.startsWith(it) }
                || blacklistEndsWith.any { lc.endsWith(it) }
                || blacklistEquals.contains(lc)
                // a few less simple checks
                || lcSplit.contains("moto") && note.startsWith("MOTO") // "MOTO9564" and "MOTO9916" seen
                || lcSplit.first() == "audi"            // some cars seem to have this AP on-board
                || lc == macSuffix                      // Apparent default SSID name for many cars
                // deal with words not achievable with the blacklist sets, checking only if
                // lcSplit.contains(<something>) (for performance reasons)
                || (lcSplit.contains("admin") && lc.contains("admin@ms"))
                || (lcSplit.contains("guest") && lc.contains("guest@ms"))
                || (lcSplit.contains("contiki") && lc.contains("contiki-wifi"))    // transport
                || (lcSplit.contains("interakti") && lc.contains("nsb_interakti")) // ???
                || (lcSplit.contains("nvram") && lc.contains("nvram warning"))     // transport

        if (DEBUG && blacklisted) Log.d(TAG, "blacklistWifi('$logString'): blacklisted")
        return blacklisted
    }

    /**
     * Our status can only make a small set of allowed transitions. Basically a simple
     * state machine. To assure our transitions are all legal, this routine is used for
     * all changes.
     *
     * @param newStatus The desired new status (state)
     * @param info Logging information for debug purposes
     */
    private fun changeStatus(newStatus: EmitterStatus, info: String) {
        if (newStatus == status) return
        when (status) {
            EmitterStatus.STATUS_BLACKLISTED -> { }
            EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_CHANGED ->
                when (newStatus) {
                   EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_CHANGED ->
                       status = newStatus
                   else -> { }
            }
            EmitterStatus.STATUS_NEW ->
                when (newStatus) {
                    EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED ->
                        status = newStatus
                    else -> { }
            }
            EmitterStatus.STATUS_UNKNOWN ->
                when (newStatus) {
                    EmitterStatus.STATUS_BLACKLISTED, EmitterStatus.STATUS_CACHED, EmitterStatus.STATUS_NEW ->
                        status = newStatus
                    else -> { }
            }
        }
        if (DEBUG) Log.d(TAG, "$info: tried switching to $newStatus, result: $status")
        return
    }
}

private val DEBUG = BuildConfig.DEBUG

private const val TAG = "LocalNLP RfEmitter"

private val splitRegex = "[^a-z]".toRegex() // for splitting SSID into "words"
// use hashSets for fast blacklist*.contains() check
private val blacklistWords = hashSetOf(
    "android", "ipad", "iphone", "phone", "motorola", "huawei", "nokia", "redmi", "realme",
    "honor", "oppo", "galaxy", "oneplus", // mobile tethering
    "mobile", // sounds like name for mobile hotspot
    "deinbus", "ecolines", "eurolines", "fernbus", "flixbus", "muenchenlinie",
    "postbus", "skanetrafiken", "oresundstag", "regiojet", "hotspotarriva", // transport

    // Per an instructional video on YouTube, recent (2014 and later) Chrysler-Fiat
    // vehicles have a SSID of the form "Chrysler uconnect xxxxxx" where xxxxxx
    // seems to be a hex digit string (suffix of BSSID?).
    "uconnect", // Chrysler built vehicles
    "chevy", // "Chevy Cruz 7774" and "Davids Chevy" seen.
    "silverado", // GMC Silverado. "Bryces Silverado" seen, maybe move to startsWith?
    "myvolvo", // Volvo in car WiFi, maybe move to startsWith?
    "bmw", // examples: BMW98303 CarPlay, My BMW Hotspot 8303, DIRECT-BMW 67727
    "skoda", // My Skoda 3358, Skoda_WLAN_5790
    "seat", // My SEAT 741, SEAT_WLAN
    "vw", // VW WLAN 9266, VW_WLAN, My VW 4025
)
private val blacklistEquals = hashSetOf(
    "amtrak", "amtrakconnect", "cdwifi", "megabus", "westlan","wifi in de trein",
    "svciob", "oebb", "oebb-postbus", "dpmbfree", "telekom_ice", "db ic bus",
    "gkbgast", "mavstart-wifi", "wifionice", "wifi@db", "crosscountrywifi",
    "gwr wifi", "thalysnet", "_sncf_wifi_inoui", "_sncf_wifi_intercities",
    "normandietrainconnecte", "keolis nederland", "ouifi", "raillan", "vorwlan",
    "zssk wifi", "wifi zssk", "mavstart-wifi", "raaberbahn", "hotspot ic", "vmobil" // transport
)
// and arrays if we just want to iterate
private val blacklistStartsWith = arrayOf(
    "moto ", "lg aristo", "androidap", "vivo ", "mi ", // mobile tethering
    "cellspot", // T-Mobile US portable cell based WiFi
    "verizon", // Verizon mobile hotspot

    // Per some instructional videos on YouTube, recent (2015 and later)
    // General Motors built vehicles come with a default WiFi SSID of the
    // form "WiFi Hotspot 1234" where the 1234 is different for each car.
    "wifi hotspot ", // Default GM vehicle WiFi name

    // Per instructional video on YouTube, Mercedes cars have and SSID of
    // "MB WLAN nnnnn" where nnnnn is a 5 digit number, same for MB Hostspot and direct-mb hotspot
    "mb wlan ", "mb hotspot", "direct-mb hotspot",
    "westbahn ", "buswifi", "coachamerica", "disneylandresortexpress",
    "taxilinq", "transitwirelesswifi", // transport, maybe move some to words?
    "yicarcam", // Dashcam WiFi
)
private val blacklistEndsWith = arrayOf(
    "corvette", // Chevy Corvette. "TS Corvette" seen.

    // General Motors built vehicles SSID can be changed but the recommended SSID to
    // change to is of the form "first_name vehicle_model" (e.g. "Bryces Silverado").
    "truck", // "Morgans Truck" and "Wally Truck" seen
    "suburban", // Chevy/GMC Suburban. "Laura Suburban" seen
    "terrain", // GMC Terrain. "Nelson Terrain" seen
    "sierra", // GMC pickup. "dees sierra" seen
    "gmc wifi", // General Motors
)

enum class EmitterStatus {
    STATUS_UNKNOWN,     // Newly discovered emitter, no data for it at all
    STATUS_NEW,         // Not in database but we've got location data for it
    STATUS_CHANGED,     // In database but something has changed
    STATUS_CACHED,      // In database no changes pending
    STATUS_BLACKLISTED  // Has been blacklisted
}

// most recent location information about the emitter
data class RfLocation(
    /** timestamp of most recent observation, like System.currentTimeMillis() */
    val time: Long,
    /** elapsedRealtimeNanos of most recent observation */
    val elapsedRealtimeNanos: Long,
    val lat: Double,
    val lon: Double,
    /** emitter radius, may be 0 */
    val radius: Double,
    /** asu of most recent observation */
    val asu: Int,
    val id: RfIdentification,
    /** whether we suspect the most recent observation might not be entirely correct */
    val suspicious: Boolean,
) {
    /** emitter radius, but at least minimumRange for this EmitterType */
    val accuracyEstimate: Double = radius.coerceAtLeast(id.rfType.getRfCharacteristics().minimumRange)
}
