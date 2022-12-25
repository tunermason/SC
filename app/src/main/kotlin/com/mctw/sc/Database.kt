package com.mctw.sc

import com.mctw.sc.Crypto.decryptDatabase
import com.mctw.sc.Crypto.encryptDatabase
import org.json.JSONObject
import org.json.JSONArray
import java.nio.charset.Charset

class Database() {
    var version = BuildConfig.VERSION_NAME
    var settings = Settings()
    var contacts = Contacts()
    var events = Events()

    // clear keys before the app exits
    fun destroy() {
        settings.destroy()
        contacts.destroy()
        events.destroy()
    }

    class WrongPasswordException() : Exception()

    companion object {
        private const val TAG = "Database"

        fun fromData(db_data: ByteArray, password: String?): Database {
            // encrypt database
            val stringData = if (password != null && password.isNotEmpty()) {
                Log.d(this, "Decrypt database")
                val encrypted = decryptDatabase(db_data, password.toByteArray())
                if (encrypted == null) {
                    throw WrongPasswordException()
                }
                encrypted
            } else {
                db_data
            }

            val obj = JSONObject(
                String(stringData, Charset.forName("UTF-8"))
            )

            upgradeDatabase(obj)

            val db = fromJSON(obj)
            Log.d(this, "Loaded ${db.contacts.contactList.size} contacts")
            Log.d(this, "Loaded ${db.events.eventList.size} events")
            return db
        }

        fun toData(db: Database, password: String?): ByteArray? {
            val obj = toJSON(db)
            var dbdata : ByteArray? = obj.toString().toByteArray()

            // encrypt database
            if (password != null && password.isNotEmpty()) {
                Log.d(this, "Encrypt database")
                dbdata = encryptDatabase(dbdata, password.toByteArray())
            }
            Log.d(TAG, "Stored ${db.contacts.contactList.size} contacts")
            Log.d(TAG, "Stored ${db.events.eventList.size} events")

            return dbdata
        }

        // add missing keys with defaults and remove unexpected keys
        private fun alignSettings(settings: JSONObject) {
            val defaults: JSONObject = Settings.toJSON(Settings())

            // default keys
            val defaultsIter = defaults.keys()
            val defaultsKeys = mutableListOf<String>()
            while (defaultsIter.hasNext()) {
                defaultsKeys.add(defaultsIter.next())
            }

            // current keys
            val settingsIter = settings.keys()
            val settingsKeys = mutableListOf<String>()
            while (settingsIter.hasNext()) {
                settingsKeys.add(settingsIter.next())
            }

            // add missing keys
            for (key in defaultsKeys) {
                if (!settings.has(key)) {
                    settings.put(key, defaults[key])
                }
            }

            // remove extra keys
            for (key in settingsKeys) {
                if (!defaults.has(key)) {
                    settings.remove(key)
                }
            }
        }

        private fun upgradeDatabase(db: JSONObject): Boolean {
            val from = db.getString("version")
            val to = BuildConfig.VERSION_NAME

            if (from == to) {
                return false
            }

            Log.d(this, "Upgrade database from $from to $to")
            val settings = db.getJSONObject("settings")
            var newFrom = from

            // 2.0.0 => 2.1.0
            if (newFrom == "2.0.0") {
                // add blocked field (added in 2.1.0)
                val contacts = db.getJSONArray("contacts")
                var i = 0
                while (i < contacts.length()) {
                    contacts.getJSONObject(i).put("blocked", false)
                    i += 1
                }
                newFrom = "2.1.0"
            }

            // 2.1.0 => 3.0.0
            if (newFrom == "2.1.0") {
                // add new fields
                settings.put("ice_servers", JSONArray())
                settings.put("development_mode", false)
                newFrom = "3.0.0"
            }

            // 3.0.0 => 3.0.1
            if (newFrom == "3.0.0") {
                // nothing to do
                newFrom = "3.0.1"
            }

            // 3.0.1 => 3.0.2
            if (newFrom == "3.0.1") {
                // fix typo in setting name
                settings.put("night_mode", settings.getBoolean("might_mode"))
                newFrom = "3.0.2"
            }

            // 3.0.2 => 3.0.3
            if (newFrom == "3.0.2") {
                // nothing to do
                newFrom = "3.0.3"
            }

            // 3.0.3 => 4.0.0
            if (newFrom == "3.0.3") {
                newFrom = "4.0.0"
                val contacts = Contacts()
                val contactArray = db.getJSONArray("contacts")
                for (i in 0 until contactArray.length()) {
                    val contactObj = contactArray.getJSONObject(i)
                    contacts.addContact(Contact.fromJSON(contactObj, true))
                }

                db.put("contacts", Contacts.toJSON(contacts))

                val events = Events()
                db.put("events", Events.toJSON(events))
            }

            // 4.0.0+ => 4.0.4
            if (newFrom in listOf("4.0.0", "4.0.1", "4.0.2", "4.0.3")) {
                // nothing to do
                newFrom = "4.0.4"
            }

            if (newFrom == "4.0.4") {
                db.put("connect_timeout", 500)
                db.put("use_system_table", false)
                db.put("prompt_outgoing_calls", false)
                db.remove("settings_mode")
                db.remove("ice_servers")

                val eventsObject = db.getJSONObject("events")
                val eventsArray = eventsObject.getJSONArray("entries")
                for (i in 0 until eventsArray.length()) {
                    val eventObject = eventsArray.getJSONObject(i)
                    val oldType = eventObject.getString("type")
                    val newType = when (oldType) {
                        "OUTGOING_UNKNOWN" -> "UNKNOWN"
                        "INCOMING_UNKNOWN" -> "UNKNOWN"
                        "OUTGOING_DECLINED" -> "OUTGOING_ACCEPTED"
                        "INCOMING_DECLINED" -> "INCOMING_ACCEPTED"
                        else -> oldType
                    }

                    eventObject.put("type", newType)
                }

                if (settings.getBoolean("night_mode")) {
                    settings.put("night_mode", "on")
                } else {
                    settings.put("night_mode", "off")
                }

                newFrom = "4.0.5"
            }

            if (newFrom == "4.0.5") {
                settings.put("video_hardware_acceleration", false)
                settings.put("no_audio_processing", false)
                newFrom = "4.0.6"
            }

            if (newFrom == "4.0.6") {
                // nothing to do
                newFrom = "4.0.7"
            }

            alignSettings(settings)

            db.put("version", newFrom)
            return true
        }

        private fun toJSON(db: Database): JSONObject {
            val obj = JSONObject()
            obj.put("version", db.version)
            obj.put("settings", Settings.toJSON(db.settings))
            obj.put("contacts", Contacts.toJSON(db.contacts))
            obj.put("events", Events.toJSON(db.events))
            return obj
        }

        private fun fromJSON(obj: JSONObject): Database {
            val db = Database()

            db.version = obj.getString("version")

            // import contacts
            val contacts = obj.getJSONObject("contacts")
            db.contacts = Contacts.fromJSON(contacts)

            // import settings
            val settings = obj.getJSONObject("settings")
            db.settings = Settings.fromJSON(settings)

            // import events
            val events = obj.getJSONObject("events")
            db.events = Events.fromJSON(events)

            return db
        }
    }
}