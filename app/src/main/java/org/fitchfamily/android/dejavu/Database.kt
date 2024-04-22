package org.fitchfamily.android.dejavu

/*
*    Local NLP Backend / DejaVu - A location provider backend for microG/UnifiedNlp
*
*    Copyright (C) 2017 Tod Fitch
*    Copyright (C) 2022 Helium314
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

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 *
 * Created by tfitch on 9/1/17.
 * modified by helium314 in 2022
 */
/**
 * Interface to our on flash SQL database. Note that these methods are not
 * thread safe. However all access to the database is through the Cache object
 * which is thread safe.
 */
class Database(context: Context?, name: String = DB_NAME) : // allow overriding name, useful for importing db
    SQLiteOpenHelper(context, name, null, VERSION) {
    private val database: SQLiteDatabase get() = writableDatabase
    private var withinTransaction = false
    private var updatesMade = false

    override fun onCreate(db: SQLiteDatabase) {
        withinTransaction = false
        // Always create version 1 of database, then update the schema
        // in the same order it might occur "in the wild". Avoids having
        // to check to see if the table exists (may be old version)
        // or not (can be new version).
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $TABLE_SAMPLES (
                $COL_RFID STRING PRIMARY KEY,
                $COL_TYPE STRING,
                $OLD_COL_TRUST INTEGER,
                $COL_LAT REAL,
                $COL_LON REAL,
                $OLD_COL_RAD REAL,
                $COL_NOTE STRING
            );
        """.trimIndent()
        )
        onUpgrade(db, 1, VERSION)
    }

    @SuppressLint("Recycle") // cursor is closed in toSequence
    private fun <T> query(
        columns: Array<String>? = null,
        where: String? = null,
        args: Array<String>? = null,
        groupBy: String? = null,
        having: String? = null,
        orderBy: String? = null,
        limit: String? = null,
        distinct: Boolean = false,
        transform: (CursorPosition) -> T
    ): Sequence<T> {
        return database.query(distinct, TABLE_SAMPLES, columns, where, args, groupBy, having, orderBy, limit).toSequence(transform)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upGradeToVersion2(db)
        if (oldVersion < 3) upGradeToVersion3(db)
        if (oldVersion < 4) upGradeToVersion4(db)
    }

    @SuppressLint("SQLiteString") // issue is known and fixed later, but keep this old code exactly as it was
    private fun upGradeToVersion2(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion2(): Entry")
        // Sqlite3 does not support dropping columns so we create a new table with our
        // current fields and copy the old data into it.
        with(db) {
            execSQL("BEGIN TRANSACTION;")
            execSQL("ALTER TABLE " + TABLE_SAMPLES + " RENAME TO " + TABLE_SAMPLES + "_old;")
            execSQL(
                ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "(" +
                        COL_RFID + " STRING PRIMARY KEY, " +
                        COL_TYPE + " STRING, " +
                        OLD_COL_TRUST + " INTEGER, " +
                        COL_LAT + " REAL, " +
                        COL_LON + " REAL, " +
                        COL_RAD_NS + " REAL, " +
                        COL_RAD_EW + " REAL, " +
                        COL_NOTE + " STRING);")
            )
            execSQL(
                ("INSERT INTO " + TABLE_SAMPLES + "(" +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        OLD_COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        COL_RAD_NS + ", " +
                        COL_RAD_EW + ", " +
                        COL_NOTE +
                        ") SELECT " +
                        COL_RFID + ", " +
                        COL_TYPE + ", " +
                        OLD_COL_TRUST + ", " +
                        COL_LAT + ", " +
                        COL_LON + ", " +
                        OLD_COL_RAD + ", " +
                        OLD_COL_RAD + ", " +
                        COL_NOTE +
                        " FROM " + TABLE_SAMPLES + "_old;")
            )
            execSQL("DROP TABLE " + TABLE_SAMPLES + "_old;")
            execSQL("COMMIT;")
        }
    }

    private fun upGradeToVersion3(db: SQLiteDatabase) {
        if (DEBUG) Log.d(TAG, "upGradeToVersion3(): Entry")

        // We are changing our key field to a new text field that contains a hash of
        // of the ID and type. In addition, we are dealing with a Lint complaint about
        // using a string field where we ought to be using a text field.
        db.execSQL("BEGIN TRANSACTION;")
        db.execSQL(
            ("CREATE TABLE IF NOT EXISTS " + TABLE_SAMPLES + "_new (" +
                    OLD_COL_HASH + " TEXT PRIMARY KEY, " +
                    COL_RFID + " TEXT, " +
                    COL_TYPE + " TEXT, " +
                    OLD_COL_TRUST + " INTEGER, " +
                    COL_LAT + " REAL, " +
                    COL_LON + " REAL, " +
                    COL_RAD_NS + " REAL, " +
                    COL_RAD_EW + " REAL, " +
                    COL_NOTE + " TEXT);")
        )
        val insert = db.compileStatement(
            ("INSERT INTO " +
                    TABLE_SAMPLES + "_new(" +
                    OLD_COL_HASH + ", " +
                    COL_RFID + ", " +
                    COL_TYPE + ", " +
                    OLD_COL_TRUST + ", " +
                    COL_LAT + ", " +
                    COL_LON + ", " +
                    COL_RAD_NS + ", " +
                    COL_RAD_EW + ", " +
                    COL_NOTE + ") " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")
        )
        val query = ("SELECT " +
                COL_RFID + "," + COL_TYPE + "," + OLD_COL_TRUST + "," + COL_LAT + "," + COL_LON + "," + COL_RAD_NS + "," + COL_RAD_EW + "," + COL_NOTE + " " +
                "FROM " + TABLE_SAMPLES + ";")
        db.rawQuery(query, null).use { cursor ->
            if (cursor!!.moveToFirst()) {
                do {
                    val rfId = cursor.getString(0)
                    var rftype = cursor.getString(1)
                    if ((rftype == "WLAN")) rftype = "WLAN_24GHZ"
                    val hash = rfId + rftype  // value doesn't matter, it's removed in next upgrade anyway

                    // Log.d(TAG,"upGradeToVersion2(): Updating '"+rfId.toString()+"'");
                    insert.bindString(1, hash)
                    insert.bindString(2, rfId)
                    insert.bindString(3, rftype)
                    insert.bindString(4, cursor.getString(2))
                    insert.bindString(5, cursor.getString(3))
                    insert.bindString(6, cursor.getString(4))
                    insert.bindString(7, cursor.getString(5))
                    insert.bindString(8, cursor.getString(6))
                    insert.bindString(9, cursor.getString(7))
                    insert.executeInsert()
                    insert.clearBindings()
                } while (cursor.moveToNext())
            }
        }
        db.execSQL("DROP TABLE $TABLE_SAMPLES;")
        db.execSQL("ALTER TABLE ${TABLE_SAMPLES}_new RENAME TO $TABLE_SAMPLES;")
        db.execSQL("COMMIT;")
    }

    private fun upGradeToVersion4(db: SQLiteDatabase) {
        // We replace the rfId hash with the actual rfId
        //  mobile emitter IDs are already unique
        //  WiFi emitters get WiFi type prefixed
        // Trust column is removed, like the whole trust system
        db.execSQL("BEGIN TRANSACTION;")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS ${TABLE_SAMPLES}_new (
            $COL_RFID TEXT PRIMARY KEY NOT NULL,
            $COL_TYPE TEXT NOT NULL,
            $COL_LAT REAL NOT NULL,
            $COL_LON REAL NOT NULL,
            $COL_RAD_NS REAL NOT NULL,
            $COL_RAD_EW REAL NOT NULL,
            $COL_NOTE TEXT
            );
        """.trimIndent()
        )
        // add 2.4 GHz WiFis
        db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT '${EmitterType.WLAN2}/' || $COL_RFID, '${EmitterType.WLAN2}', $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'WLAN_24GHZ';
        """.trimIndent()
        )
        // add 5 GHz WiFis
        db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT '${EmitterType.WLAN5}/' || $COL_RFID, '${EmitterType.WLAN5}', $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'WLAN_5GHZ';
        """.trimIndent()
        )
        // cell towers are already unique, but we need to split the types, as they may have different characteristics
        for (emitterType in arrayOf(EmitterType.GSM, EmitterType.WCDMA, EmitterType.CDMA, EmitterType.LTE)) {
            db.execSQL("""
            INSERT INTO ${TABLE_SAMPLES}_new($COL_RFID, $COL_TYPE, $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE)
            SELECT $COL_RFID, '${emitterType}', $COL_LAT, $COL_LON, $COL_RAD_NS, $COL_RAD_EW, $COL_NOTE
            FROM $TABLE_SAMPLES
            WHERE $COL_TYPE = 'MOBILE' AND $COL_RFID LIKE '${emitterType}%';
        """.trimIndent()
            )
        }
        db.execSQL("DROP TABLE $TABLE_SAMPLES;")
        db.execSQL("ALTER TABLE ${TABLE_SAMPLES}_new RENAME TO $TABLE_SAMPLES;")
        db.execSQL("COMMIT;")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (databaseName == DB_NAME)
            instance = this
    }

    override fun close() {
        if (databaseName == DB_NAME)
            instance = null
        super.close()
    }

    /**
     * Start an update operation.
     */
    fun beginTransaction() {
        if (withinTransaction) {
            if (DEBUG) Log.d(TAG, "beginTransaction() - Already in a transaction?")
            return
        }
        withinTransaction = true
        updatesMade = false
        database.beginTransaction()
    }

    /**
     * End a transaction. If we actually made any changes then we mark
     * the transaction as successful. Once marked as successful we
     * end the transaction with the underlying SQL database.
     */
    fun endTransaction() {
        if (!withinTransaction) {
            if (DEBUG) Log.d(TAG, "Asked to end transaction but we are not in one???")
            return
        }
        if (updatesMade)
            database.setTransactionSuccessful()
        updatesMade = false
        database.endTransaction()
        withinTransaction = false
    }

    /**
     * End a transaction without marking it as successful.
     */
    fun cancelTransaction() {
        if (!withinTransaction) {
            if (DEBUG) Log.d(TAG, "Asked to end transaction but we are not in one???")
            return
        }
        updatesMade = false
        database.endTransaction()
        withinTransaction = false
    }

    /**
     * Drop an RF emitter from the database.
     *
     * @param emitter The emitter to be dropped.
     */
    fun drop(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Dropping " + emitter.logString + " from db")
        database.delete(TABLE_SAMPLES, "$COL_RFID = '${emitter.uniqueId}'", null)
        updatesMade = true
    }

    /**
     * Insert a new RF emitter into the database.
     *
     * @param emitter The emitter to be added.
     */
    fun insert(emitter: RfEmitter, collision: Int = SQLiteDatabase.CONFLICT_ABORT) {
        val cv = ContentValues(7).apply {
            put(COL_RFID, emitter.uniqueId)
            put(COL_TYPE, emitter.type.toString())
            put(COL_LAT, emitter.lat)
            put(COL_LON, emitter.lon)
            put(COL_RAD_NS, emitter.radiusNS)
            put(COL_RAD_EW, emitter.radiusEW)
            put(COL_NOTE, emitter.note)
        }
        insertWithCollision(cv, collision)
    }

    fun insertLine(collision: Int, rfId: String, type: String, lat: Double, lon: Double, radius_ns: Double, radius_ew: Double, note: String) {
        val cv = ContentValues(7).apply {
            put(COL_RFID, rfId)
            put(COL_TYPE, type)
            put(COL_LAT, lat)
            put(COL_LON, lon)
            put(COL_RAD_NS, radius_ns)
            put(COL_RAD_EW, radius_ew)
            put(COL_NOTE, note)
        }
        insertWithCollision(cv, collision)
    }

    private fun insertWithCollision(cv: ContentValues, collision: Int) {
        if (DEBUG) Log.d(TAG, "Inserting $cv into db with collision $collision")
        if (collision == COLLISION_MERGE && database.insertWithOnConflict(TABLE_SAMPLES, null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) { // -1 is returned if a conflict is detected
            // trying to insert, but row exists and we want to merge
            val bboxOld = query(arrayOf(COL_LAT, COL_LON, COL_RAD_NS, COL_RAD_EW), "$COL_RFID = '${cv.getAsString(COL_RFID)}'", limit = "1") {
                val ew = it.getDouble(COL_RAD_EW)
                if (ew < 0) null
                else BoundingBox(it.getDouble(COL_LAT), it.getDouble(COL_LON), it.getDouble(COL_RAD_NS), ew)
            }.firstOrNull()
            val bboxNew = BoundingBox(cv.getAsDouble(COL_LAT), cv.getAsDouble(COL_LON), cv.getAsDouble(COL_RAD_NS), cv.getAsDouble(COL_RAD_EW))
            if (bboxNew == bboxOld) return
            if (bboxOld != null) {
                bboxNew.update(bboxOld.south, bboxOld.east)
                bboxNew.update(bboxOld.north, bboxOld.west)
            }
            val cvUpdate = ContentValues(4).apply {
                put(COL_LAT, bboxNew.center_lat)
                put(COL_LON, bboxNew.center_lon)
                put(COL_RAD_NS, bboxNew.radius_ns)
                put(COL_RAD_EW, bboxNew.radius_ew)
            }
            database.update(TABLE_SAMPLES, cvUpdate, "$COL_RFID = '${cv.getAsString(COL_RFID)}'", null)
        } else if (collision != COLLISION_MERGE)
            database.insertWithOnConflict(TABLE_SAMPLES, null, cv, collision)
        updatesMade = true
    }

    fun setInvalid(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Setting to invalid: " + emitter.logString)
        database.update(
            TABLE_SAMPLES,
            ContentValues(2).apply {
                put(COL_RAD_NS, -1.0)
                put(COL_RAD_EW, -1.0)
            },
            "$COL_RFID = '${emitter.uniqueId}'",
            null
        )
        updatesMade = true
    }

    /**
     * Update information about an emitter already existing in the database
     *
     * @param emitter The emitter to be updated
     */
    fun update(emitter: RfEmitter) {
        if (DEBUG) Log.d(TAG, "Updating " + emitter.logString)
        val cv = ContentValues(5).apply {
            put(COL_LAT, emitter.lat)
            put(COL_LON, emitter.lon)
            put(COL_RAD_NS, emitter.radiusNS)
            put(COL_RAD_EW, emitter.radiusEW)
            put(COL_NOTE, emitter.note)
        }
        database.update(TABLE_SAMPLES, cv, "$COL_RFID = '${emitter.uniqueId}'", null)
        updatesMade = true
    }

    /**
     * Get all the information we have on a single RF emitter
     *
     * @param rfId The identification of the emitter caller wants
     * @return A emitter object with all the information we have. Or null if we have nothing.
     */
    fun getEmitter(rfId: RfIdentification) =
        query(
            arrayOf(COL_LAT, COL_LON, COL_RAD_NS, COL_RAD_EW, COL_NOTE),
            "$COL_RFID = '${rfId.uniqueId}'",
            limit = "1"
        ) { it.toRfEmitter(rfId) }.firstOrNull()

    // get multiple emitters instead of querying one by one
    fun getEmitters(rfIds: Collection<RfIdentification>): List<RfEmitter> {
        val idString = rfIds.joinToString(",") { "'${it.uniqueId}'" }
        return query(allColumns, "$COL_RFID IN ($idString)") { it.toRfEmitter() }.filterNotNull().toList()
    }

    fun getAll() = query(allColumns) { it.toRfEmitter() }.filterNotNull()

    fun getSize() = DatabaseUtils.queryNumEntries(database, TABLE_SAMPLES)

    companion object {
        var instance: Database? = null
            private set
    }
}

private const val TAG = "LocalNLP DB"
private val DEBUG = BuildConfig.DEBUG

private const val DB_NAME = "rf.db"
private const val TABLE_SAMPLES = "emitters"
private const val VERSION = 4
const val COL_TYPE = "rfType"
const val COL_RFID = "rfID"
const val COL_LAT = "latitude"
const val COL_LON = "longitude"
const val COL_RAD_NS = "radius_ns" // v2 of database
const val COL_RAD_EW = "radius_ew" // v2 of database
const val COL_NOTE = "note"
// columns used in old db versions
private const val OLD_COL_HASH = "rfHash" // v3 of database, removed in v4
private const val OLD_COL_TRUST = "trust" // removed in v4
private const val OLD_COL_RAD = "radius" // v1 of database

const val COLLISION_MERGE = 0 // merge emitters on collision when inserting

private val allColumns = arrayOf(COL_RFID, COL_TYPE, COL_LAT, COL_LON, COL_RAD_NS, COL_RAD_EW, COL_NOTE)
private val wifis = hashSetOf(EmitterType.WLAN2, EmitterType.WLAN5, EmitterType.WLAN6)

class EmitterInfo(
    val latitude: Double,
    val longitude: Double,
    val radius_ns: Double,
    val radius_ew: Double,
    val note: String
)

private class CursorPosition(private val cursor: Cursor) {
    fun getDouble(columnName: String): Double = cursor.getDouble(index(columnName))
    fun getString(columnName: String): String = cursor.getString(index(columnName))

    private fun index(columnName: String): Int = cursor.getColumnIndexOrThrow(columnName)
}

private inline fun <T> Cursor.toSequence(crossinline transform: (CursorPosition) -> T): Sequence<T> {
    val c = CursorPosition(this)
    moveToFirst()
    return generateSequence {
        if (!isAfterLast) {
            val r = transform(c)
            moveToNext()
            r
        } else {
            close()
            null
        }
    }
}

private fun CursorPosition.toRfEmitter(rfId: RfIdentification? = null): RfEmitter? {
    val info = EmitterInfo(getDouble(COL_LAT), getDouble(COL_LON), getDouble(COL_RAD_NS), getDouble(COL_RAD_EW), getString(COL_NOTE))
    return if (rfId == null) {
        val type = try {
            EmitterType.valueOf(getString(COL_TYPE))
        } catch (_: Exception) {
            return null
        }
        val dbId = getString(COL_RFID)
        val id = if (type in wifis) dbId.substringAfter('/')
                else dbId
        RfEmitter(type, id, info)
    } else
        RfEmitter(rfId, info)
}
