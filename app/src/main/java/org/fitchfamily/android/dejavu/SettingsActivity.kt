package org.fitchfamily.android.dejavu

/*
*    Local NLP Backend - location provider backend for microG/UnifiedNlp
*
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

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color.TRANSPARENT
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.provider.OpenableColumns
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import kotlinx.coroutines.*
import java.io.File

// deprecated, but replacement is annoying to handle...
class SettingsActivity : PreferenceActivity() {

    private val prefs: SharedPreferences by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener {_, _ ->
        if (!importExportRunning) // actually changing settings shouldn't be possible, as a dialog is showing
            BackendService.instance?.reloadSettings()
    }
    private val scope = CoroutineScope(SupervisorJob())
    private var importExportRunning = false
        set(value) {
            if (value) {
                BackendService.instance?.pause()
                BackendService.resetCache()
            } else {
                BackendService.instance?.reloadSettings()
                BackendService.resetCache()
            }
            field = value
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        addPreferencesFromResource(R.xml.preferences)
        findPreference(PREF_CULL)?.setOnPreferenceClickListener {
            onClickCull()
            true
        }
        findPreference(PREF_IMPORT)?.setOnPreferenceClickListener {
            onClickImport()
            true
        }
        findPreference(PREF_EXPORT)?.setOnPreferenceClickListener {
            onClickExport()
            true
        }
        findPreference(PREF_SHOW_EMITTERS)?.setOnPreferenceClickListener {
            onClickScan()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        if (!importExportRunning) // avoid unpausing the backend while database is used
            BackendService.instance?.reloadSettings()
        // never close database here, this will happen automatically by GC if BackendService
        // is not running: https://stackoverflow.com/a/35648781
    }

    private fun onClickCull() {
        val currentChoice = when (prefs.getInt(PREF_CULL, 0)) {
            1 -> R.string.pref_cull_median
            2 -> R.string.pref_cull_none
            else -> R.string.pref_cull_default
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pref_cull_title))
            .setMessage(getString(R.string.pref_cull_message, getString(currentChoice)))
            .setPositiveButton(R.string.pref_cull_default) { _,_ -> prefs.edit().putInt(PREF_CULL, 0).apply() }
            .setNeutralButton(R.string.pref_cull_median) { _,_ -> prefs.edit().putInt(PREF_CULL, 1).apply() }
            .setNegativeButton(R.string.pref_cull_none) { _,_ -> prefs.edit().putInt(PREF_CULL, 2).apply() }
            .show()
    }

    private fun onClickImport() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, IMPORT_CODE)
    }

    private fun onClickExport() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT){
            Toast.makeText(this, R.string.export_jb, Toast.LENGTH_LONG).show()
            return
        }
        // todo: how to do it for old api?
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            val fileName = "emitters.csv"
            putExtra(Intent.EXTRA_TITLE, fileName)
            type = "application/text"
        }
        startActivityForResult(intent, EXPORT_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK || data == null)
            return
        val uri = data.data ?: return
        when (requestCode) {
            IMPORT_CODE -> importFile(uri)
            EXPORT_CODE -> exportToFile(uri)
        }
    }

    private fun importFile(uri: Uri) {
        val inputStream = contentResolver?.openInputStream(uri) ?: return
        val reader = inputStream.bufferedReader()
        // if text, determine format and insert
        // if db, copy content

        // but first open a dialog
        // what to do on collisions (keep local, overwrite, merge)
        val collisionReplace = SQLiteDatabase.CONFLICT_REPLACE
        val collisionKeep = SQLiteDatabase.CONFLICT_IGNORE
        val collisionMerge = 0

        fun readFromFile(collision: Int, readFormat: Int, mlsIgnoreTypes: Set<String>, mlsCountryCodes: Set<String>?) {
            val db = Database.instance ?: Database(this)
            importExportRunning = true
            var importJob: Job? = null
            var i = 0
            var j = 0
            val dialogBuilder = AlertDialog.Builder(this)
            dialogBuilder.setTitle(R.string.import_title)
            dialogBuilder.setMessage(getString(R.string.import_number_progress, i, j))
            dialogBuilder.setCancelable(false)
            dialogBuilder.setNegativeButton(android.R.string.cancel) { _, _ ->
                importJob?.cancel()
                Toast.makeText(this, R.string.import_canceled, Toast.LENGTH_LONG).show()
            }
            val dialog = dialogBuilder.show()
            val sa = this
            importJob = scope.launch(Dispatchers.IO) {
                db.beginTransaction()
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                var success = true
                reader.useLines {
                    for (line in it) {
                        try {
                            val splitLine = parseLine(line, readFormat, mlsIgnoreTypes, mlsCountryCodes)
                            if (splitLine == null) j++
                            else {
                                db.insertLine(
                                    collision,
                                    splitLine[0],
                                    splitLine[1],
                                    splitLine[2].toDouble(),
                                    splitLine[3].toDouble(),
                                    splitLine[4].toDouble(),
                                    splitLine[5].toDouble(),
                                    splitLine[6]
                                )
                                i++
                            }
                            if ((i + j) % 500 == 0) {

                                runOnUiThread { dialog.setMessage(getString(R.string.import_number_progress, i, j)) }
                                if (!coroutineContext.isActive) {
                                    db.cancelTransaction()
                                    success = false
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            // don't break if lines can't be read, just notify in Toast
                            j++
                            Log.w(TAG, "import / readFromFile - error parsing line ${i + j}: $line", e)
                            runOnUiThread { Toast.makeText(sa, getString(R.string.import_error_line, line), Toast.LENGTH_LONG).show() }
                        }
                    }
                }
                runOnUiThread {
                    dialog.setMessage(getString(R.string.import_finishing))
                    // disable cancel button, the actual db write may take a while
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).isEnabled = false
                }
                db.endTransaction()
                inputStream.close()
                dialog.dismiss()
                if (success)
                    runOnUiThread { Toast.makeText(sa, R.string.import_done, Toast.LENGTH_LONG).show() }
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                importExportRunning = false
            }
        }

        fun readFromDatabase(collision: Int) {
            val db = Database.instance ?: Database(this)
            importExportRunning = true
            val dbFile = File(this.applicationInfo.dataDir + File.separator + "databases" + File.separator + "tmp.db")
            dbFile.delete()
            dbFile.parentFile?.mkdirs()
            var importJob: Job? = null
            val dialogBuilder = AlertDialog.Builder(this)
            dialogBuilder.setTitle(R.string.import_title)
            dialogBuilder.setMessage("0 / ?")
            dialogBuilder.setCancelable(false)
            dialogBuilder.setNegativeButton(android.R.string.cancel) { _, _ ->
                importJob?.cancel()
                Toast.makeText(this, R.string.import_canceled, Toast.LENGTH_LONG).show()
            }
            val dialog = dialogBuilder.show()
            val sa = this
            importJob = scope.launch(Dispatchers.IO) {
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                dbFile.outputStream().use { inputStream.copyTo(it) }
                inputStream.close()
                try {
                    val otherDb = Database(sa, "tmp.db")
                    val max = otherDb.getSize()
                    runOnUiThread { dialog.setMessage("0 / $max") }
                    // read each entry and copy it to db, respecting collision
                    otherDb.beginTransaction()
                    db.beginTransaction()
                    var i = 0
                    otherDb.getAll().forEach {
                        db.insert(it, collision)
                        i++
                        if (i % 100 == 0) {
                            runOnUiThread { dialog.setMessage("$i / $max") }
                            if (!coroutineContext.isActive) {
                                db.cancelTransaction()
                                otherDb.endTransaction()
                                otherDb.close()
                                dbFile.delete()
                                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                                importExportRunning = false
                                return@launch
                            }
                        }
                    }
                    otherDb.endTransaction()
                    otherDb.close()
                } catch (e: Exception) {
                    Log.w(TAG, "import / readFromDatabase - error", e)
                    db.cancelTransaction()
                    runOnUiThread { Toast.makeText(sa, getString(R.string.import_error_database, e.message), Toast.LENGTH_LONG).show() }
                }
                db.endTransaction()
                dbFile.delete()
                dialog.dismiss()
                runOnUiThread {
                    Toast.makeText(sa, R.string.import_done, Toast.LENGTH_LONG).show()
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                importExportRunning = false
            }
        }

        fun import(collision: Int) {
            val isDatabaseFile = if (uri.toString().startsWith("file:")) {
                uri.toString().endsWith(".db")
            } else {
                var db = false
                contentResolver.query(uri, null, null, null, null).use {
                    if (it != null && it.moveToFirst()) {
                        val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0)
                            db = it.getString(idx).endsWith(".db")
                    }
                }
                db
            }
            if (isDatabaseFile) {
                readFromDatabase(collision)
                return
            }

            // no database, check whether it's an export or a MLS file
            val firstLine = reader.readLine()
            val readFormat = when {
                firstLine == MLS -> 0
                firstLine.substringAfter("database v").toIntOrNull() == 4 -> {
                    reader.readLine() // skip the header line, as it's not used
                    4
                }
                parseLine(firstLine, 0, emptySet(), null) != null -> 0 // MLS, but without header
                else -> {
                    Toast.makeText(this, R.string.import_error_format, Toast.LENGTH_LONG).show()
                    return
                }
            }

            val mlsIgnoreTypes = mutableSetOf<String>()
            val mlsCountryCodes = mutableSetOf<String>()
            if (readFormat != 0) {
                readFromFile(collision, readFormat, mlsIgnoreTypes, mlsCountryCodes)
                return
            }
            val gsmBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "GSM")
                isChecked = true
            }
            val umtsBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "UMTS")
                isChecked = true
            }
            val lteBox = CheckBox(this).apply {
                text = resources.getString(R.string.import_mls_use_type, "LTE")
                isChecked = true
            }
            val countryCodesText = TextView(this).apply { setText(R.string.import_mls_country_code_message) }
            val countryCodes = EditText(this)
            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(gsmBox)
                addView(umtsBox)
                addView(lteBox)
                addView(countryCodesText)
                addView(countryCodes)
                setPadding(30, 10, 30, 10)
            }
            val d = AlertDialog.Builder(this)
                .setTitle(R.string.import_mls_title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    if (!gsmBox.isChecked) mlsIgnoreTypes.add("GSM")
                    if (!umtsBox.isChecked) mlsIgnoreTypes.add("UMTS")
                    if (!lteBox.isChecked) mlsIgnoreTypes.add("LTE")
                    mlsCountryCodes.addAll(parseCountryCodes(countryCodes.text.toString()))
                    readFromFile(collision, readFormat, mlsIgnoreTypes, mlsCountryCodes.takeIf { it.isNotEmpty() })
                }
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            val mcc = "[xX\\d]{3}".toRegex()
            countryCodes.doAfterTextChanged { cc ->
                d.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = cc.toString().split(",").all { it.isBlank() || it.trim().matches(mcc) }
            }
            d.show()
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.pref_import_title)
            .setMessage(R.string.import_message)
            .setPositiveButton(R.string.import_replace) { _,_ -> import(collisionReplace) }
            .setNeutralButton(R.string.import_merge) { _,_ -> import(collisionMerge) }
            .setNegativeButton(R.string.import_keep) { _,_ -> import(collisionKeep) }
            .setOnCancelListener { inputStream.close() }
            .show()
    }

    /** converts the line to the 7 required entries for putting in DB */
    private fun parseLine(line: String, readFormat: Int, mlsIgnoreTypes: Set<String>, mlsCountryCodes: Set<String>?): List<String>? {
        var splitLine = line.split(',')
        if (readFormat == 0) { // MLS / OpenCelliD list
            if (splitLine.size < 10 || splitLine.first() in mlsIgnoreTypes || mlsCountryCodes?.contains(splitLine[1]) == false) return null
            val rfid = when (splitLine.first()) {
                "GSM" -> "GSM/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // GSM,202,0,42,26363 -> GSM/202/0/42/26363
                "UMTS" -> "WCDMA/${splitLine[1]}/${splitLine[2]}/${splitLine[3]}/${splitLine[4]}" // UMTS,202,0,6060,4655229 -> WCDMA/202/0/6060/4655229
                "LTE" -> {
                    if (splitLine[5].isEmpty()) return null // why is this the case so often? do some phones not report it (properly)?
                    "LTE/${splitLine[1]}/${splitLine[2]}/${splitLine[4]}/${splitLine[5]}/${splitLine[3]}" //LTE,202,1,3126,35714457,20 -> LTE/202/1/35714457/20/3126
                }
                else -> return null
            }
            splitLine = listOf(rfid, rfid.substringBefore('/'), splitLine[7], splitLine[6], splitLine[8], splitLine[8], "")

        } else if (readFormat == 4 && splitLine.size != 7) {
            if (splitLine[0].uppercase() !in emitterTypes) return null
            // we have one or more comma in ssid, rare enough to not optimize anything
            splitLine = splitLine.subList(0, 6) + splitLine.subList(6, splitLine.size).joinToString(",") // careful, subList endIndex is exclusive!
        }
        if (splitLine[0].uppercase() !in emitterTypes) return null
        return splitLine
    }

    /** creates a list from comma-separated MCCs, expanding x to 0-9 */
    private fun parseCountryCodes(codes: String): List<String> {
        fun String.expandX(): List<String> {
            val list = mutableListOf<List<String>>()
            if (contains('x') || contains('X')) {
                for (i in 0..9) {
                    list.add(replaceFirst("x", i.toString(), true).expandX())
                }
            } else list.add(listOf(this))
            return list.flatten()
        }

        val code = "[xX\\d]{3}".toRegex() // actually check is not necessary, but better be safe
        return codes.split(',').mapNotNull {
            val c = it.trim()
            if (c.matches(code)) c.expandX() else null
        }.flatten()
    }

    private fun exportToFile(uri: Uri) {
        val outputStream = contentResolver?.openOutputStream(uri) ?: return
        val os = outputStream.bufferedWriter()
        importExportRunning = true
        val db = Database.instance ?: Database(this)
        var exportJob: Job? = null
        val max = db.getSize()
        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setTitle(R.string.export_title)
        dialogBuilder.setMessage("0 / $max")
        dialogBuilder.setCancelable(false)
        dialogBuilder.setNegativeButton(android.R.string.cancel) { _, _ ->
            exportJob?.cancel()
            Toast.makeText(this, R.string.export_canceled, Toast.LENGTH_LONG).show()
        }
        val dialog = dialogBuilder.show()
        val sa = this
        exportJob = scope.launch(Dispatchers.IO) {
            runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            var success = true
            try {
                os.write("database v4\n")
                os.write("${COL_RFID},${COL_TYPE},${COL_LAT},${COL_LON},${COL_RAD_NS},${COL_RAD_EW},${COL_NOTE}\n")
                db.beginTransaction()

                var i = 0
                db.getAll().forEach {
                    os.write("${it.uniqueId},${it.type},${it.lat},${it.lon},${it.radiusNS},${it.radiusEW},${it.note}\n")
                    i++
                    if (i % 100 == 0) {
                        runOnUiThread { dialog.setMessage("$i / $max") }
                        if (!coroutineContext.isActive) {
                            db.cancelTransaction()
                            os.close()
                            importExportRunning = false
                            return@launch
                        }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "exportToFile - error", e)
                runOnUiThread { Toast.makeText(sa, getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show() }
                success = false
            }
            db.endTransaction()
            os.close()
            if (success)
                runOnUiThread { Toast.makeText(sa, R.string.export_done, Toast.LENGTH_LONG).show() }
            dialog.dismiss()
            outputStream.close()
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            importExportRunning = false
        }
    }

    @Suppress("Deprecation") // requestSingleUpdate is deprecated, but there is no replacement for api < 30
    private fun onClickScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
            && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        )
            return // do nothing, this means the user actively denied location access
        BackendService.instance?.reloadSettings() // unpause if necessary

        // request location, because backendService may not be running
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // what if network provider doesn't exist? see https://github.com/Helium314/Local-NLP-Backend/issues/12#issuecomment-1518894136
        // try fused provider, though not sure whether this would help
        val provider = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                LocationManager.NETWORK_PROVIDER
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER))
                LocationManager.FUSED_PROVIDER
            else null
        if (provider == null) {
            Toast.makeText(this, R.string.show_scan_no_network_provider, Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            lm.getCurrentLocation(provider, null, {  }, {  })
        else
            lm.requestSingleUpdate(provider, { }, null)

        try {
            runBlocking { withTimeout(3500) {
                while (BackendService.instance == null) { delay(100) }
            } }
            BackendService.instance!!.settingsActivity = this
            Toast.makeText(this, R.string.show_scan_started, Toast.LENGTH_LONG).show()
        } catch (e: CancellationException) {
            Toast.makeText(this, R.string.show_scan_start_failed, Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("SetTextI18n") // we want to concatenate the text string, requiring resource strings for emitter ids doesn't make sense
    fun showEmitters(emitters: Collection<RfEmitter>) = runOnUiThread {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var d: AlertDialog? = null
        val db = Database.instance ?: Database(this)
        emitters.sortedBy { it.uniqueId }.forEach { emitter ->
            val emitterInDb = db.getEmitter(emitter.rfIdentification) != null
            val t = TextView(this).apply { text = emitter.uniqueId + (if (emitter.note.isNotBlank()) ", ${emitter.note}" else "") }
            t.setOnClickListener {
                val text = listOfNotNull(
                    getString(R.string.show_scan_details_emitter_type, emitter.type),
                    if (emitter.type == EmitterType.WLAN2 || emitter.type == EmitterType.WLAN5)
                        getString(R.string.show_scan_details_emitter_ssid, emitter.note)
                    else null,
                    if (emitterInDb) getString(R.string.show_scan_details_emitter_center, emitter.lat, emitter.lon) else null,
                    if (emitterInDb) getString(R.string.show_scan_details_emitter_radius_ew, emitter.radiusEW) else null,
                    if (emitterInDb) getString(R.string.show_scan_details_emitter_radius_ns, emitter.radiusNS) else null,
                    emitter.lastObservation?.let { getString(R.string.show_scan_details_emitter_signal, (it.asu + 4) / 6) },
                    if (emitter.status == EmitterStatus.STATUS_BLACKLISTED) getString(R.string.show_scan_details_emitter_blacklisted) else null,
                    if (!emitterInDb) getString(R.string.show_scan_details_emitter_not_in_db) else null,
                ).joinToString("\n")
                val builder = AlertDialog.Builder(this)
                    .setTitle(getString(R.string.show_scan_details_emitter, emitter.uniqueId))
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                if (emitterInDb && emitter.status != EmitterStatus.STATUS_BLACKLISTED)
                    builder.setNegativeButton(R.string.show_scan_details_emitter_blacklist) { _, _ ->
                        db.setInvalid(emitter)
                        BackendService.resetCache()
                        d?.dismiss()
                    }
                builder.show()
            }
            val deleteButton = Button(this)
            deleteButton.setBackgroundColor(TRANSPARENT)
            deleteButton.setPadding(0, -15, 0, 0)
            if (emitterInDb) deleteButton.setBackgroundResource(android.R.drawable.ic_delete)
            deleteButton.isEnabled = emitterInDb
            deleteButton.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.show_scan_emitter_delete, emitter.uniqueId))
                    .setPositiveButton(R.string.show_scan_emitter_delete_confirm) { _, _ ->
                        db.drop(emitter)
                        BackendService.resetCache()
                        d?.dismiss()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            layout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(deleteButton)
                addView(t)
            })
        }
        layout.setPadding(30, 10, 30, 10)
        d = AlertDialog.Builder(this)
            .setTitle(R.string.show_scan_results)
            .setView(ScrollView(this).apply { addView(layout) })
            .setPositiveButton(android.R.string.ok, null)
            .create()
        d.show()
    }

}

private const val IMPORT_CODE = 6957238
private const val EXPORT_CODE = 75902745
private val emitterTypes = EmitterType.entries.map { it.name.uppercase() }.toHashSet()

const val PREF_KALMAN = "pref_kalman"
const val PREF_MOBILE = "pref_use_cell"
const val PREF_WIFI = "pref_use_wlan"
const val PREF_BUILD = "build"
const val PREF_CULL = "pref_cull"
const val PREF_IMPORT = "pref_import"
const val PREF_EXPORT = "pref_export"
const val PREF_SHOW_EMITTERS = "pref_show_emitters"
const val PREF_ACTIVE_MODE = "pref_active_mode2" // old one was boolean, and thus can't re re-used after switch to int
const val PREF_ACTIVE_TIME = "pref_active_time"

private const val MLS = "radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal"
private const val TAG = "LocalNLP Settings"
